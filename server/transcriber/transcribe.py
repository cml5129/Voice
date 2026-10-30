#!/usr/bin/env python3
"""Transcribe one audiobook into a Voice transcript (.json) and an .lrc sidecar.

Usage: transcribe.py <m4b path> [--out DIR]

The JSON is an array of {"timestamp_start": ms, "timestamp_end": ms, "text": str},
one object per segment, which is what core/transcript's TranscriptParser reads.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_OUT = Path("/mnt/f/Transcripts")
MODEL = "large-v3-turbo"
SAMPLE_RATE = 16000
CHUNK_SECONDS = 30 * 60

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


def audio_duration(audio: Path) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(audio)],
        capture_output=True,
        text=True,
        check=True,
    )
    return float(result.stdout.strip())


def decode_chunk(audio: Path, offset: int, length: int):
    """Decode `length` seconds from `offset` as 16 kHz mono float32, the input Whisper expects."""
    import numpy as np

    result = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-ss", str(offset), "-t", str(length), "-i", str(audio),
         "-f", "s16le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(result.stdout, dtype=np.int16).astype(np.float32) / 32768.0


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
    duration = audio_duration(audio)
    entries = []
    # faster-whisper decodes a whole file into RAM (about 6 bytes per sample), which runs
    # a 40-hour book out of memory, so feed it fixed-length chunks decoded by ffmpeg.
    for offset in range(0, int(duration) + 1, CHUNK_SECONDS):
        samples = decode_chunk(audio, offset, CHUNK_SECONDS)
        if samples.size == 0:
            break
        # No initial_prompt or hotwords: both fixed name spellings but made Whisper merge
        # sentences into 15-30 s segments, which is worse for following along.
        segments, _ = load_model().transcribe(samples, language="en", vad_filter=True)
        for segment in segments:
            text = segment.text.strip()
            if not text:
                continue
            entries.append(
                {
                    "timestamp_start": int(round((offset + segment.start) * 1000)),
                    "timestamp_end": int(round((offset + segment.end) * 1000)),
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
        "duration_s": duration,
        "wall_s": wall,
        "speed": duration / wall if wall else 0.0,
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
