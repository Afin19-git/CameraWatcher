# Changelog

## 1.0.0

First public release.

- Grid-based motion detection (16×9 by default, up to 32 cells on the long side), sensitivity, paintable mask.
- Hardware-encoded recording (HEVC or H.264) with separate resolution, FPS (16/24/30/60) and bitrate; optional audio; date/time and custom text burned into the video.
- Automatic rotation by accelerometer; live preview while positioning the phone.
- Recording schedule (camera fully off outside set hours).
- Daily timelapse (whole day or set hours, 15 s to 10 min), built from key frames without re-encoding.
- Google Drive upload via Google Play Services sign-in (`drive.file` scope), folders by date and part of day.
- Separate retention for source clips and timelapses, on the phone and on Drive.
- Foreground service with watchdog; low-space protection.
- English, Russian and Ukrainian interface.
