# Arkhive 0.4.16 — responsive tiled reader

Canceled image-region requests no longer wait for the global native decode slot and then decode tiles for a viewport that has already moved. The current viewport can proceed after the active native decode finishes. Bitmap results from canceled in-flight decodes are released when native decoding returns. The change does not alter library data, reading progress, backups, preferences or reader controls.

## Verification

- All 127 app JVM unit tests pass, including regressions for canceled queued decodes and for releasing the decode permit after a decode failure.
- The universal preview APK uses package `dev.francisgiann.readingapp.dev`, internal version `0.4.16-25`, version code 23 and the existing update-compatible signing certificate. Its exact SHA-256 is attached as `Arkhive-0.4.16.apk.sha256`.
- This host had no connected Android device or usable emulator, so sustained on-device scrolling smoothness has not been measured.
- The reported failure across online novel sources remains unresolved. This release makes no LNReader compatibility claim; the public Wikisource endpoint check does not validate the user's installed extensions or device network.

This is a preview debug APK, not a production Play Store build.
