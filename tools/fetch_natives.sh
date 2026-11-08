#!/bin/sh
# Fetches the Android builds of Python, FFmpeg and QuickJS that HELINK ships
# (from youtubedl-android 0.18.1) into third_party/jniLibs/.
set -e
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
git clone --depth 1 --branch 0.18.1 --filter=blob:none --sparse https://github.com/yausername/youtubedl-android.git "$TMP/ydl" \
  || git clone --depth 1 --filter=blob:none --sparse https://github.com/yausername/youtubedl-android.git "$TMP/ydl"
cd "$TMP/ydl"
git sparse-checkout set library/src/main/jniLibs ffmpeg/src/main/jniLibs
cd - >/dev/null
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p third_party/jniLibs/$abi
  cp "$TMP"/ydl/library/src/main/jniLibs/$abi/*.so "$TMP"/ydl/ffmpeg/src/main/jniLibs/$abi/*.so third_party/jniLibs/$abi/
done
rm -rf "$TMP"
ls -la third_party/jniLibs/*
