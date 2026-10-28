#!/usr/bin/env python3
"""Transcribe one audiobook into a Voice transcript (.json) and an .lrc sidecar.

Usage: transcribe.py <m4b path> [--out DIR]

The JSON is an array of {"timestamp_start": ms, "timestamp_end": ms, "text": str},
one object per segment, which is what core/transcript's TranscriptParser reads.
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

DEFAULT_OUT = Path("/mnt/f/Transcripts")
MODEL = "large-v3-turbo"

_model = None


def load_model():
    global _model
    if _model is None:
        from faster_whisper import WhisperModel

        _model = WhisperModel(MODEL, device="cuda", compute_type="float16")
    return _model


def output_paths(audio: Path, out_dir: Path) -> tuple[Path, Path]:
    return out_dir / f"{audio.stem}.json", out_dir / f"{audio.stem}.lrc"


def is_up_to_date(audio: Path, out_dir: Path) -> bool:
    json_path, lrc_path = output_paths(audio, out_dir)
    audio_mtime = audio.stat().st_mtime
    return all(p.exists() and p.stat().st_mtime > audio_mtime for p in (json_path, lrc_path))


def book_prompt(audio: Path) -> str:
    """Title and author as a starting prompt, so Whisper spells their names right."""
    title = re.sub(r"\s*\[[A-Z0-9]{10}\]$", "", audio.stem)
    author = ""
    try:
        import av

        with av.open(str(audio)) as container:
            tags = {k.lower(): v for k, v in container.metadata.items()}
        title = tags.get("title") or title
        author = tags.get("artist") or tags.get("album_artist") or ""
    except Exception:
        pass
    return f"{title}, by {author}." if author else f"{title}."


def lrc_timestamp(ms: int) -> str:
    minutes, rest = divmod(ms, 60_000)
    seconds, millis = divmod(rest, 1000)
    return f"[{minutes:02d}:{seconds:02d}.{millis // 10:02d}]"


def write_atomic(path: Path, text: str) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def transcribe(audio: Path, out_dir: Path = DEFAULT_OUT, force: bool = False) -> dict | None:
    """Transcribe `audio`; returns stats, or None if the output was already current."""
    out_dir.mkdir(parents=True, exist_ok=True)
    if not force and is_up_to_date(audio, out_dir):
        return None

    started = time.monotonic()
    segments, info = load_model().transcribe(
        str(audio),
        language="en",
        initial_prompt=book_prompt(audio),
        vad_filter=True,
    )
    entries = []
    for segment in segments:
        text = segment.text.strip()
        if not text:
            continue
        entries.append(
            {
                "timestamp_start": int(round(segment.start * 1000)),
                "timestamp_end": int(round(segment.end * 1000)),
                "text": text,
            }
        )
    wall = time.monotonic() - started

    json_path, lrc_path = output_paths(audio, out_dir)
    write_atomic(json_path, json.dumps(entries, ensure_ascii=False, indent=1))
    write_atomic(
        lrc_path,
        "".join(f"{lrc_timestamp(e['timestamp_start'])}{e['text']}\n" for e in entries),
    )
    return {
        "file": str(audio),
        "duration_s": info.duration,
        "wall_s": wall,
        "speed": info.duration / wall if wall else 0.0,
        "segments": len(entries),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("audio", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--force", action="store_true", help="re-transcribe even if output is current")
    args = parser.parse_args()

    stats = transcribe(args.audio, args.out, args.force)
    if stats is None:
        print(f"up to date: {args.audio}")
    else:
        print(
            f"{stats['file']}: {stats['duration_s'] / 60:.1f} min audio in "
            f"{stats['wall_s'] / 60:.1f} min ({stats['speed']:.1f}x), {stats['segments']} segments"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
