"""E3 frame-time sweep (spike branch only): sets the number of ship copies over RCON and holds each for a while.

Usage: python e3_sweep.py <rcon password> [seconds per step] [copies ...]
Default copies: 0 1 10 100 1000 0. The client logs "[spike] E3 window" lines every 5 s; e3_report.py reads them.
"""
import subprocess
import sys
import time
from pathlib import Path

RCON = Path(__file__).with_name("rcon.py")


def main() -> int:
    password = sys.argv[1]
    hold = float(sys.argv[2]) if len(sys.argv) > 2 else 11.0
    steps = [int(c) for c in sys.argv[3:]] or [0, 1, 10, 100, 1000, 0]
    for copies in steps:
        subprocess.run([sys.executable, "-I", str(RCON), password, f"we spike e3 copies {copies}"], check=True)
        print(f"copies={copies} at {time.strftime('%H:%M:%S')}", flush=True)
        time.sleep(hold)
    return 0


if __name__ == "__main__":
    sys.exit(main())
