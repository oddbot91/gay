#!/usr/bin/env bash
# tools/check.sh - offline sanity checks for the Jobbdialer sources (no Gradle, no network).
#
#   a) every .xml under app/src/main is well-formed
#   b) every @type/name, ?attr/name (XML) and R.type.name (Java) reference resolves to a
#      resource defined under res/; manifest/shortcut class names resolve to .java files
#   c) all Java sources + a generated dummy R.java compile with plain javac against android.jar
#   d) guard: no AndroidX / Material usage (Java, XML, app/build.gradle.kts)
#
# Run from anywhere; paths are resolved relative to the repo root (parent of tools/).
#
# Environment:
#   ANDROID_JAR   path to a platform android.jar (compile classpath only, never executed).
#                 Default: the scratchpad copy below, else $ANDROID_HOME/platforms/android-35
#   APP_PACKAGE   package of the generated R class (default: se.jobbdialer)
#
# Exit status: 0 = everything passed, 1 = at least one check failed, 2 = bad setup.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEFAULT_ANDROID_JAR="/tmp/claude-0/-home-claude/45c174cd-86d0-5acd-adc0-a68b5c77f238/scratchpad/android-platforms/repo/android-35/android.jar"
if [ -z "${ANDROID_JAR:-}" ]; then
  # No explicit ANDROID_JAR: use the scratchpad copy, else an installed SDK platform.
  ANDROID_JAR="$DEFAULT_ANDROID_JAR"
  for candidate in "$DEFAULT_ANDROID_JAR" \
                   "${ANDROID_HOME:-/nonexistent}/platforms/android-35/android.jar" \
                   "${ANDROID_SDK_ROOT:-/nonexistent}/platforms/android-35/android.jar" \
                   "${ANDROID_HOME:-/nonexistent}/platforms/android-34/android.jar"; do
    if [ -f "$candidate" ]; then ANDROID_JAR="$candidate"; break; fi
  done
fi
APP_PACKAGE="${APP_PACKAGE:-se.jobbdialer}"

MAIN="app/src/main"
RES="$MAIN/res"
JAVA_DIR="$MAIN/java"
OUT="tools/out"
GEN="$OUT/gen"
CLASSES="$OUT/classes"

STATUS_A="-"; STATUS_B="-"; STATUS_C="-"; STATUS_D="-"
FAIL=0
section() { printf '\n== %s ==\n' "$1"; }

# ---------------------------------------------------------------------------
# Embedded Python helper (stdlib only). Sub-commands: xml | refs | guard
# ---------------------------------------------------------------------------
PYSRC=""
IFS= read -r -d '' PYSRC <<'PYEOF' || true
import json, os, re, sys
import xml.etree.ElementTree as ET
from xml.parsers import expat

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def walk_files(root, suffix):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            if name.endswith(suffix):
                yield os.path.join(dirpath, name)


def blank_java(src):
    """Blank out comments and string/char literals (newlines kept for line numbers)."""
    pat = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*[\s\S]*?\*/')
    return pat.sub(lambda m: re.sub(r'[^\n]', ' ', m.group(0)), src)


def line_of(text, pos):
    return text.count('\n', 0, pos) + 1


def read_text(path):
    with open(path, 'r', encoding='utf-8') as fh:
        return fh.read()


# ---------------------------------------------------------------- a) XML
def cmd_xml(main):
    files = list(walk_files(main, '.xml'))
    bad = 0
    for path in files:
        parser = expat.ParserCreate()
        try:
            with open(path, 'rb') as fh:
                parser.Parse(fh.read(), True)
        except expat.ExpatError as exc:
            bad += 1
            print('  ERROR %s:%d:%d: %s' % (path, exc.lineno, exc.offset, expat.ErrorString(exc.code)))
        except OSError as exc:
            bad += 1
            print('  ERROR %s: %s' % (path, exc))
    print('  %d XML file(s) checked, %d malformed' % (len(files), bad))
    return 1 if bad else 0


# ---------------------------------------------------------------- b) references
REF = re.compile(r'@(\+?)([a-z]+)/([A-Za-z0-9_.]+)')
ATTRREF = re.compile(r'\?attr/([A-Za-z0-9_.]+)')
JAVA_REF = None  # compiled in cmd_refs (needs the package)


class XmlRefScanner(object):
    def __init__(self, path, res, problems):
        self.path, self.res, self.problems = path, res, problems
        self.buf, self.buf_line, self.count = [], 0, 0
        self.parser = expat.ParserCreate()
        self.parser.buffer_text = True
        self.parser.StartElementHandler = self.start
        self.parser.EndElementHandler = self.end
        self.parser.CharacterDataHandler = self.chars

    def lookup(self, rtype, name, line, raw):
        self.count += 1
        names = self.res.get(rtype)
        if names is None:
            self.problems.append('%s:%d: %s -> unknown resource type "%s"' % (self.path, line, raw, rtype))
        elif name.replace('.', '_') not in names:
            self.problems.append('%s:%d: %s -> no such %s resource' % (self.path, line, raw, rtype))

    def check(self, value, line):
        value = value.strip()
        m = REF.fullmatch(value)
        if m:
            plus, rtype, name = m.groups()
            if not plus:          # '@+id/x' defines, never references
                self.lookup(rtype, name, line, value)
            return
        m = ATTRREF.fullmatch(value)
        if m:
            self.lookup('attr', m.group(1), line, value)

    def flush(self):
        text = ''.join(self.buf).strip()
        if text:
            self.check(text, self.buf_line)
        self.buf, self.buf_line = [], 0

    def start(self, name, attrs):
        self.flush()
        line = self.parser.CurrentLineNumber
        for value in attrs.values():
            self.check(value, line)

    def end(self, name):
        self.flush()

    def chars(self, data):
        if not self.buf:
            self.buf_line = self.parser.CurrentLineNumber
        self.buf.append(data)

    def run(self):
        with open(self.path, 'rb') as fh:
            self.parser.Parse(fh.read(), True)
        return self.count


def class_candidates(fq):
    outer = fq.split('$')[0]
    return outer.replace('.', '/') + '.java'


def cmd_refs(main, resjson, default_pkg, app_dir):
    with open(resjson, 'r', encoding='utf-8') as fh:
        res = dict((k, set(v)) for k, v in json.load(fh).items())
    problems = []
    java_root = os.path.join(main, 'java')

    # --- XML references
    xml_count = ref_count = 0
    for path in walk_files(main, '.xml'):
        xml_count += 1
        try:
            ref_count += XmlRefScanner(path, res, problems).run()
        except expat.ExpatError:
            pass  # reported by step a
        except OSError as exc:
            problems.append('%s: cannot read: %s' % (path, exc))
    print('  XML: %d file(s), %d resource reference(s) checked' % (xml_count, ref_count))

    # --- Java references R.type.name
    pat = re.compile(r'(?<![\w.])(?:%s\.)?R\.([a-z]+)\.([A-Za-z0-9_]+)' % re.escape(default_pkg))
    java_count = rj = 0
    for path in walk_files(java_root, '.java'):
        java_count += 1
        try:
            src = blank_java(read_text(path))
        except (OSError, UnicodeDecodeError) as exc:
            problems.append('%s: cannot read: %s' % (path, exc))
            continue
        for m in pat.finditer(src):
            rj += 1
            rtype, name = m.groups()
            line = line_of(src, m.start())
            ref = 'R.%s.%s' % (rtype, name)
            names = res.get(rtype)
            if names is None:
                problems.append('%s:%d: %s -> unknown resource type "%s"' % (path, line, ref, rtype))
            elif name not in names:
                problems.append('%s:%d: %s -> no such %s resource' % (path, line, ref, rtype))
    print('  Java: %d file(s), %d R.* reference(s) checked' % (java_count, rj))

    # --- manifest / xml class names
    manifest = os.path.join(main, 'AndroidManifest.xml')
    pkg = default_pkg
    gradle = os.path.join(app_dir, 'build.gradle.kts')
    if not os.path.isfile(gradle):
        gradle = os.path.join(app_dir, 'build.gradle')
    if os.path.isfile(gradle):
        m = re.search(r'namespace\s*=?\s*["\']([\w.]+)["\']', read_text(gradle))
        if m:
            pkg = m.group(1)
    classes = 0
    if not os.path.isfile(manifest):
        problems.append('%s: manifest not found' % manifest)
    else:
        try:
            root = ET.parse(manifest).getroot()
            pkg = root.get('package') or pkg
            wanted = []
            for el in root.iter():
                if not isinstance(el.tag, str):
                    continue
                if el.tag in ('activity', 'service', 'receiver', 'provider', 'application'):
                    wanted.append((el.get(ANDROID_NS + 'name'), el.tag + '/android:name'))
                if el.tag == 'activity-alias':
                    wanted.append((el.get(ANDROID_NS + 'targetActivity'), 'activity-alias/android:targetActivity'))
                if el.get(ANDROID_NS + 'parentActivityName'):
                    wanted.append((el.get(ANDROID_NS + 'parentActivityName'), el.tag + '/android:parentActivityName'))
            for name, what in wanted:
                if not name:
                    continue
                classes += 1
                if name.startswith('.'):
                    fq = pkg + name
                elif '.' not in name:
                    fq = pkg + '.' + name
                else:
                    fq = name
                if fq.startswith(('android.', 'java.', 'javax.', 'com.android.')):
                    continue
                rel = class_candidates(fq)
                if not os.path.isfile(os.path.join(java_root, rel)):
                    problems.append('%s: %s="%s" -> class %s not found (expected %s/%s)'
                                    % (manifest, what, name, fq, java_root, rel))
        except (ET.ParseError, OSError):
            pass  # reported by step a
    # shortcuts and other xml/ files: android:targetClass="se.jobbdialer.MainActivity"
    for path in walk_files(os.path.join(main, 'res'), '.xml'):
        try:
            for el in ET.parse(path).getroot().iter():
                if not isinstance(el.tag, str):
                    continue
                target = el.get(ANDROID_NS + 'targetClass')
                if target:
                    classes += 1
                    fq = pkg + target if target.startswith('.') else target
                    rel = class_candidates(fq)
                    if not os.path.isfile(os.path.join(java_root, rel)):
                        problems.append('%s: android:targetClass="%s" -> class %s not found (expected %s/%s)'
                                        % (path, target, fq, java_root, rel))
        except (ET.ParseError, OSError):
            pass
    print('  Manifest/shortcuts: %d class reference(s) checked (package %s)' % (classes, pkg))

    for p in problems:
        print('  MISSING ' + p)
    print('  %d unresolved reference(s)' % len(problems))
    return 1 if problems else 0


# ---------------------------------------------------------------- d) guard
BANNED_JAVA = re.compile(r'(?<![\w.])(?:androidx|com\.google\.android\.material)\.')
BANNED_VALUE = re.compile(r'^(?:androidx|com\.google\.android\.material)\.')


def guard_xml(path, problems):
    parser = expat.ParserCreate()

    def start(name, attrs):
        line = parser.CurrentLineNumber
        if BANNED_VALUE.match(name):
            problems.append('%s:%d: forbidden view/tag <%s>' % (path, line, name))
        for key, value in attrs.items():
            if BANNED_VALUE.match(value.strip()):
                problems.append('%s:%d: forbidden class in %s="%s"' % (path, line, key, value))

    parser.StartElementHandler = start
    try:
        with open(path, 'rb') as fh:
            parser.Parse(fh.read(), True)
    except (expat.ExpatError, OSError):
        pass  # malformed XML is reported by step a


def cmd_guard(main, app_dir):
    problems = []
    nfiles = 0
    for path in walk_files(os.path.join(main, 'java'), '.java'):
        nfiles += 1
        try:
            src = blank_java(read_text(path))
        except (OSError, UnicodeDecodeError):
            continue
        for m in BANNED_JAVA.finditer(src):
            line = src.splitlines()[line_of(src, m.start()) - 1].strip()
            problems.append('%s:%d: forbidden dependency: %s' % (path, line_of(src, m.start()), line))
    for path in walk_files(main, '.xml'):
        nfiles += 1
        guard_xml(path, problems)
    for name in ('build.gradle.kts', 'build.gradle'):
        gradle = os.path.join(app_dir, name)
        if os.path.isfile(gradle):
            nfiles += 1
            for i, line in enumerate(read_text(gradle).splitlines(), 1):
                code = line.split('//', 1)[0]
                if re.search(r'androidx|com\.google\.android\.material', code):
                    problems.append('%s:%d: forbidden dependency: %s' % (gradle, i, line.strip()))
    print('  %d file(s) scanned' % nfiles)
    for p in problems:
        print('  FORBIDDEN ' + p)
    print('  %d forbidden usage(s)' % len(problems))
    return 1 if problems else 0


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'xml':
        sys.exit(cmd_xml(sys.argv[2]))
    if cmd == 'refs':
        sys.exit(cmd_refs(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]))
    if cmd == 'guard':
        sys.exit(cmd_guard(sys.argv[2], sys.argv[3]))
    sys.stderr.write('unknown sub-command %s\n' % cmd)
    sys.exit(2)
PYEOF

pycheck() { python3 -I -c "$PYSRC" "$@"; }

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------
echo "Jobbdialer check - repo root: $ROOT"
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found in PATH" >&2; exit 2
fi
if [ ! -d "$MAIN" ]; then
  echo "ERROR: $MAIN does not exist - nothing to check (run from a checkout that contains the app sources)." >&2
  exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# ---------------------------------------------------------------------------
# a) XML well-formedness
# ---------------------------------------------------------------------------
section "a) XML well-formedness ($MAIN/**/*.xml)"
if pycheck xml "$MAIN"; then STATUS_A="PASS"; else STATUS_A="FAIL"; FAIL=1; fi

# ---------------------------------------------------------------------------
# b) resource table + reference validation
# ---------------------------------------------------------------------------
section "b) Resource references (XML @type/name, ?attr/name, Java R.type.name, manifest classes)"
if [ ! -d "$RES" ]; then
  echo "  ERROR: $RES does not exist" >&2
  STATUS_B="FAIL"; FAIL=1
else
  GEN_RC=0
  python3 -I "$ROOT/tools/gen_r.py" --package "$APP_PACKAGE" "$RES" "$GEN" || GEN_RC=$?
  if [ "$GEN_RC" -ne 0 ]; then
    echo "  ERROR: gen_r.py reported problems with the resources (exit $GEN_RC)"
    STATUS_B="FAIL"; FAIL=1
  fi
  if [ -f "$GEN/resources.json" ]; then
    if pycheck refs "$MAIN" "$GEN/resources.json" "$APP_PACKAGE" "app"; then
      [ "$STATUS_B" = "FAIL" ] || STATUS_B="PASS"
    else
      STATUS_B="FAIL"; FAIL=1
    fi
  else
    echo "  ERROR: resources.json was not produced; reference check skipped"
    STATUS_B="FAIL"; FAIL=1
  fi
fi

# ---------------------------------------------------------------------------
# c) javac compile against android.jar
# ---------------------------------------------------------------------------
section "c) javac --release 17 -proc:none -Xlint:all (classpath: android.jar)"
STATUS_C="FAIL"
JAVA_FILES=()
if [ -d "$JAVA_DIR" ]; then
  while IFS= read -r f; do JAVA_FILES+=("$f"); done < <(find "$JAVA_DIR" -name '*.java' | LC_ALL=C sort)
fi
R_JAVA="$GEN/$(printf '%s' "$APP_PACKAGE" | tr . /)/R.java"

if ! command -v javac >/dev/null 2>&1; then
  echo "  ERROR: javac not found in PATH (need a JDK 17+)"
elif [ ! -f "$ANDROID_JAR" ]; then
  echo "  ERROR: android.jar not found at: $ANDROID_JAR"
  echo "         Set ANDROID_JAR=/path/to/android-35/android.jar (e.g. \$ANDROID_HOME/platforms/android-35/android.jar)"
elif [ "${#JAVA_FILES[@]}" -eq 0 ]; then
  echo "  ERROR: no .java files under $JAVA_DIR"
elif [ ! -f "$R_JAVA" ]; then
  echo "  ERROR: generated R.java missing ($R_JAVA); cannot compile"
else
  JAVAC_MAJOR="$(javac -version 2>&1 | grep -v '^Picked up' | head -n1 | sed -E 's/^javac (1\.)?([0-9]+).*/\2/')"
  if ! [ "${JAVAC_MAJOR:-0}" -ge 17 ] 2>/dev/null; then
    echo "  ERROR: javac $JAVAC_MAJOR is too old; JDK 17 or newer required for --release 17"
  else
    mkdir -p "$CLASSES"
    LOG="$OUT/javac.log"
    JAVAC_RC=0
    javac --release 17 -proc:none -Xlint:all -Xmaxwarns 10000 -Xmaxerrs 1000 -encoding UTF-8 \
          -cp "$ANDROID_JAR" -d "$CLASSES" "${JAVA_FILES[@]}" "$R_JAVA" >"$LOG" 2>&1 || JAVAC_RC=$?
    grep -v '^Picked up ' "$LOG" || true
    WARN_COUNT="$(grep -Ec '^([^ ]+:[0-9]+: )?warning:' "$LOG" || true)"
    ERR_COUNT="$(grep -Ec '^([^ ]+:[0-9]+: )?error:' "$LOG" || true)"
    CLASS_COUNT="$(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ')"
    echo "  Summary: ${#JAVA_FILES[@]} source file(s) + R.java, ${CLASS_COUNT} class file(s), ${WARN_COUNT} warning(s), ${ERR_COUNT} error(s)"
    if [ "$JAVAC_RC" -eq 0 ] && [ "$ERR_COUNT" -eq 0 ]; then
      STATUS_C="PASS"
    else
      echo "  javac exited with status $JAVAC_RC"
    fi
  fi
fi
[ "$STATUS_C" = "PASS" ] || FAIL=1

# ---------------------------------------------------------------------------
# d) guard: no AndroidX / Material
# ---------------------------------------------------------------------------
section "d) Guard: no androidx.* / com.google.android.material"
if pycheck guard "$MAIN" "app"; then STATUS_D="PASS"; else STATUS_D="FAIL"; FAIL=1; fi

# ---------------------------------------------------------------------------
printf '\n== Summary ==\n'
printf '  a) XML well-formed ........ %s\n' "$STATUS_A"
printf '  b) resource references .... %s\n' "$STATUS_B"
printf '  c) javac compile .......... %s\n' "$STATUS_C"
printf '  d) no androidx/material ... %s\n' "$STATUS_D"
if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL"
exit 1
