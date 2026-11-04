#!/usr/bin/env python3
"""
Sorts a FullerScope "Export all" folder into condition / replicate folders.

On the phone: Library -> Export all -> pick (or create) a folder. That writes
FullerScope_export_<date>_<time>/ with one WAV + PDF per recording and recordings.csv.

Then on the PC, either
    python tools/organize_export.py                  # pull the newest export off the phone over USB
    python tools/organize_export.py <export folder>  # an export already copied to the PC

Naming convention (the app's name field): conditions first, replicate last as r<N>,
    "mitral bell r1", "aortic diaphragm gain 1.5 r3", "trial_A-R12"
Everything before r<N> is the condition. Case and spacing differences ("Mitral  Bell",
"mitral-bell") are treated as the same condition, so a typo in capitalisation does not
split a condition in two. Names without a trailing r<N> are put in _unsorted/ and listed,
never guessed at.

The name is read from recordings.csv, not from the filenames: filenames go through the
app's cleanup, which drops "." (so "1.5" would read as "15").

Output (next to the export unless --out is given), e.g. test_2026-11-04/:
    mitral bell/r1/001_StethoScope_Heart_....wav  (+ .pdf)
    _unsorted/...
    all_recordings.csv   every row of recordings.csv + condition, replicate, sorted_to
    summary.csv          one row per condition: replicates present, missing, duplicated

Standard library only, so it runs on any PC with Python 3.
"""
import argparse
import csv
import os
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ADB_CANDIDATES = [
    Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
    Path(__file__).resolve().parent / "installer/platform-tools/adb.exe",
]
EXPORT_PREFIX = "FullerScope_export_"
REPLICATE = re.compile(r"^(?P<cond>.*?)[\s_-]*\br(?:ep)?\s*(?P<n>\d+)\s*$", re.IGNORECASE)


def find_adb():
    for p in ADB_CANDIDATES:
        if p.is_file():
            return str(p)
    return shutil.which("adb")


def adb(adb_path, *args, serial=None):
    cmd = [adb_path] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=600)


def pull_newest_export(dest_root: Path) -> Path:
    adb_path = find_adb()
    if not adb_path:
        sys.exit("adb not found. Install Android platform-tools, or copy the export folder "
                 "to the PC and pass its path.")
    devices = [l.split()[0] for l in adb(adb_path, "devices").stdout.splitlines()[1:]
               if l.strip().endswith("device")]
    if not devices:
        sys.exit("No phone found over USB. Plug it in, unlock it, allow USB debugging, and retry "
                 "(or copy the export folder to the PC and pass its path).")
    if len(devices) > 1:
        print(f"Several phones attached ({', '.join(devices)}); using {devices[0]}.")
    serial = devices[0]
    # Trailing slash: /sdcard is a symlink, and find does not descend into one without it.
    found = adb(adb_path, "shell", f"find /sdcard/ -maxdepth 5 -type d -name '{EXPORT_PREFIX}*' 2>/dev/null",
                serial=serial).stdout.split()
    if not found:
        sys.exit("No FullerScope_export_* folder on the phone. In the app: Library -> Export all.")
    newest = max(found, key=lambda p: p.rsplit("/", 1)[-1])  # names sort by date/time
    print(f"Pulling {newest} from {serial} ...")
    dest_root.mkdir(parents=True, exist_ok=True)
    r = adb(adb_path, "pull", newest, str(dest_root), serial=serial)
    if r.returncode != 0:
        sys.exit("adb pull failed:\n" + r.stderr)
    return dest_root / newest.rsplit("/", 1)[-1]


def condition_key(cond: str) -> str:
    """Case- and separator-insensitive identity for a condition."""
    return re.sub(r"[\s_-]+", " ", cond).strip().lower()


def safe_folder(name: str) -> str:
    """Windows-safe folder name that keeps decimals ("gain 1.5")."""
    s = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "-", name).strip().rstrip(".")
    return s or "_blank"


def parse_name(name: str):
    m = REPLICATE.match(name or "")
    if not m or not m.group("cond").strip():
        return None, None
    return m.group("cond").strip(), int(m.group("n"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export", nargs="?", help="export folder already on the PC (default: pull from phone)")
    ap.add_argument("--out", help="where to put the sorted copy (default: alongside the export)")
    args = ap.parse_args()

    if args.export:
        export = Path(args.export)
    else:
        export = pull_newest_export(Path(__file__).resolve().parent.parent / "captures" / "exports")
    index = export / "recordings.csv"
    if not index.is_file():
        sys.exit(f"{index} not found. Was the export cancelled before it finished?")

    stamp = export.name[len(EXPORT_PREFIX):] if export.name.startswith(EXPORT_PREFIX) else export.name
    out = Path(args.out) if args.out else export.parent / f"sorted_{stamp}"
    if out.exists():
        sys.exit(f"{out} already exists; remove it or pass --out.")
    out.mkdir(parents=True)

    with open(index, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))

    display = {}                      # condition key -> first spelling seen, used as folder name
    reps = defaultdict(lambda: defaultdict(list))  # key -> replicate -> [row]
    unsorted = []
    for row in rows:
        cond, n = parse_name(row.get("name", ""))
        if cond is None:
            row["condition"], row["replicate"] = "", ""
            unsorted.append(row)
            continue
        key = condition_key(cond)
        display.setdefault(key, cond)
        row["condition"], row["replicate"] = display[key], str(n)
        reps[key][n].append(row)

    def place(row, folder: Path):
        folder.mkdir(parents=True, exist_ok=True)
        for col in ("wav_file", "pdf_file"):
            fn = row.get(col)
            if fn and (export / fn).is_file():
                shutil.copy2(export / fn, folder / fn)
        row["sorted_to"] = str(folder.relative_to(out))

    for key, by_n in reps.items():
        base = out / safe_folder(display[key])
        for n, group in by_n.items():
            for row in group:   # duplicates share a folder; filenames are unique by index
                place(row, base / f"r{n}")
    for row in unsorted:
        place(row, out / "_unsorted")

    fields = list(rows[0].keys()) if rows else []
    for extra in ("condition", "replicate", "sorted_to"):
        if extra not in fields:
            fields.append(extra)
    with open(out / "all_recordings.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    summary = []
    for key in sorted(reps, key=lambda k: display[k].lower()):
        ns = sorted(reps[key])
        missing = [n for n in range(1, max(ns) + 1) if n not in reps[key]]
        dup = [n for n in ns if len(reps[key][n]) > 1]
        failed = sum(1 for g in reps[key].values() for r in g if r.get("error"))
        summary.append({
            "condition": display[key],
            "recordings": sum(len(g) for g in reps[key].values()),
            "replicates": " ".join(f"r{n}" for n in ns),
            "missing": " ".join(f"r{n}" for n in missing),
            "duplicated": " ".join(f"r{n}" for n in dup),
            "export_errors": failed,
        })
    with open(out / "summary.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["condition", "recordings", "replicates", "missing",
                                          "duplicated", "export_errors"])
        w.writeheader()
        w.writerows(summary)

    print(f"\n{len(rows)} recordings -> {out}\n")
    width = max([len(s["condition"]) for s in summary] + [9])
    print(f"{'condition':<{width}}  n   replicates")
    for s in summary:
        flags = []
        if s["missing"]:
            flags.append(f"MISSING {s['missing']}")
        if s["duplicated"]:
            flags.append(f"DUPLICATE {s['duplicated']}")
        if s["export_errors"]:
            flags.append(f"{s['export_errors']} FAILED TO EXPORT")
        print(f"{s['condition']:<{width}}  {s['recordings']:<3} {s['replicates']}"
              + (f"   <-- {'; '.join(flags)}" if flags else ""))
    if unsorted:
        print(f"\n{len(unsorted)} without a trailing r<N> (copied to _unsorted/, check the name):")
        for r in unsorted:
            print(f"  #{r.get('index')}  {r.get('recorded_at')}  name={r.get('name')!r}")
    errors = [r for r in rows if r.get("error")]
    if errors:
        print(f"\n{len(errors)} recording(s) the phone could not export:")
        for r in errors:
            print(f"  #{r.get('index')}  {r.get('name')!r}: {r.get('error')}")


if __name__ == "__main__":
    main()
