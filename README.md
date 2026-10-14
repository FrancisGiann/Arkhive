# Arkhive - Manga & Novel Reader

**Android preview 0.4.0.** A cover-led reader for manga, manhwa, manhua, online novels, and local EPUB/PDF/text. Built on the retained Mihon source host and native image reader, with Readium for reflowable books.

[Download Arkhive-0.4.0.apk](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.0/Arkhive-0.4.0.apk) · [Release and checksum](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.0)

Android 8 / API 26 or later. The universal debug APK includes arm64-v8a, armeabi-v7a, x86 and x86_64. Installed version: `0.4.0-0`, code 7. It retains the existing package and signing identity for updates.

SHA-256:

```
bb8356d06d0074067440ca18c8022a83a78d397ce6f2b7540647d9870830c1de
```

The interface follows the planned violet cover/header and rounded lower chapter panel. Settings opens with one header; Library keeps All / Novels / Manga visible and moves status, shelves, PDF and In progress into Filters. Filters persist across restarts, have visible active labels, and can be cleared. Management actions have a separate overflow.

Manga reading supports RTL/LTR pages, vertical strips, full-width controls, bounded tall-image rendering and single-image retry. Online novels and reflowable EPUBs support separate saved Pages / Vertical scroll layouts with manual swipe/tap navigation. A small bottom percentage and compact pause/resume icon remain visible when controls are hidden, including Continuous scroll. The strip reserves space below the text and keeps a labelled 48dp touch target; online percentage describes the current chapter and EPUB follows its publication locator. Adjustable Continuous Auto-read and timed steps pause for interaction, backgrounding and chapter boundaries. Local imports show progress, recognize exact duplicates and retain bookmarks/progress.

Library tools include opt-in Smart offline for the next 5/10 unread chapters on Wi-Fi, a shared download budget and queue, reviewed source migration, a shared chapter inbox, custom shelves and status, per-story reading profiles, observed source health, local metadata backup/restore, static Reading Rooms, deliberate hidden-library reveal and optional local reading statistics. Buying is a separate tab.

Wikisource is the built-in online novel source. Discovery shows real Open Library catalog matches and supported trend windows with provenance. Unsupported manga/source rankings and unconfirmed prices stay unavailable. Native Mihon extension compatibility is retained; self-authored API 1.4/1.6 trust fixtures are tested. External sources can change or fail independently.

Validation on the delivered universal APK: **81 JVM tests, 60 Android runtime cases, and 4 staged offline/restart checks passed** on the preserved API 35 x86_64 emulator. Actual filter controls were checked at normal and 200% text size. Physical-device install/update, ARM execution, the API 26/36 runtime matrix, full TalkBack, long-session battery and sustained 120fps remain unverified. Native reader recreation restores chapter/logical page; the inherited webtoon reader can reset the pixel offset within one tall image. Single-image retry preserves its live viewport.

All six screenshots below use original, self-authored synthetic samples. The public repository contains release APKs, documentation, notices and these screenshots. The implementation/source and detailed QA archive are delivered privately. Apache-2.0/Mihon, Readium BSD and Noto SIL OFL notices are retained in [NOTICE](NOTICE) and [licenses](licenses).

| Library | Local chapters | Manga chapters |
|---|---|---|
| ![Library](docs/screenshots/library.png) | ![Local chapters](docs/screenshots/local-chapters.png) | ![Manga chapters](docs/screenshots/manga-chapters.png) |

| Manga reader | Webtoon reader | Novel reader |
|---|---|---|
| ![Manga reader](docs/screenshots/manga-reader.png) | ![Webtoon reader](docs/screenshots/webtoon-reader.png) | ![Novel reader](docs/screenshots/novel-reader.png) |
