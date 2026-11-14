# Playback diagnostics Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, one final independent review.

**Goal:** Produce comparative, on-device evidence for YouTube menu/media failure without destroying working profiles.
**Architecture:** Pure parsing/assessment, bounded cancellable TLS transport, manual UI; equivalent Windows diagnostics uses native engine lease. Endpoint6/6 remains separately labelled.
**Tech Stack:** Kotlin Android21+, org.json; C# .NET10; GitHub Actions.
**Spec:** docs/superpowers/specs/2026-11-14-playback-diagnostics-design.md

## Global Constraints
No VPN/DNS/settings changes from diagnostic; no auto scans; no sensitive signed queries in reports; TLS validated; inconclusive discovery cannot reject strategies.

## Review Focus
Cancellation during DNS/TLS/body; redirects to untrusted hosts; HTTP403/429/login; paid VPN vs app-excluded route labels; reuse of wrong-route samples.

### Task1: Android diagnostic engine
- [x] Add pure assessment/parser tests RED, implement minimal GREEN.
- [x] Add bounded actual TLS/HTTP transport with injectable transport for tests, stage reporting, privacy-safe output and real media bytes.
- [x] Verify cancellation, URL allowlist, JSON handling, partial ranges and false-positive TLS-only fixtures.

### Task2: Android integration
- [x] Add manual shared activity and buttons phone/TV, cancellation and copy report.
- [x] Label endpoint proof accurately, retain existing strategy cache and rare scan policy.
- [x] Run full Android build/tests in CI.

### Task3: Windows parity
- [x] Add equivalent current-route/engine diagnostic with lease, explicit UI; no PAC/system networking mutation.
- [x] Test same media/API assessment cases and existing Chrome isolation suite.

### Task4: Review and delivery
- [x] Independent review; resolve Important findings, verify all platform builds.
- [x] Release phone/TV/Windows and verify actual asset URLs.
- [x] Ask user for paired reports (current network and paid VPN); provider root remains open until compared.
