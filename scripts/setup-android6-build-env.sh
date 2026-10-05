#!/usr/bin/env bash
# One-shot toolchain installer for building this fork's Android 6 (API 23) port on Windows.
# Installs JDK 21 (fallback tools), JDK 25 (Gradle runtime - AGP 9.3 built-in Kotlin requires it),
# Android cmdline-tools, platform/build-tools, NDK and extracts the MFi identity from an official
# release APK. Everything lands in D:\android-build (ASCII path - the NDK build breaks on
# non-ASCII paths, so the repo must be checked out/built from an ASCII location too).
set -u
ROOT=/d/android-build
DL=$ROOT/downloads
SDK=$ROOT/sdk
mkdir -p "$DL" "$SDK"

log() { echo "[setup $(date +%H:%M:%S)] $*"; }

fetch() { # fetch <url> <outfile>
  local url="$1" out="$2" attempt
  for attempt in 1 2 3; do
    if curl -sS -L --ssl-no-revoke --connect-timeout 20 --max-time 1800 -o "$out" "$url"; then
      [ "$(stat -c%s "$out")" -gt 100000 ] && return 0   # reject tiny error pages
    fi
    log "retry $attempt failed for $url"
    sleep 5
  done
  return 1
}

unzip_to() { unzip -q "$1" -d "$2"; }   # use unzip, NOT tar: GNU tar cannot read zip archives

# --- 1. JDK 21 (Temurin via Tsinghua mirror; general-purpose JDK) -------------
if [ ! -x "$ROOT/jdk/bin/java.exe" ]; then
  JDK_NAME=$(curl -sS --ssl-no-revoke --max-time 60 \
    https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/ \
    | grep -oE 'OpenJDK21U-jdk_x64_windows_hotspot_[0-9._+]+\.zip' | sort -uV | tail -1)
  log "fetching $JDK_NAME"
  fetch "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/$JDK_NAME" "$DL/jdk.zip"
  unzip_to "$DL/jdk.zip" "$ROOT"
  mv "$ROOT"/jdk-21* "$ROOT/jdk"
  log "JDK 21 at $ROOT/jdk"
fi

# --- 2. JDK 25 -> Gradle's toolchain probe dir --------------------------------
# AGP 9.3's built-in Kotlin requests a JDK 25 toolchain; foojay auto-download fails on
# some networks (GitHub HEAD request). Running Gradle itself on JDK 25 satisfies it.
JDK25_DIR="$HOME/.gradle/jdks"
if [ ! -d "$JDK25_DIR"/jdk-25* ] 2>/dev/null; then
  JDK25_NAME=$(curl -sS --ssl-no-revoke --max-time 60 \
    https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/ \
    | grep -oE 'OpenJDK25U-jdk_x64_windows_hotspot_[0-9._+]+\.zip' | sort -uV | tail -1)
  log "fetching $JDK25_NAME"
  fetch "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/$JDK25_NAME" "$DL/jdk25.zip"
  mkdir -p "$JDK25_DIR"
  unzip_to "$DL/jdk25.zip" "$JDK25_DIR"
  log "JDK 25 at $JDK25_DIR/jdk-25*"
fi
JDK25_HOME=$(ls -d "$JDK25_DIR"/jdk-25* | head -1)

# --- 3. Android cmdline-tools (latest) + SDK components -----------------------
# NB: SDK 37 uses minor-version package names - install platforms;android-37.0,
# NOT platforms;android-37 (which does not exist).
if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  fetch "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip" "$DL/cmdtools.zip"
  unzip_to "$DL/cmdtools.zip" "$DL"
  mkdir -p "$SDK/cmdline-tools"
  cp -r "$DL/cmdline-tools" "$SDK/cmdline-tools/latest"
  log "cmdline-tools latest ready"
fi
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
export JAVA_HOME="$JDK25_HOME"
export PATH="$JDK25_HOME/bin:$PATH"

yes | "$SDKMGR" --sdk_root='D:\android-build\sdk' --licenses > "$DL/licenses.log" 2>&1
"$SDKMGR" --sdk_root='D:\android-build\sdk' 'platform-tools' 'platforms;android-37.0' \
  'build-tools;36.0.0' 'ndk;28.2.13676358' > "$DL/sdk-install.log" 2>&1
log "SDK components installed"

# --- 4. Gradle (three-component name: gradle-9.5.0-bin.zip) -------------------
if [ ! -x "$ROOT/gradle-9.5.0/bin/gradle.bat" ]; then
  fetch "https://services.gradle.org/distributions/gradle-9.5.0-bin.zip" "$DL/gradle.zip"
  unzip_to "$DL/gradle.zip" "$ROOT"
  log "Gradle 9.5.0 at $ROOT/gradle-9.5.0"
fi

# --- 5. MFi identity from an official release APK -----------------------------
mkdir -p "$ROOT/identity"
if [ ! -s "$ROOT/identity/offline-mfi/identity.pk8" ]; then
  APK_URL=$(curl -sS --ssl-no-revoke --max-time 60 \
    https://api.github.com/repos/shihabal3amri/DiPlay/releases/latest \
    | grep -oE '"browser_download_url": *"[^"]+\.apk"' | head -1 | grep -oE 'https[^"]+')
  if [ -n "${APK_URL:-}" ] && fetch "$APK_URL" "$DL/official.apk"; then
    unzip_to "$DL/official.apk" "$DL/apk-extract"
    cp -r "$DL/apk-extract/assets/offline-mfi" "$ROOT/identity/"
    log "identity files at $ROOT/identity/offline-mfi"
  else
    log "could not fetch official APK; place identity.pk8+certificate.p7b in $ROOT/identity/offline-mfi manually"
  fi
fi

log "DONE. Build with:"
cat <<EOF
  export JAVA_HOME='$JDK25_HOME'   # JDK 25 as the Gradle runtime
  export ANDROID_HOME='D:\\android-build\\sdk'
  export DIPLAY_AUTH_ASSETS_DIR='D:\\android-build\\identity'
  git clone https://github.com/<you>/DiPlay.git D:/DiPlayBuild/DiPlay   # ASCII path!
  cd /d/DiPlayBuild/DiPlay && /d/android-build/gradle-9.5.0/bin/gradle.bat :mobile:assembleDebug
EOF
