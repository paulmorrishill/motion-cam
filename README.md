# MotionCam

A native Android video-recording app for unattended, motion-triggered capture with
automatic FTP off-load. Built for a phone that is permanently powered and mounted —
walk in, it records; walk out, it stops and uploads.

## What it does

- **Continuous camera monitoring.** The camera preview runs all the time (in a
  foreground service) analysing a low-resolution stream for motion. It does **not**
  record continuously.
- **Motion-triggered recording.** On motion it starts recording at the configured
  resolution (up to the camera's max, e.g. 4K / 1080p). When motion stops it keeps
  recording for a configurable grace period (default 60s); if motion returns it keeps
  going, otherwise it finalises the file and re-arms.
- **Seamless 2 GB rollover.** Recording rolls over to a new file at the configured max
  size using `MediaRecorder.setNextOutputFile`, so no frames are dropped at the split.
- **Autofocus + manual focus.** Continuous video autofocus by default; tap to focus at a
  point; long-press to lock focus; tap again to unlock.
- **Torch control.** Tri-state light button: Off / Auto (on while motion) / On.
- **Keep-screen control.** Tri/quad-state button on the recording screen:
  Off (screen black) / 1-minute timer / on-while-motion / always-on. The screen is kept
  awake so the phone never locks; when "asleep" it shows an all-black overlay to avoid
  burn-in rather than displaying a static image.
- **Audio cues.** Distinct beeps for: recording started, recording stopped (motion lost),
  storage low (repeats every 5 min while recording), and battery low.
- **On-screen state.** The top of the screen always shows the current state
  (Armed / Recording / Recording–finalising / Disarmed) plus low-storage / low-battery
  banners.
- **Manual stop & re-arm.** Stop the current recording and disarm; it won't record again
  until motion clears and returns (or you re-arm manually to skip the wait).
- **FTP auto-upload.** Finished files sync to an unsecured FTP server on the local network
  (host/user/password/path configured in-app), with a progress screen showing the queue
  and a per-file progress bar. Uploads are verified before the file is trusted.
- **Automatic cleanup.** Uploaded files are deleted a week after recording — the purge runs
  whenever a new file is finalised, so storage clears itself with no schedule.
- **File naming.** `<deviceName>_dd-MM-yyyy-HH-mm-ss.mp4` using the recording start time.

## Tech

- Kotlin, Jetpack Compose UI, **Camera2 + MediaRecorder** (chosen over CameraX because
  seamless file rollover needs `setNextOutputFile`).
- Foreground service (`camera|microphone`) with a wake lock and `START_STICKY` so the OS
  memory manager does not kill it; restarts on boot.
- Apache Commons Net for plain FTP.

## Building

CI builds the APK on every push to `main` (see below). To build locally you need JDK 17 and
the Android SDK (platform 35, build-tools 35):

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (debug-signed, directly installable)
./gradlew testDebugUnitTest      # unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs an emulator/device)
```

APKs are written to `app/build/outputs/apk/`.

## Install

Download **motion-cam-release.apk** from the
[latest release](../../releases/latest) and install it on the phone (enable "install from
unknown sources"). On first launch grant Camera, Microphone and Notification permissions,
then allow the app to ignore battery optimisation when prompted. Configure the device name
and FTP server in **Settings**.

## Configuration via QR code

Every setting can be filled from a QR code instead of typed on the phone:

1. Open [`tools/qr-config.html`](tools/qr-config.html) in any browser (double-click it — no
   server needed, works offline). Fill in the settings; the QR updates live. Entries are
   remembered in the browser's `localStorage`, so the form is pre-filled next time.
2. On the phone: **Settings → Scan config QR**, point the camera at the code.
3. The scanned values populate the Settings fields (still fully editable) — review, then
   **Save** to apply.

The QR carries a compact JSON payload (`app/.../settings/SettingsCodec.kt`). It is decoded
directly off the existing camera preview stream (`QrScanner`), so no second camera is
needed. A QR may carry only some fields — omitted ones keep their current value. Passwords
travel in the QR as plain text (the FTP link is unencrypted LAN regardless), so only
display the code on a trusted screen.

## CI

`.github/workflows/build.yml`:

1. **unit-and-build** — runs unit tests and builds the debug + release APKs.
2. **instrumented** — runs the instrumented smoke test on a real Android emulator.
3. **release** — publishes the APKs to the `latest` GitHub Release.

## Tests

- Unit: motion-detection algorithm, filename formatting, retention policy, recorder state
  machine, config-QR codec + scanner, and a cross-tool interop test that decodes a QR the
  HTML generator produced (`app/src/test`).
- Instrumented: app-launch smoke test on an emulator (`app/src/androidTest`).
