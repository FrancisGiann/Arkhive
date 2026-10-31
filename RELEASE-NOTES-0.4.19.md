# Arkhive 0.4.19 — unpublished local validation candidate

This candidate addresses the four catalog failures in the reviewed screenshots. It adds the standards-based `URL` and `URLSearchParams` APIs to the isolated LNReader runtime, accepts safe structured filter keys ending in `[]`, preserves valid catalog rows up to the existing resource budget, and lets users explicitly review an additional public HTTPS source origin when a catalog, chapter, or manually queued download requires it. Approval remains per exact origin; the broker still rejects local/private addresses and validates public DNS before connecting.

The reader and manual-download recovery actions reuse the existing origin review screen. Reader position and queue state are retained, and an origin error does not trigger automatic retries. The queue schema update adds a nullable field; it does not recreate or clear saved library, progress, bookmarks, or preferences. Smart offline continues to use its prior error presentation and is not covered by this recovery action.

## Verification

- The host Node regression passes 14 `URLSearchParams` semantic cases, 13 error cases, six `URL` resolution/serialization cases, URL/search-parameter identity and bidirectional mutation, and a bare-isolate check with no text codecs, shared buffers, `fetch`, `process`, or ambient `Buffer`. Temporary compatibility properties are absent again after helper initialization.
- The authored bridge fixture passes catalog, filtered POST, search POST, detail, and chapter requests through the packaged bootstrap and helpers. The pinned official Galaxy Novels source blob (`LNReader/lnreader-plugins`, blob `d06b6b4d71d0c3606875e7058e1556cab42f3f18`) also passes catalog, detail, and chapter parsing against controlled synthetic HTML/JSON responses. No source was installed into the app registry, and these tests make no live website requests.
- The full app JVM suite passes **141 tests** with zero failures, errors, or skips. Android instrumentation Kotlin sources, including the new compatibility and queue migration coverage, compile successfully.
- The host SQLite migration check confirms existing v1/v2 queue rows, state, error, source identity, progress, bookmarks, and saved flags survive the additive v3 migration. Instrumentation was compiled but not run.
- The universal local APK is a debug candidate signed with the existing Arkhive certificate. Android runtime, physical-phone rendering, live website endpoints, and actual downloaded-extension behavior have not been verified on a device.

## Candidate metadata

Version name `0.4.19`, version code `26`; the commit-based debug suffix is expected to be `-29` and will be checked against the built APK. Package: `dev.francisgiann.readingapp.dev`. The final APK size, SHA-256, and signing-certificate fingerprint are recorded after the local build. This candidate has not been published or uploaded as a GitHub release. The public 0.4.18 download links remain unchanged.

This is a local universal preview debug build, not a production Play Store release. The current-library/source links are unchanged. No newly encountered source origin is automatically trusted.
