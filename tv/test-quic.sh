#!/usr/bin/env bash
set -euo pipefail
out=$(mktemp)
trap 'rm -f "$out"' EXIT
cc -Wall -Wextra -Werror tv/src/main/cpp/quic_guard.c tv/src/test/c/quic_guard_test.c -o "$out"
"$out"
