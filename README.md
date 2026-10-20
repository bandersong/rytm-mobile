# Rytm Mobile

Put sounds on an **Elektron Analog Rytm MK1** from an Android phone: trim any audio or video, split tracks into stems, and send the result straight to the Rytm's +Drive over USB.

**Download and setup instructions: https://rytm-mobile.pages.dev**

## What it does

- **Rytm.** Browse +Drive, make folders, delete samples, and send new ones over USB-MIDI. The app uses Elektron's SysEx file protocol, ported from [Elektroid](https://github.com/dagargo/elektroid).
- **Chop.** Open MP3, WAV (16/24/32-bit), M4A, FLAC, screen recordings and voice memos. Trim on a waveform, normalize and fade, then send. The app converts to the Rytm's native 48 kHz, 16-bit mono with a Kaiser-windowed sinc resampler and TPDF dither.
- **Split.** Separate a track into drums, bass, guitar, piano, other and vocals (BS-RoFormer SW), vocals and instrumental, or a drum stem into kick, snare, toms and cymbals. The split runs on your own Mac or Linux computer, which the phone reaches over your home network or [Tailscale](https://tailscale.com).
- **Updates itself.** When a new version is on the site, the app offers to install it. It checks the SHA-256 first, and Android only accepts updates signed with the same key.

## Repository

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose). |
| `server/stemserver.py` | Stem server (FastAPI + [python-audio-separator](https://github.com/nomadkaraoke/python-audio-separator)). |
| `server/install.sh` | One-line installer for the stem server (macOS, Linux). |
| `site/` | The download site (Cloudflare Pages), including `version.json` for the updater. |
| `conformance/` | Builds Elektroid's own C message code to produce reference SysEx; `ElektronTest` checks the app against it byte for byte. |
| `tools/release.sh` | Builds, tests, deploys the site, and publishes a GitHub release. |
| `design/` | App icon and illustrations, and the scripts that generated them. |

## Build

You need JDK 17 and the Android SDK (API 36).

```bash
cd android && JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug testDebugUnitTest
```

Release builds are signed with a key that isn't in this repository.

## Run the stem server

```bash
curl -fsSL https://rytm-mobile.pages.dev/install.sh | bash
```

It installs into `~/.rytm-stems`, runs as a background service, and prints a QR code to pair the app. `~/.rytm-stems/pair` shows the code again; `~/.rytm-stems/uninstall` removes it. Windows isn't supported yet.

## Status

| Platform | Tested |
|---|---|
| Analog Rytm MK1 over USB-MIDI (Galaxy Z Fold) | Yes |
| Stem server on Apple-silicon Macs, Intel Macs and Ubuntu 24.04 | Yes |
| Analog Rytm MKII | No (the protocol is the same in Elektroid) |
| NVIDIA GPUs | No |

## License

GPL-3.0, because the transfer protocol is derived from Elektroid (GPL-3.0). Separation models belong to their authors; check each model's terms before commercial use.

Not affiliated with Elektron.
