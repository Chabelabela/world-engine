"""Builds the E1 results table from a spike client log (spike branch only).

Usage: python e1_report.py <client latest.log> [more logs...]

Pairs each "[CHAT] [E1] run i/n preload=P to=D" line with the next "[spike] E1 jump #k: ..." summary,
then prints one Markdown row per jump and a summary per (render distance, pre-load) variant.
"""
import re
import statistics
import sys
from collections import defaultdict

LABEL = re.compile(r"\[CHAT\] \[E1\] run (\d+)/(\d+) preload=(\d+) to=(\w+)")
SUMMARY = re.compile(r"\[spike\] E1 jump #\d+: (.*)$")
FIELD = re.compile(r'(\w+)=("[^"]*"|\S+)')


def parse(path: str) -> list[dict]:
    rows, label = [], None
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            m = LABEL.search(line)
            if m:
                label = {"run": int(m[1]), "preload": int(m[3]), "to": m[4]}
                continue
            m = SUMMARY.search(line)
            if m and label is not None:
                fields = {k: v.strip('"') for k, v in FIELD.findall(m[1])}
                rows.append({**label, **fields})
                label = None
    return rows


def pct(values: list[int], q: float) -> float:
    values = sorted(values)
    return values[min(len(values) - 1, int(round(q * (len(values) - 1))))]


def main() -> int:
    rows = [row for path in sys.argv[1:] for row in parse(path)]
    if not rows:
        print("no labelled E1 jumps found")
        return 1
    print("| RD | pre-load | to | loading screen | missing frames | frames in 2 s | rx bytes in 2 s | last missing |")
    print("|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['renderDistance']} | {r['preload']} | {r['to']} | {r['loadingScreen']} | {r['missingFrames']} "
              f"| {r['framesIn2s']} | {r['rxBytes2s']} | {r.get('lastMissing', '')} |")
    groups = defaultdict(list)
    for r in rows:
        groups[(int(r["renderDistance"]), r["preload"])].append(r)
    print()
    print("| RD | pre-load | n | loading screens | missing frames median | p90 | max | <= 2 frames | rx KB in 2 s median |")
    print("|---|---|---|---|---|---|---|---|---|")
    for (rd, preload), group in sorted(groups.items()):
        missing = [int(r["missingFrames"]) for r in group]
        loading = sum(r["loadingScreen"] == "true" for r in group)
        rx = [int(r["rxBytes2s"]) for r in group if r["rxBytes2s"].isdigit()]
        rx_text = f"{statistics.median(rx) / 1024:.0f}" if rx else "n/a"
        ok = sum(m <= 2 for m in missing)
        print(f"| {rd} | {preload} | {len(group)} | {loading} | {statistics.median(missing):g} | {pct(missing, 0.9):g} "
              f"| {max(missing)} | {ok}/{len(group)} | {rx_text} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
