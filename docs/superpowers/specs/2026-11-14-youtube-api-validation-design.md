# Stable YouTube API validation

Evidence: three user reports on the same Wi-Fi. Direct route times out during TLS for YouTube/API/CDN while gstatic and DNS work; paid VPN completes HTTP/API; local DPI path has mixed TLS success/failure and only1/2 API responses. User profile is a static multi-split/disorder command, no --auto/--timeout; fallback-budget hypothesis is ruled out for that profile. Exact external filtering mechanism and media playback remain unproven. No new guessed bypass flags.

Confirmed software defect: one TLS-only API request can qualify a phone candidate, TV/Windows omit that API from mandatory checks. Fix network eligibility by requiring meaningful public player JSON repeatedly over fresh TLS connections. Anonymous UNPLAYABLE/LOGIN_REQUIRED JSON proves API transport only, never playback. HTTP400/401/403/404/410/429/5xx is unavailable fixture, not a permanent strategy rejection.

Phone keeps13 distinct endpoints and one visible round; its existing API endpoint performs two fresh HTTP exchanges and requires2/2. TV/Windows add the API to their existing two-round matrix:8/8. Old proofs lacking API evidence are stale/insufficient, but saved commands/history/exclusions and the72h schedule are not erased. Instagram eligibility remains unchanged. No permanent exclusions from timeouts/unavailable fixtures. API client body/schema is shared with manual diagnostics, TLS/hostname validation remains strict. Diagnostic reports expose sanitized public playability reason plus profile-at-open metadata, without signed URLs/cookies.

This fixes unsupported eligibility, not a proven provider bypass. Release only after regression/full platform CI; user then runs a single forced recheck and verifies actual playback.
