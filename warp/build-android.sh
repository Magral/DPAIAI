#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Android SDK required}/ndk/26.1.10909125}"
clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CGO_ENABLED=1 GOTOOLCHAIN=local
for target in 'armeabi-v7a arm armv7a-linux-androideabi' 'arm64-v8a arm64 aarch64-linux-android' 'x86 386 i686-linux-android' 'x86_64 amd64 x86_64-linux-android'; do
    read -r abi arch triple <<< "$target"
    mkdir -p "$root/app/src/main/jniLibs/$abi"
    GOOS=android GOARCH="$arch" GOARM=7 CC="$clang/${triple}24-clang" \
      go -C "$root/warp/native" build -mod=readonly -trimpath -buildvcs=false -buildmode=c-shared \
      -ldflags='-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384' \
      -o "$root/app/src/main/jniLibs/$abi/libpilotwarp.so" .
    rm -f "$root/app/src/main/jniLibs/$abi/libpilotwarp.h"
    echo "WARP source build: $abi"
done
mkdir -p "$root/app/build/generated/warpAssets"
python3 "$root/warp/licenses.py" > "$root/app/build/generated/warpAssets/warp-licenses.txt"
