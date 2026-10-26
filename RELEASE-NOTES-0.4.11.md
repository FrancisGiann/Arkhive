# Arkhive 0.4.11 — Continue Reading widget repair

This preview replaces the withdrawn 0.4.10 APK. The large Continue Reading card remains available, and the separate compact picker option now declares a 2×2 home-screen size. The compact card keeps the cover, title, progress, status and resume action. Both widget layouts replace the unsupported `Space` view, the probable cause of Android's “Can't load widget” placeholder.

The widget continues to select the latest visible reading item, respect the app lock and hidden library items, refresh from library changes, and resume through the app's existing reader routes. The in-app update check is enabled in this APK.

## Install and verification

Android 8/API 26 or later. Universal APK: `Arkhive-0.4.11.apk` (101,649,130 bytes). Package: `dev.francisgiann.readingapp.dev`; internal version `0.4.11-1`, version code 18. Signing certificate SHA-256: `e65c4a59ababea528d0f0e18e5a81f7bde897c03e3000bef0155b2df021ac472`, matching prior public previews. APK SHA-256: `bf54923798745a56bf6beec910c896450e05cbe03ad30c4a5cab78a4c9f031bb`.

All 121 app JVM tests passed. Both widget layouts contain only RemoteViews-supported view classes, the compiled compact provider declares 2×2 cells, and the Android RemoteViews inflation regression test APK compiled. The private API 35 emulator crashed during boot before that test ran. Actual phone launcher loading, resize, long-text rendering, refresh, tapping, TalkBack and installation over an existing app remain unverified. Please report any launcher loading issue before relying on this preview.

This is a preview release. Keep **Include preview releases** enabled in the app's manual updater to see it. Signing material, credentials, user data and private QA records are not included in the source archive.
