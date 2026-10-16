# Arkhive - Manga & Novel Reader

A cover-led Android reader for manga, manhwa, manhua, online novels, and local EPUB/PDF/text. Arkhive retains Mihon's native image reader and source host, with Readium for reflowable books.

**Current preview: 0.4.4.** This is a universal debug APK, not a production release.

[Download Arkhive-0.4.4.apk](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.4/Arkhive-0.4.4.apk) · [Release notes](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.4) · [SHA-256 file](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.4/Arkhive-0.4.4.sha256)

## Install or update

1. Use Android 8 / API 26 or later. Download the APK above and compare its SHA-256 with the attached checksum.
2. Open the APK on Android. If prompted, allow installation from the browser or file manager used for this download.
3. To update the existing Arkhive preview, install over it. The package and signing certificate are unchanged; keep the existing installation to retain its data.

Internal version: **0.4.4-1**, version code **11**. Package: `dev.francisgiann.readingapp.dev`. The universal APK includes ARM64, ARMv7, x86 and x86_64 libraries. Size: **103,317,859 bytes**.

SHA-256:

~~~text
d288d2a30d4043868adf5004478ff906a240dd06c613578340753975e3870548
~~~

The previous [0.4.3 prerelease](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.3) remains available. Check each release's notes before changing versions.

## Reading

- **Manga and webtoons:** RTL/LTR pages, vertical strips, native fullscreen controls, bounded tall-image rendering and single-image retry.
- **Novels and EPUBs:** saved Pages and continuous/vertical-scroll layouts, manual swipe/tap navigation, bookmarks and reading progress. A quiet bottom percentage remains visible in both modes when controls are hidden; online text reports chapter progress, while EPUB uses its publication locator.
- **Auto-read:** adjustable continuous scrolling and timed discrete steps. Interaction, backgrounding and chapter boundaries pause reading; manual advancement remains available.
- **Local imports:** EPUB, PDF and text, with import progress, exact-duplicate recognition and saved bookmarks/progress.
- **Library:** All / Novels / Manga tabs, persistent clearable filters, shelves/status, per-story reading profiles, chapter inbox, reviewed source migration, local metadata backup/restore, hidden-library reveal, static Reading Rooms and optional local reading statistics. Buying stays in a separate tab.

## New in 0.4.4

- **Theme consistency:** selected palettes propagate through app surfaces and reader controls, with Light/Dark/System updates and contrast fixes. Per-title reading paper remains separate.
- **Clearer actions:** outlined Settings shortcuts and surfaced Series tools rows.
- **Add to shelf:** shared additive selection preserves existing memberships and reading status; new shelves stay drafts until Save.
- **Title metadata:** synopsis and tags use available source/import metadata; manga detail titles have bounded responsive typography.
- **Reader center taps:** EPUB and online-novel controls toggle without repaginating or moving the passage in Pages/Continuous layouts.

The 0.4.3 adjacent-chapter preload, contextual Return, live typography preview and offline-status indicators remain included.

## Online sources and offline reading

Wikisource is the built-in online novel source. Native Mihon extension compatibility is retained; external sources can change or fail independently. Authored API 1.4/1.6 trust fixtures do not establish that every external extension or live source works. Use sources and content you are entitled to access; there is no DRM or paywall bypass.

Online reading needs an available source and network connection unless the chapter has been saved. Temporary preload cache is **not** a saved offline chapter. Optional downloads and Smart offline can save eligible chapters, with a shared budget and queue; Smart offline is opt-in for the next 5/10 unread chapters on Wi-Fi. Local imports and completed saved chapters can be read offline. Queued, paused or failed work does not mean a chapter is available offline.

Discovery uses real Open Library catalog matches and supported source trend windows with provenance. Catalog relevance is not a popularity ranking. Unsupported rankings and unconfirmed prices remain unavailable.

## Preview validation and limits

**92 JVM tests across 16 suites passed** before the final visual-only 1 dp shortcut outline; the final APK compiled that adjustment successfully. Focused API 35 x86_64 emulator runs covered EPUB/online-novel passage and locator preservation, narrow width and enlarged text, metadata, library/settings persistence, and rendered default/Catppuccin Light/Dark/System examples. Final APK manual checks covered Tools, shelf draft/cancel/save/reopen/cleanup and the outlined shortcuts. These are focused checks, not one passing full Android suite or exhaustive coverage of every palette and surface.

A synthetic long-title check at approximately 274 dp width and 130% font showed bounded text and an accessible full title, but a long uppercase word still split awkwardly across lines. This wrapping defect needs a later patch; 0.4.4 assets are unchanged. The broader saved-copy removal UI case was not reverified here; see the 0.4.3 release notes for its earlier failed selector case.

Runtime checks used an API 35 x86_64 emulator. Physical-phone install/update, ARM execution, API 26/36 runtime, full TalkBack, long-session battery and sustained 120fps remain unverified. Native image-reader recreation restores chapter/logical page; the inherited webtoon reader may reset the pixel offset within one tall image. Single-image retry preserves its live viewport. See the release notes for the tested scope.

## Repository and credits

This public repository contains release downloads, documentation, notices and authored sample screenshots. The application implementation and detailed QA archive remain private. GitHub's automatic **Source code** archives for these release tags contain this public repository; they **do not contain the private implementation used to build the APK** and are not a matching buildable 0.4.4 source release.

Arkhive is based on [Mihon](https://github.com/mihonapp/mihon). Apache-2.0/Mihon, Readium BSD and Noto SIL OFL notices and upstream credits are retained in [NOTICE.md](NOTICE.md), [LICENSE](LICENSE) and [licenses](licenses).

## Sample screenshots

These six existing screenshots show original, self-authored synthetic samples from the **0.4.0 preview**. They illustrate the app and do not document every 0.4.4 change.

| Library | Local chapters | Manga chapters |
| --- | --- | --- |
| ![Library](docs/screenshots/library.png) | ![Local chapters](docs/screenshots/local-chapters.png) | ![Manga chapters](docs/screenshots/manga-chapters.png) |

| Manga reader | Webtoon reader | Novel reader |
| --- | --- | --- |
| ![Manga reader](docs/screenshots/manga-reader.png) | ![Webtoon reader](docs/screenshots/webtoon-reader.png) | ![Novel reader](docs/screenshots/novel-reader.png) |

