# Building the compat-4.4 on-car APK (Allwinner T3 / Android 4.4.2, SDK 19)

Audience: an AI tool or engineer provisioning a DiPlay debug APK that installs and runs on the
API-19 head unit (target `com.shihab.diplay.android6`, `armeabi-v7a`, Dalvik 1.6.0). This complements
the generic [`BUILD.md`](BUILD.md); read that first. This file only captures the extra,
machine-specific rules required to produce a package that actually works on the unit.

The compat work lives on branch `compat-4.4`. Two rules below are not style preferences: skipping
either yields a build that "succeeds" but is useless on the car.

## Toolchain (this machine)

| Item | Value |
| --- | --- |
| JDK (used by Gradle) | `$HOME/.gradle/jdks/jdk-25.0.4.1+1` (Temurin 25) |
| Android SDK | `D:/android-build/sdk` (platform `android-37.0`, build-tools `36.0.0`, NDK `28.2.13676358`) |
| Gradle | wrapper, `gradle-9.5.0` (`gradle/wrapper/gradle-wrapper.properties`) |
| Python (dex gate) | `D:/App/Python311/python.exe` |
| adb | `C:/Users/90721/platform-tools/adb.exe` (platform-tools 37.0.1; a second copy lives in `D:/android-build/sdk/platform-tools`). Not on PATH inside Git Bash - call it by full path. |
| Network | build with `--offline`; dependencies are already in the Gradle cache |

## Rule 1 — build in an ASCII directory

NDK and AGP cannot emit `.so` / process resources from a non-ASCII path (the working copy under a
Chinese directory fails with `Expected output file ... but there was none`, and `aapt` cannot open
an APK on such a path either). Copy the tree to a fresh ASCII directory and build there, then copy
the APK back.

Exclude `.git`, every `build/` and any `*.apk` so secrets and stale outputs never travel:

```sh
SRC="/d/软件/养鱼/DiPlay"          # the real (non-ASCII) working copy
export LC_ALL=C.UTF-8               # lets tar read the Chinese path
rm -rf /d/diplay-build && mkdir -p /d/diplay-build
( cd "$SRC" && tar --exclude=.git --exclude='*/build' --exclude='*/.gradle' -cf - . ) \
  | ( cd /d/diplay-build && tar -xf - )
```

**Always `rm -rf` the export directory before re-running.** Tar-overwriting a tree that already has
build products mixes intermediate files and produces a hollow APK: identical zip entries but ~4.8 MB
larger (21.9 MB -> ~26.7 MB). Detect it by "same entries, bigger file".

## Rule 2 — inject the MFi identity, or the car reports an auth failure

`:mobile:assembleDebug` deliberately produces a source-only, **identity-free** APK
(`mobile/build.gradle.kts` gate). Installing that on the unit fails at session start with
`身份加载失败: offline-mfi/identity.pk8` — it looks like broken authentication but the APK simply has
no identity inside. For a car-testable package use the standalone task with the runtime asset dir:

```sh
cd /d/diplay-build
export JAVA_HOME="$HOME/.gradle/jdks/jdk-25.0.4.1+1"
export ANDROID_HOME=/d/android-build/sdk
export DIPLAY_AUTH_ASSETS_DIR=D:/android-build/identity   # contains offline-mfi/{identity.pk8,certificate.p7b}
./gradlew --offline :mobile:assembleStandaloneDebug
```

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk`.

### Where the identity files are

- On disk (operator-provided, outside the repo): `D:/android-build/identity/offline-mfi/`
  - `identity.pk8` — 67 B, the MFi accessory **ECDSA private key** (PKCS#8: `30 41 ...`).
  - `certificate.p7b` — 607 B, the Apple-issued accessory certificate.
- Inside a built APK they land at `assets/offline-mfi/identity.pk8` and `assets/offline-mfi/certificate.p7b`.
- `assembleStandaloneDebug` refuses missing or empty inputs, so a successful standalone build proves
  both were picked up.

### SECURITY — never commit the identity keys (this repo is PUBLIC)

- `Erane/DiPlay` on GitHub is a **public** repository. `.gitignore` blocks `*.pk8`, `*.p7b`, `*.key`,
  and `*.apk` on purpose. Keep it that way.
- Do **not** `git add -f` the identity files. A private key pushed to a public repo is scraped by
  bots within seconds and is **not** removable afterwards (forks, crawlers, caches, web archive),
  even with a force-push and history rewrite.
- If a build genuinely needs a different identity, place it under a fresh `DIPLAY_AUTH_ASSETS_DIR`
  path; do not stage it.

## Verify the APK before shipping it

```sh
APK=/d/diplay-build/mobile/build/outputs/apk/debug/mobile-debug.apk

# 1) Size is in the real range (~21.9 MB). ~26.7 MB with the same entries = hollow trap; rebuild clean.
stat -c '%s' "$APK"

# 2) Identity present and byte-identical to the inputs.
unzip -l "$APK" | grep offline-mfi
mkdir -p /tmp/m && ( cd /tmp/m && unzip -o -q "$APK" 'assets/offline-mfi/*' )
md5sum /tmp/m/assets/offline-mfi/* /d/android-build/identity/offline-mfi/*   # pairs must match

# 3) Package sanity — run aapt against the ASCII-path copy (aapt cannot open the Chinese path).
/d/android-build/sdk/build-tools/36.0.0/aapt.exe dump badging "$APK" \
  | grep -E '^package|sdkVersion|native-code'
# expect: name='com.shihab.diplay.android6', sdkVersion:'18', native-code includes armeabi-v7a

# 4) API-floor gate: every framework call above the floor must sit behind a runtime SDK_INT check.
/d/App/Python311/python.exe scripts/check-dex-api-levels.py "$APK" --floor 19 \
  --api /d/android-build/sdk/platforms/android-37.0/data/api-versions.xml \
  --dexdump /d/android-build/sdk/build-tools/36.0.0/dexdump.exe
```

The gate **reports** call sites above the floor; exit code 1 means "N sites to account for", not a
hard failure. A compat-4.4 build is expected to still print API 20/21 entries (MediaCodec,
AudioAttributes, `UsbInterface.getAlternateSetting`, `setInterface`/`setConfiguration`, etc.); the
rule is that each is guarded by `Build.VERSION.SDK_INT >= 21` (or routed through
`compatAlternateSetting()`). Confirm no *new* unguarded site appears versus the previous APK; use
`--show-callers` to locate any you do not recognize.

## Copy back and name it

```sh
cp "$APK" "/d/软件/养鱼/DiPlay/DiPlay-0.2.12-compat44-<change>-debug.apk"
sha256sum "/d/软件/养鱼/DiPlay/"DiPlay-0.2.12-compat44-<change>-debug.apk
rm -rf /d/diplay-build /tmp/m
```

Give the filename a meaningful suffix (what the build proves or fixes) and record the SHA-256 so the
on-car log can be matched to the exact binary. The APK files stay ignored by Git.

## adb and on-device logging (this machine)

Known devices: `b069ee3b` = Samsung SM-G5108 bench phone (msm8916, Android 4.4.4, API 19,
armeabi-v7a, Dalvik). The real target is the Allwinner T3 head unit (Android 4.4.2, API 19) -
same floor, same code paths.

### Git Bash traps when driving adb

MSYS rewrites arguments that look like POSIX paths, which silently corrupts device paths:

```sh
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # before any adb pull/push with /sdcard/...
ADB=/c/Users/90721/platform-tools/adb.exe
"$ADB" pull /sdcard/legacy-log.txt D:/android-build/device-logs/   # local side MUST be D:/ form
```

With conversion disabled the local destination stops accepting `/d/...` - always write the
local side as a Windows path. For pure `adb shell` commands (no device paths in argv) the
export is unnecessary.

### Device analysis

```sh
"$ADB" devices -l
"$ADB" shell getprop | grep -E "ro.build.version.(sdk|release)|ro.product.(manufacturer|model)|cpu.abi|dalvik.vm.version"
"$ADB" shell cat /proc/cpuinfo | grep -E "Processor|Features"          # NEON present?
"$ADB" shell cat /proc/net/udp | grep :14E9                            # who holds mDNS 5353
"$ADB" shell ps | grep mdnsd                                           # ROM's own mDNS daemon
```

The bench phone answers all of these; the head unit exposes the same props through its own
USB port if it has one (most T3 units do, vendor adb may need enabling in its settings).

### Install flows

- `"$ADB" install -r app.apk` updates in place and keeps the app's settings, but only under
  the SAME signing key. A different key fails with `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`;
  the only way forward is `"$ADB" uninstall com.shihab.diplay.android6` first (settings lost,
  AirPlay display options must be re-entered).
- First launch after (re)install runs legacy MultiDex extraction: give a 4.4 unit 30-60 s
  before judging "the app is stuck".

### Log retrieval - bench phone (adb attached)

```sh
"$ADB" logcat -c                                                  # clear, then reproduce
"$ADB" logcat -v time > D:/android-build/device-logs/session.log  # streaming capture
"$ADB" pull /sdcard/legacy-log.txt D:/android-build/device-logs/  # the app's own file log
"$ADB" pull /sdcard/diplay-crash.txt D:/android-build/device-logs/
```

App-side files: `/sdcard/legacy-log.txt`, `/sdcard/diplay-crash.txt`, `/sdcard/diplay-started.txt`
(every write fans out to the storage root, the app external dir and the private dir; the largest
copy wins). The 系统档案 header inside each carries `App: <pkg> <version>` so a pulled log is
matched to the exact APK build.

### Log retrieval - head unit (no adb, USB stick)

1. Copy the APK to the stick, install from the unit's file manager, reproduce (a success plus
   several failures makes the log most useful).
2. Copy back with the unit's file manager, from `/sdcard/`: `legacy-log.txt` (plus
   `legacy-log.1.txt` if present), `diplay-crash.txt`, `diplay-started.txt`. Fallback path:
   `/sdcard/Android/data/com.shihab.diplay.android6/files/`.
3. `legacy-log.txt` includes a full mirror of this process's logcat lines (`LogcatMirror`,
   PID-filtered), so stack traces that previously lived only in logcat are in the file. It
   rotates at 24 MB into `legacy-log.1.txt`; a running video session produces roughly 100 MB/h.

### Known quirks carried over from the bench phone (assume the T3 shares them)

- The ROM's own `mdnsd` holds UDP 5353 and JmDNS cannot bind (EADDRINUSE). The controller
  treats Bonjour as optional and continues over iAP2, which carries the AirPlay endpoint in
  CarPlayStartSession - do not "fix" this by making Bonjour mandatory again.
- Dalvik 4.x rejects `InetAddress.getByName("::")` binds ("Can't bind to a link-local address
  without a scope id") - every session socket must bind via `listenerBindAddress()` from
  `shared/src/main/java/com/shilapi/xcertplay/Compat.kt`.

## Environment limitations to know

- `:shared` JVM tests do **not** run on this machine (every class reports `Could not execute test
  class`, including files you did not touch). This is pre-existing; do not treat it as your
  regression. Validate changes by **compiling + on-device logs** instead.
- `aapt`/NDK path handling: anything touching the APK by path must use the ASCII copy.
- A second tool edits the sibling tree `D:\DiPlayBuild\compat44b`. Confirm who owns a tree before
  changing it; do not edit both.
