# Arkhive 0.4.20 — unpublished local validation candidate

This candidate extends the isolated novel-source compatibility and explicit origin review from the 0.4.19 local candidate. When a user reviews another origin, the review now shows existing approvals selected by default; the user can deliberately remove one. A cover error has a **Retry cover** action that fetches with the current source grants without refreshing the chapter list or losing its page, search, or selection state. An origin that was already lost by an earlier replacement cannot be restored silently and may require one explicit review on the phone. This build approves no new origins by itself.

Long novel and manga/manhwa chapter lists are shown in bounded 100-row pages with search, page navigation, current/next-unread shortcuts, and direct page jumps. Manga missing-chapter separators retain the real global gaps at list edges and between pages. Novel chapter refreshes remain atomic; the adapter and repository retain explicit row and serialized-metadata limits. Viewer mode, reading progress, bookmarks, widgets, and saved chapter data are preserved.

The source-runtime work also includes standards-based `URL` and `URLSearchParams` APIs for the isolated LNReader runtime, structured filter keys ending in `[]`, large but bounded catalog/chapter rows, and explicit review of additional public HTTPS origins for catalog, chapter, cover, and manually queued requests. Origin review still does not approve local/private hosts, bypass public-DNS checks, or grant an origin without the user selecting **Trust**. Smart offline retains its earlier error presentation and is not covered by the new recovery action.

## Verification

- The full app JVM suite passes **151 tests**, with zero failures, errors, or skips. Android-test Kotlin sources compile successfully; instrumentation was not run.
- The host JavaScript suite covers the URL APIs, bare-isolate behavior, bridge operations, and the pinned official Galaxy Novels parser against controlled synthetic responses. These fixtures make no live website requests and do not establish that an extension works on a phone.
- Host SQLite checks confirmed the additive queue migration preserves existing queue state and reading data. The migration was not executed on an Android device.
- A local universal APK is built from this commit and signed with the existing Arkhive certificate. Physical-device behavior, screen rotation, live source endpoints, and actual downloaded-extension behavior have not been verified.

## Candidate metadata

Version name `0.4.20`, version code `27`; the commit-based debug suffix is expected to be `-30`. Package: `dev.francisgiann.readingapp.dev`. The final APK hash, size, and signer fingerprint are recorded with the local build receipt. This candidate has not been published or uploaded as a GitHub release. The public 0.4.19 download links remain unchanged.

This is a local universal preview debug build, not a production Play Store release. Installing it does not change the approval status of any origin.
