# Android 6 (API 23) port notes

This fork extends DiPlay to head units running Android 6.0 (API 23), wired USB only.
Upstream requires Android 9 (minSdk 28); wireless features still require Android 10+ by design.

## What differs from upstream

- `minSdk` 28 → 23 in `mobile`, `common`, `shared` (`automotive` stays at 28: AAOS units never run 6).
- `shared` NDK build `APP_PLATFORM=android-23` (JNI modules are plain Linux syscalls, unchanged).
- `mobile` enables `coreLibraryDesugaring` (desugar_jdk_libs): upstream uses `java.util.Base64`
  (API 26) in the Lockdown/MFi pairing path; desugaring rewrites it for older runtimes and also
  covers any future `java.time`/stream usage.
- Runtime gates added for pre-26/24 platforms:
  - `DiPlaySessionService`: `NotificationChannel` / `Notification.Builder(channel)` are API 26;
    Android 6-7 use the legacy builder.
  - `AndroidMediaSink` `AudioFocusCoordinator` and `CarPlayMediaKeys`: `AudioFocusRequest` is
    API 26; fall back to the legacy stream-based `requestAudioFocus`/`abandonAudioFocus`.
    `Map.computeIfAbsent` (API 24) replaced with stdlib `getOrPut`;
    `AtomicLong.accumulateAndGet` (API 24) replaced with a CAS loop.
  - `Iap2LocationClient`: `java.time.Instant` replaced with `java.util.Calendar` (UTC fields).
  - `NtpClock`: `Math.floorDiv`/`floorMod` (API 24) replaced with Kotlin's equivalents.
  - `VideoDecodeQueue`: `Collection.removeIf` (API 24) replaced with `removeAll`.

Everything else (video decode capability probes, diagnostics export, per-app locale, USB receiver
registration) already carried pre-Q/pre-R fallbacks upstream.

## What still does not work on Android 6

- Wireless (Wi-Fi Direct / local-only hotspot): requires Android 10+ by design.
- BYD extras (HUD navigation, CAN battery dashboard, dashboard-map mirror): depend on DiLink 4/5
  system services that older Android 6 units do not ship.
- Floor note: Compose UI 1.10 and core-ktx 1.19 require exactly API 23, so Android 5.x is not
  reachable with this dependency stack.

## Building the debug APK

Requires JDK 17+ (the toolchain resolves JDK 25 for AGP's built-in Kotlin), an Android SDK with
`platforms;android-37.0` (minor-version package name), build-tools 36.0.0 and NDK 28.2.13676358,
and the MFi identity assets described in `docs/BUILD.md` via `DIPLAY_AUTH_ASSETS_DIR`.

```bash
JAVA_HOME=<jdk25> ANDROID_HOME=<sdk> DIPLAY_AUTH_ASSETS_DIR=<identity-dir> \
  ./gradlew :mobile:assembleDebug
```

Keep the checkout path ASCII-only: paths with non-ASCII characters break the NDK build on Windows.
