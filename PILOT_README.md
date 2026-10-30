# DPI Pilot 0.5.0 — Android prototype

Experimental GPL-3.0 derivative of dovecoteescapee/ByeDPIAndroid. Original copyright, LICENSE and README are retained. Native engine: hufrea/byedpi; tunnel: heiher/hev-socks5-tunnel. No upstream endorsement.

Application ID: io.github.magral.dpipilot. Internal JNI namespace is retained for compatibility. Android 5+, no root. No remote tunnel and no IP hiding.

## Added
- Russian profiles screen, five experimental presets including a control without modification.
- Three local slots: home Wi-Fi, cellular, fallback. Manual selection; no automatic network detection yet.
- Apply settings only while stopped; profiles survive restarts.
- Explicit SOCKS HTTPS probe, verified certificates/hostname, bounded header read, socket timeouts. App is excluded from the VPN, so a direct request would test the wrong path.
- Separate app ID and displayed version; internal settings activity is not exported.

## Usage
Open menu «Профили и проверка». Choose a preset, press «Взять готовый профиль», save if desired and apply. Return home, enable processing and approve Android VPN prompt. Return to profiles and check a domain. A response (including 403) proves transport reachability, not successful content access. Video, QUIC, voice and provider compatibility require device tests.

## Build
JDK 17, Android SDK 34, build-tools 34.0.0, NDK 26.1.10909125, CMake 3.22.1, Gradle wrapper 8.4.
`git submodule update --init --recursive`
`bash gradlew assembleDebug`
APK: app/build/outputs/apk/debug/app-debug.apk.
Debug APK is a development build, not production signing. Published APKs use the permanent release key described in docs/SIGNING.md.

## Next milestones
Per-app routing and an isolated cancellable service-oriented strategy runner are implemented in 0.5.0. See README.md for probe scope and limits. Still missing: full content/media checks, automatic network-specific profile selection and rollback, production VPN lifecycle hardening.

Do not publish as a stable release until launch/stop, Wi-Fi/cellular handoff, screen-off, IPv6, DNS, another VPN, and target service behavior are checked on real devices. Source code must accompany redistribution under the retained license.
