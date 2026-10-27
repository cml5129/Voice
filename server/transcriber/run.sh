#!/usr/bin/env bash
# Runs a transcriber script from the venv with the CUDA 12 wheel libraries on the loader path.
# Usage: run.sh transcribe.py <m4b> | run.sh queue_worker.py [args]
set -euo pipefail
VENV=${VENV:-/opt/voice-transcriber/venv}
HERE=$(cd "$(dirname "$0")" && pwd)
SITE=$("$VENV/bin/python" -c 'import site; print(site.getsitepackages()[0])')
export LD_LIBRARY_PATH="$SITE/nvidia/cublas/lib:$SITE/nvidia/cudnn/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
script=$1
shift
exec "$VENV/bin/python" -u "$HERE/$script" "$@"
