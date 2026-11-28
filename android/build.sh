#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
for tool in aapt2 d8 zipalign apksigner; do
    if [[ ! -x "$tools/$tool" ]]; then
        printf 'Missing %s. Set ANDROID_SDK_ROOT to an SDK with Build Tools 36.0.0.\n' "$tools/$tool" >&2
        exit 1
    fi
done
if [[ ! -f "$platform" ]]; then
    printf 'Missing Android SDK Platform 36 at %s.\n' "$platform" >&2
    exit 1
fi

umask 077
mkdir -p "$root/build"
scratch=$(mktemp -d "$root/build/.work.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
mkdir "$scratch/classes" "$scratch/dex"

mkdir "$scratch/gen"
"$tools/aapt2" compile --dir "$root/brand" -o "$scratch/brand.zip"
node "$root/sync/build.mjs" "$scratch/assets"
"$tools/aapt2" link "$scratch/brand.zip" -A "$scratch/assets" -I "$platform" --manifest "$root/AndroidManifest.xml" \
    --java "$scratch/gen" --rename-manifest-package com.yinyangeq.companion.preview --debug-mode --version-code 77 --version-name 0.17.3 -o "$scratch/base.apk"
javac --release 8 -Xlint:-options -classpath "$platform" -d "$scratch/classes" "$root"/src/*.java "$root"/plus/src/*.java "$scratch/gen/com/yinyangeq/qudelixprobe/R.java"
jar --create --file "$scratch/probe.jar" -C "$scratch/classes" .
"$tools/d8" --lib "$platform" --min-api 26 --output "$scratch/dex" "$scratch/probe.jar"
jar --update --file "$scratch/base.apk" -C "$scratch/dex" classes.dex
"$tools/zipalign" -f 4 "$scratch/base.apk" "$scratch/aligned.apk"

key="$root/build/debug.p12"
if [[ ! -f "$key" ]]; then
    keytool -genkeypair -keystore "$scratch/debug.p12" -storetype PKCS12 -storepass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=YinYang EQ Local Debug' -noprompt
    # Concurrent builds use the first completed key and keep the same install identity.
    ln -- "$scratch/debug.p12" "$key" || [[ -f "$key" ]]
fi
"$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --out "$scratch/signed.apk" "$scratch/aligned.apk"
"$tools/apksigner" verify "$scratch/signed.apk"
"$tools/zipalign" -c 4 "$scratch/signed.apk"
apk="${APK_OUTPUT:-$root/build/YinYangEQ Q5K Companion App.apk}"
mv -f -- "$scratch/signed.apk" "$apk"
printf 'Built %s\n' "$apk"
