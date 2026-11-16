# WARP reliability Implementation Plan

> Execute inline, then one fresh whole-branch review.

**Goal:** Recover free WARP with bounded backoff and diagnose actual media through its VPN.
**Architecture:** Pure retry/session policy, existing VpnService engine, explicit VPN-bound shared HTTP requests, manual WarpActivity diagnostic.
**Tech Stack:** Kotlin, Android API24+ WARP, existing Go/AmneziaWG pins unchanged.
**Spec:** docs/superpowers/specs/2026-11-17-warp-reliability-design.md

## Global Constraints
No paid VPN/shared keys/TLS bypass, profile survives failures, fixed target apps plus own diagnostic UID only, no background video polls, no automatic local fallback before acceptance.

## Review Focus
Stopped/reconnected VPN must cancel old probes, VPN-bound socket must not escape to physical route, retry exhaustion must not spin, failed setup must not erase saved profile, stale STOP must not stop later START.

### Task1: Recovery policy
- [x] RED Kotlin tests for 30s/120s/600s backoff, 12s per-attempt and finite sweep, routing guard zero targets and only own diagnostic UID, stale diagnostic session.
- [x] Add WarpRecoveryPolicy.kt pure policy and session gate; integrate WarpVpnService recovery loop and explicit safe error reasons, invalidate session on start/end/reconnect.
- [x] GREEN Kotlin tests and Android CI.

### Task2: VPN-bound manual diagnostic
- [x] Extend shared PlaybackHttp with explicit Network-bound path while preserving old request signature; requests and resolver remain bound, reject SOCKS+Network combination.
- [x] WarpActivity check/cancel controls and progress using PlaybackDiagnostics, capture ready session+VPN Network and invalidate across reconnect/stop. No request merely opening screen; bounded resource use and cancellation.
- [x] Update UI routing explanation, APK audit, release notes0.13.1/code28 and signed-release request.
- [x] Review full diff, run Android CI/tests/APK audit, fix Important findings, merge and verify signed release/certificate/asset.

### Follow-up acceptance
- [ ] User tests own WARP menu/video/Shorts/Instagram with Nova off; only then automatic fallback and TV integration. MASQUE researched separately, not claimed implemented.
