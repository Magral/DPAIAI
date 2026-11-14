# Playback failure investigation

Intent: user reports menu/video failure on TV and phone, both DPI Pilot and ByeByeDPI, Wi-Fi and mobile; a paid full VPN immediately restores playback. Exact provider mechanism is unproven. Existing endpoint tests do not exercise signed videoplayback transfers.

Approach chosen by implementer under the user's instruction to investigate and fix: bounded, explicit on-device differential diagnostics. Increasing presets alone cannot identify DNS vs API vs media throttling. A permanent external relay is outside this investigation and cannot be invented from test failures.

Deliverable: one manual diagnostic screen in phone and TV. User can test current system route with own bypass off (including paid VPN) and the running local bypass. Neither button starts/stops a VPN, changes DNS, switches strategies, scans the catalog, or alters proof/exclusions. Reports label route, DNS results, HTTP API response, cache mapping, fresh public player discovery, and actual small video/audio range transfers with bytes/time/gaps. Cancellation closes sockets. No signed URL queries, account cookies, tokens, watch history, or public client IP are saved.

Transport rules: TLS/hostname validation always; HTTPS only; no credentials/userinfo/fragments/non443 media hosts; only exact YouTube API hosts and proper googlevideo.com subdomains. Anonymous sample unavailable/login/expired/403/429 must be INCONCLUSIVE, not evidence that all strategies are dead. CDN success requires actual media bytes, not just TLS. API JSON response is separate from permission to play. Unavailable discovery cannot be treated as confirmed video.

Platform boundaries: phone and TV share Kotlin implementation. Windows gets equivalent explicit diagnostics through the active engine or current direct system connection; browser/PAC/game/system DNS scope remains unchanged. Existing 72h schedule and cached choices stay intact. UI explicitly calls old6/6 endpoint reachability, never video proof. All releases signed/verified using existing pipelines; Windows installer signing limitation unchanged.

Success: tests reproduce false-positive TLS-only, HTML-as-media, truncated/slow content, blocked API, discovery unavailable, URL validation, privacy redaction and cancellation. Full Android and Windows CI required. Root cause on user network still needs paired reports; no claim of user playback fixed until confirmed there.
