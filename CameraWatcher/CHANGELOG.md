# Changelog

## 1.2.1

Fixes to the USB webcam source after first testing on a Redmi 9C. Still **not verified on real hardware with a webcam**.

- **Reconnect no longer breaks the camera.** Before a new session opens, the previous UVC session is now closed and its USB interface released. Before, reopening after unplug/replug failed with `open failed: result=-1` or `-99` because the old session still held the camera.
- **No double open.** The connect event and the permission request could both open the same webcam, and the second attempt hit a busy device. A camera that is already open is no longer opened again.
- **Clean release on disconnect.** Unplugging the webcam, or stopping the service, now closes the camera and unregisters the USB receiver. Callbacks that arrive after stop are ignored.
- **Resolution fallback.** If the webcam rejects the chosen size with `unsupported preview size`, the app retries once at 640×480 instead of failing. The `unsupported preview size` message in earlier builds was a side effect of the failed open, not a real size problem.
- Version bumped to 1.2.1 (versionCode 5).

## 1.2.0

- **Camera source setting**: use the phone's own camera (as before) or a USB webcam (UVC) connected via an OTG adapter, picked in Settings → Camera source. Implemented on top of the third-party [AndroidUSBCamera](https://github.com/WojciechCzeronko/AndroidUSBCamera) library; feeds into the existing GL/encoding/motion-detection pipeline unchanged, so every other feature (grid detection, beep, schedule, timelapse, Drive upload, retention) works the same regardless of source. Resolution is a fixed target list (640×480 / 1280×720 / 1920×1080), FPS is not selectable for USB, rotation is manual (0/90/180/270) since the webcam isn't mounted to the phone. The first connection must happen with the app open so Android can show the USB permission prompt once. **Not tested on real hardware.** (Build note: `libausbc` depends on the `libuvc` module as `implementation`, not `api`, so `libuvc` must be declared as a separate explicit dependency too — otherwise classes like `USBMonitor` fail to resolve at compile time.)

## 1.0.0

First public release.

- Grid-based motion detection (16×9 by default, up to 32 cells on the long side), sensitivity, paintable mask.
- Hardware-encoded recording (HEVC or H.264) with separate resolution, FPS (16/24/30/60) and bitrate; optional audio; date/time and custom text burned into the video.
- Short double beep on motion, with a configurable cooldown, on the alarm volume.
- Automatic rotation by accelerometer; live preview while positioning the phone.
- Recording schedule (camera fully off outside set hours).
- Daily timelapse (whole day or set hours, 15 s to 10 min), built from key frames without re-encoding.
- Google Drive upload via Google Play Services sign-in (`drive.file` scope), folders by date and part of day.
- Separate retention for source clips and timelapses, on the phone and on Drive.
- Foreground service with watchdog; low-space protection.
- English, Russian and Ukrainian interface.
