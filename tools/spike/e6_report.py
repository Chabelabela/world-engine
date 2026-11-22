"""E6 report (spike branch only): do two clients aboard one ship agree on where everyone stands (Q5)?

Usage: python -I e6_report.py <client A log> <client B log> [--from LABEL]

With --from, each log is read from the first settings line with that label on (one log can hold several runs).

Each client logs one line per tick ("[spike] E6 tick c=… pose=… mode=… self=(x,y,z) peers=Name:seen(x,y,z):rep(…)…"):
- self: where this player stands relative to the ship as this client draws it (ship cells);
- peers: where this client draws each other player relative to the same ship ("seen"), or "other" (drawn in the other
  frame, so not visible here), or "absent" (no entity on this client); "rep" is that player's own last report as
  relayed by the server.

The two logs are lined up on an estimated server tick: the pose tick while the ship moves (one pose per tick), counted
on from the last pose change while it doesn't. For each viewer and peer, per tick:
- error: |where the viewer draws the peer - where the peer stands by its own client| at the same server tick;
- the peer counts as walking if its own position changed within the last 10 ticks.
Bytes: mean wire bytes per second received by each client, by phase, and in the 2 s after each frame switch.
"""
import bisect
import math
import re
import statistics
import sys
from collections import defaultdict

TICK = re.compile(
    r"\[spike\] E6 tick c=(?P<c>\d+) pose=(?P<pose>\d+) pend=(?P<pend>\d+) mv=(?P<mv>[01]) mode=(?P<mode>\w+) "
    r"self=\((?P<self>[^)]*)\) peers=(?P<peers>\S+) rx=(?P<rx>\d+) nPose=\d+ nPeer=\d+ fix=(?P<fix>\d+) label=\"(?P<label>[^\"]*)\"")
SETTINGS = re.compile(r"\[spike\] E5 client settings: flags=(?P<flags>\d+) label=\"(?P<label>[^\"]*)\"")
SWITCH = re.compile(r"\[spike\] E5 client switch to (?P<to>ship|surface) frame")
PEER = re.compile(r"(?P<name>[^:;]+):(?:(?P<absent>absent)|(?P<kind>seen|other)\((?P<pos>[^)]*)\)(?::rep\((?P<rep>[^)]*)\)(?P<repflags>\w+?)(?P<reptick>\d+))?)")
FIX_PEERS = 128
WALK_WINDOW = 10
AFTER = 40


def vec(s):
    return tuple(float(x) for x in s.split(","))


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def load(path, start_label=None):
    ticks = []
    flags = 0
    switch_next = None
    started = start_label is None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = SETTINGS.search(line)
            if m:
                flags = int(m["flags"])
                started = started or m["label"] == start_label
                continue
            if not started:
                continue
            m = SWITCH.search(line)
            if m:
                switch_next = m["to"]
                continue
            m = TICK.search(line)
            if not m:
                continue
            peers = {}
            if m["peers"] != "-":
                for part in m["peers"].split(";"):
                    p = PEER.fullmatch(part)
                    if not p:
                        continue
                    if p["absent"]:
                        peers[p["name"]] = ("absent", None, None)
                    else:
                        peers[p["name"]] = (p["kind"], vec(p["pos"]), vec(p["rep"]) if p["rep"] else None)
            ticks.append({
                "c": int(m["c"]), "pose": int(m["pose"]), "pend": int(m["pend"]), "mv": m["mv"] == "1", "mode": m["mode"],
                "self": vec(m["self"]), "peers": peers, "rx": int(m["rx"]), "label": m["label"], "flags": flags,
                "switch": switch_next,
            })
            switch_next = None
    # Estimated server tick: the pose tick when it changes, else counted on from the last change.
    last_pose, last_c = None, None
    for t in ticks:
        if last_pose is None or t["pose"] != last_pose:
            last_pose, last_c = t["pose"], t["c"]
        t["s"] = last_pose + (t["c"] - last_c)
    return ticks


def name_of(own, other):
    """A client's player name: the peer name the other client logs."""
    names = defaultdict(int)
    for t in other:
        for n in t["peers"]:
            names[n] += 1
    return max(names, key=names.get) if names else "?"


def phase(t, since_switch):
    if since_switch is not None and since_switch[1] < AFTER:
        return f"{since_switch[0]} +0..{AFTER - 1}"
    if t["mode"] == "ship":
        return "ship frame"
    return "surface, moving" if t["mv"] else "surface, resting"


def pct(values, q):
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, int(q * len(s)))]


def main():
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    start_label = None
    if "--from" in sys.argv:
        start_label = sys.argv[sys.argv.index("--from") + 1]
        args = [x for x in args if x != start_label]
    a, b = load(args[0], start_label), load(args[1], start_label)
    na, nb = name_of(a, b), name_of(b, a)
    clients = {na: a, nb: b}
    # Each client's own position by estimated server tick.
    own = {}
    for name, ticks in clients.items():
        keys = [t["s"] for t in ticks]
        own[name] = (keys, ticks)

    def self_at(name, s):
        keys, ticks = own[name]
        i = bisect.bisect_right(keys, s) - 1
        if i < 0:
            return None, False
        t = ticks[i]
        j = bisect.bisect_left(keys, s - WALK_WINDOW)
        walking = any(ticks[k]["self"] != t["self"] for k in range(max(0, j), i))
        return t["self"], walking

    rows = defaultdict(lambda: {"err": [], "rep": [], "absent": 0, "other": 0, "n": 0})
    settled = []
    for viewer, ticks in clients.items():
        since = None
        pending_after = []
        for t in ticks:
            if t["switch"]:
                since = [("up" if t["switch"] == "ship" else "down"), 0]
                pending_after.append([since[0], t["s"] + AFTER, t["flags"]])
            elif since is not None:
                since[1] += 1
            if not t["label"] or t["label"] in ("start", "settle"):
                continue
            fix = "peer fix" if t["flags"] & FIX_PEERS else "vanilla"
            for peer, (kind, pos, rep) in t["peers"].items():
                truth, walking = self_at(peer, t["s"])
                key = (fix, "walking" if walking else "standing", phase(t, since))
                row = rows[key]
                row["n"] += 1
                if kind == "absent":
                    row["absent"] += 1
                    continue
                if kind == "other":
                    row["other"] += 1
                    continue
                if truth is not None:
                    row["err"].append(dist(pos, truth))
                if rep is not None:
                    row["rep"].append(dist(pos, rep))
                for p in pending_after:
                    if p[1] == t["s"] and truth is not None:
                        settled.append((viewer, peer, p[0], fix, walking, dist(pos, truth)))
    print(f"Clients: {na} (log 1), {nb} (log 2)\n")
    print("| peers | peer | phase | ticks | error median | error p95 | error max | vs relayed report, max | absent | other frame |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    order = ["surface, resting", "surface, moving", "up +0..39", "ship frame", "down +0..39"]
    for key in sorted(rows, key=lambda k: (k[0], k[1], order.index(k[2]) if k[2] in order else 9)):
        r = rows[key]
        e = r["err"]
        print(f"| {key[0]} | {key[1]} | {key[2]} | {r['n']} | {pct(e, 0.5):.3f} | {pct(e, 0.95):.3f} | "
              f"{max(e) if e else float('nan'):.3f} | {max(r['rep']) if r['rep'] else float('nan'):.3f} | "
              f"{r['absent']} | {r['other']} |")
    print(f"\n{AFTER} ticks after each switch (both players in the new frame):\n")
    print("| viewer | peer | switch | peers | peer walking | error |")
    print("|---|---|---|---|---|---|")
    for v, p, sw, fix, walking, e in settled:
        print(f"| {v} | {p} | {sw} | {fix} | {'yes' if walking else 'no'} | {e:.3f} |")

    print("\nBytes received (wire, incl. framing), mean per second:\n")
    print("| client | label | phase | ticks | B/s |")
    print("|---|---|---|---|---|")
    for name, ticks in clients.items():
        groups = defaultdict(list)
        for t in ticks:
            label = t["label"].split(" ")[0]
            ph = "ship frame" if t["mode"] == "ship" else ("surface, moving" if t["mv"] else "surface, resting")
            groups[(label, ph)].append(t["rx"])
        for (label, ph), rx in sorted(groups.items()):
            if len(rx) >= 20:
                print(f"| {name} | {label} | {ph} | {len(rx)} | {statistics.mean(rx) * 20:.0f} |")
    print("\nBytes in the 2 s after each switch:\n")
    print("| client | switch | flags | bytes |")
    print("|---|---|---|---|")
    for name, ticks in clients.items():
        for i, t in enumerate(ticks):
            if t["switch"]:
                print(f"| {name} | {'up' if t['switch'] == 'ship' else 'down'} | {t['flags']} | "
                      f"{sum(x['rx'] for x in ticks[i:i + 40])} |")


if __name__ == "__main__":
    main()
