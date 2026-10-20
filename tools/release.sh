#!/usr/bin/env bash
# Ship a new Rytm Mobile version:
#   1. bump versionCode / versionName in android/app/build.gradle.kts and commit
#   2. tools/release.sh "What's new, one line"
# Builds and tests the signed APK, puts it on the download site with version.json (what the app's
# updater reads), deploys the site, pushes, and creates the GitHub release with the APK attached.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NOTES="${1:?usage: tools/release.sh \"release notes\"}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
G="$ROOT/android/app/build.gradle.kts"
CODE="$(grep -oE 'versionCode = [0-9]+' "$G" | grep -oE '[0-9]+')"
NAME="$(grep -oE 'versionName = "[^"]+"' "$G" | cut -d'"' -f2)"
APK="RytmMobile-$NAME.apk"
SITE_URL="https://rytm-mobile.pages.dev"

[ -z "$(git -C "$ROOT" status --porcelain)" ] || { echo "Commit your changes first."; exit 1; }
git -C "$ROOT" tag | grep -qx "v$NAME" && { echo "v$NAME is already released. Bump the version."; exit 1; }
LIVE="$(curl -fsS "$SITE_URL/version.json" 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["versionCode"])' 2>/dev/null || echo 0)"
[ "$CODE" -gt "$LIVE" ] || { echo "versionCode $CODE must be higher than the live $LIVE."; exit 1; }

echo "▸ Building and testing $NAME ($CODE)"
(cd "$ROOT/android" && ./gradlew -q assembleRelease testDebugUnitTest)
OUT="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
SIGNER="$("$HOME/Library/Android/sdk/build-tools/36.0.0/apksigner" verify --print-certs "$OUT" | grep 'SHA-256 digest' | awk '{print $NF}')"
[ "$SIGNER" = b2c6fbd9e1bb36e0460c32966e7e5c290c3e71bf055b08c89acb054bc19875f4 ] \
  || { echo "APK is not signed with the release key (got $SIGNER). Updates would fail to install."; exit 1; }
mkdir -p "$ROOT/release"
cp "$OUT" "$ROOT/release/$APK"; cp "$OUT" "$ROOT/site/$APK"
SHA="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"
MB="$(python3 -c "import os;print(f'{os.path.getsize(\"$OUT\")/1e6:.1f}')")"

echo "▸ Updating the site"
python3 - "$ROOT/site" "$APK" "$NAME" "$CODE" "$SHA" "$MB" "$NOTES" <<'EOF'
import json, re, sys, pathlib
site, apk, name, code, sha, mb, notes = sys.argv[1:]
p = pathlib.Path(site, "index.html"); s = p.read_text()
s = re.sub(r'RytmMobile-[0-9][^"]*?\.apk', apk, s)
s = re.sub(r'<b>v[0-9][^<]*</b> · [0-9.]+ MB', f'<b>v{name}</b> · {mb} MB', s)
s = re.sub(r'SHA‑256 <b>[0-9a-f]{64}</b>', f'SHA‑256 <b>{sha}</b>', s)
assert apk in s and sha in s, "index.html patterns changed; update release.sh"
p.write_text(s)
pathlib.Path(site, "version.json").write_text(json.dumps(
    {"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha, "notes": notes}, indent=1) + "\n")
EOF
git -C "$ROOT" add site && git -C "$ROOT" commit -qm "Release $NAME" && git -C "$ROOT" tag "v$NAME"

echo "▸ Deploying $SITE_URL"
(cd "$ROOT" && wrangler-personal pages deploy site --project-name rytm-mobile --branch main --commit-dirty=true >/dev/null)
for _ in $(seq 20); do
  [ "$(curl -fsS "$SITE_URL/version.json" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sha256"])')" = "$SHA" ] && break; sleep 3
done
[ "$(curl -fsS "$SITE_URL/$APK" | shasum -a 256 | cut -d' ' -f1)" = "$SHA" ] || { echo "Live APK doesn't match; check the deploy."; exit 1; }

echo "▸ GitHub"
git -C "$ROOT" push -q origin main "v$NAME"
gh release create "v$NAME" "$ROOT/release/$APK" --repo "$(git -C "$ROOT" remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')" \
  --title "Rytm Mobile $NAME" --notes "$NOTES

SHA-256: \`$SHA\`
Install: $SITE_URL"
echo "Released $NAME: $SITE_URL"
