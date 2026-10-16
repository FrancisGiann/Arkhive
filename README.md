# Arkhive - Manga & Novel Reader

A cover-led Android reader for manga, manhwa, manhua, online novels, and local EPUB/PDF/text. Arkhive retains Mihon's native image reader and source host, with Readium for reflowable books.

**Current preview: 0.4.6.** This is a universal debug APK, not a production release.

[Download Arkhive-0.4.6.apk](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.6/Arkhive-0.4.6.apk) · [Release notes](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.6) · [SHA-256 file](https://github.com/FrancisGiann/Arkhive/releases/download/v0.4.6/Arkhive-0.4.6.sha256)

## Install or update

1. Use Android 8 / API 26 or later. Download the APK above and compare its SHA-256 with the attached checksum.
2. Open the APK on Android. If prompted, allow installation from the browser or file manager used for this download.
3. To update the existing Arkhive preview, install over it. The package and signing certificate are unchanged; keep the existing installation to retain its data.

Internal version: **0.4.6-1**, version code **13**. Package: `dev.francisgiann.readingapp.dev`. The universal APK includes ARM64, ARMv7, x86 and x86_64 libraries. Size: **102,217,490 bytes**.

SHA-256:

~~~text
be77992724a61392308e7de69bbe8e12738e8f49bb630d713dccdc9ecafacd80
~~~

The previous [0.4.4 preview](https://github.com/FrancisGiann/Arkhive/releases/tag/v0.4.4) remains available with its original APK and checksum. Check each release's notes before changing versions.

## Reading

- **Manga and webtoons:** RTL/LTR pages, vertical strips, native fullscreen controls, bounded tall-image rendering and single-image retry.
- **Novels and EPUBs:** saved Pages and continuous/vertical-scroll layouts, manual swipe/tap navigation, bookmarks and reading progress. A quiet bottom percentage remains visible in both modes when controls are hidden; online text reports chapter progress, while EPUB uses its publication locator.
- **Auto-read:** adjustable continuous scrolling and timed discrete steps. Interaction, backgrounding and chapter boundaries pause reading; manual advancement remains available.
- **Local imports:** EPUB, PDF and text, with import progress, exact-duplicate recognition and saved bookmarks/progress.
- **Library:** All / Novels / Comics tabs, persistent clearable filters, shelves/status, per-story reading profiles, chapter inbox, reviewed source migration, local metadata backup/restore, hidden-library reveal, static Reading Rooms and optional local reading statistics. Buying stays in a separate tab.

## Changes

- Retains the approved full-width Discover panel with rounded top corners below the cover, with one explicit **Explore related titles** button. Related category/genre results retain context and Back navigation; synopsis/tags remain separate.
- Library labels use explicit Manga/Manhwa/Manhua metadata. Unknown or conflicting metadata is shown as Comics; source language or website does not determine nationality. The broad Library filter is now labelled Comics.
- Online novel details use a natural-case accessible title, compact metadata, responsive actions and a virtualized chapter list beneath a rounded panel.
- Accent foregrounds preserve readable colors or choose black/white for at least 4.5:1 contrast. Status-bar icons choose the higher-contrast color. Per-title reading-paper preferences are preserved.

## Verified and limits

**20 distinct focused JVM checks passed** across three scoped builds: discovery/classification5, Library tools5, reader status2, origin normalization4, foreground/status contrast4. Final Light/Dark novel runtime tests passed on API35 x86_64 (13.689s/13.396s), including a 119-character title at 130% font. Actual rendered Light purple/white title contrast measured5.41:1; Dark pastel/dark title9.23:1 and dark status icons were verified.

Manual UI checks verified Manhwa Library caption, rounded Discover panel, explicit button → contextual Manhwa/Fantasy results → Back, immediate palette changes, System following OS appearance, and selection persistence after force-stop/reopen. Temporary fixtures were cleaned up and the emulator stopped. These are scoped checks, not a full Android suite. The chapter controls fit the tested initial viewport; no actual swipe occurred in that runtime fixture.

Physical ARM devices, API26/36 runtime, full TalkBack, sustained frame rate and battery remain unverified. **Kol Novel's `invalid source filter: genre[]` remains reference-only and is not fixed in this release.** Installation does not guarantee compatibility with every community source.


[Full 0.4.6 release notes](docs/releases/0.4.6.md).

## Online sources and offline reading

Wikisource is the built-in online novel source. Native Mihon extension compatibility is retained; external sources can change or fail independently. Authored API 1.4/1.6 trust fixtures do not establish that every external extension or live source works. Use sources and content you are entitled to access; there is no DRM or paywall bypass.

Online reading needs an available source and network connection unless the chapter has been saved. Temporary preload cache is **not** a saved offline chapter. Optional downloads and Smart offline can save eligible chapters, with a shared budget and queue; Smart offline is opt-in for the next 5/10 unread chapters on Wi-Fi. Local imports and completed saved chapters can be read offline. Queued, paused or failed work does not mean a chapter is available offline.

Discovery uses real Open Library catalog matches and supported source trend windows with provenance. Catalog relevance is not a popularity ranking. Unsupported rankings and unconfirmed prices remain unavailable.

## Preview validation and limits

See the [0.4.6 verification and limits](docs/releases/0.4.6.md#verified-and-limits) above: 20 distinct focused JVM checks and final Light/Dark runtime checks passed. Physical devices and measured sustained performance remain unverified.

The frozen 0.4.5 origin workflow subsequently received manual fixture verification of HTTP rejection, Cancel without permission changes, exact observed-host Trust persistence and successful cover retry/cache bytes. Those observations span two authored sessions; they are not one passing automated end-to-end test. No real extension permissions were changed.

## Repository and credits

The implementation source for **0.4.6 is published under [android/](android/)**, including the Gradle project, authored fixtures/tests and required license notices. The v0.4.6 tag points to the corresponding implementation snapshot. See [build instructions](android/README.md). Signing material, local build caches, credentials, user data and private QA archives are excluded.

Earlier release tags through 0.4.4 predate source publication: their automatic **Source code** archives contain the then-public documentation repository, not a matching implementation source release. The APKs and checksums attached to those releases remain unchanged.

Arkhive is based on [Mihon](https://github.com/mihonapp/mihon). Apache-2.0/Mihon, Readium BSD and Noto SIL OFL notices and upstream credits are retained in [NOTICE.md](NOTICE.md), [LICENSE](LICENSE) and [licenses](licenses).

## Sample screenshots

These six existing screenshots show original, self-authored synthetic samples from the **0.4.0 preview**. They illustrate the app and do not document every current change.

| Library | Local chapters | Manga chapters |
| --- | --- | --- |
| ![Library](docs/screenshots/library.png) | ![Local chapters](docs/screenshots/local-chapters.png) | ![Manga chapters](docs/screenshots/manga-chapters.png) |

| Manga reader | Webtoon reader | Novel reader |
| --- | --- | --- |
| ![Manga reader](docs/screenshots/manga-reader.png) | ![Webtoon reader](docs/screenshots/webtoon-reader.png) | ![Novel reader](docs/screenshots/novel-reader.png) |


## Current screenshots

Final 0.4.6 authored online-novel fixture, 130% font:

| Light | Dark |
| --- | --- |
| ![Light](docs/screenshots/0.4.6/novel-light.png) | ![Dark](docs/screenshots/0.4.6/novel-dark.png) |
