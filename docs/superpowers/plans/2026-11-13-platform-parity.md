# TV / Windows evidence parity Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Track each task's RED/GREEN proof in the ledger.

**Goal:** Ship stable cached manual/automatic strategy selection on TV and Windows.
**Architecture:** Platform-specific controllers consume persisted complete evidence, 72-hour wall-clock budgets and latency-first ordering. Preserve the existing network boundaries and native engines.
**Tech Stack:** Kotlin / Android API 21+, C# / .NET 10, GitHub Actions.
**Spec:** docs/superpowers/specs/2026-11-13-platform-parity-design.md

## Global Constraints
6/6 required; TTL 72 hours; no renewal on reuse; preserve explicit reports; no global Windows networking changes; APK permanent identity unchanged.

## Review Focus
- Clock rollback/expiry cannot cause a retry storm or freshen stale proof.
- A transient failed probe cannot erase prior valid proof.
- A new network cannot reuse another network's chosen strategy or cache.
- Stopping or changing routes cannot break unrelated or leased TCP streams.
- Partial reachability cannot enter the eligible list merely because latency is low.

### Task 1: TV persisted evidence
Files: QualityPolicy.kt, QualityStore.kt, TvCheckPolicy.kt, KnownStrategiesTest.kt, TvCheckPolicyTest.kt.
Interface: QualityStore.fresh(index,now): ProbeQuality?; defer preserves full evidence; choices/fastest sort latency first.
- [x] Add tests for preserved successful proof, TTL/rollback, expiry without deletion, exclusion and latency ordering; run RED.
- [x] Implement minimal persisted checkedAt field and fresh lookup; preserve proof on defer; update ordering.
- [x] Run relevant suite GREEN; commit.

### Task 2: TV controller reuse and complete reporting
Files: TvVpnService.kt, TvProbe.kt, TvActivity.kt and TV policy tests.
- [x] Test full core reporting despite one failed concurrent request; keep strict 6/6.
- [x] Use fresh proof for manual selection and scan candidates without renewing it; preserve selected startup route; prevent timeout exclusions.
- [x] Keep temporary test failure from removing the active verified route, update user copy.
- [x] Build/test all Android modules and native adapters in CI.

### Task 3: Windows evidence and schedule
Files: new Core/StrategyEvidence.cs, Settings.cs, CheckPolicy.cs; new Tests/CachePolicyTests.cs.
Interface: evidence store persists valid 6/6 results scoped by profile/ID/engine; fresh lookup; choices sorted by latency; deferred failure preserves success; persisted scan timestamps.
- [x] Add tests for success retention, TTL/clock rollback, reload/network/engine isolation, explicit exclusion and 72h policy; run RED.
- [x] Implement minimal evidence store and schedule helpers; run GREEN.

### Task 4: Windows controller and selection UI
Files: PilotController.cs, Probe.cs, MainForm.cs, Tests/Program.cs.
- [x] Wire evidence into startup, profile restoration, candidate selection and manual choices.
- [x] Compare a bounded block of full results, choose lowest latency, keep existing connections leased; no 2-minute network retest of cached proof.
- [x] Add eligible-strategy menu, separate force recheck, exclusion reset, cache timestamps and quiet-mode copy.
- [x] Keep passive counters informational; no periodic sub-hour recovery scans.
- [x] Run full Windows native/PAC/Chrome isolation suite.

### Task 5: Review and release
- [x] Review final diff independently against the spec and ledger; resolve important findings with tests.
- [x] Bump TV to 0.6.12, Windows to 0.2.0; update release notes and installer resources.
- [x] Publish only after CI passes, verify asset URLs and permanent APK certificate.
