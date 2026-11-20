# AmbiFrame

[![Build and test](https://github.com/yashsriv/philips-ambiframe/actions/workflows/build.yml/badge.svg)](https://github.com/yashsriv/philips-ambiframe/actions/workflows/build.yml)

An Android TV screensaver that shows fine art, built for Philips Ambilight TVs.
When the screensaver starts, it turns Ambilight **off**. When it is dismissed,
it restores Ambilight to the style it had before.

See [SPEC.md](SPEC.md) for the original idea and [docs/PLAN.md](docs/PLAN.md)
for how it was built.

## How it works

- **Screensaver:** an Android `DreamService` (`dream/ArtDreamService`) that
  shows a full-screen slideshow of landscape artworks. Each slide crossfades in
  once fully loaded, a small credit line sits in the bottom-right corner, and
  the picture drifts a few pixels over several minutes to protect OLED panels.
  Any remote key ends it.
- **Art sources** (`art/`):
  - [Art Institute of Chicago API](https://api.artic.edu/docs/): public-domain
    Impressionism and Post-Impressionism works. On by default.
  - Google Arts & Culture: an unofficial feed (the one behind Google's
    new-tab browser extension). Off by default. Street-art photos in it are
    filtered out. If it disappears, the app falls back to Chicago.
  - Only landscape works (4:3 to 2:1) are shown, so they fill a 16:9 screen.
- **Ambilight control** (`ambilight/`): the TV's JointSpace API
  (v6, `https://127.0.0.1:1926/6/` from the TV itself, HTTP Digest auth).
  The app pairs once, using a PIN the TV shows.
- **Switching Ambilight** (`dream/AmbilightGuard`): when the screensaver
  starts, the current Ambilight style is saved and Ambilight switched off.
  When it ends, the saved style is posted back. If the TV doesn't answer, a
  WorkManager job retries once the network is up, so Ambilight isn't left off.

## Using it

Open **AmbiFrame** from the TV's launcher. The settings screen has:

- **Preview screensaver**: runs it now, Ambilight included.
- **Time per picture**: 15 seconds to 30 minutes (default 1 minute). On Philips
  OLEDs keep it at 90 seconds or less, so the TV's logo screensaver (below)
  never sees a still picture for 2 minutes.
- **Show credits**: the title, artist and museum line.
- **Dim pictures**: off, or 20/40/60% darker. On OLED TVs power use and wear
  follow pixel brightness, so dimming saves both.
- **Google Arts & Culture**: adds the unofficial feed.
- **Ambilight setup**: pair with the TV, test Ambilight off/on, or forget it.
- **TV screensaver settings**: the system screen for choosing a screensaver.

The left side shows whether the TV is paired and whether AmbiFrame is the
active screensaver.

## Build

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK
with platform 36.

```sh
./gradlew assembleDebug   # build the APK
./gradlew test            # run unit tests
./gradlew lint            # Android lint
```

## Install and enable on the TV

1. Enable developer options and network debugging on the TV, then connect:
   `adb connect <tv-ip>`
2. Install: `./gradlew installDebug`
3. Open **AmbiFrame** from the launcher, choose **Ambilight setup** and pair
   with the TV (enter the PIN it shows).
4. Make it the active screensaver: pick it under Settings > Device Preferences >
   Screen saver. If it isn't listed (Google TV and newer firmware hide
   third-party screensavers), use adb:

   ```sh
   adb shell settings put secure screensaver_components org.yashsriv.ambiframe/.dream.ArtDreamService
   ```

5. Start it right away to test it:
   `adb shell am start -n com.android.systemui/.Somnambulator`

## Troubleshooting

- **Pairing shows no PIN:** on some Philips TVs the PIN toast comes from
  `org.droidtv.tvsystemui`. If that app isn't allowed to draw over other apps,
  the PIN is invisible. Allow it, or read the PIN from `adb logcat`.
- **A bouncing Philips logo appears instead:** Philips OLEDs show it after about
  2 minutes of a still picture (burn-in protection in `org.droidtv.tvsystemui`).
  Make the screensaver start sooner than that. The TV's menu doesn't offer
  times under 5 minutes, and "Never" is stored as 604800000, but adb can set
  any value (here 90 seconds):

  ```sh
  adb shell settings put system screen_off_timeout 90000
  ```

- **Logs:** `adb logcat -s AmbiFrame AmbiFrameDream AmbiFrameArt`. The TV's log
  buffer is small and busy, so stream it rather than reading it afterwards.
- **Debug art preview** (debug builds only), with source stats on screen:
  `adb shell am start -n org.yashsriv.ambiframe/.art.debug.ArtPreviewActivity`
