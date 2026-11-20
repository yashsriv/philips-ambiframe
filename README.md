# AmbiFrame

An Android TV screensaver that shows fine art, built for Philips Ambilight TVs.
When the screensaver starts, it turns Ambilight **off**. When it is dismissed,
it restores Ambilight to the style it had before.

See [SPEC.md](SPEC.md) for the original idea.

## How it works

- **Screensaver:** an Android `DreamService` that shows a full-screen slideshow
  of landscape artworks. The attribution sits small in the bottom-right corner.
- **Art sources:**
  - [Art Institute of Chicago API](https://api.artic.edu/docs/): public-domain
    Impressionism and Post-Impressionism works. This is the default.
  - Google Arts & Culture feed: unofficial, off by default.
- **Ambilight control:** the TV's JointSpace API (v6, `https://<tv>:1926/6/`,
  HTTP Digest auth). The app pairs with the TV once, in its setup screen,
  using a PIN shown on the TV.

## Build

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK
with platform 36.

```sh
./gradlew assembleDebug   # build the APK
./gradlew test            # run unit tests
```

## Install and enable on the TV

1. Enable developer options and network debugging on the TV, then connect:
   `adb connect <tv-ip>`
2. Install: `./gradlew installDebug`
3. Open **AmbiFrame** from the launcher and pair with the TV.
4. Make it the active screensaver. On Google TV and newer firmware,
   third-party screensavers are hidden from Settings, so use adb:

   ```sh
   adb shell settings put secure screensaver_components com.ambiframe.tv/.dream.ArtDreamService
   ```

5. Start it right away to test it:
   `adb shell am start -n com.android.systemui/.Somnambulator`

## Status

Being built in steps; see [docs/PLAN.md](docs/PLAN.md). Done: step 1 (repo) and step 2 (Gradle + app skeleton).
