# Arkhive - Manga & Novel Reader

A cover-led Android reader for manga, manhwa, manhua, online novels, and local EPUB/PDF/text. Arkhive retains Mihon's native image reader and source host, with Readium for reflowable books.

**Current preview: 0.4.3.** This is a universal debug APK, not a production release.

[Download Arkhive-0.4.3.apk](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.3/Arkhive-0.4.3.apk) · [Release notes](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.3) · [SHA-256 file](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.3/Arkhive-0.4.3.apk.sha256)

## Install or update

1. Use Android 8 / API 26 or later. Download the APK above and compare its SHA-256 with the attached checksum.
2. Open the APK on Android. If prompted, allow installation from the browser or file manager used for this download.
3. To update the existing Arkhive preview, install over it. The package and signing certificate are unchanged; keep the existing installation to retain its data.

Internal version: **0.4.3-1**, version code **10**. Package: `dev.francisgiann.readingapp.dev`. The universal APK includes ARM64, ARMv7, x86 and x86_64 libraries. Size: **102,115,234 bytes**.

SHA-256:

~~~text
8a7f4077aeacaba57940ea0c2387fda52c5e495c93b02f777e06e5b855f1ad4e
~~~

The previous [0.4.2 prerelease](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.2) remains available. Check each release's notes before changing versions.

## Reading

- **Manga and webtoons:** RTL/LTR pages, vertical strips, native fullscreen controls, bounded tall-image rendering and single-image retry.
- **Novels and EPUBs:** saved Pages and continuous/vertical-scroll layouts, manual swipe/tap navigation, bookmarks and reading progress. A quiet bottom percentage remains visible in both modes when controls are hidden; online text reports chapter progress, while EPUB uses its publication locator.
- **Auto-read:** adjustable continuous scrolling and timed discrete steps. Interaction, backgrounding and chapter boundaries pause reading; manual advancement remains available.
- **Local imports:** EPUB, PDF and text, with import progress, exact-duplicate recognition and saved bookmarks/progress.
- **Library:** All / Novels / Manga tabs, persistent clearable filters, shelves/status, per-story reading profiles, chapter inbox, reviewed source migration, local metadata backup/restore, hidden-library reveal, static Reading Rooms and optional local reading statistics. Buying stays in a separate tab.

## New in 0.4.3

- **Adjacent chapter preload:** bounded online-chapter text preload into temporary cache, respecting network and private-reading rules.
- **Return after a jump:** one contextual Return point for deliberate reading jumps, including online chapters and EPUB/PDF navigation.
- **Live typography preview:** preserve the reading anchor through Pages/Continuous layout and appearance changes.
- **Offline status:** Library and novel details distinguish saved chapters, queued/paused work, retry and failed work.
- **Shared appearance controls:** EPUB and online text share font size, line spacing, margins and reading-paper options.

Continuous online text also supports manual edge advances.

## Online sources and offline reading

Wikisource is the built-in online novel source. Native Mihon extension compatibility is retained; external sources can change or fail independently. Authored API 1.4/1.6 trust fixtures do not establish that every external extension or live source works. Use sources and content you are entitled to access; there is no DRM or paywall bypass.

Online reading needs an available source and network connection unless the chapter has been saved. Temporary preload cache is **not** a saved offline chapter. Optional downloads and Smart offline can save eligible chapters, with a shared budget and queue; Smart offline is opt-in for the next 5/10 unread chapters on Wi-Fi. Local imports and completed saved chapters can be read offline. Queued, paused or failed work does not mean a chapter is available offline.

Discovery uses real Open Library catalog matches and supported source trend windows with provenance. Catalog relevance is not a popularity ranking. Unsupported rankings and unconfirmed prices remain unavailable.

## Preview validation and limits

On the exact 0.4.3 APK bytes, **90 JVM tests across 16 suites and 22 distinct focused Android emulator cases passed**. The online Return case covered background pause, chapter jump and actual position restoration. Library mixed-status and complete batch download/queue-drain assertions passed. Saved-copy removal through the broader UI test remains **unverified** because a later selector focused the search field instead of opening the card; that complete case is retained as failed. These are focused runs across recorded test APKs, not one passing full Android suite.

Runtime checks used an API 35 x86_64 emulator. Physical-phone install/update, ARM execution, API 26/36 runtime, full TalkBack, long-session battery and sustained 120fps remain unverified. Native image-reader recreation restores chapter/logical page; the inherited webtoon reader may reset the pixel offset within one tall image. Single-image retry preserves its live viewport. See the release notes for the tested scope.

## Repository and credits

This public repository contains release downloads, documentation, notices and authored sample screenshots. The application implementation and detailed QA archive remain private. GitHub's automatic **Source code** archives for these release tags contain this public repository; they **do not contain the private implementation used to build the APK** and are not a matching buildable 0.4.3 source release.

Arkhive is based on [Mihon](https://github.com/mihonapp/mihon). Apache-2.0/Mihon, Readium BSD and Noto SIL OFL notices and upstream credits are retained in [NOTICE.md](NOTICE.md), [LICENSE](LICENSE) and [licenses](licenses).

## Sample screenshots

These six existing screenshots show original, self-authored synthetic samples from the **0.4.0 preview**. They illustrate the app and do not document every 0.4.3 change.

| Library | Local chapters | Manga chapters |
| --- | --- | --- |
| ![Library](docs/screenshots/library.png) | ![Local chapters](docs/screenshots/local-chapters.png) | ![Manga chapters](docs/screenshots/manga-chapters.png) |

| Manga reader | Webtoon reader | Novel reader |
| --- | --- | --- |
| ![Manga reader](docs/screenshots/manga-reader.png) | ![Webtoon reader](docs/screenshots/webtoon-reader.png) | ![Novel reader](docs/screenshots/novel-reader.png) |
