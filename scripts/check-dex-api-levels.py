#!/usr/bin/env python3
"""Report every framework API above the floor that DiPlay's own code actually calls.

Why this exists: Android Lint's NewApi check is the right gate, but CI only runs
`:mobile:lintDebug` — and `:mobile` has no main-source Kotlin at all. The 450 files that
talk to the framework live in `:common` and `:shared`, which are never linted, and Lint
also honours `@SuppressLint("NewApi")`. This script reads the finished APK instead, so it
lists every framework call reachable from DiPlay's own classes together with the API level
the SDK says introduced it. Each API `since > floor` entry must sit behind a runtime gate.

Usage:
  python scripts/check-dex-api-levels.py <apk> --floor 23 \
      --api "<sdk>/platforms/android-XX/data/api-versions.xml" \
      --dexdump "<sdk>/build-tools/36.0.0/dexdump.exe"

Exit code 0 = nothing above the floor, 1 = at least one call site to account for.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

OWNER = re.compile(r"\(in L(com/shilapi/[A-Za-z0-9_$./]+);\)")
METHOD_NAME = re.compile(r"^\s*name\s*:\s*'([^']+)'")
INVOKE = re.compile(
    r"invoke-[^ ]*\s*\{[^}]*\},\s*"
    r"L(android/[A-Za-z0-9_$/]+);\.([A-Za-z0-9_$<>]+):(\([^)]*\)[A-Za-z0-9_$/;\[\].<>]+)",
)


def api_level(value, default=1):
    """'26', '37.0' or '' -> int. Preview SDK minor versions carry a dot."""
    if not value:
        return default
    try:
        return int(float(value.split(":")[0]))
    except ValueError:
        return default


def load_api_levels(path):
    """class -> ({'name(proto)': since}, {name: set(since)}).

    The signature-keyed map is exact. The name-keyed map only answers when the class has a
    single overload of that name, which is what keeps 'Handler.postDelayed' from being
    reported as an API 28 call when the code plainly uses the API 1 form.
    """
    by_class = {}
    for _, node in ET.iterparse(path, events=("end",)):
        if node.tag != "class":
            continue
        signature_levels = {}
        name_levels = {}
        class_since = api_level(node.get("since"))
        for member in node:
            if member.tag not in ("method", "field"):
                continue
            raw = member.get("name", "")
            since = api_level(member.get("since"), class_since)
            if raw.startswith("<"):
                continue
            if "(" in raw:
                key = raw
                name = raw.split("(")[0]
            else:
                key = name = raw
            signature_levels[key] = since
            name_levels.setdefault(name, set()).add(since)
        unique_names = {
            name: next(iter(levels)) for name, levels in name_levels.items() if len(levels) == 1
        }
        by_class[node.get("name", "")] = (signature_levels, unique_names)
        node.clear()
    return by_class


def scan(dexdump, apk, by_class, floor):
    hits = {}
    unknown = {}
    with zipfile.ZipFile(apk) as archive:
        dexes = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        with tempfile.TemporaryDirectory() as workdir:
            for name in dexes:
                path = archive.extract(name, workdir)
                listing = subprocess.run(
                    [dexdump, "-d", path], capture_output=True, text=True, errors="ignore",
                ).stdout
                owner = None
                method = "?"
                for line in listing.splitlines():
                    matched = OWNER.search(line)
                    if matched:
                        owner = matched.group(1).replace("/", ".")
                        method = "?"
                        continue
                    named = METHOD_NAME.match(line)
                    if named:
                        method = named.group(1)
                        continue
                    invoke = INVOKE.search(line)
                    if not invoke or owner is None:
                        continue
                    descriptor, called, signature = (
                        invoke.group(1), invoke.group(2), invoke.group(3),
                    )
                    entry = by_class.get(descriptor)
                    if entry is None:
                        continue
                    signature_levels, unique_names = entry
                    since = signature_levels.get(called + signature)
                    if since is None:
                        since = unique_names.get(called)
                        if since is None or since <= floor:
                            continue
                        unknown[(since, descriptor, called)] = f"{owner}.{method}"
                    if since > floor:
                        hits.setdefault((since, descriptor, called + signature), set()).add(
                            f"{owner}.{method}"
                        )
    return hits, unknown


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--floor", type=int, default=23)
    parser.add_argument("--api", default=os.environ.get("API_VERSIONS_XML", ""))
    parser.add_argument("--dexdump", default=os.environ.get("DEXDUMP", ""))
    parser.add_argument("--show-callers", action="store_true")
    args = parser.parse_args()

    if not args.api or not os.path.isfile(args.api):
        raise SystemExit("--api must point at <sdk>/platforms/<android-XX>/data/api-versions.xml")
    if not args.dexdump or not os.path.isfile(args.dexdump):
        raise SystemExit("--dexdump must point at <sdk>/build-tools/<v>/dexdump(.exe)")

    levels = load_api_levels(args.api)
    hits, by_name_only = scan(args.dexdump, args.apk, levels, args.floor)

    print(
        f"{os.path.basename(args.apk)}: DiPlay's own classes call "
        f"{len(hits)} framework APIs introduced above API {args.floor}.",
    )
    print("Every one of them needs a runtime gate, or must be unreachable below that level.\n")
    for since, descriptor, member in sorted(hits, reverse=True):
        callers = hits[(since, descriptor, member)]
        note = "  (overload matched by name only)" if (since, descriptor, member.split("(")[0]) in by_name_only else ""
        print(f"  API {since:>3}  {descriptor.replace('/', '.')}.{member}  <- {len(callers)} site(s){note}")
        if args.show_callers:
            for caller in sorted(callers):
                print(f"            {caller}")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
