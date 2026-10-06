# Android 4.3/4.4+ port notes (compat-4.4 branch)

This branch extends DiPlay to head units running **Android 4.3/4.4 (API 18/19) and up**, wired
USB or wireless via the unit's own hotspot. Upstream requires Android 9 (minSdk 28); the
modern Compose UI still needs API 23, so this branch carries a second, View-based UI for the
older units inside the same APK.

The first verified target is a Geely H52 head unit (K2X firmware) that reports a fabricated
"Android 6.1.1" in its settings screen: the diagnostics show the real platform is
**Android 4.4.2 (SDK 19), Allwinner T3, kernel 3.10.65, armeabi-v7a**.

## Branch architecture

One APK serves both generations. `LegacyRouterActivity` is the single launcher: on API 23+ it
routes to the upstream Compose UI unchanged; below that it opens the View-based legacy home
(`legacy/LegacyHomeActivity`) and the View-based projection host
(`legacy/LegacyCarPlayActivity`), which drive the same protocol stack
(`shared`/`orchestration`) as the modern UI.

## What differs from upstream

Build configuration:

- `minSdk` 28 -> 18 in `mobile`/`common`/`shared` (`automotive` stays 28: AAOS units never run
  4.4). Legacy multidex + `MultiDexApplication` cover the dex count below API 21.
- `shared` NDK build `APP_PLATFORM=android-21` with
  `android.ndk.suppressMinSdkVersionError=21` (NDK r28's floor; the two JNI modules are plain
  syscalls, so building against 21 and running on 18 is safe). ABIs already include
  armeabi-v7a/armeabi.
- **No core library desugaring**: AGP 9 cannot desugar below minSdk 21. `java.util.Base64`
  (API 26, used by the Lockdown/MFi pairing path) is replaced with `android.util.Base64`;
  `java.time.Instant` with `java.util.Calendar`; `Math.floorDiv/floorMod` and
  `String.join` are auto-backported by D8.
- Packaging is legacy (compressed `.so`, `extractNativeLibs=true`): AGP 9's default
  uncompressed page-aligned layout is rejected by pre-7 package installers with
  "problem parsing the package".
- Debug build uses applicationIdSuffix `.android6` so it installs beside community builds
  (`.hudtest`), whose different debug keys would otherwise be reported as a parse error.

Runtime gates for pre-21 platforms (verified against the Allwinner T3 unit):

- `ContextCompat.getSystemService/checkSelfPermission` stopped supporting pre-23 when
  androidx.core 1.19 raised its floor to 23 - replaced everywhere with local shims
  (`systemServiceCompat` / `checkSelfPermissionCompat` in shared `Compat.kt`) that fall back
  to the string-based lookup and `checkPermission` below API 23.
- `CarPlayMediaKeys`: `CarPlayMediaCallback` extends `MediaSession.Callback` (API 21) and was
  instantiated in the object's class-init - built only on API 21+ now; the whole media-keys
  surface is skipped below 21. AudioAttributes (API 21) are likewise optional below 21 in the
  sink and focus coordinator, which fall back to stream types and the stream-based focus API.
- MediaCodec buffer access uses the pre-21 array API below 21
  (`inputBufferCompat`/`outputBufferCompat` in the sink and OpusEncoder).
- Wired USB below 21: `IphoneCarPlayConfiguration.readLayout` parses the configuration
  descriptors via control transfers into a `UsbDeviceLayout` tree, and the device-level
  interface scan + control-transfer SET_INTERFACE replace the API-21 UsbConfiguration calls
  (the re-enumeration trigger itself is a vendor control transfer and was always pre-21-safe).
- `CarPlayVpnService`: `VpnService.Builder.setBlocking/addAllowedApplication` (API 21) are
  skipped below 21.
- `DiPlaySessionService`: NotificationChannel (26) and Notification.Action (20) skipped below.
- Typed `getSystemService(Class)` (API 23) replaced with `systemServiceCompat` everywhere -
  including direct call sites, since androidx no longer covers them.
- `DiPlayBootstrap`/`AdbKeys`: `noBackupFilesDir` (21) via `compatNoBackupFilesDir`.
- `LockdownTlsEngineFactory`: KeyManagerFactory algorithm fallback chain
  (default/PKIX/SunX509/X509) and explicit non-SSLv3 protocols (Android 4.4 disables TLS 1.2
  by default).

Wireless on old units - `WirelessHotspotMode.PASSIVE_HOTSPOT`:

Every wireless manager (ExistingWifiManager/LocalOnlyHotspotManager/ManualHotspotManager)
rejects Android < 10. On 4.x units whose own system hotspot carries the CarPlay network, no
hotspot management is needed: the iPhone attaches to the unit's hotspot, and the passive mode
finds the unit's Wi-Fi IPv4 by scanning `NetworkInterface` (ap0/wlan0/swlan0 naming variants)
and runs discovery on it. `NsdServiceInfo.setAttribute/getAttributes` (API 21) are gated, so
pre-21 discovery proceeds without TXT-id matching. Wireless also requires the iPhone to be
Bluetooth-paired with the unit (standard wireless CarPlay handshake).

Diagnostics (`DiPlayApplication` + `DiPlayProbeActivity`, the "DiPlay 诊断" launcher entry):

- Every uncaught throwable is written to `diplay-crash.txt` and shown as a toast - car ROMs
  frequently swallow crash dialogs.
- The startup marker `diplay-started.txt` distinguishes "the process never ran" (launcher/ROM
  side) from "crashed after starting".
- `legacy-log.txt` captures the whole legacy session with timestamps and the real platform
  profile (SDK level, kernel, ABI, RAM - settings screens on car ROMs show rebranded version
  strings; the K2X reports a fabricated "6.1.1" while running 4.4.2).

## What still does not work / known risks

- **Compose UI floor**: the upstream Compose UI needs API 23 (Compose 1.10/core-ktx 1.19);
  pre-23 units get the View-based legacy UI instead, which has fewer settings (defaults come
  from persistence) and no BYD extras.
- **K2X wired freeze**: on the verified unit, the wired USB re-enumeration hard-freezes the
  whole system (also reproduced by the community's build) - firmware-level, no app-side
  workaround. Wireless via the unit's own hotspot bypasses USB entirely and is the
  recommended path on this hardware.
- **1 GB RAM / old SoC**: H.264 forced (HEVC off) for the legacy host; decode performance on
  these SoCs is the remaining unknown.
- **Pre-21 best-effort areas**: NSD TXT matching is degraded (see above), microphone/telephony
  is untested, and the dex audit still lists gated call sites (MediaCodec buffers, audio
  builders, VPN builder) that only execute on their gated branches.
- BYD extras (HUD navigation, CAN battery dashboard, dashboard-map mirror) need DiLink 4/5
  system services; the modern Compose UI needs API 23.

## Building the debug APK

Requires JDK 17+ (run Gradle itself on JDK 25: AGP 9.3's built-in Kotlin resolves a JDK 25
toolchain), an Android SDK with `platforms;android-37.0` (minor-version package name),
build-tools 36.0.0, NDK 28.2.13676358, and the MFi identity assets (`identity.pk8` +
`certificate.p7b` - extract from an official release APK) via `DIPLAY_AUTH_ASSETS_DIR`.

```bash
JAVA_HOME=<jdk25> ANDROID_HOME=<sdk> DIPLAY_AUTH_ASSETS_DIR=<identity-dir>   ./gradlew :mobile:assembleDebug
```

Keep the checkout path ASCII-only: non-ASCII paths break the NDK build on Windows, and the
NDK floor (21) vs minSdk 18 requires `android.ndk.suppressMinSdkVersionError=21` in
gradle.properties.
