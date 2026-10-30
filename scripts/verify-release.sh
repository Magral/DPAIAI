#!/usr/bin/env bash
set -euo pipefail
# The expected public certificate digest is pinned in a repository secret.
apk=${1:?APK path is required}
: "${ANDROID_SIGNING_CERT_SHA256:?Permanent certificate SHA-256 is required}"
: "${ANDROID_HOME:?Android SDK is required}"
report=$("$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs "$apk")
actual=$(printf '%s\n' "$report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')
expected=$(printf '%s' "$ANDROID_SIGNING_CERT_SHA256" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid expected certificate digest' >&2; exit 1; }
[[ "$actual" == "$expected" ]] || { echo 'Release certificate mismatch' >&2; exit 1; }
printf '%s\n' "$report"
