#!/usr/bin/env bash
set -euo pipefail

# Build the Ren'Py 8.5.3 Android payload from the matching public SDK + RAPT.
# The temporary sample-game APK is only a packaging intermediate; it is never
# installed, committed, or shipped inside Ramen.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/ramen-renpy-runtime"
SDK_ZIP="$WORK/renpy-8.5.3-sdk.zip"
RAPT_ZIP="$WORK/renpy-8.5.3-rapt.zip"
SDK_UNPACK="$WORK/sdk"
RAPT_UNPACK="$WORK/rapt"
OUTPUT="$WORK/output"

rm -rf "$WORK"
mkdir -p "$SDK_UNPACK" "$RAPT_UNPACK" "$OUTPUT"

curl --fail --location --retry 5 --retry-all-errors --retry-delay 3 \
  https://www.renpy.org/dl/release/renpy-8.5.3-sdk.zip -o "$SDK_ZIP"
echo "ff57648f9c04f27e381c48af6d8e3ee3cdec296bed4d3831f47f09b0a71b505e  $SDK_ZIP" | sha256sum --check -

curl --fail --location --retry 5 --retry-all-errors --retry-delay 3 \
  https://www.renpy.org/dl/release/renpy-8.5.3-rapt.zip -o "$RAPT_ZIP"
echo "8a12be34a2f5238d125ff6dd76a56772fd8e44838af864f81bca82c1059b00e6  $RAPT_ZIP" | sha256sum --check -

unzip -q "$SDK_ZIP" -d "$SDK_UNPACK"
unzip -q "$RAPT_ZIP" -d "$RAPT_UNPACK"

SDK_ROOT="$(find "$SDK_UNPACK" -maxdepth 4 -type f -name renpy.sh -printf '%h\n' | head -n 1)"
if [[ -z "$SDK_ROOT" ]]; then
  echo "Could not locate renpy.sh in the Ren'Py SDK archive." >&2
  exit 1
fi
SDK_ROOT="$(cd "$SDK_ROOT" && pwd)"

if [[ -d "$RAPT_UNPACK/rapt" ]]; then
  RAPT_ROOT="$RAPT_UNPACK/rapt"
else
  RAPT_ROOT="$(find "$RAPT_UNPACK" -maxdepth 5 -type f -path '*/buildlib/rapt/build.py' -printf '%h\n' | head -n 1)"
  [[ -n "$RAPT_ROOT" ]] || { echo "Could not locate RAPT build.py." >&2; exit 1; }
  RAPT_ROOT="$(cd "$RAPT_ROOT/../../.." && pwd)"
fi
rm -rf "$SDK_ROOT/rapt"
cp -a "$RAPT_ROOT" "$SDK_ROOT/rapt"

# Use the direct official Gradle distribution host and a longer timeout.
RAPT_WRAPPER="$SDK_ROOT/rapt/prototype/gradle/wrapper/gradle-wrapper.properties"
if [[ -f "$RAPT_WRAPPER" ]]; then
  sed -i 's#services.gradle.org#downloads.gradle.org#; s/networkTimeout=10000/networkTimeout=120000/' "$RAPT_WRAPPER"
  # Gradle 9.1.0 supports retry/back-off in the wrapper properties. The
  # downloads.gradle.org endpoint can redirect to GitHub Releases, which may
  # transiently return HTTP 5xx in hosted CI runners.
  if ! grep -q '^retries=' "$RAPT_WRAPPER"; then
    printf '\\nretries=5\\nretryBackOffMs=5000\\n' >> "$RAPT_WRAPPER"
  fi
fi

: "${ANDROID_HOME:?Android SDK must be installed by android-actions/setup-android}"
printf '%s\n' "$ANDROID_HOME" > "$SDK_ROOT/rapt/sdk.txt"
chmod +x "$SDK_ROOT/renpy.sh"

GAME_DIR="$SDK_ROOT/the_question"
if [[ ! -f "$GAME_DIR/game/options.rpy" ]]; then
  GAME_DIR="$SDK_ROOT/tutorial"
fi
if [[ ! -f "$GAME_DIR/game/options.rpy" ]]; then
  OPTIONS="$(find "$SDK_ROOT" -maxdepth 6 -type f -path '*/game/options.rpy' -print -quit)"
  [[ -n "$OPTIONS" ]] || { echo "Could not locate the included Ren'Py sample project." >&2; exit 1; }
  GAME_DIR="$(dirname "$(dirname "$OPTIONS")")"
fi
if [[ ! -f "$GAME_DIR/game/options.rpy" ]]; then
  echo "Could not locate the included Ren'Py sample project." >&2
  exit 1
fi

cat > "$GAME_DIR/.android.json" <<'JSON'
{
  "name": "Ramen Runtime Payload",
  "icon_name": "Ramen Runtime",
  "package": "org.renpy.ramen.runtimepayload",
  "version": "1.0",
  "numeric_version": 1,
  "orientation": "sensor",
  "permissions": ["VIBRATE"],
  "store": "none",
  "update_icons": false,
  "update_always": true,
  "update_keystores": false
}
JSON

keytool -genkeypair -noprompt -keystore "$GAME_DIR/android.keystore" \
  -storepass android -alias android -keypass android -keyalg RSA \
  -keysize 2048 -validity 20000 -dname "CN=Ramen Runtime Build"

(
  cd "$SDK_ROOT"
  ./renpy.sh launcher android_build "$GAME_DIR" --destination "$OUTPUT"
)

DONOR_APK="$(find "$OUTPUT" -type f -name '*.apk' -print -quit)"
if [[ -z "$DONOR_APK" ]]; then
  echo "Ren'Py Android build completed without producing a universal APK." >&2
  find "$OUTPUT" -maxdepth 3 -type f -print >&2
  exit 1
fi

echo "Inspecting official Ren'Py 8.5.3 RAPT host sources:"
find "$SDK_ROOT/rapt" -type f \( -name PythonSDLActivity.java -o -name SDLActivity.java \) -print
UPSTREAM_ACTIVITY="$(find "$SDK_ROOT/rapt" -type f -name PythonSDLActivity.java -print -quit)"
if [[ -n "$UPSTREAM_ACTIVITY" ]]; then
  echo "Official PythonSDLActivity: $UPSTREAM_ACTIVITY"
  grep -n -A8 -B2 'getLibraries' "$UPSTREAM_ACTIVITY" || true
  sha1sum "$UPSTREAM_ACTIVITY"
fi

echo "Native library entries in the official universal APK:"
unzip -Z1 "$DONOR_APK" | grep -E '^lib/[^/]+/[^/]+\.so
test -s "$ROOT/app/src/main/assets/private.mp3"
test -d "$ROOT/app/src/main/assets/x-renpy/x-common"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/libSDL2.so"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/librenpython.so"

echo "Ren'Py 8.5.3 runtime payload imported into Ramen."
 | sort || true
INSPECT_DIR="$WORK/native-inspect"
mkdir -p "$INSPECT_DIR"
if unzip -p "$DONOR_APK" lib/arm64-v8a/librenpython.so > "$INSPECT_DIR/librenpython.so" 2>/dev/null; then
  echo "librenpython.so dynamic dependencies:"
  readelf -d "$INSPECT_DIR/librenpython.so" | grep -E 'NEEDED|SONAME' || true
  echo "librenpython.so SDL JNI exports:"
  readelf --dyn-syms -W "$INSPECT_DIR/librenpython.so" | grep -E 'Java_org_libsdl_app_SDLActivity_(nativeSetupJNI|nativeRunMain)' || true
fi

python3 "$ROOT/tools/renpy-8.5.3/import_runtime_from_apk.py" "$DONOR_APK" --project-root "$ROOT" --replace

test -s "$ROOT/app/src/main/assets/private.mp3"
test -d "$ROOT/app/src/main/assets/x-renpy/x-common"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/libSDL2.so"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/librenpython.so"

echo "Ren'Py 8.5.3 runtime payload imported into Ramen."
