# Rytm Mobile

Put sounds on an **Elektron Analog Rytm MK1** from an Android phone: record from the phone mic or a USB audio input, trim any audio or video, split tracks into stems, and send the result straight to the Rytm's +Drive over USB.

**Download and setup instructions: https://rytm-mobile.pages.dev**

## What it does

- **Rytm.** Browse +Drive, make folders, delete samples, and send new ones over USB-MIDI. The app uses Elektron's SysEx file protocol, ported from [Elektroid](https://github.com/dagargo/elektroid).
- **Record.** Record from the phone's mic or a class-compliant USB mic or audio interface, then chop, send or split the take. Takes are kept on the phone until you delete them.
- **Chop.** Open MP3, WAV (16/24/32-bit), M4A, FLAC, screen recordings and voice memos. Trim on a waveform, normalize and fade, then send. The app converts to the Rytm's native 48 kHz, 16-bit mono with a Kaiser-windowed sinc resampler and TPDF dither.
- **Split.** Separate a track into drums, bass, guitar, piano, other and vocals (BS-RoFormer SW), vocals and instrumental, or a drum stem into kick, snare, toms and cymbals. The split runs on your own Mac or Linux computer, which the phone reaches over your home network or [Tailscale](https://tailscale.com).
- **Updates itself.** When a new version is on the site, the app offers to install it. It checks the SHA-256 first, and Android only accepts updates signed with the same key.

## Recording

Tap **Record** in the Chop tab, pick an input, watch the level, then tap REC. Nothing is kept until you do. Android asks once for microphone access, and the app listens only while the recorder is open. When you stop, the take stays on the phone; chop it, listen, save it to Music, share it, or split it into stems. Opening, chopping, sending and splitting files all work without microphone access.

Known limits:

- Recording runs only while Rytm Mobile is on screen. Leaving the app, turning the screen off, or folding to the cover screen (if your phone stops the app there) ends the take, and what was recorded is kept. The screen stays on while the recorder is open.
- USB inputs: class-compliant USB mics and audio interfaces. Android records at most 2 channels from a USB device, and some USB Audio Class 2-only interfaces may not work.
- The Rytm MK1's USB port carries MIDI only. To record the Rytm, patch its Main Out or Headphones into a USB interface.
- The phone has one USB-C port. Using the Rytm and a USB interface together needs a powered hub (not yet tested), or record first and then plug the Rytm back in.
- Recordings are 16-bit WAV, at 48 kHz when the input allows it.
- Takes can be up to 30 minutes (the stem server's split limit). How long a take you can chop depends on the phone's memory; a longer take can be split instead.
- Bluetooth microphones aren't offered.
- With the phone mic, the tap on REC can be heard at the start. Auto-trim usually removes it.
- A phone call or another recording app takes the microphone. The take stops and is kept.
- The Android emulator can't record audio.

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
| Recording (phone mic, USB audio) | Not yet tested on hardware |
| Analog Rytm MKII | No (the protocol is the same in Elektroid) |
| NVIDIA GPUs | No |

## License

GPL-3.0, because the transfer protocol is derived from Elektroid (GPL-3.0). Separation models belong to their authors; check each model's terms before commercial use.

Not affiliated with Elektron.
