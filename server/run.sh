#!/bin/zsh
cd "$(dirname "$0")"
export STEM_TOKEN="$(cat token.txt)" STEM_MODELS="$HOME/rytm-mobile/models" PATH="/opt/homebrew/bin:$PATH"
exec ../sepvenv/bin/uvicorn stemserver:app --host 0.0.0.0 --port 8765
