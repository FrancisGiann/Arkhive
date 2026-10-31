# Arkhive 0.4.17 — compact Bookplate widget

The compact 2×2 Continue Reading widget now uses the approved Bookplate layout at roomy square sizes: a larger inset cover, the title, a circular progress indicator with its read count, and a full-width Resume reading action. The existing compact presentation remains for tighter, large-font, and non-square sizes. The larger widget and hybrid 2×2 option are unchanged. The cover and metadata continue to come from the same current story selection, and both the card and visible action retain the existing resume target.

## Verification

- All 133 app JVM unit tests pass, including the new compact layout geometry, progress/count, fallback, and size-routing checks.
- The Android instrumentation test APK compiles with a 160dp/180dp RemoteViews layout and overlap test. It was not run because this host has no usable emulator (`/dev/kvm` and configured AVD are absent).
- The universal debug APK uses package `dev.francisgiann.readingapp.dev`, internal version `0.4.17-25`, version code 24, and the existing Android debug signing certificate. Compare its SHA-256 with the adjacent checksum file.
- Launcher rendering, actual tap behavior, large system font settings, and the 110dp fallback have not been exercised on a device.
- This change does not address the reported LNReader community-source failure or claim that a community source works on the user's device.

Arkhive's manual update checker remains available; include preview releases to discover this update. The upstream Mihon update endpoint remains disabled.

APK SHA-256: `af78a25bbbb827ee2f036e2df414bc84b9d6ae6cd52bad33d074e48e5a952e48`. Size: 101,870,597 bytes.

This is a universal preview debug build, not a production Play Store build. The APK retains its tested pre-commit internal version `0.4.17-25`; publishing the completed source does not change or rebuild the verified binary.
