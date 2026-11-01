#!/usr/bin/env python3
"""Write the chapter and half-hour summaries of one transcribed audiobook.

Usage: summarize.py <m4b path> [--out DIR] [--model NAME]

Reads <book>.json from the transcripts folder, asks the local Ollama for a short summary
of every chapter (the m4b's chapter marks) and every 30 minutes, and writes
<book>.summaries.json next to it:

    {"version": 1, "model": "qwen3:14b", "complete": true,
     "chapters": [{"title": str, "startMs": int, "endMs": int, "summary": str}],
     "halfHours": [{"startMs": int, "endMs": int, "summary": str}]}

Times are positions in the audio file. Each summary covers its whole part; the app shows
one only once the listener has heard all of that part, so nothing is spoiled. The file is
rewritten after every summary with "complete": false, so a stopped run picks up where it
left off and the app can already use what is done.
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_OUT = Path("/mnt/f/Transcripts")
DEFAULT_MODEL = os.environ.get("VOICE_SUMMARY_MODEL", "qwen3:14b")
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
HALF_HOUR_MS = 30 * 60 * 1000
CONTEXT_TOKENS = 16384
# fits CONTEXT_TOKENS with room for the instructions and the answer
MAX_INPUT_CHARS = 40_000
PART_WORDS = 120
NOTE_WORDS = 150
VERSION = 1


def summaries_path(audio: Path, out_dir: Path) -> Path:
    return out_dir / f"{audio.stem}.summaries.json"


def transcript_path(audio: Path, out_dir: Path) -> Path:
    return out_dir / f"{audio.stem}.json"


def is_up_to_date(audio: Path, out_dir: Path) -> bool:
    """True when the summaries are complete and newer than the transcript."""
    path = summaries_path(audio, out_dir)
    transcript = transcript_path(audio, out_dir)
    if not path.exists() or not transcript.exists():
        return False
    if path.stat().st_mtime < transcript.stat().st_mtime:
        return False
    try:
        return bool(json.loads(path.read_text(encoding="utf-8")).get("complete"))
    except (OSError, ValueError):
        return False


def summary_system(words: int) -> str:
    return (
        "You summarize part of an audiobook from a rough speech-to-text transcript, for a listener who has "
        f"heard this part and wants to remember it. In at most {words} words of plain prose, say what happens "
        "or what is explained: the people, places and events, and anything important that comes out. Use only "
        "this transcript. Never mention or hint at anything from later in the book, even if you know it. Names "
        "in the transcript may be misheard; fix the spelling only when you're sure. No headings, no lists."
    )


def combine_system(words: int) -> str:
    return (
        "You get notes on consecutive stretches of one part of an audiobook, in order. Turn them into one "
        f"summary of at most {words} words of plain prose, for a listener who has heard this part and wants to "
        "remember it. Use only the notes. Never mention or hint at anything from later in the book, even if "
        "you know it. No headings, no lists."
    )


def chat(model: str, system: str, prompt: str) -> str:
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
            "stream": False,
            "think": False,
            "options": {"num_ctx": CONTEXT_TOKENS},
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{OLLAMA_URL}/api/chat", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        answer = json.loads(response.read().decode("utf-8"))["message"]["content"]
    # models that think anyway put it in front of the answer
    if "</think>" in answer:
        answer = answer.split("</think>", 1)[1]
    answer = answer.strip()
    if not answer:
        raise RuntimeError("Ollama returned no summary")
    return answer


def split_text(text: str, max_chars: int) -> list[str]:
    """Splits at spaces into pieces of at most about max_chars."""
    pieces = []
    start = 0
    while start < len(text):
        end = min(start + max_chars, len(text))
        if end < len(text):
            space = text.rfind(" ", start, end)
            if space > start:
                end = space
        piece = text[start:end].strip()
        if piece:
            pieces.append(piece)
        start = end
    return pieces


def summarize_text(model: str, about: str, text: str, words: int) -> str:
    pieces = split_text(text, MAX_INPUT_CHARS)
    if len(pieces) == 1:
        return chat(model, summary_system(words), f'{about}\n\nTranscript:\n"""\n{text}\n"""')
    # too long for one request: summarize stretch by stretch, then put those together
    notes = [
        chat(
            model,
            summary_system(NOTE_WORDS),
            f'{about}\nStretch {index + 1} of {len(pieces)}.\n\nTranscript:\n"""\n{piece}\n"""',
        )
        for index, piece in enumerate(pieces)
    ]
    joined = "\n\n".join(f"Stretch {index + 1}:\n{note}" for index, note in enumerate(notes))
    return chat(model, combine_system(words), f"{about}\n\n{joined}")


def format_position(ms: int) -> str:
    seconds = ms // 1000
    return f"{seconds // 3600}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


def probe(audio: Path) -> tuple[dict, list[dict], int]:
    """The audio's tags, its chapter marks and its duration in ms."""
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_chapters", "-show_format", "-of", "json", str(audio)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(result.stdout)
    duration = int(float(data["format"]["duration"]) * 1000)
    tags = {k.lower(): v for k, v in (data["format"].get("tags") or {}).items()}
    chapters = []
    for index, chapter in enumerate(data.get("chapters") or []):
        start = int(round(float(chapter["start_time"]) * 1000))
        end = int(round(float(chapter["end_time"]) * 1000))
        title = ((chapter.get("tags") or {}).get("title") or "").strip() or f"Chapter {index + 1}"
        if end > start:
            chapters.append({"title": title, "startMs": start, "endMs": end})
    if not chapters:
        chapters = [{"title": tags.get("title") or audio.stem, "startMs": 0, "endMs": duration}]
    return tags, chapters, duration


def text_between(cues: list[dict], start: int, end: int) -> str:
    return " ".join(c["text"].strip() for c in cues if start <= c["timestamp_start"] < end).strip()


def write_atomic(path: Path, data: dict) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def summarize(audio: Path, out_dir: Path = DEFAULT_OUT, model: str = DEFAULT_MODEL, force: bool = False) -> dict | None:
    """Summarize `audio`; returns stats, or None if the summaries were already current."""
    if not force and is_up_to_date(audio, out_dir):
        return None
    transcript = transcript_path(audio, out_dir)
    if not transcript.exists():
        raise FileNotFoundError(f"no transcript at {transcript}")
    cues = json.loads(transcript.read_text(encoding="utf-8"))
    tags, chapters, duration = probe(audio)
    author = (tags.get("artist") or tags.get("album_artist") or "").strip()
    title = (tags.get("album") or tags.get("title") or audio.stem).strip()
    book = f"Book: {title} by {author}" if author else f"Book: {title}"

    path = summaries_path(audio, out_dir)
    done: dict[tuple[str, int], str] = {}
    if path.exists() and not force:
        try:
            previous = json.loads(path.read_text(encoding="utf-8"))
            if previous.get("model") == model and previous.get("version") == VERSION:
                for kind in ("chapters", "halfHours"):
                    for part in previous.get(kind) or []:
                        done[(kind, part["startMs"])] = part["summary"]
        except (OSError, ValueError, KeyError):
            pass

    half_hours = [
        {"startMs": start, "endMs": min(start + HALF_HOUR_MS, duration)} for start in range(0, duration, HALF_HOUR_MS)
    ]
    result = {"version": VERSION, "model": model, "complete": False, "chapters": [], "halfHours": []}
    started = time.monotonic()
    written = 0
    for kind, parts in (("chapters", chapters), ("halfHours", half_hours)):
        for part in parts:
            summary = done.get((kind, part["startMs"]))
            if summary is None:
                text = text_between(cues, part["startMs"], part["endMs"])
                if not text:
                    continue
                stretch = f"{format_position(part['startMs'])} to {format_position(part['endMs'])} of the book"
                about = f'{book}\nChapter "{part["title"]}" ({stretch}).' if kind == "chapters" else f"{book}\nThe half hour from {stretch}."
                summary = summarize_text(model, about, text, PART_WORDS)
                written += 1
            result[kind].append({**part, "summary": summary})
            write_atomic(path, result)
    result["complete"] = True
    write_atomic(path, result)
    return {
        "file": str(audio),
        "chapters": len(result["chapters"]),
        "half_hours": len(result["halfHours"]),
        "written": written,
        "wall_s": time.monotonic() - started,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("audio", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--force", action="store_true", help="write every summary again")
    args = parser.parse_args()

    stats = summarize(args.audio, args.out, args.model, args.force)
    if stats is None:
        print(f"up to date: {args.audio}")
    else:
        print(
            f"{stats['file']}: {stats['chapters']} chapters, {stats['half_hours']} half hours, "
            f"{stats['written']} written in {stats['wall_s'] / 60:.1f} min"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
