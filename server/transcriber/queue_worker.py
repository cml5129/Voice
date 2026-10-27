#!/usr/bin/env python3
"""Long-running worker that transcribes the whole audiobook library.

Each pass handles the paths listed in priority.txt first (one per line), then every
other .m4b under the library, newest first. Files whose output is current are skipped.
When a pass finds nothing to do, the worker sleeps and rescans.
"""

import argparse
import logging
import sys
import time
from pathlib import Path

from transcribe import DEFAULT_OUT, is_up_to_date, transcribe

HERE = Path(__file__).resolve().parent
DEFAULT_LIBRARY = Path("/mnt/f/Libation")
DEFAULT_PRIORITY = HERE / "priority.txt"

log = logging.getLogger("voice-transcriber")


def read_priority(path: Path) -> list[Path]:
    if not path.exists():
        return []
    lines = (line.strip() for line in path.read_text(encoding="utf-8").splitlines())
    return [Path(line) for line in lines if line and not line.startswith("#")]


def build_queue(library: Path, priority_file: Path) -> list[Path]:
    priority = [p for p in read_priority(priority_file) if p.is_file()]
    seen = {p.resolve() for p in priority}
    rest = [p for p in library.rglob("*.m4b") if p.resolve() not in seen]
    rest.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return priority + rest


def run_pass(library: Path, out_dir: Path, priority_file: Path) -> int:
    done = 0
    for audio in build_queue(library, priority_file):
        if is_up_to_date(audio, out_dir):
            continue
        log.info("start %s", audio)
        try:
            stats = transcribe(audio, out_dir)
        except Exception:
            log.exception("failed %s", audio)
            continue
        if stats:
            done += 1
            log.info(
                "done %s duration=%.0fs wall=%.0fs speed=%.1fx segments=%d",
                audio,
                stats["duration_s"],
                stats["wall_s"],
                stats["speed"],
                stats["segments"],
            )
    return done


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--library", type=Path, default=DEFAULT_LIBRARY)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--priority", type=Path, default=DEFAULT_PRIORITY)
    parser.add_argument("--idle-sleep", type=int, default=3600, help="seconds between rescans when idle")
    parser.add_argument("--once", action="store_true", help="exit after one pass")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    while True:
        done = run_pass(args.library, args.out, args.priority)
        log.info("pass complete, %d transcribed", done)
        if args.once:
            return 0
        if done == 0:
            time.sleep(args.idle_sleep)


if __name__ == "__main__":
    sys.exit(main())
