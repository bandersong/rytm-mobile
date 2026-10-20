#!/usr/bin/env bash
# Rytm Mobile stem server installer, for macOS and Linux.
#   curl -fsSL https://rytm-mobile.pages.dev/install.sh | bash
# Re-running it updates the server and keeps your token, jobs and models.
# Show the phone pairing code again later:  ~/.rytm-stems/pair
# Remove it (moves the folder aside, deletes nothing):  ~/.rytm-stems/uninstall
#
# Settings (environment variables):
#   RYTM_STEMS_DIR         install folder            (default ~/.rytm-stems)
#   RYTM_STEMS_PORT        port the server listens on (default 8765). Any other port also gets
#                          its own service name (ai.rytmmobile.stems.<port> / rytm-stems-<port>),
#                          so a second install never replaces the first one.
#   RYTM_STEMS_NO_SERVICE  1 = don't install a background service
#   RYTM_STEMS_BASE        where to download stemserver.py from
#
# Everything lives inside main(), so a download cut off halfway runs nothing.
set -Eeuo pipefail

BASE="${RYTM_STEMS_BASE:-https://rytm-mobile.pages.dev/server}"
DIR="${RYTM_STEMS_DIR:-$HOME/.rytm-stems}"
PORT="${RYTM_STEMS_PORT:-8765}"
NO_SERVICE="${RYTM_STEMS_NO_SERVICE:-0}"
OS="$(uname -s)"
STEP="starting"

say()  { printf '\033[1;38;5;209m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }
step() { STEP="$1"; say "$1"; }

# Any command that fails names the step it was in, instead of the script just stopping.
on_err() {
  local rc=$1 line=$2 cmd=$3
  trap - ERR
  printf '\033[1;31m✗\033[0m Install failed while: %s\n' "$STEP" >&2
  printf '  The command that failed (line %s, exit code %s): %s\n' "$line" "$rc" "$cmd" >&2
  [ -f "$DIR/install.log" ] && printf '  More detail may be in %s\n' "$DIR/install.log" >&2
  printf '  Fix the problem above and run the installer again; it picks up where it left off.\n' >&2
  exit "$rc"
}

# Service names. The default port keeps the original names; any other port gets its own.
if [ "$PORT" = 8765 ]; then
  LABEL="ai.rytmmobile.stems"; UNIT="rytm-stems.service"
else
  LABEL="ai.rytmmobile.stems.$PORT"; UNIT="rytm-stems-$PORT.service"
fi
PL="$HOME/Library/LaunchAgents/$LABEL.plist"
UNIT_FILE="$HOME/.config/systemd/user/$UNIT"
GUI="gui/$(id -u)"

# ---- macOS launchd helpers ----------------------------------------------------------------
svc_loaded() { launchctl print "$GUI/$LABEL" >/dev/null 2>&1; }

# Stop the job and wait until launchd has really let go of it. bootout returns before the
# job is gone, and a bootstrap sent in that window fails ("Bootstrap failed: 5: Input/output error").
svc_stop() {
  svc_loaded || return 0
  local out
  out="$(launchctl bootout "$GUI/$LABEL" 2>&1)" || true
  for _ in $(seq 60); do svc_loaded || return 0; sleep 0.5; done
  die "launchd didn't stop $LABEL within 30 s. launchctl said: ${out:-nothing}. Try: launchctl bootout $GUI/$LABEL"
}

# Load the plist, retrying while launchd finishes tearing the old job down.
svc_start() {
  local out="" n
  for n in 1 2 3 4 5 6; do
    if out="$(launchctl bootstrap "$GUI" "$PL" 2>&1)"; then return 0; fi
    # Something loaded it in the meantime: restart it in place instead.
    if svc_loaded; then launchctl kickstart -k "$GUI/$LABEL" >/dev/null 2>&1 && return 0; fi
    warn "launchctl bootstrap attempt $n failed: $out (retrying)"
    sleep "$n"
  done
  die "Couldn't start the background service. launchctl bootstrap said: $out
  Plist: $PL
  Try by hand: launchctl bootstrap $GUI \"$PL\""
}

# ---- port check --------------------------------------------------------------------------
port_free() {
  "$DIR/venv/bin/python" - "$PORT" <<'EOF'
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", int(sys.argv[1])))
except OSError:
    sys.exit(1)
EOF
}

wait_port_free() {
  for _ in $(seq 40); do port_free && return 0; sleep 0.5; done
  local who=""
  command -v lsof >/dev/null 2>&1 && who="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1{print $1" (pid "$2")"}' | sort -u | tr '\n' ' ' | sed 's/ *$//')"
  die "Port $PORT is already in use${who:+ by $who}. Stop that program, or install on another port:
  curl -fsSL https://rytm-mobile.pages.dev/install.sh | RYTM_STEMS_PORT=8766 bash"
}

main() {
  trap 'on_err $? $LINENO "$BASH_COMMAND"' ERR

  [ "$OS" = Darwin ] || [ "$OS" = Linux ] || die "This installer is for macOS or Linux. Windows isn't supported yet."
  command -v curl >/dev/null || die "curl is needed."
  case "$PORT" in ''|*[!0-9]*) die "RYTM_STEMS_PORT must be a number (got '$PORT')." ;; esac
  [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "RYTM_STEMS_PORT must be between 1 and 65535 (got $PORT)."

  step "Installing to $DIR (port $PORT)"
  mkdir -p "$DIR/jobs" "$DIR/models"
  chmod 700 "$DIR"   # uploads, stems and the token are yours alone
  : >>"$DIR/install.log"

  # 1. uv: a self-contained Python manager, so nothing touches your system Python.
  step "Checking for uv (Python manager)"
  UV="$(command -v uv || true)"
  if [ -z "$UV" ] && [ -x "$HOME/.local/bin/uv" ]; then UV="$HOME/.local/bin/uv"; fi
  if [ -z "$UV" ]; then
    step "Installing uv (Python manager from astral.sh)"
    curl -LsSf https://astral.sh/uv/install.sh | env UV_NO_MODIFY_PATH=1 UV_INSTALL_DIR="$HOME/.local/bin" sh >>"$DIR/install.log" 2>&1
    UV="$HOME/.local/bin/uv"
    [ -x "$UV" ] || die "uv didn't install (see $DIR/install.log)."
  fi

  # 2. Python 3.12 venv with the separator. NVIDIA GPU on Linux gets the CUDA build.
  EXTRA=cpu
  if [ "$OS" = Linux ] && command -v nvidia-smi >/dev/null 2>&1; then EXTRA=gpu; fi
  # Intel Macs: numba 0.63+ and llvmlite 0.46+ ship no x86_64 macOS wheels, and building
  # llvmlite needs cmake. Take the newest versions that still have a ready-made wheel.
  WHEELS_ONLY=()
  if [ "$OS" = Darwin ] && [ "$(uname -m)" = x86_64 ]; then
    WHEELS_ONLY=(--only-binary numba --only-binary llvmlite)
  fi
  step "Setting up Python 3.12 and audio-separator[$EXTRA]. This takes a few minutes and about 1.3 GB of disk."
  "$UV" venv --quiet --allow-existing --python 3.12 "$DIR/venv"
  # The Demucs model needs diffq, which has no ready-made build for Python 3.12 on any system, so
  # it is compiled here. Check for a C compiler first, so the user gets the fix instead of a build log.
  if ! "$DIR/venv/bin/python" -c 'import diffq' >/dev/null 2>&1; then
    if [ "$OS" = Darwin ]; then
      xcode-select -p >/dev/null 2>&1 || die "One part (diffq) has to be compiled, and this Mac has no compiler yet.
  Install Apple's command line tools:  xcode-select --install
  then run the installer again."
    else
      command -v cc >/dev/null 2>&1 || die "One part (diffq) has to be compiled, and there is no C compiler here yet. Install one:
  Ubuntu, Debian, Mint:  sudo apt install build-essential
  Fedora:                sudo dnf install gcc
  Arch:                  sudo pacman -S gcc
  then run the installer again."
    fi
  fi
  # On Linux, PyPI's torch is the CUDA build and pulls in about 4 GB of NVIDIA libraries.
  # Without an NVIDIA GPU, take the CPU build from PyTorch's own index instead.
  TB=""
  if [ "$OS" = Linux ] && [ "$EXTRA" = cpu ]; then TB=cpu; fi
  env ${TB:+UV_TORCH_BACKEND=$TB} "$UV" pip install --quiet --python "$DIR/venv/bin/python" ${WHEELS_ONLY[@]+"${WHEELS_ONLY[@]}"} "audio-separator[$EXTRA]" audioread \
    fastapi "uvicorn[standard]" python-multipart imageio-ffmpeg "qrcode[pil]"

  # ffmpeg: link one into the venv, because background services don't see Homebrew's PATH.
  # Use the system one if there is one, else the static build that imageio-ffmpeg ships.
  step "Linking ffmpeg"
  FF="$(command -v ffmpeg || true)"
  if [ -z "$FF" ] || [ "$FF" = "$DIR/venv/bin/ffmpeg" ]; then
    FF="$("$DIR/venv/bin/python" -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')"
  fi
  ln -sf "$FF" "$DIR/venv/bin/ffmpeg"

  # 3. The server itself.
  step "Downloading the server from $BASE"
  curl -fsSL "$BASE/stemserver.py" -o "$DIR/stemserver.py.new"
  mv "$DIR/stemserver.py.new" "$DIR/stemserver.py"
  if [ ! -s "$DIR/token" ]; then
    "$DIR/venv/bin/python" -c 'import secrets; print(secrets.token_hex(12))' > "$DIR/token"
    chmod 600 "$DIR/token"
  fi

  step "Writing the start, pair and uninstall scripts"
  cat > "$DIR/run.sh" <<EOF
#!/usr/bin/env bash
cd "$DIR"
export PATH="$DIR/venv/bin:\$PATH" STEM_DIR="$DIR/jobs" STEM_MODELS="$DIR/models" STEM_TOKEN="\$(cat "$DIR/token")"
exec "$DIR/venv/bin/uvicorn" stemserver:app --host 0.0.0.0 --port $PORT
EOF
  chmod +x "$DIR/run.sh"

  # 4. Address the phone will use: Tailscale if it's here, otherwise the local network.
  cat > "$DIR/pair" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="$(grep -oE -- '--port [0-9]+' "$DIR/run.sh" | awk '{print $2}')"
# RYTM_STEMS_HOST overrides the address, e.g. a Tailscale IP when its CLI isn't on the PATH.
TS=""
for t in tailscale /Applications/Tailscale.app/Contents/MacOS/Tailscale /usr/local/bin/tailscale /opt/homebrew/bin/tailscale; do
  [ -n "${RYTM_STEMS_HOST:-}" ] && break
  TS="$("$t" ip -4 2>/dev/null | head -1)" && [ -n "$TS" ] && break
done
if [ -n "${RYTM_STEMS_HOST:-}" ]; then HOST="$RYTM_STEMS_HOST"; NOTE="Address set by RYTM_STEMS_HOST."
elif [ -n "$TS" ]; then HOST="$TS"; NOTE="Tailscale: works anywhere both devices are signed in to Tailscale."
else
  HOST=""
  NOTE="Local network only. Install Tailscale on this computer and your phone to use it away from home, then run: $DIR/pair"
fi
# Home network address, shown too so a phone without Tailscale can be typed in by hand.
if [ "$(uname -s)" = Darwin ]; then LAN="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
else LAN="$(hostname -I 2>/dev/null | awk '{print $1}')"; fi
[ -n "$HOST" ] || HOST="$LAN"
if [ -z "$HOST" ]; then
  echo "Couldn't find this computer's network address. Connect it to Wi-Fi or Ethernet (or Tailscale), then run: $DIR/pair" >&2
  exit 1
fi
URL="http://$HOST:$PORT"
LINK="rytmmobile://pair?url=$("$DIR/venv/bin/python" -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$URL")&token=$(cat "$DIR/token")"
echo
echo "  Server  $URL"
echo "  Token   $(cat "$DIR/token")"
echo "  $NOTE"
[ -n "$LAN" ] && [ "$LAN" != "$HOST" ] && echo "  On your home Wi-Fi without Tailscale, type in http://$LAN:$PORT instead."
echo
echo "  Scan with the phone's camera (or open this link on the phone) to pair the app:"
"$DIR/venv/bin/python" -c 'import qrcode,sys; q=qrcode.QRCode(border=2); q.add_data(sys.argv[1]); q.print_ascii(invert=True)' "$LINK"
echo "  $LINK"
echo
EOF
  chmod +x "$DIR/pair"

  # Uninstaller: stops and removes the service, then moves the folder aside. It deletes nothing.
  cat > "$DIR/uninstall" <<EOF
#!/usr/bin/env bash
# Rytm Mobile stem server uninstaller. Stops the background service and moves
# $DIR aside to $DIR.removed-<date>. Nothing is deleted.
set -uo pipefail
DIR="$DIR"
OS="\$(uname -s)"
LABEL="$LABEL"
UNIT="$UNIT"
PL="$PL"
UNIT_FILE="$UNIT_FILE"
GUI="gui/\$(id -u)"
say()  { printf '\033[1;38;5;209m▸\033[0m %s\n' "\$*"; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "\$*" >&2; exit 1; }
main() {
  [ -d "\$DIR" ] || die "\$DIR isn't there; nothing to uninstall."
  if [ "\$OS" = Darwin ]; then
    if launchctl print "\$GUI/\$LABEL" >/dev/null 2>&1; then
      say "Stopping \$LABEL"
      out="\$(launchctl bootout "\$GUI/\$LABEL" 2>&1)" || true
      for _ in \$(seq 60); do launchctl print "\$GUI/\$LABEL" >/dev/null 2>&1 || break; sleep 0.5; done
      launchctl print "\$GUI/\$LABEL" >/dev/null 2>&1 && die "launchd didn't stop \$LABEL. launchctl said: \${out:-nothing}"
    fi
    # The plist goes into the folder that is moved aside, so it is kept, not deleted.
    [ -f "\$PL" ] && mv "\$PL" "\$DIR/\$LABEL.plist" && say "Removed the LaunchAgent (\$PL)"
  elif [ "\$OS" = Linux ] && command -v systemctl >/dev/null 2>&1; then
    if [ -f "\$UNIT_FILE" ]; then
      say "Stopping \$UNIT"
      systemctl --user disable --now "\$UNIT" || die "systemctl couldn't stop \$UNIT"
      mv "\$UNIT_FILE" "\$DIR/\$UNIT"
      systemctl --user daemon-reload
      say "Removed the systemd unit (\$UNIT_FILE)"
    fi
  fi
  DEST="\$DIR.removed-\$(date +%Y%m%d-%H%M%S)"
  mv "\$DIR" "\$DEST" || die "Couldn't move \$DIR to \$DEST"
  SIZE="\$(du -sh "\$DEST" 2>/dev/null | cut -f1)"
  say "Uninstalled. Your files (token, jobs, models and the Python env, \${SIZE:-size unknown} in all) are in:"
  echo "    \$DEST"
  echo "  The models (about 700 MB each) and the Python env (about 1.1 GB) are most of that."
  echo "  Reinstalling? Move it back to \$DIR first to keep your token and models."
  echo "  To free the space for good, delete it yourself:  rm -rf \"\$DEST\""
}
main "\$@"
EOF
  chmod +x "$DIR/uninstall"

  # 5. Start it, and keep it running after logins and reboots.
  if [ "$NO_SERVICE" = 1 ]; then
    say "Skipping the background service (RYTM_STEMS_NO_SERVICE=1). Start it with: $DIR/run.sh"
  elif [ "$OS" = Darwin ]; then
    step "Stopping the old server, if one is running ($LABEL)"
    svc_stop
    wait_port_free
    step "Starting the background service ($LABEL)"
    mkdir -p "$HOME/Library/LaunchAgents"
    cat > "$PL.new" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$LABEL</string>
<key>ProgramArguments</key><array><string>$DIR/run.sh</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardOutPath</key><string>$DIR/server.log</string><key>StandardErrorPath</key><string>$DIR/server.log</string>
</dict></plist>
EOF
    mv "$PL.new" "$PL"
    svc_start
  else
    command -v systemctl >/dev/null 2>&1 || die "systemd isn't available here. Run with RYTM_STEMS_NO_SERVICE=1 and start the server with $DIR/run.sh"
    step "Stopping the old server, if one is running ($UNIT)"
    systemctl --user stop "$UNIT" 2>/dev/null || true
    wait_port_free
    step "Starting the background service ($UNIT)"
    mkdir -p "$HOME/.config/systemd/user"
    cat > "$UNIT_FILE" <<EOF
[Unit]
Description=Rytm Mobile stem server (port $PORT)
[Service]
ExecStart=$DIR/run.sh
Restart=always
[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable "$UNIT" >/dev/null 2>&1
    systemctl --user restart "$UNIT"
    loginctl enable-linger "${USER:-$(id -un)}" 2>/dev/null || warn "Couldn't enable lingering; the server runs while you're logged in."
  fi

  if [ "$NO_SERVICE" != 1 ]; then
    step "Waiting for the server to come up on port $PORT"
    up=0
    for _ in $(seq 90); do
      if curl -fs -H "X-Token: $(cat "$DIR/token")" "http://127.0.0.1:$PORT/models" >/dev/null 2>&1; then up=1; break; fi
      sleep 2
    done
    if [ "$up" != 1 ]; then
      warn "Last lines of $DIR/server.log:"
      tail -n 20 "$DIR/server.log" >&2 2>/dev/null || true
      die "The server didn't answer on port $PORT within 3 minutes. See $DIR/server.log"
    fi
  fi

  # 6. Fetch the default model now so the first split doesn't wait on a download.
  step "Downloading the 6-stem model (up to 700 MB, once)"
  # Retry in case the download is interrupted. The file name is case-sensitive: BS-Roformer-SW.ckpt.
  # Intel Macs get an older audio-separator without BS-RoFormer SW; they use HTDemucs 6-stem (same list
  # as the server's CANDIDATES["stems"]).
  ok=0
  for _ in 1 2 3; do
    # Download into a staging folder and move the files in only when complete, so an interrupted
    # download never leaves a half file in models/ that later runs would trust.
    if STEM_MODELS="$DIR/models" STAGE="$DIR/models.partial" PATH="$DIR/venv/bin:$PATH" "$DIR/venv/bin/python" - <<'EOF' >>"$DIR/install.log" 2>&1
import os, shutil, tempfile
from pathlib import Path
from audio_separator.separator import Separator
final, stage = Path(os.environ["STEM_MODELS"]), Path(os.environ["STAGE"])
shutil.rmtree(stage, ignore_errors=True)  # leftovers of an interrupted earlier attempt
stage.mkdir()
for f in final.iterdir():  # reuse what's already complete
    if f.is_file():
        os.link(f, stage / f.name) if not (stage / f.name).exists() else None
s = Separator(model_file_dir=str(stage), output_dir=tempfile.gettempdir())
for f in ("BS-Roformer-SW.ckpt", "htdemucs_6s.yaml"):
    try:
        s.load_model(f)
        print("Downloaded", f)
        for g in stage.iterdir():
            if g.is_file():
                g.replace(final / g.name)
        shutil.rmtree(stage, ignore_errors=True)
        break
    except ValueError as e:
        if "not found in supported model files" not in str(e):
            raise
else:
    raise SystemExit("No 6-stem model this audio-separator version knows")
EOF
    then ok=1; break; fi
    sleep 5
  done
  [ "$ok" = 1 ] || warn "Model download will happen on the first split instead (details in $DIR/install.log)."

  STEP="showing the pairing code"
  if [ "$NO_SERVICE" = 1 ]; then say "Done. Start the server with: $DIR/run.sh"
  else say "Done. Your stem server is running."; fi
  "$DIR/pair"
  echo "  Show this again: $DIR/pair      Uninstall: $DIR/uninstall"
  echo
}

main "$@"
