#!/usr/bin/env bash
# build-manual.sh - build a signed Jobbdialer release APK WITHOUT Gradle.
#
# Pipeline: aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
#
# Required environment:
#   ANDROID_JAR   platform jar, e.g. $ANDROID_HOME/platforms/android-35/android.jar
#   BUILD_TOOLS   Android SDK build-tools dir (contains aapt2, d8 or lib/d8.jar, zipalign,
#                 apksigner or lib/apksigner.jar), e.g. $ANDROID_HOME/build-tools/35.0.0
#                 (ANDROID_BUILD_TOOLS is accepted as an alias.)
# Optional environment:
#   JAVA_HOME     JDK 17+ used for javac/java (otherwise whatever is in PATH)
#   AAPT2         path to an aapt2 binary (e.g. a native build on a non-x86 host)
#   D8_JAR        path to d8.jar / r8.jar; used when the `d8` launcher script is not available
#   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#                 override the values read from signing.properties
#   MIN_SDK (26), TARGET_SDK (34), VERSION_CODE, VERSION_NAME, APP_PACKAGE (se.jobbdialer)
#
# Output: build-manual/jobbdialer-release.apk (v1+v2+v3 signed, zip-aligned).
#
# Note on d8: build-tools ship `d8` as a launcher script around lib/d8.jar. If only the jar is
# available, this script runs it as:  java -cp d8.jar com.android.tools.r8.D8 ...
# The same fallback exists for apksigner (java -jar lib/apksigner.jar).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

die()  { echo "build-manual: ERROR: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

case "${1:-}" in
  -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
esac

# ----------------------------------------------------------------------------- config
APP_PACKAGE="${APP_PACKAGE:-se.jobbdialer}"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-34}"
BUILD_TOOLS="${BUILD_TOOLS:-${ANDROID_BUILD_TOOLS:-}}"
ANDROID_JAR="${ANDROID_JAR:-}"

# versionCode / versionName: env > app/build.gradle.kts > defaults from the spec (1 / 1.0)
gradle_value() { # $1 = versionCode|versionName
  [ -f app/build.gradle.kts ] || return 0
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"?([^\"[:space:]]+)\"?.*/\1/p" app/build.gradle.kts | head -n1
}
VERSION_CODE="${VERSION_CODE:-$(gradle_value versionCode)}"; VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-$(gradle_value versionName)}"; VERSION_NAME="${VERSION_NAME:-1.0}"

SRC_MAIN="app/src/main"
SRC_RES="$SRC_MAIN/res"
SRC_JAVA="$SRC_MAIN/java"
SRC_MANIFEST="$SRC_MAIN/AndroidManifest.xml"
OUT="build-manual"
FINAL_APK="$OUT/jobbdialer-release.apk"

# ----------------------------------------------------------------------------- tool discovery
[ -n "$ANDROID_JAR" ]  || die "ANDROID_JAR is not set. Point it at a platform jar, e.g. export ANDROID_JAR=\$ANDROID_HOME/platforms/android-35/android.jar"
[ -f "$ANDROID_JAR" ]  || die "ANDROID_JAR does not exist: $ANDROID_JAR"
[ -n "$BUILD_TOOLS" ]  || die "BUILD_TOOLS is not set. Point it at the SDK build-tools dir, e.g. export BUILD_TOOLS=\$ANDROID_HOME/build-tools/35.0.0 (install with: sdkmanager 'build-tools;35.0.0')"
[ -d "$BUILD_TOOLS" ]  || die "BUILD_TOOLS is not a directory: $BUILD_TOOLS"

if [ -n "${JAVA_HOME:-}" ]; then
  [ -x "$JAVA_HOME/bin/javac" ] || die "JAVA_HOME=$JAVA_HOME has no bin/javac (need a JDK 17+)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null 2>&1 || die "javac not found. Install a JDK 17+ or set JAVA_HOME."
JAVAC_MAJOR="$(javac -version 2>&1 | grep -v '^Picked up' | head -n1 | sed -E 's/^javac (1\.)?([0-9]+).*/\2/')"
[ "${JAVAC_MAJOR:-0}" -ge 17 ] 2>/dev/null || die "javac $JAVAC_MAJOR is too old; JDK 17+ required (--release 17)"

# Prefer an executable in BUILD_TOOLS; fall back to PATH.
tool_path() {
  if [ -x "$BUILD_TOOLS/$1" ] && [ ! -d "$BUILD_TOOLS/$1" ]; then printf '%s' "$BUILD_TOOLS/$1"; return 0; fi
  command -v "$1" 2>/dev/null || true
}

AAPT2="${AAPT2:-$(tool_path aapt2)}"
[ -n "$AAPT2" ] && [ -x "$AAPT2" ] || die "aapt2 not found in $BUILD_TOOLS (or set AAPT2=/path/to/aapt2)"

ZIPALIGN="$(tool_path zipalign)"
[ -n "$ZIPALIGN" ] || die "zipalign not found in $BUILD_TOOLS"

D8_CMD=()
if [ -n "${D8_JAR:-}" ]; then
  [ -f "$D8_JAR" ] || die "D8_JAR does not exist: $D8_JAR"
  command -v java >/dev/null 2>&1 || die "java not found (needed to run D8_JAR)"
  D8_CMD=(java -cp "$D8_JAR" com.android.tools.r8.D8)
elif [ -n "$(tool_path d8)" ]; then
  D8_CMD=("$(tool_path d8)")
elif [ -f "$BUILD_TOOLS/lib/d8.jar" ]; then
  command -v java >/dev/null 2>&1 || die "java not found (needed to run lib/d8.jar)"
  D8_CMD=(java -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8)
else
  die "d8 not found in $BUILD_TOOLS (looked for d8 and lib/d8.jar; or set D8_JAR=/path/to/d8.jar)"
fi

APKSIGNER_CMD=()
if [ -n "$(tool_path apksigner)" ]; then
  APKSIGNER_CMD=("$(tool_path apksigner)")
elif [ -f "$BUILD_TOOLS/lib/apksigner.jar" ]; then
  command -v java >/dev/null 2>&1 || die "java not found (needed to run lib/apksigner.jar)"
  APKSIGNER_CMD=(java -jar "$BUILD_TOOLS/lib/apksigner.jar")
else
  die "apksigner not found in $BUILD_TOOLS (looked for apksigner and lib/apksigner.jar)"
fi

HAVE_ZIP=0; command -v zip >/dev/null 2>&1 && HAVE_ZIP=1
if [ "$HAVE_ZIP" -eq 0 ] && ! command -v python3 >/dev/null 2>&1; then
  die "need either 'zip' or 'python3' to add classes.dex to the APK"
fi
command -v awk >/dev/null 2>&1 || die "awk not found"

# ----------------------------------------------------------------------------- inputs
[ -d "$SRC_RES" ]      || die "resources not found: $SRC_RES (run from the project root)"
[ -d "$SRC_JAVA" ]     || die "Java sources not found: $SRC_JAVA"
[ -f "$SRC_MANIFEST" ] || die "manifest not found: $SRC_MANIFEST"
[ -f signing.properties ] || [ -n "${KEYSTORE_FILE:-}" ] || die "signing.properties not found (and KEYSTORE_FILE is not set)"

prop() { [ -f signing.properties ] && sed -n "s/^$1=//p" signing.properties | head -n1 | tr -d '\r' || true; }

KS_FILE="${KEYSTORE_FILE:-$(prop storeFile)}"
KS_ALIAS="${KEY_ALIAS:-$(prop keyAlias)}"
KS_PASS="${KEYSTORE_PASSWORD:-$(prop storePassword)}"
KEY_PASS="${KEY_PASSWORD:-$(prop keyPassword)}"
case "$KS_FILE" in
  "") die "no keystore configured (storeFile missing in signing.properties)" ;;
  /*) ;;
  *)  KS_FILE="$ROOT/$KS_FILE" ;;
esac
[ -f "$KS_FILE" ]  || die "keystore not found: $KS_FILE"
[ -n "$KS_ALIAS" ] || die "key alias missing (keyAlias in signing.properties or KEY_ALIAS)"
[ -n "$KS_PASS" ]  || die "keystore password missing (storePassword in signing.properties or KEYSTORE_PASSWORD)"
[ -n "$KEY_PASS" ] || KEY_PASS="$KS_PASS"
# Pass secrets to apksigner through the environment, not the command line (visible in `ps`).
export JD_KS_PASS="$KS_PASS" JD_KEY_PASS="$KEY_PASS"

echo "Jobbdialer manual build"
echo "  ANDROID_JAR : $ANDROID_JAR"
echo "  BUILD_TOOLS : $BUILD_TOOLS"
echo "  javac       : $(command -v javac) (major $JAVAC_MAJOR)"
echo "  version     : $VERSION_NAME ($VERSION_CODE), minSdk $MIN_SDK, targetSdk $TARGET_SDK, package $APP_PACKAGE"
echo "  keystore    : $KS_FILE (alias $KS_ALIAS)"

# ----------------------------------------------------------------------------- build
step "Clean $OUT/"
rm -rf "$ROOT/${OUT:?}"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# AGP injects the package from `namespace` in app/build.gradle.kts; aapt2 needs it in the manifest.
MANIFEST="$SRC_MANIFEST"
MANIFEST_FLAT="$(tr '\n' ' ' < "$SRC_MANIFEST")"
if ! grep -Eq '<manifest[^>]*[[:space:]]package[[:space:]]*=' <<<"$MANIFEST_FLAT"; then
  step "Injecting package=\"$APP_PACKAGE\" into a build copy of the manifest"
  MANIFEST="$OUT/AndroidManifest.xml"
  awk -v pkg="$APP_PACKAGE" 'BEGIN{done=0} { if (!done && sub(/<manifest/, "<manifest package=\"" pkg "\"")) done=1; print }' \
      "$SRC_MANIFEST" > "$MANIFEST"
  grep -q "package=\"$APP_PACKAGE\"" "$MANIFEST" || die "could not inject package into the manifest"
fi

step "aapt2 compile"
"$AAPT2" compile --dir "$SRC_RES" -o "$OUT/res.zip"

step "aapt2 link"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay "$OUT/res.zip"

step "javac"
JAVA_FILES=()
while IFS= read -r f; do JAVA_FILES+=("$f"); done < <(find "$SRC_JAVA" "$OUT/gen" -name '*.java' | LC_ALL=C sort)
[ "${#JAVA_FILES[@]}" -gt 0 ] || die "no Java sources found"
echo "  ${#JAVA_FILES[@]} source file(s)"
javac --release 17 -proc:none -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" "${JAVA_FILES[@]}"

step "d8"
CLASS_FILES=()
while IFS= read -r f; do CLASS_FILES+=("$f"); done < <(find "$OUT/classes" -name '*.class' | LC_ALL=C sort)
[ "${#CLASS_FILES[@]}" -gt 0 ] || die "javac produced no class files"
echo "  ${#CLASS_FILES[@]} class file(s)"
"${D8_CMD[@]}" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" "${CLASS_FILES[@]}"
[ -f "$OUT/dex/classes.dex" ] || die "d8 did not produce $OUT/dex/classes.dex"

step "Add classes.dex to the APK"
if [ "$HAVE_ZIP" -eq 1 ]; then
  (cd "$OUT/dex" && zip -j -q "$ROOT/$OUT/unsigned.apk" classes.dex)
else
  python3 -I - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PYEOF
fi

step "zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$ZIPALIGN" -c -p 4 "$OUT/aligned.apk" || die "zipalign verification failed"

step "apksigner sign (v1+v2+v3)"
"${APKSIGNER_CMD[@]}" sign \
  --ks "$KS_FILE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:JD_KS_PASS --key-pass env:JD_KEY_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$FINAL_APK" "$OUT/aligned.apk"

step "apksigner verify"
"${APKSIGNER_CMD[@]}" verify --verbose --print-certs "$FINAL_APK"

echo
echo "Done: $ROOT/$FINAL_APK ($(du -h "$FINAL_APK" | cut -f1))"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$FINAL_APK"
elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$FINAL_APK"; fi
