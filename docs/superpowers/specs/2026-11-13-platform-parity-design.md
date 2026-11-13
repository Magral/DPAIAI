# TV / Windows strategy-evidence parity

User authorization: prepare the improvements identified in the platform audit and carry them through release. Continue inline without additional approval prompts; preserve the established product boundaries.

## Intent
Keep a known working configuration stable and avoid repeated or destructive diagnostics. Bring TV and Windows up to the phone's evidence-cache and quiet-selection behavior. This is a port of the audited policies, not a new universal networking architecture.

## Design
- Full core reachability remains mandatory: TV and Windows require 6/6. Picture-quality or anonymous media-source failures do not revoke core success. Never merge partial attempts.
- Persist successful proof for 72 hours, keyed by physical-network profile and stable strategy ID. A cache read or application never renews the proof's timestamp. Backward clock movement and changed engine identity require revalidation.
- Deferred/transient failures keep the last successful proof. Explicit exclusions and user reports remain authoritative. Negative evidence requires live positive control in the same network; no mass exclusions after global failure.
- Apply a fresh manually selected strategy without another network probe. Stale manual choices get a test before touching the active route. Keep selected strategies across restarts.
- Rank eligible strategies by HTTPS latency first; preview quality only breaks ties. Existing working traffic survives candidate tests. Windows connection leases preserve streams when changing engines; TV restarts its own YouTube tunnel only on an actual strategy change.
- Optional periodic scans use a persisted 72-hour attempt budget. Default Windows quiet-mode preference remains unchanged; manual requests work immediately. Passive traffic counters never launch a new scan.
- Windows gets a persisted list of full results, manual selection, explicit recheck and exclusion reset. No global proxy, DNS, firewall or game routing changes.
- TV completes every core request in a round before reporting failure. Optional preview probes cannot cancel core evidence.

## Approach choice
Retain Kotlin/Android and C#/.NET implementations and transfer tested policy contracts. A shared networking runtime would risk platform isolation and is unnecessary. A display-only change would leave cache and scheduler defects unfixed.

## Release scope and constraints
TV 0.6.12 APK signed with the existing permanent Android identity; Windows 0.2.0 per-user EXE installer. The phone 0.12.1 behavior is the reference and remains compatible. Keep all existing native-engine and browser-isolation tests. DNS-provider selection for TV/Windows is a separate port requiring platform-specific resolution plumbing; this release does not pretend that changing a setting alone implements it.

## Acceptance
Regression tests cover deferred success, TTL boundaries, cache reuse after restart, explicit exclusion, stable latency ordering and schedule persistence. Windows tests also cover cache network isolation, engine mismatch, partial 3/6 rejection and existing PAC/direct-game boundaries. Existing full build suites and release identity checks must pass before publication.

Ограничение идентификации сети: профиль определяется доступными параметрами интерфейса/шлюза и DNS, без SSID. Две физически разные сети с одинаковыми параметрами могут иметь общий профиль; после такого переключения используйте принудительную перепроверку. Кэш изолирован между различающимися профилями, но это не гарантирует уникальность физической сети.
