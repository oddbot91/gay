#!/usr/bin/env python3
"""Generate a dummy R.java (and resources.json) from an Android res/ directory.

Purpose: let plain `javac` compile the app sources against android.jar without
running aapt2/Gradle.  The ids are dummies (unique 0x7f...... values); only the
*names* matter.  Python 3 standard library only; nothing is executed.

Usage:
    gen_r.py [--package se.jobbdialer] <res_dir> <out_dir>

Writes:
    <out_dir>/se/jobbdialer/R.java    (path follows --package)
    <out_dir>/resources.json          {"string": ["app_name", ...], "id": [...], ...}

Exit codes:
    0  ok
    2  usage error / res dir missing
    3  resource problems found (invalid names, duplicates, bad XML, ...);
       the output files are still written on a best-effort basis
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

DEFAULT_PACKAGE = "se.jobbdialer"

# res/<dir> names (before the first '-' qualifier) holding one resource per file.
FILE_TYPES = (
    "anim", "animator", "color", "drawable", "font", "interpolator",
    "layout", "menu", "mipmap", "raw", "transition", "xml",
)

# Every inner class of R we always emit (also when empty), alphabetical.
ALL_TYPES = sorted(set(FILE_TYPES) | {
    "array", "attr", "bool", "dimen", "fraction", "id", "integer",
    "plurals", "string", "style", "styleable",
})

# <resources> child tag -> R inner class.
VALUE_TAGS = {
    "string": "string",
    "string-array": "array",
    "integer-array": "array",
    "array": "array",
    "plurals": "plurals",
    "bool": "bool",
    "color": "color",
    "dimen": "dimen",
    "integer": "integer",
    "fraction": "fraction",
    "attr": "attr",
    "declare-styleable": "styleable",
    "style": "style",
    "id": "id",
    "drawable": "drawable",  # <drawable name="x">#fff</drawable>
}

# Valid values of <item type="..."> / <add-resource type="...">, mapped to R classes.
ITEM_TYPE_ALIASES = {
    "string-array": "array",
    "integer-array": "array",
    "declare-styleable": "styleable",
}

# Tags that carry no resource of their own.
IGNORED_TAGS = {
    "public", "public-group", "staging-public-group", "staging-public-group-final",
    "eat-comment", "skip", "overlayable", "java-symbol", "macro",
}

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false", "null",
    "_",
}

IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
FILE_NAME_RE = re.compile(r"^[a-z_][a-z0-9_]*$")
NEW_ID_RE = re.compile(r"@\+id/([A-Za-z0-9_.\-]+)")
COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)


class Table(object):
    """Collects resource names per type and the problems found on the way."""

    def __init__(self):
        self.names = dict((t, set()) for t in ALL_TYPES)
        # styleable name -> list of (member entry name) in declaration order
        self.styleables = {}
        # styleable member entry name -> framework attr key ("android:textSize") or R.attr name
        self.styleable_attr_refs = {}
        self.attr_defs = set()
        self.attr_refs = []  # (attr name, source) referenced by declare-styleable without format
        self.seen = {}       # (qualifier dir, type, name) -> source, for duplicate detection
        self.errors = []
        self.warnings = []

    def error(self, msg):
        self.errors.append(msg)
        sys.stderr.write("gen_r: ERROR: %s\n" % msg)

    def warn(self, msg):
        self.warnings.append(msg)
        sys.stderr.write("gen_r: warning: %s\n" % msg)

    def add(self, rtype, raw_name, source, qualifier_dir, file_based=False):
        """Register one resource; returns the sanitized Java name or None."""
        if rtype not in self.names:
            self.error("%s: unsupported resource type '%s'" % (source, rtype))
            return None
        if raw_name is None or raw_name == "":
            self.error("%s: <%s> without a name" % (source, rtype))
            return None
        # aapt turns '.' (and '-') in value names into '_' for the Java field.
        name = raw_name.replace(".", "_").replace("-", "_") if not file_based else raw_name
        if not IDENT_RE.match(name):
            self.error("%s: invalid resource name '%s' (type %s)" % (source, raw_name, rtype))
            return None
        if name in JAVA_KEYWORDS:
            self.error("%s: resource name '%s' is a Java keyword (type %s)" % (source, raw_name, rtype))
            return None
        key = (qualifier_dir, rtype, name)
        if key in self.seen and rtype not in ("id", "attr", "styleable"):
            self.error("%s: duplicate resource %s/%s (also defined in %s)"
                       % (source, rtype, raw_name, self.seen[key]))
        self.seen.setdefault(key, source)
        self.names[rtype].add(name)
        return name


def sanitize(name):
    return name.replace(".", "_").replace("-", "_").replace(":", "_")


def scan_file_resources(res_dir, table):
    """res/<type>[-qualifiers]/<file> -> R.<type>.<basename before first dot>."""
    for entry in sorted(os.listdir(res_dir)):
        path = os.path.join(res_dir, entry)
        if entry.startswith("."):
            continue
        if not os.path.isdir(path):
            table.warn("%s: stray file directly in res/ (ignored)" % path)
            continue
        rtype = entry.split("-", 1)[0]
        if rtype == "values":
            continue
        if rtype not in FILE_TYPES:
            table.error("%s: unknown resource directory type '%s'" % (path, rtype))
            continue
        for fname in sorted(os.listdir(path)):
            fpath = os.path.join(path, fname)
            if fname.startswith("."):
                continue
            if os.path.isdir(fpath):
                table.error("%s: subdirectories are not allowed inside res/%s" % (fpath, entry))
                continue
            base = fname.split(".", 1)[0]  # foo.9.png -> foo
            if not FILE_NAME_RE.match(base):
                table.error("%s: invalid file-based resource name '%s' "
                            "(only a-z, 0-9 and _ allowed, must not start with a digit)" % (fpath, base))
                continue
            table.add(rtype, base, fpath, entry, file_based=True)


def handle_styleable(el, source, qualifier_dir, table):
    raw = el.get("name")
    sname = table.add("styleable", raw, source, qualifier_dir)
    if sname is None:
        return
    members = []
    for child in el:
        if not isinstance(child.tag, str):
            continue
        if child.tag != "attr":
            table.warn("%s: <declare-styleable %s> contains unexpected <%s>" % (source, raw, child.tag))
            continue
        aname = child.get("name")
        if not aname:
            table.error("%s: <attr> without name in <declare-styleable %s>" % (source, raw))
            continue
        if ":" in aname:
            # framework attribute such as android:textSize -> Foo_android_textSize
            member = "%s_%s" % (sname, sanitize(aname))
            table.styleable_attr_refs[member] = aname
        else:
            member = "%s_%s" % (sname, sanitize(aname))
            attr_java = sanitize(aname)
            table.styleable_attr_refs[member] = attr_java
            if child.get("format") is not None or len(child) > 0:
                table.names["attr"].add(attr_java)  # this declaration defines the attr
                table.attr_defs.add(attr_java)
            else:
                table.attr_refs.append((aname, source))
        members.append(member)
    table.styleables[sname] = members
    table.names["styleable"].update(members)


def scan_value_resources(res_dir, table):
    """res/values*/*.xml -> resource entries."""
    for entry in sorted(os.listdir(res_dir)):
        if not (entry == "values" or entry.startswith("values-")):
            continue
        dpath = os.path.join(res_dir, entry)
        if not os.path.isdir(dpath):
            continue
        for fname in sorted(os.listdir(dpath)):
            if not fname.endswith(".xml"):
                if not fname.startswith("."):
                    table.warn("%s: non-XML file in values dir (ignored)" % os.path.join(dpath, fname))
                continue
            source = os.path.join(dpath, fname)
            try:
                root = ET.parse(source).getroot()
            except (ET.ParseError, OSError) as exc:
                table.error("%s: cannot parse: %s" % (source, exc))
                continue
            if root.tag != "resources":
                table.error("%s: root element is <%s>, expected <resources>" % (source, root.tag))
                continue
            for el in root:
                if not isinstance(el.tag, str):
                    continue
                tag = el.tag
                if tag in IGNORED_TAGS:
                    continue
                if tag == "declare-styleable":
                    handle_styleable(el, source, entry, table)
                    continue
                if tag in ("item", "add-resource"):
                    itype = el.get("type")
                    if not itype:
                        table.error("%s: <%s name=%r> needs a type attribute" % (source, tag, el.get("name")))
                        continue
                    rtype = ITEM_TYPE_ALIASES.get(itype, itype)
                elif tag in VALUE_TAGS:
                    rtype = VALUE_TAGS[tag]
                else:
                    table.warn("%s: unknown tag <%s> (ignored)" % (source, tag))
                    continue
                name = table.add(rtype, el.get("name"), source, entry)
                if name is not None and rtype == "attr":
                    table.attr_defs.add(name)


def scan_new_ids(res_dir, table):
    """@+id/name anywhere in any res XML file defines R.id.name."""
    for dirpath, dirnames, filenames in os.walk(res_dir):
        dirnames.sort()
        for fname in sorted(filenames):
            if not fname.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fname)
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    text = fh.read()
            except (OSError, UnicodeDecodeError) as exc:
                table.error("%s: cannot read: %s" % (path, exc))
                continue
            for m in NEW_ID_RE.finditer(COMMENT_RE.sub("", text)):
                name = sanitize(m.group(1))
                if not IDENT_RE.match(name) or name in JAVA_KEYWORDS:
                    table.error("%s: invalid id name '%s'" % (path, m.group(1)))
                    continue
                table.names["id"].add(name)


def check_attr_refs(table):
    for aname, source in table.attr_refs:
        if sanitize(aname) not in table.attr_defs:
            table.error("%s: declare-styleable references attr '%s' which is never defined "
                        "(add a format or define <attr name=\"%s\" format=\"...\"/>)" % (source, aname, aname))


def render_java(package, table):
    """Return (java source, number of entries). Dummy ids are unique."""
    counter = [0]

    def next_id():
        value = 0x7F000000 + counter[0]
        counter[0] += 1
        return value

    values = {}  # (type, name) -> int
    for rtype in ALL_TYPES:
        if rtype == "styleable":
            continue
        for name in sorted(table.names[rtype]):
            values[(rtype, name)] = next_id()

    framework_attrs = {}

    def framework_id(key):
        if key not in framework_attrs:
            framework_attrs[key] = 0x01010000 + len(framework_attrs)
        return framework_attrs[key]

    out = []
    out.append("/* AUTO-GENERATED by tools/gen_r.py - dummy ids, for javac checks only. DO NOT COMMIT. */")
    out.append("package %s;" % package)
    out.append("")
    out.append("public final class R {")
    out.append("    private R() {}")
    out.append("")
    entries = 0
    for rtype in ALL_TYPES:
        out.append("    public static final class %s {" % rtype)
        out.append("        private %s() {}" % rtype)
        if rtype == "styleable":
            for sname in sorted(table.styleables):
                members = table.styleables[sname]
                elems = []
                for member in members:
                    ref = table.styleable_attr_refs.get(member, "")
                    if ":" in ref:
                        elems.append("0x%08x" % framework_id(ref))
                    else:
                        elems.append("0x%08x" % values.get(("attr", ref), next_id()))
                out.append("        public static final int[] %s = { %s };" % (sname, ", ".join(elems)))
                entries += 1
                for idx, member in enumerate(members):
                    out.append("        public static final int %s = %d;" % (member, idx))
                    entries += 1
        else:
            for name in sorted(table.names[rtype]):
                out.append("        public static final int %s = 0x%08x;" % (name, values[(rtype, name)]))
                entries += 1
        out.append("    }")
    out.append("}")
    out.append("")
    return "\n".join(out), entries


def main(argv):
    parser = argparse.ArgumentParser(
        description="Generate a dummy R.java + resources.json from an Android res/ directory.")
    parser.add_argument("res_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--package", default=DEFAULT_PACKAGE,
                        help="Java package of R (default: %s)" % DEFAULT_PACKAGE)
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return 2 if exc.code else 0

    if not os.path.isdir(args.res_dir):
        sys.stderr.write("gen_r: ERROR: res directory not found: %s\n" % args.res_dir)
        return 2
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$", args.package):
        sys.stderr.write("gen_r: ERROR: invalid package name: %s\n" % args.package)
        return 2

    table = Table()
    scan_file_resources(args.res_dir, table)
    scan_value_resources(args.res_dir, table)
    scan_new_ids(args.res_dir, table)
    check_attr_refs(table)

    java_src, entries = render_java(args.package, table)
    pkg_dir = os.path.join(args.out_dir, *args.package.split("."))
    try:
        os.makedirs(pkg_dir, exist_ok=True)
        with open(os.path.join(pkg_dir, "R.java"), "w", encoding="utf-8") as fh:
            fh.write(java_src)
        with open(os.path.join(args.out_dir, "resources.json"), "w", encoding="utf-8") as fh:
            json.dump(dict((t, sorted(table.names[t])) for t in ALL_TYPES), fh, indent=2, sort_keys=True)
            fh.write("\n")
    except OSError as exc:
        sys.stderr.write("gen_r: ERROR: cannot write output: %s\n" % exc)
        return 2

    summary = ", ".join("%s=%d" % (t, len(table.names[t])) for t in ALL_TYPES if table.names[t])
    print("gen_r: %d R fields written to %s (%s)" % (
        entries, os.path.join(pkg_dir, "R.java"), summary or "no resources"))
    if table.errors:
        sys.stderr.write("gen_r: %d resource error(s) found\n" % len(table.errors))
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
