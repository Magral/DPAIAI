# Stable API eligibility Implementation Plan

> **For agentic workers:** use superpowers:executing-plans inline, one final whole-branch review.

**Goal:** Stop intermittent API transport from qualifying as stable YouTube access.
**Architecture:** Shared public JSON validator/HTTP request plus endpoint-specific confirmation; preserve routes/history. Platform counters reflect actual endpoints.
**Tech Stack:** Kotlin Android, C# .NET10, pinned ByeDPI, GitHub Actions.
**Spec:** docs/superpowers/specs/2026-11-14-youtube-api-validation-design.md

## Global Constraints
Phone13addresses; TV/Windows8/8. Never infer playback from public API JSON. No strategy invention, TLS bypass, DNS mutation or timeout-based permanent exclusion. Preserve saved commands and72h schedule.

## Review Focus
Legacy cached proof, duplicate/missing API observations, mutable shared sessions, fixture403/login changes, probe budget on adaptive commands.

### Task1: Shared API assessment
- [x] RED meaningful JSON, unavailable status, sanitized reason, repeated observations.
- [x] Implement shared request/body/result; command-aware bounded timeout.

### Task2: Phone/TV wiring
- [x] Phone existing API endpoint requires two successes;13addresses unchanged; assessment rejects old/missing evidence.
- [x] TV API added to both rounds;8/8 strict, previous route retained despite stale proof.
- [x] Report request rejection reason/profile metadata without signed URLs.

### Task3: Windows parity
- [x] HTTP API request each round and8/8 evidence/cache/UI; migration preserves previous route.
- [x] Regression policy/controller tests and actual Chrome isolation CI.

### Task4: Review/release
- [x] Independent review, fixes RED→GREEN; full Android/native/Windows CI.
- [x] Publish signed phone0.12.3/TV0.6.14 and Windows0.2.2; verify assets.
- [ ] User forced recheck and playback confirmation; external cause still open.
