# Phone 0.9.3 / TV 0.6.11

Scan failures are deferred in memory. They become persistent exclusions only if the initially working active strategy passes a fresh complete control after the scan on the same network. A failed control cancels these new exclusions and ends the cycle. If there is no working active strategy, failed candidates are not permanently excluded by this scan.

When current access fails, candidates retain the existing history-first ordering. The first full successful reserve ends the scan and enters the existing bounded trial/rollback procedure. Successful normal cycles still compare candidates.

Phone failure descriptions prioritize failed endpoints, retain the error type and are bounded to 1200 characters. Progress displays the cause; failure history stores it. TV progress distinguishes pending exclusions from confirmed exclusions and includes probe details.

Limits: a strategy-specific failure of the control also defers exclusions conservatively. This does not prove the whole Internet is down. Historical manual complaints and existing exclusions remain unchanged. Trial rollback policies are unchanged. No extra scheduled cycle is added.

Validation: Android build/unit tests, failed endpoint formatting, existing eligibility and exclusion persistence tests, native catalog/routing tests, permanent signing checks.
