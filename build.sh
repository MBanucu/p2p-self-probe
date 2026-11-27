#!/bin/sh
# Build one APK without Gradle: aapt2, javac, d8, zipalign, apksigner.
# Run it inside `nix develop` (or `nix develop .#`); the shell supplies the SDK
# and the JDK. The dev key is generated under build/ and never tracked.
set -eu

here=$(cd -- "$(dirname -- "$0")" && pwd -P)
build="$here/build"
keystore="$build/dev.keystore"

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$sdk" ]; then
    echo "build.sh: ANDROID_SDK_ROOT is not set; run inside 'nix develop'" >&2
    exit 2
fi
if ! command -v javac >/dev/null 2>&1; then
    echo "build.sh: javac is not on PATH; run inside 'nix develop'" >&2
    exit 2
fi

newest() {
    find -L "$1" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null |
        sort -V | tail -1
}
build_tools="$sdk/build-tools/$(newest "$sdk/build-tools")"
platform="$sdk/platforms/$(newest "$sdk/platforms")"
android_jar="$platform/android.jar"
if [ ! -x "$build_tools/aapt2" ]; then
    echo "build.sh: no build-tools with aapt2 under $sdk" >&2
    exit 2
fi
if [ ! -f "$android_jar" ]; then
    echo "build.sh: no android.jar under $sdk/platforms" >&2
    exit 2
fi

mkdir -p "$build"
if [ ! -f "$keystore" ]; then
    echo "generating a dev key at $keystore (password: android)"
    (umask 077 &&
        keytool -genkeypair \
            -keystore "$keystore" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=p2p-self-probe debug" >/dev/null 2>&1)
fi

work="$build/app"
rm -rf "$work"
mkdir -p "$work/res" "$work/gen" "$work/classes" "$work/dex"

"$build_tools/aapt2" compile --dir "$here/res" -o "$work/res/resources.zip"
"$build_tools/aapt2" link \
    --manifest "$here/AndroidManifest.xml" \
    --java "$work/gen" \
    -I "$android_jar" \
    -o "$work/unaligned.apk" \
    "$work/res/resources.zip"

find "$here/src" "$work/gen" -name '*.java' -print | sort >"$work/sources.txt"
javac --release 11 -encoding UTF-8 -classpath "$android_jar" \
    -d "$work/classes" @"$work/sources.txt"

# shellcheck disable=SC2046 # the build directory is ours and holds no spaces
"$build_tools/d8" --lib "$android_jar" --min-api 33 --output "$work/dex" \
    $(find "$work/classes" -name '*.class' -print)

cp "$work/unaligned.apk" "$work/base.apk"
(cd "$work/dex" && zip -q -0 "$work/base.apk" classes.dex)

"$build_tools/zipalign" -f -p 4 "$work/base.apk" "$work/aligned.apk"

apk="$build/p2p-self-probe.apk"
"$build_tools/apksigner" sign \
    --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$apk" "$work/aligned.apk"
"$build_tools/apksigner" verify --print-certs "$apk" | head -3

echo "built $apk ($(wc -c <"$apk") bytes)"
