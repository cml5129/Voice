# Voice transcriber

Turns the audiobook library into transcripts the app can show. It runs as a systemd
service inside the `mediasite` WSL distro (outside Docker) and uses faster-whisper
(`large-v3-turbo`, CUDA, float16, VAD on, English) on the host GPU.

For every `<book>.m4b` it writes to `/mnt/f/Transcripts`:

- `<book>.json`: an array of `{"timestamp_start": ms, "timestamp_end": ms, "text": "..."}`,
  one per segment, the format `core/transcript`'s `TranscriptParser` reads.
- `<book>.lrc`: `[mm:ss.xx]text` lines, which Jellyfin can serve as lyrics.

Outputs are written to a temp file and renamed, and a book is skipped when both outputs
exist and are newer than the audio.

## Setup (as root in the `mediasite` distro)

```bash
apt-get install -y ffmpeg python3-venv python3-pip
python3 -m venv /opt/voice-transcriber/venv
/opt/voice-transcriber/venv/bin/pip install -r server/transcriber/requirements.txt

# Install the code (re-run after pulling changes)
mkdir -p /opt/voice-transcriber/app /mnt/f/Transcripts
cp server/transcriber/*.py server/transcriber/run.sh /opt/voice-transcriber/app/
chmod +x /opt/voice-transcriber/app/run.sh
cp server/transcriber/voice-transcriber.service /etc/systemd/system/
systemctl daemon-reload
```

`run.sh` puts the cuBLAS and cuDNN libraries from the pip wheels on `LD_LIBRARY_PATH`,
so no system CUDA toolkit is needed. The WSL GPU driver comes from Windows.

## Use

```bash
# One book
/opt/voice-transcriber/app/run.sh transcribe.py "/mnt/f/Libation/Books/Some Book/Some Book.m4b"

# Whole library, one pass, in the foreground
/opt/voice-transcriber/app/run.sh queue_worker.py --once

# As a service
systemctl enable --now voice-transcriber
journalctl -u voice-transcriber -f
```

To jump the queue, list `.m4b` paths (one per line, `#` for comments) in
`/opt/voice-transcriber/app/priority.txt`. The worker reads it at the start of each pass.
Each finished book is logged with its audio duration, wall time and speed factor.
