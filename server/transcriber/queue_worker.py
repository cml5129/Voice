#!/usr/bin/env python3
"""Long-running worker that transcribes and summarizes the books Chris picks, plus new arrivals.

Each pass handles, in order: the books the Voice app asked for (one JSON file per request
in requests/, written by the site's POST /api/transcripts/requests), the paths listed in
priority.txt (one per line), then every .m4b under the library that arrived after the
worker was first started (newest first). The rest of the library is left alone unless
--all is given. Every book is summarized right after its transcript (summarize.py), and
books transcribed before summaries existed get theirs at the end of the pass. Files whose
output is current are skipped. When a pass finds nothing to do, the worker sleeps, and
wakes early for a new request.
"""

import argparse
import json
import logging
import re
import sys
import time
from pathlib import Path

import summarize
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


def new_since(out_dir: Path) -> float:
    """When "new" starts: the first time the worker ran, kept in a marker file."""
    marker = out_dir / ".new-since"
    if not marker.exists():
        out_dir.mkdir(parents=True, exist_ok=True)
        marker.write_text(f"{time.time()}\n", encoding="utf-8")
    return float(marker.read_text(encoding="utf-8").strip())


def match_key(name: str) -> str:
    """The same key the app's transcriptMatchKey makes: letters and digits, lowercased."""
    return re.sub(r"[^0-9a-z]", "", name.lower())


def requests_dir(out_dir: Path) -> Path:
    return out_dir / "requests"


def read_requests(out_dir: Path, library: Path) -> list[Path]:
    """The library books the app asked for, oldest request first. Requests for a book that is
    already transcribed, or that isn't in the library, are removed."""
    folder = requests_dir(out_dir)
    if not folder.is_dir():
        return []
    files = sorted(folder.glob("*.json"), key=lambda f: f.stat().st_mtime)
    if not files:
        return []
    by_key = {match_key(p.stem): p for p in library.rglob("*.m4b")}
    books = []
    for file in files:
        try:
            name = str(json.loads(file.read_text(encoding="utf-8"))["name"])
        except (OSError, ValueError, KeyError, TypeError):
            log.warning("unreadable request %s", file)
            file.unlink(missing_ok=True)
            continue
        audio = by_key.get(match_key(name))
        if audio is None:
            log.warning("requested book not in the library: %s", name)
            file.unlink(missing_ok=True)
        elif is_up_to_date(audio, out_dir):
            file.unlink(missing_ok=True)
        else:
            log.info("requested %s", audio)
            books.append(audio)
    return books


def build_queue(library: Path, out_dir: Path, priority_file: Path, everything: bool) -> list[Path]:
    requested = read_requests(out_dir, library)
    priority = [p for p in read_priority(priority_file) if p.is_file()]
    seen = {p.resolve() for p in requested}
    priority = [p for p in priority if p.resolve() not in seen]
    seen |= {p.resolve() for p in priority}
    cutoff = 0.0 if everything else new_since(out_dir)
    rest = [p for p in library.rglob("*.m4b") if p.resolve() not in seen and p.stat().st_mtime > cutoff]
    rest.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return requested + priority + rest


def summarize_book(audio: Path, out_dir: Path) -> bool:
    try:
        stats = summarize.summarize(audio, out_dir)
    except Exception:
        # Ollama may be down or busy; the summaries are picked up again next pass
        log.exception("summaries failed %s", audio)
        return False
    if stats:
        log.info(
            "summarized %s chapters=%d half_hours=%d written=%d wall=%.0fs",
            audio,
            stats["chapters"],
            stats["half_hours"],
            stats["written"],
            stats["wall_s"],
        )
    return stats is not None


def run_pass(library: Path, out_dir: Path, priority_file: Path, everything: bool = False) -> int:
    done = 0
    queue = build_queue(library, out_dir, priority_file, everything)
    for audio in queue:
        if not is_up_to_date(audio, out_dir):
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
        if summarize_book(audio, out_dir):
            done += 1
        # a request is done once its book is
        for request in requests_dir(out_dir).glob("*.json") if requests_dir(out_dir).is_dir() else []:
            try:
                if match_key(str(json.loads(request.read_text(encoding="utf-8"))["name"])) == match_key(audio.stem):
                    request.unlink(missing_ok=True)
            except (OSError, ValueError, KeyError, TypeError):
                pass
        if read_requests_waiting(out_dir, queue):
            # a new request goes ahead of the rest of this pass
            break
    else:
        # books transcribed before summaries existed
        for audio in transcribed_books(library, out_dir):
            if summarize_book(audio, out_dir):
                done += 1
            if any(requests_dir(out_dir).glob("*.json")) if requests_dir(out_dir).is_dir() else False:
                break
    return done


def read_requests_waiting(out_dir: Path, queue: list[Path]) -> bool:
    """True when a request came in for a book that isn't in this pass's queue."""
    folder = requests_dir(out_dir)
    if not folder.is_dir():
        return False
    queued = {match_key(p.stem) for p in queue}
    for request in folder.glob("*.json"):
        try:
            if match_key(str(json.loads(request.read_text(encoding="utf-8"))["name"])) not in queued:
                return True
        except (OSError, ValueError, KeyError, TypeError):
            return True
    return False


def transcribed_books(library: Path, out_dir: Path) -> list[Path]:
    """Library books that have a transcript, most recently transcribed first."""
    books = [p for p in library.rglob("*.m4b") if (out_dir / f"{p.stem}.json").exists()]
    books.sort(key=lambda p: (out_dir / f"{p.stem}.json").stat().st_mtime, reverse=True)
    return books


def idle(out_dir: Path, seconds: int) -> None:
    """Sleeps up to `seconds`, waking early when the app asks for a book."""
    deadline = time.monotonic() + seconds
    folder = requests_dir(out_dir)
    while time.monotonic() < deadline:
        if folder.is_dir() and any(folder.glob("*.json")):
            return
        time.sleep(30)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--library", type=Path, default=DEFAULT_LIBRARY)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--priority", type=Path, default=DEFAULT_PRIORITY)
    parser.add_argument("--idle-sleep", type=int, default=3600, help="seconds between rescans when idle")
    parser.add_argument("--once", action="store_true", help="exit after one pass")
    parser.add_argument("--all", action="store_true", help="also transcribe the books already in the library")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    while True:
        done = run_pass(args.library, args.out, args.priority, args.all)
        log.info("pass complete, %d transcribed", done)
        if args.once:
            return 0
        if done == 0:
            idle(args.out, args.idle_sleep)


if __name__ == "__main__":
    sys.exit(main())
