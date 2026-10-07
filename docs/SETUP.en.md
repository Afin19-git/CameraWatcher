# Setup: build, signing key, Google Drive

**English** · [Русский](SETUP.ru.md) · [Українська](SETUP.uk.md)

## 1. Build

1. Install Android Studio (Koala 2024.1 or newer). It bundles JDK 17.
2. *File → Open* the project folder and wait for Gradle sync.
3. Connect a phone with USB debugging and press *Run*, or use *Build → Build APK(s)*.

The app needs Android 7.0 (API 24). Dependencies: `play-services-auth` (Google Drive sign-in) and, for the USB-webcam camera source, `libausbc` fetched from JitPack — the first Gradle sync needs internet access to jitpack.io.

## 2. Sign with your own key

1. Create a key once. In Android Studio: *Build → Generate Signed App Bundle / APK → Create new…*, or in a terminal:
   `keytool -genkeypair -v -keystore camerawatcher.jks -alias camerawatcher -keyalg RSA -keysize 2048 -validity 36500`
   On Windows `keytool` is inside Android Studio: `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe` (run it with `&` in PowerShell).
2. Copy `keystore.properties.example` to `keystore.properties` and fill it in. Use forward slashes in `storeFile`.
3. Sync Gradle. Now both *Run* and *Build APK* are signed with your key, so the SHA-1 is the same for debug and release.
4. Release build: `./gradlew assembleRelease`, the file is `app/build/outputs/apk/release/app-release.apk`. Rename it `CameraWatcher-<version>.apk` for the GitHub release.

**Never commit or share the key file or `keystore.properties`.** `.gitignore` covers `*.jks`, `*.keystore` and `keystore.properties`; if your key file has another name, add it. Keep a backup: without the key you cannot publish updates.

Installing over a build signed with a different key is impossible, uninstall the old app first (its settings and local clips are lost; files already on Drive stay).

## 3. Google Drive sign-in

Google Play Services identify the app by package name and signing SHA-1, so every signing key needs its own OAuth client.

1. Open <https://console.cloud.google.com> and create a project.
2. *APIs & Services → Library*: enable **Google Drive API**.
3. *Google Auth platform*:
   - **Branding**: app name, support email, **application home page** and **privacy policy link** (both are required to publish). Templates for these two pages are in `docs/site/`; host them anywhere public, for example GitHub Pages, and replace the contact email. Add the pages' domain under *Authorized domains* (for GitHub Pages: `yourname.github.io`). Do not upload a logo: it triggers brand verification, which needs your own verified domain.
   - **Audience**: *External*, then **Publish app** so the status becomes *In production*. In *Testing* Google expires sign-in every 7 days. The app asks only for `drive.file`, which is not a sensitive scope, so no Google review is needed.
4. *Clients → Create client → Android*: package name `com.camerawatcher`, and the SHA-1 of your key. The installed app shows it in *Settings → Google Drive* (tap to copy); or run `keytool -list -v -keystore <file> -alias <alias>`.
5. Wait 5–10 minutes, then *Settings → Google Drive → Sign in with Google*. Google shows “hasn't verified this app”: *Advanced → Go to CameraWatcher*.

If you change `applicationId` in a fork, create a client for the new package name.

## Troubleshooting

- **Error 10 / “app not found”**: package name or SHA-1 in the client does not match the installed build, or the client was created minutes ago.
- **Sign-in expires every week**: the app is still in *Testing*, publish it.
- **`keytool` is not recognized**: use the full path (see step 2.1).
- **Gradle can't resolve `com.github.WojciechCzeronko.AndroidUSBCamera:libausbc`**: check internet access to jitpack.io (corporate networks sometimes block it), and that the tag `3.6.0-lowlatency1` still exists in that GitHub repo — if the maintainer renamed or removed it, open the repo's Releases/Tags page, pick a current tag, and update the version in `app/build.gradle.kts`.
- **Play Protect warns about the app**: expected for a background camera service that is not from Google Play. *More details → Install anyway*.
- **Folders on Drive are named in the wrong language**: names are fixed when you sign in. Sign out and in again after changing the language to switch to a new folder tree (old files stay in the old one).
