# Ambilight-aware Fine-Art Screensaver for Android TV — Plan

## Context
`D:\code\phillips-hue-frame` holds only `SPEC.md`. The goal is an Android TV **screensaver (DreamService)** that shows fine art. What sets it apart from the built-in art screensaver: on a Philips Ambilight TV it **turns Ambilight off when the screensaver starts and restores it when the screensaver is dismissed**. Controlling Ambilight goes through the TV's JointSpace API, which needs a pairing step, and that pairing has to be a setup flow inside the app.

Decisions made: art defaults to the **Art Institute of Chicago API (Impressionism + Post-Impressionism, public domain)**, with the unofficial **Google Arts & Culture feed** as an opt-in second source. Google doesn't publish the Chromecast "Fine Art" collection itself; Google Arts & Culture is where that collection comes from. Only **landscape works that fill a 16:9 screen** are shown. The stack is **Kotlin + Jetpack Compose for TV**. Scope is **Ambilight only, no Hue**.

### JointSpace API facts (from the linked gist and repo, plus suborb/philips_android_tv and pylips)
- Discovery: `GET http://<host>:1925/6/system`. If `featuring.systemfeatures.pairing_type == "digest_auth_pairing"`, use `https://<host>:1926/6/...`. That port has a **self-signed cert** and every call needs **HTTP Digest auth**.
- Pair request: `POST /6/pair/request` with body `{"scope":["read","write","control"],"device":{"device_name","device_os":"Android","app_name","type":"native","app_id","id":<16-char random [A-Za-z0-9]>}}`. The response has `error_id=="SUCCESS"`, `auth_key` and `timestamp`, and the TV then shows a PIN.
- Grant: `POST /6/pair/grant`, sent with Digest(`device_id`, `auth_key`). Body: `{"auth":{"auth_AppId":"1","pin","auth_timestamp","auth_signature"},"device":{…same…}}`.
  - Signature: `base64( hex( HMAC-SHA1( base64decode(SECRET), timestamp+pin ) ) )`.
  - suborb's SECRET: `ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==`. pylips uses a different key, which is the fallback.
  - The Python code wraps the result in `str(bytes)`, which may add literal `b'…'` around it. Try the clean form first and fall back to that one.
- After pairing, every call uses Digest(username=`device_id`, password=`auth_key`).
- Ambilight calls:
  - `GET/POST /6/ambilight/power` with `{"power":"On"|"Off"}`.
  - `GET/POST /6/ambilight/currentconfiguration` with `{"styleName","isExpert","menuSetting"}`, used to save and restore the exact style.
- Unknowns to check on the device: whether `127.0.0.1` reaches the API from an app on the TV itself (fallback: the TV's own LAN IP, or a manually entered IP), and whether 1925 accepts calls without auth on some firmwares.

## Steps (each step = one commit on `main`)

**1. Repo bootstrap**
- `git init -b main`.
- Add an Android/Gradle/IDE `.gitignore` and a `.gitattributes` (LF for sources, `*.bat` CRLF, binary jars).
- Add a `README.md` (purpose, build, how to enable the screensaver) and keep `SPEC.md`.
- Before this step, check that JDK 17+ and the Android SDK are available (`java -version`, `ANDROID_HOME`).

**2. Gradle + app skeleton**
- `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper. Generate the wrapper with a local `gradle wrapper`; if Gradle isn't installed, ask before downloading the official wrapper.
- Single `:app` module, package `org.yashsriv.ambiframe`, minSdk 26 (Android TV 8, the floor for Philips Android TVs), targetSdk latest.
- Manifest:
  - `android.software.leanback` required, touchscreen not required.
  - `INTERNET` and `ACCESS_NETWORK_STATE` permissions.
  - Launcher activity with `LEANBACK_LAUNCHER`, plus a banner drawable.
- Compose for TV (`androidx.tv:tv-material`) "hello" setup screen.
- Done when `./gradlew assembleDebug` and `./gradlew test` pass.

**3. JointSpace client** (`ambilight/` package)
- `JointSpaceClient` on OkHttp with:
  - Digest auth (`io.github.rburgst:okhttp-digest`).
  - TLS trust-on-first-use: the TV cert's SHA-256 fingerprint is recorded at pairing time and pinned through a custom `X509TrustManager` for that host only. No global trust-all.
  - IP-tolerant hostname verification for the pinned host.
- API surface:
  - `system()`, `pairRequest()`, `pairGrant(pin)`.
  - `getAmbilightPower()` / `setAmbilightPower(on)`.
  - `getConfiguration()` / `setConfiguration(cfg)`.
- Models with kotlinx.serialization.
- `Signature.kt` holds the HMAC function with both secret variants.
- Unit tests:
  - Signature against a known vector (generated with the Python reference code).
  - Full pair and ambilight flows against OkHttp `MockWebServer`, including a 401 digest challenge.

**4. Pairing + credential storage + setup UI**
- `PairingRepository` stores host, port, device_id, auth_key and cert fingerprint. Storage is DataStore, with the secret encrypted by an Android Keystore AES key.
- Setup screen (Compose for TV, D-pad friendly):
  1. Host detection: try `127.0.0.1`, then the device's own Wi-Fi/Ethernet IP. Offer manual IP entry, and remind the user that JointSpace may need enabling.
  2. "Pair" button → TV shows PIN → on-screen numeric PIN entry → grant.
  3. Status and "Test: Ambilight Off / On" buttons.
  4. Re-pair / forget.
- Errors are shown in plain language (TV unreachable, wrong PIN, timeout).

**5. Art source** (`art/` package)
- `ArtProvider` interface. Each work is an `Artwork(id, imageUrl, width?, height?, title, artist, date, credit, source)`.
- **Landscape-only rule (all providers):** keep a work only if its aspect ratio is between **1.33 (4:3) and 2.0**. The screen is 16:9 (1.78), so the image fills it with `ContentScale.Crop`. At most about 25% of the height (at 4:3) or 11% of the width (at 2.0) is cut off. The bound was lowered from 1.6, which left only 15 AIC works; 1.33 gives 69 AIC and 213 Google works. When a provider gives no size, the image header is probed over HTTP and the measured size is cached by ID, so rejected works aren't probed again and the bounds can change without clearing the cache.
- **`ArtInstituteProvider` (default):** `POST https://api.artic.edu/api/v1/artworks/search` with an Elasticsearch body:
  - `bool.must`: `is_public_domain = true`, `exists image_id`, and `terms style_id` ∈ {Impressionism, Post-Impressionism}.
  - `fields=id,title,artist_display,date_display,image_id,thumbnail,credit_line`.
  - `thumbnail.width/height` gives the aspect ratio for the landscape filter.
  - Images come from IIIF at `https://www.artic.edu/iiif/2/{image_id}/full/1686,/0/default.jpg`. 1686 is AIC's recommended maximum width.
  - The two style term IDs (`TM-…`) are looked up once from `/api/v1/category-terms/search?q=Impressionism` and `?q=Post-Impressionism` (subtype `style`) while implementing, then hardcoded as constants. The API refused automated fetches during planning, so they aren't known yet.
  - Requests send a polite `AIC-User-Agent` header, as the AIC docs ask.
- **`GoogleArtsCultureProvider` (opt-in in settings):** reads the unofficial feed `https://www.gstatic.com/culturalinstitute/tabext/imax_2_2.json` used by the Google Arts & Culture new-tab extension.
  - Entries have the fields `title, creator, attribution, attribution_link, image, link, artist_link, source`; image URLs look like `lh3.ggpht.com/ci/…`.
  - The feed has no image sizes, so works go through the bounds probe. A `=s3840`/`=w3840` size suffix may work but hasn't been verified, so test it first.
  - Fails soft: if the feed is gone or malformed, the app falls back to AIC. The code is labelled as unofficial.
- Coil with a disk cache, a shuffled rotating playlist, and prefetch of the next image. An offline fallback reuses cached images.
- Attribution (title, artist, date, credit/museum) is fetched and stored with each work.

**6. Screensaver DreamService** (`dream/` package)
- `ArtDreamService : DreamService`, declared with `BIND_DREAM_SERVICE` permission, `android.service.dreams.DreamService` intent filter, and `@xml/dream_info` (settingsActivity → setup).
- Compose in a Dream: set `ViewTree` lifecycle / savedState owners manually.
- `isInteractive=false`, fullscreen, screen kept on.
- Full-bleed crossfade slideshow (`ContentScale.Crop`) with a configurable interval and a slow sub-pixel drift for burn-in safety.
- Attribution sits in the **very bottom-right corner** in small text: `Title — Artist, Date · Museum`, about 10–12sp, semi-transparent white with a faint shadow, one line, ellipsized. Its size and style live in one `AttributionStyle` object so they're easy to tweak later.

**7. Ambilight hook in the dream lifecycle**
- `onDreamingStarted`: if paired, GET the current configuration and power, persist them as "saved state", then POST power Off.
- `onDreamingStopped` / `onDetachedFromWindow`: POST the saved configuration back (fall back to power On), then clear the saved state.
- Calls run on an app-scoped coroutine with `NonCancellable` and a short timeout, so they finish after the dream window is gone.
- Resilience:
  - If restore fails or the process dies, the persisted saved state is restored on the next app/dream start.
  - Do nothing if Ambilight was already off before the dream.
  - Every failure is logged without crashing the dream.

**8. Settings & polish**
- Settings for slide interval, attribution on/off and art sources (AIC on; Google Arts & Culture off by default). A "Preview screensaver" button.
- Instructions for making this the active screensaver. On Google TV and newer firmware, third-party screensavers are hidden from Settings, so the README and setup screen show:
  `adb shell settings put secure screensaver_components org.yashsriv.ambiframe/.dream.ArtDreamService`
- App icon/banner, README update.

**Parallelism:** after step 2, the Ambilight track (steps 3→4) and the Art track (step 5) are independent and touch separate packages. Steps 6–8 need both.

## Verification
- **Unit:** `./gradlew test` covers signature vectors, MockWebServer digest/pairing/ambilight flows, and the saved-state restore logic.
- **Build:** `./gradlew assembleDebug lint`.
- **Emulator** (Android TV AVD): setup UI navigation with D-pad, art slideshow through the dream preview. Ambilight calls fail gracefully there because there is no TV.
- **On the Philips TV** (adb over network):
  1. Install the APK.
  2. Run setup: confirm whether localhost or the LAN IP works, pair with the PIN, and check that the Off/On test buttons work.
  3. Set the screensaver with the adb command above.
  4. Start it with `adb shell am start -n com.android.systemui/.Somnambulator` (or wait for the timeout).
  5. Confirm Ambilight goes off, then press a remote key and confirm the previous style comes back.
  6. Also test: TV standby while the dream runs, and a network drop.
