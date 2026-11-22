"""E5 report (spike branch only): pops at each surface <-> ship handoff, from the client log's per-frame lines.

Usage: python -I e5_report.py <client log> [label filter]

The client logs 30 frames before and 120 after each frame switch ("[spike] E5 frame sw=up3 i=-12 ..."). For each switch:
- baseline: the median whole-frame luminance change of frames -30..-3 (the view drifts as the ship climbs);
- switch pop: a frame in -1..+6 whose change is above max(4 x baseline, baseline + 6), i.e. clearly more than the drift;
  the 3x3 grid cells that changed say where on screen;
- camera pop: the camera moved relative to the drawn ship (dCam > 0.01 block) in -1..+6;
- sky: sky/fog colour before (-2) and after (+6) the switch;
- terrain: frames after the switch with chunks missing within render distance or visible sections not yet meshed, and
  the frame where both reach zero.
"""
import re
import statistics
import sys
from collections import OrderedDict

LINE = re.compile(
    r"\[spike\] E5 frame sw=(?P<sw>\S+) i=(?P<i>-?\d+) f=(?P<f>\d+) dt=(?P<dt>[\d.]+) mode=(?P<mode>\w+) "
    r"camRel=(?P<cam>\S+) dCam=(?P<dcam>\S+) diff=(?P<diff>\S+) grid=(?P<grid>\S+) miss=(?P<miss>\d+) "
    r"holes=(?P<holes>\d+) sky=(?P<sky>\w+) fog=(?P<fog>\w+) label=\"(?P<label>[^\"]*)\"")


def num(s):
    try:
        return float(s)
    except ValueError:
        return float("nan")


def load(path, label_filter):
    switches = OrderedDict()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE.search(line)
            if not m:
                continue
            if label_filter and label_filter not in m["label"]:
                continue
            switches.setdefault(m["sw"], []).append({
                "i": int(m["i"]), "dt": num(m["dt"]), "mode": m["mode"], "dcam": num(m["dcam"]), "diff": num(m["diff"]),
                "grid": [num(x) for x in m["grid"].split(",")], "miss": int(m["miss"]), "holes": int(m["holes"]),
                "sky": m["sky"], "fog": m["fog"], "label": m["label"],
            })
    return switches


def analyse(name, frames):
    by_i = {fr["i"]: fr for fr in frames}
    base = [fr["diff"] for fr in frames if -30 <= fr["i"] <= -3 and fr["diff"] == fr["diff"]]
    baseline = statistics.median(base) if base else 0.0
    threshold = max(4 * baseline, baseline + 6.0)
    window = [by_i[i] for i in range(-1, 7) if i in by_i]
    spikes = [fr for fr in window if fr["diff"] > threshold]
    peak = max(window, key=lambda fr: fr["diff"] if fr["diff"] == fr["diff"] else -1) if window else None
    cells = []
    if peak is not None:
        cells = [k for k, v in enumerate(peak["grid"]) if v > max(4 * baseline, baseline + 6.0)]
    dcams = [fr["dcam"] for fr in window if fr["dcam"] == fr["dcam"]]
    cam_pop = max(dcams) if dcams else float("nan")
    before = by_i.get(-1) or by_i.get(-2)
    after = by_i.get(6) or by_i.get(max(by_i))
    post = [fr for fr in frames if fr["i"] >= 0]
    sky_frames = sum(1 for fr in post if before and fr["sky"] != before["sky"])
    hitch = max((fr["dt"] for fr in window), default=0.0)
    hand = max((fr["grid"][8] for fr in window if fr["grid"][8] == fr["grid"][8]), default=0.0)
    terrain = [fr for fr in post if fr["miss"] > 0 or fr["holes"] > 0]
    clear_at = None
    for fr in post:
        if fr["miss"] == 0 and fr["holes"] == 0:
            if clear_at is None:
                clear_at = fr["i"]
        else:
            clear_at = None
    ms_per_frame = statistics.median([fr["dt"] for fr in frames if fr["dt"] > 0]) if frames else 0
    return {
        "name": name, "label": frames[0]["label"], "dir": "up" if name.startswith("up") else "down",
        "baseline": baseline, "peak": peak["diff"] if peak else float("nan"), "peak_i": peak["i"] if peak else None,
        "spike_frames": len(spikes), "cells": cells, "cam": cam_pop,
        "sky": (before["sky"], after["sky"]) if before and after else ("?", "?"),
        "fog": (before["fog"], after["fog"]) if before and after else ("?", "?"),
        "terrain_frames": len(terrain), "max_miss": max((fr["miss"] for fr in post), default=0),
        "max_holes": max((fr["holes"] for fr in post), default=0), "clear_at": clear_at, "ms": ms_per_frame,
        "frames": len(frames), "sky_frames": sky_frames, "hitch": hitch, "hand": hand,
    }


def main():
    path = sys.argv[1]
    label_filter = sys.argv[2] if len(sys.argv) > 2 else ""
    switches = load(path, label_filter)
    rows = [analyse(name, frames) for name, frames in switches.items()]
    print("| switch | label | baseline | peak diff (frame) | pop frames | screen cells | camera jump | longest frame ms | "
          "hand cell | sky-changed frames | terrain frames | max missing chunks | max holes | clear at frame |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['name']} | {r['label']} | {r['baseline']:.2f} | {r['peak']:.2f} ({r['peak_i']}) | {r['spike_frames']} | "
              f"{','.join(map(str, r['cells'])) or '-'} | {r['cam']:.4f} | {r['hitch']:.0f} | {r['hand']:.1f} | "
              f"{r['sky_frames']} | {r['terrain_frames']} | {r['max_miss']} | {r['max_holes']} | {r['clear_at']} |")
    for direction in ("up", "down"):
        sel = [r for r in rows if r["dir"] == direction]
        if not sel:
            continue
        pops = sum(1 for r in sel if r["spike_frames"] > 0)
        cams = sum(1 for r in sel if r["cam"] == r["cam"] and r["cam"] > 0.01)
        skies = sum(1 for r in sel if r["sky_frames"] > 0)
        terrain = [r["terrain_frames"] for r in sel]
        hitches = [r["hitch"] for r in sel]
        hands = sum(1 for r in sel if r["hand"] > 5)
        print(f"\n{direction}: {len(sel)} switches | with a switch-frame pop: {pops} | camera jumps > 0.01: {cams} | "
              f"hand relit: {hands} | sky colour changed: {skies} (frames: {[r['sky_frames'] for r in sel]}) | "
              f"longest frame around the switch: median {statistics.median(hitches):.0f} ms, max {max(hitches):.0f} ms | "
              f"terrain-incomplete frames after: median {statistics.median(terrain)}, max {max(terrain)} | "
              f"frame time median {statistics.median([r['ms'] for r in sel]):.2f} ms")


if __name__ == "__main__":
    main()
