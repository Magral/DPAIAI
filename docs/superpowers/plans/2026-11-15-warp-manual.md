# Manual WARP Implementation Plan

> **For agentic workers:** use superpowers:executing-plans inline; one fresh final whole-branch review.

**Goal:** Source-built standalone free WARP mode, only YouTube/Instagram on phone.
**Architecture:** Android VpnService owns routing and lifecycle, small pinned AmneziaWG Go C-shared backend owns WireGuard packets, C JNI callback protects outer UDP. Independent encrypted config store and bounded enrollment; local mode preserved.
**Tech Stack:** Kotlin/API34, Go1.25.3, AmneziaWG b5928efb6ca19f0153958460c3d141f04abc5c2e, NDK26.1.
**Spec:** docs/superpowers/specs/2026-11-15-warp-manual-design.md

## Global Constraints
Manual phone first. API24+ WARP, local minSdk21.4ABIs. Fixed installed YouTube/Instagram only. MTU1280. No shared keys/Nova relay/no paid VPN. Existing strategies untouched. No real playback claim before device acceptance.

## Review Focus
Cancelled setup/start must not save/activate late work. No installed client must not create global TUN. Never log private key or full UAPI stats. Outer sockets protected before use. External VPN replacement/revoke must stop recovery loop.

### Task1: Native backend and bounded config
- [x] RED Go tests reserved packet transformation, protect failure, close/reopen and stats redaction; Kotlin tests profile parsing/provider checks and IPC allowlist.
- [x] Implement warp/native source and source-build script, JNI callback,4ABIs; generate/validate private/public keys.
- [x] GREEN host tests, cross-build and native symbols audit.

### Task2: Android manual flow
- [x] RED fixed app allowlist/zero-app guard and start/cancel generation policy.
- [x] Implement encrypted store, bounded own registration/import, setup screen, manual service lifecycle/foreground notification/revoke/network change/handshake status.
- [x] Wire ServiceManager and main screen. Local mode unchanged, WARP selected only stopped. Disable incompatible tuning/local probes while WARP active.
- [x] GREEN module tests and signed Gradle/APK audit.

### Task3: Review and release
- [x] Fresh whole-branch review; important fixes RED→GREEN.
- [x] Release experimental manual phone0.13.0 under permanent signing key; verify asset/cert. No TV/Windows release until actual phone acceptance.
- [ ] User confirms standalone connection and real menu/video/Shorts; only then automatic fallback/parity.
