# Arkhive - Manga & Novel Reader

An Android reader for manga, manhwa, manhua, webtoons and local EPUB, PDF or novel text. The cover-led library keeps reading, progress, bookmarks and optional offline manga downloads in focus. Buying/catalog lookup stays in a separate secondary tab.

## Download

[Download the latest release](https://github.com/FrancisGiann/Arkhive/releases/latest).

- **ARM64 APK:** for an ARM64 POCO and other compatible ARM64 devices. 60,004,016 bytes (57.22 MiB).
- **Universal APK:** includes ARM64, ARMv7, x86 and x86_64. 100,313,015 bytes (95.67 MiB).

Both APKs are the same **0.3.1-storyboard2-1**, version code **5**, and use the same package and signing certificate. Android 8.0/API26 or later is required. Install through Android's normal installer; update the existing installation to keep its data. This is a debug build; physical POCO installation and performance have not been tested.

## Actual app screenshots

These are genuine API35 Android emulator captures of the released build. All visible stories, covers and reading progress are self-authored synthetic test fixtures. They are not real personal reading history. The main APK does not seed this test library.

<table>
<tr><th>Library</th><th>Local chapters</th><th>Manga chapters</th></tr>
<tr>
<td><img src="docs/screenshots/library.png" width="250" alt="Arkhive library with original test stories"></td>
<td><img src="docs/screenshots/local-chapters.png" width="250" alt="Local chapter panel separated from the rounded purple header"></td>
<td><img src="docs/screenshots/manga-chapters.png" width="250" alt="Native manga source and chapter controls"></td>
</tr>
<tr><th>RTL manga</th><th>Vertical webtoon</th><th>Novel reader</th></tr>
<tr>
<td><img src="docs/screenshots/manga-reader.png" width="250" alt="Actual right-to-left manga reader"></td>
<td><img src="docs/screenshots/webtoon-reader.png" width="250" alt="Actual vertical webtoon reader"></td>
<td><img src="docs/screenshots/novel-reader.png" width="250" alt="Actual novel reader with serif typography and quiet controls"></td>
</tr>
</table>

## Offline and batch downloads

**Manga, manhwa, manhua and webtoons from compatible image sources:** open a title and tap the toolbar download arrow. Options include the next 1, 5, 10 or 25 unread chapters, Unread, and Bookmarked. Long-press chapter rows to select multiple chapters; Select all and Download queue the selection. Existing downloaded/queued chapters are skipped.

**Multiple series:** Library -> More -> Collections & manga filters (or View all) opens the native library. Select series there and use the download menu. Downloads -> Open download queue exposes pause/resume and queue ordering. Download permission, working source support, storage and network conditions still apply.

**Novels and PDF:** Library -> More -> Import EPUB / PDF / text copies whole owned/lawful books for offline reading. The file picker supports selecting multiple documents. This build has no remote text-novel chapter batch downloader; a catalog result does not provide licensed readable text.

Batch menu/selection and multiple-series paths above are inspected in the shipped implementation. The runtime offline test covers one original chapter: persisted paused queue, force-stop restoration and native completion, then restart with airplane mode/Wi-Fi off/no network; six loader pages are returned and visible page0 is actually decoded. Multi-title/large batches and external-source downloads have not been stress-tested.

## Reading and verification

- Native Mihon source/extension and image-reader host; library, progress and bookmarks.
- Local EPUB/text via Readium and PDF via an isolated Android renderer.
- Continuous adjustable auto-scroll, timed screen/page steps and manual taps. Explicit Start; touch, background and chapter boundaries pause playback.
- 40 JVM tests, 16 Android core tests and four staged offline checks passed on the matching universal build. Actual EPUB motion/pause and durable bookmarks were also verified. The ARM64 package retains all 2,263 payload entries byte-for-byte from that universal build; only other architecture libraries are omitted.

No physical device/ARM64 runtime, API26/API36 runtime matrix, measured120fps, full TalkBack or large interrupted-download matrix is claimed. Unsupported source rankings stay unavailable; live trend/catalog data retains its provider scope. Broken external sources are not universally guaranteed.

## Credits and licenses

This release repository contains binaries, screenshots and notices. Arkhive is based on [Mihon v0.21.1](https://github.com/mihonapp/mihon), upstream commit e9670d8a12ff4526d9ca09daa73280b8c42b9bb0. The original [Apache-2.0 license](LICENSE) and upstream credits are preserved. [Readium](https://github.com/readium/kotlin-toolkit) uses [BSD-3-Clause](licenses/READIUM-BSD-3-CLAUSE.txt); Noto fonts use [SIL OFL](licenses/NOTO-OFL.txt). See [notices](NOTICE.md).
