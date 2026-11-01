# Voice transcriber

Turns the audiobook library into transcripts the app can show. It runs as a systemd
service inside the `mediasite` WSL distro (outside Docker) and uses faster-whisper
(`large-v3-turbo`, CUDA, float16, VAD on, English) on the host GPU.

For every `<book>.m4b` it writes to `/mnt/f/Transcripts`:

- `<book>.json`: an array of `{"timestamp_start": ms, "timestamp_end": ms, "text": "..."}`,
  one per segment, the format `core/transcript`'s `TranscriptParser` reads.
- `<book>.lrc`: `[mm:ss.xx]text` lines, which Jellyfin can serve as lyrics.

- `<book>.summaries.json`: a short summary of every chapter (the m4b's chapter marks) and
  every 30 minutes, written by `summarize.py` with the local Ollama right after the
  transcript (`{"version", "model", "complete", "chapters": [{title, startMs, endMs, summary}],
  "halfHours": [{startMs, endMs, summary}]}`, times in the audio file). The app shows a
  summary only once the listener has heard all of its part.

Outputs are written to a temp file and renamed, and a book is skipped when its outputs
exist and are newer than the audio. Summaries are rewritten after every part with
`"complete": false`, so a stopped run carries on where it left off.

## Requests from the app

When a book on the phone has no transcript, the app asks the site
(`POST /api/transcripts/requests`), which drops `{"name": "<audio file name>"}` into
`/mnt/f/Transcripts/requests/`. The worker matches the name to a library `.m4b` the same
way the app matches transcripts (letters and digits, lowercased), does those books first,
and deletes the request once the book is done or isn't in the library. While idle it
checks for requests every 30 seconds.

## Setup (as root in the `mediasite` distro)

```bash
apt-get install -y ffmpeg python3-venv python3-pip
python3 -m venv /opt/voice-transcriber/venv
/opt/voice-transcriber/venv/bin/pip install -r server/transcriber/requirements.txt

# Install the code (re-run after pulling changes)
mkdir -p /opt/voice-transcriber/app /mnt/f/Transcripts/requests
# the site's container writes requests as its own user
chmod 777 /mnt/f/Transcripts/requests
cp server/transcriber/*.py server/transcriber/run.sh /opt/voice-transcriber/app/
chmod +x /opt/voice-transcriber/app/run.sh
cp server/transcriber/voice-transcriber.service /etc/systemd/system/
systemctl daemon-reload
```

Summaries use Ollama at `OLLAMA_URL` (default `http://127.0.0.1:11434`, which reaches the
Windows Ollama through WSL's mirrored networking) with `VOICE_SUMMARY_MODEL` (default
`qwen3:14b`).

`run.sh` puts the cuBLAS and cuDNN libraries from the pip wheels on `LD_LIBRARY_PATH`,
so no system CUDA toolkit is needed. The WSL GPU driver comes from Windows.

## Use

```bash
# One book
/opt/voice-transcriber/app/run.sh transcribe.py "/mnt/f/Libation/Books/Some Book/Some Book.m4b"

# Summaries of one transcribed book
/opt/voice-transcriber/app/run.sh summarize.py "/mnt/f/Libation/Books/Some Book/Some Book.m4b"

# Whole library, one pass, in the foreground
/opt/voice-transcriber/app/run.sh queue_worker.py --once

# As a service
systemctl enable --now voice-transcriber
journalctl -u voice-transcriber -f
```

To jump the queue, list `.m4b` paths (one per line, `#` for comments) in
`/opt/voice-transcriber/app/priority.txt`. The worker reads it at the start of each pass.
Each finished book is logged with its audio duration, wall time and speed factor.
