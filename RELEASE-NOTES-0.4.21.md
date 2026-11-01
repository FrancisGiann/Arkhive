# Arkhive 0.4.21 — unpublished local validation candidate

Chapter lists now use a compact previous/page/next strip shared by novel details and manga details. The page control opens the existing search and jump dialog, including in a no-match state; first and last pages remain available through the page field. Current-chapter and next-unread shortcuts, bounded 100-row slices, search, selection, downloads, reading progress, bookmarks, reader mode and widgets remain available.

When a novel cover is blocked by an unapproved origin, the detail screen shows the exact normalized HTTPS origin and its thumbnail purpose beside one explicit **Add thumbnail origin** action. That tap adds only the displayed origin to the unchanged installed bundle's existing grants, then retries the cover. Redirected thumbnail origins use the same exact-origin action. Stale grants, changed bundles, tampered bundle files, unsafe URLs and the origin limit are rejected. Catalog, reader, download and other source-origin review flows keep their existing review path; this action does not approve a new bundle or any origin automatically.

## Verification

- The full app JVM suite passes **155 tests**, with zero failures, errors or skips; this includes four manager regressions for additive persistence, blocked redirects, stale or tampered approval snapshots and the origin cap.
- Android-test Kotlin sources compile successfully. Instrumentation was not run because no device or emulator is attached, so the 320dp/1.3x rendering and the on-device tap-and-retry flow remain unverified.
- The controlled UI fixture exercises a synthetic thumbnail origin and redirect only; it makes no live source requests.

## Candidate metadata

Version name `0.4.21`, version code `28`; the commit-based debug suffix is expected to be `-31`. Package: `dev.francisgiann.readingapp.dev`. This candidate remains unpublished and is not an external release.

This is a local preview debug build, not a production Play Store release. It adds no origin approval by itself.
