# Changelog

## 1.2.0

- **Camera source setting**: use the phone's own camera (as before) or a USB webcam (UVC) connected via an OTG adapter, picked in Settings → Camera source. Implemented on top of the third-party [AndroidUSBCamera](https://github.com/WojciechCzeronko/AndroidUSBCamera) library; feeds into the existing GL/encoding/motion-detection pipeline unchanged, so every other feature (grid detection, beep, schedule, timelapse, Drive upload, retention) works the same regardless of source. Resolution is a fixed target list (640×480 / 1280×720 / 1920×1080), FPS is not selectable for USB, rotation is manual (0/90/180/270) since the webcam isn't mounted to the phone. The first connection must happen with the app open so Android can show the USB permission prompt once. **Not tested on real hardware.**

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
