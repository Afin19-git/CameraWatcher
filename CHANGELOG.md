# Changelog

## 1.2.3

USB webcam support rewritten from scratch on a different library.

- **New USB webcam implementation** on [AUSBC](https://github.com/ernestp/AndroidUSBCamera) 3.6.0 (the maintained fork of jiangdg's AndroidUSBCamera; a Kotlin API over the native UVC library). The webcam is opened the way the library's own multi-camera demo does it, and frames are taken as raw NV21 straight from the camera instead of being drawn onto a surface. They are uploaded to OpenGL textures and converted to color by a shader, so motion detection, recording, the date/time overlay and the preview work exactly as with the phone camera.
- **Self-healing**: if the camera reports "open" but no frames arrive for 6 s, it is reopened with the other pixel format (MJPEG ↔ YUYV); after an error it re-requests access every 3 s; reconnecting the cable is handled.
- A USB-attached intent filter lets Android offer to open the app when the webcam is plugged in (tick *always* to skip the permission prompt next time).
- The app's own manifest overrides the library's "USB host required" flag, so the app still installs on phones without USB host.
- Native libraries are limited to `armeabi-v7a` and `arm64-v8a`.
- Removed the Camera2 "external camera" path tried between 1.2.2 and 1.2.3: it depends on the phone vendor exposing USB cameras to Android, which is rare.
- **Tested:** USB webcam on a Google Pixel — stable preview and recording. A Redmi 9C has not been confirmed with this version yet (older library versions gave a black preview or corrupt frames there).
- Fixed: a leftover undeclared variable that could stop the project from compiling.

## 1.2.2

- Fixed the app closing when the webcam was unplugged and plugged back in (the native camera was closed on an already detached device).
- Fixed repeated "open failed" (error -99) after reconnecting when the webcam's second USB interface (audio) appeared.
- Known issue: the very first connection after starting the app could still fail and needed a re-plug.

## 1.2.1

- Build fixes for the USB library: `libuvc` declared explicitly; `compileSdk` 36, Android Gradle Plugin 8.10.0, Gradle 8.11.1, Kotlin 2.1.0.

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
