#!/usr/bin/env bash
set -euo pipefail

# Build the Ren'Py 8.5.3 Android payload from the matching public SDK + RAPT.
# The temporary sample-game APK is only a packaging intermediate; it is never
# installed, committed, or shipped inside Ramen.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/ramen-renpy-runtime"
SDK_ZIP="$WORK/renpy-8.5.3-sdk.zip"
RAPT_ZIP="$WORK/renpy-8.5.3-rapt.zip"
GRADLE_ZIP="$WORK/gradle-9.1.0-bin.zip"
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

# RAPT 8.5.3 pins Gradle 9.1.0. Its bundled Wrapper predates built-in
# download retries (added in Gradle 9.5), so fetch and verify the exact
# distribution with curl retries, then let Wrapper use a local file:// URL.
curl --fail --location --retry 8 --retry-all-errors --retry-delay 5 \
  --retry-max-time 1800 --connect-timeout 30 --max-time 300 \
  https://downloads.gradle.org/distributions/gradle-9.1.0-bin.zip -o "$GRADLE_ZIP"
echo "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806  $GRADLE_ZIP" | sha256sum --check -

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
  RAPT_BUILD_PY="$(find "$RAPT_UNPACK" -maxdepth 5 -type f -path '*/buildlib/rapt/build.py' -print -quit)"
  [[ -n "$RAPT_BUILD_PY" ]] || { echo "Could not locate RAPT build.py." >&2; exit 1; }
  RAPT_ROOT="$(cd "$(dirname "$RAPT_BUILD_PY")/../../.." && pwd)"
fi
rm -rf "$SDK_ROOT/rapt"
cp -a "$RAPT_ROOT" "$SDK_ROOT/rapt"

RAPT_WRAPPER="$SDK_ROOT/rapt/prototype/gradle/wrapper/gradle-wrapper.properties"
if [[ ! -f "$RAPT_WRAPPER" ]]; then
  echo "RAPT Gradle wrapper properties not found: $RAPT_WRAPPER" >&2
  exit 1
fi
sed -i 's/networkTimeout=10000/networkTimeout=120000/' "$RAPT_WRAPPER"
GRADLE_ZIP_URI="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve().as_uri())' "$GRADLE_ZIP")"
python3 - "$RAPT_WRAPPER" "$GRADLE_ZIP_URI" <<'PY'
from pathlib import Path
import sys

wrapper = Path(sys.argv[1])
distribution_uri = sys.argv[2]
lines = wrapper.read_text(encoding="utf-8").splitlines()
updated = []
found_distribution = False

for line in lines:
    if line.startswith("distributionUrl="):
        updated.append("distributionUrl=" + distribution_uri)
        found_distribution = True
    elif line.startswith(("retries=", "retryBackOffMs=")):
        # Gradle 9.1.0 does not understand these Gradle 9.5+ settings.
        continue
    else:
        updated.append(line)

if not found_distribution:
    raise SystemExit("RAPT wrapper has no distributionUrl property.")

wrapper.write_text("\n".join(updated) + "\n", encoding="utf-8")
print("Configured RAPT Gradle Wrapper to use verified local distribution:", distribution_uri)
PY

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
UPSTREAM_ACTIVITY="$(find "$SDK_ROOT/rapt" -type f -name PythonSDLActivity.java -print -quit)"
if [[ -n "$UPSTREAM_ACTIVITY" ]]; then
  echo "Official PythonSDLActivity: $UPSTREAM_ACTIVITY"
  grep -n -A8 -B2 'getLibraries' "$UPSTREAM_ACTIVITY" || true
  sha1sum "$UPSTREAM_ACTIVITY"
else
  echo "No standalone PythonSDLActivity.java found in the unpacked RAPT tree."
fi

echo "Native library entries in the official universal APK:"
python3 - "$DONOR_APK" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if name.startswith("lib/") and name.endswith(".so"):
            print(name)
PY

INSPECT_DIR="$WORK/native-inspect"
mkdir -p "$INSPECT_DIR"
if unzip -p "$DONOR_APK" lib/arm64-v8a/librenpython.so > "$INSPECT_DIR/librenpython.so" 2>/dev/null; then
  echo "librenpython.so dynamic dependencies:"
  readelf -d "$INSPECT_DIR/librenpython.so" | grep -E 'NEEDED|SONAME' || true
  echo "librenpython.so JNI exports:"
  readelf --dyn-syms -W "$INSPECT_DIR/librenpython.so" | grep -E 'JNI_OnLoad|Java_org_libsdl_app_SDLActivity_' || true
fi

python3 "$ROOT/tools/renpy-8.5.3/import_runtime_from_apk.py" "$DONOR_APK" --project-root "$ROOT" --replace

test -s "$ROOT/app/src/main/assets/private.mp3"
test -d "$ROOT/app/src/main/assets/x-renpy/x-common"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/libSDL2.so"
test -s "$ROOT/app/src/main/jniLibs/arm64-v8a/librenpython.so"

echo "Ren'Py 8.5.3 runtime payload imported into Ramen."
