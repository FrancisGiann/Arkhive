# Arkhive 0.4.9 — Continue Reading widget

Adds a resizable Android home-screen Continue Reading widget matching the in-app card: Arkhive typography, theme colors, cover art, diagonal accents and saved reading progress.

The widget selects the latest visible comic, novel or imported EPUB/PDF/text book. Comics resume their recorded chapter and saved page; completed chapters advance forward, and completed comics open the title details. Existing novel and imported-book readers restore their saved position. Missing covers use the Arkhive mark; empty, removed and hidden targets return safely to Library. Enabling app lock replaces launcher reading content with a generic prompt. Widget cover rendering uses local/cache data without a network fetch.

Large text reduces secondary metadata while retaining the full accessible title/status and a large card touch target. Colors follow the app palette and light/dark choice, including the progress meter on Android 8 and later.

## Compatibility and verification

Retains the established public-preview package `dev.francisgiann.readingapp.dev` and signing certificate. Version code 16 is newer than 0.4.8/code 15; keep the existing installation to retain its library, settings and saved positions. Android 8/API26 or later.

Focused widget policy and updater checks are run for this candidate. Native launcher rendering, widget taps, TalkBack, physical-phone upgrades, ARM execution and the full reader regression matrix remain unverified: the single preserved read-only API35 emulator attempt crashed during boot before installation. The design preview is a synthetic reconstruction, not a native launcher screenshot.

This remains a preview. Existing source compatibility limitations remain unchanged. No signing material, credentials, personal library data or private QA reports are included.
