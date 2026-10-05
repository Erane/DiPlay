# Android 6 (API 23) port notes

This fork extends DiPlay to head units running Android 6.0 (API 23), wired USB only.
Upstream requires Android 9 (minSdk 28); wireless features still require Android 10+ by design.

## What differs from upstream

- `minSdk` 28 → 23 in `mobile`, `common`, `shared` (`automotive` stays at 28: AAOS units never run 6).
- `shared` NDK build `APP_PLATFORM=android-23` (JNI modules are plain Linux syscalls, unchanged).
- `mobile`, `common` and `shared` all enable `coreLibraryDesugaring` (desugar_jdk_libs 2.1.5): upstream
  uses `java.util.Base64` (API 26) in the Lockdown/MFi pairing path. Note desugaring does NOT cover
  `java.util.concurrent.CompletableFuture` — CarPlayVideo uses a `CountDownLatch` holder instead.
- Runtime gates added for pre-26/24 platforms:
  - `CarPlayHostActivity`: `startForegroundService` (API 26) gated; Android 6-7 call `startService`
    directly (the app is foreground when starting the session).
  - `IphoneUsbHost` + `NcmUsbBridge`: the async USB read uses `UsbRequest.queue(ByteBuffer)` and
    `UsbDeviceConnection.requestWait(timeout)` (both API 26); Android 6-7 fall back to the
    synchronous `bulkTransfer` with the same timeout (timeout vs I/O error are indistinguishable
    there — detach detection stays with the keepalive write path).
  - `LockdownTlsEngineFactory`: `SSLParameters.setEndpointIdentificationAlgorithm` (API 24) gated;
    null is already the engine default, so Android 6 keeps identical behaviour.
  - `AndroidMediaSink`: `AudioTrack.getUnderrunCount` (API 24) reports 0 on Android 6-7 (rebuffer
    detection degrades to the queue-empty signal); `AudioFocusRequest` (API 26) falls back to the
    legacy stream-based `requestAudioFocus`/`abandonAudioFocus`; `Map.computeIfAbsent` (API 24)
    replaced with stdlib `getOrPut`; `AtomicLong.accumulateAndGet` (API 24) replaced with a CAS loop.
  - `DiPlaySessionService`: `NotificationChannel` / `Notification.Builder(channel)` are API 26;
    Android 6-7 use the legacy builder.
  - `CarPlayMediaKeys`: `AudioFocusRequest` split with a legacy stream fallback.
  - `DiPlayActivity`: SeekBar uses `View.minimumHeight` instead of `ProgressBar.setMinHeight`
    (API 29); `WifiP2pManager.Channel.close` (API 27) wrapped in a no-op below 27.
  - `Iap2LocationClient`: `java.time.Instant` replaced with `java.util.Calendar` (UTC fields).
  - `NtpClock`: `Math.floorDiv`/`floorMod` (API 24) replaced with Kotlin's equivalents (D8 also
    backports the Java methods, this keeps sources honest).
  - `VideoDecodeQueue`: `Collection.removeIf` (API 24) replaced with `removeAll`.
  - `CarPlayController.startWirelessHotspot`: fails with a clear `WirelessStartupException` below
    API 29 — every wireless manager (LocalOnlyHotspot/WifiP2p/ExistingWifi) uses APIs that do not
    exist before Android 10 territory, so wireless is rejected gracefully instead of crashing.
  - `StandaloneHudDemoActivity` (debug): stock-receiver signing checks (API 28) gated.

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
