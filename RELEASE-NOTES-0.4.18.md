# Arkhive 0.4.18 — LNReader query parameter compatibility

The isolated LNReader source runtime now provides a pure-data `URLSearchParams` compatibility API. Sources that use URL query strings or `application/x-www-form-urlencoded` search bodies can construct and serialize parameters, preserve duplicate keys, and paginate without requiring browser globals. The compatibility layer does not add the browser `URL` constructor, cookies, storage, ambient network access, or native bridge access to extension code.

## Verification

- The dependency-free Node regression compares 14 query-parameter behavior cases and 13 error cases with native Node `URLSearchParams`, including UTF-8/form encoding, malformed percent/UTF-8 input, repeated keys, mutation, sorting, and live iteration. A separate WHATWG-expected assertion covers malformed percent bytes mixed with raw Unicode, where Node 24's native result differs.
- An authored HTTP-bridge fixture exercises popular catalog, filtered POST, search POST, detail, and chapter requests through the shipped bootstrap and helpers. The pinned official LNReader `novelsOnline` extension was also transpiled locally and run against synthetic transport responses for these same stages; no live website traffic or extension installation was involved.
- The AndroidX sandbox instrumentation test covers the same request path and is compiled with the test APK; it cannot be executed here because this host has no connected Android device, configured AVD, or `/dev/kvm`.
- All app JVM unit tests pass (133 tests). The universal local APK is signed with the existing Arkhive debug certificate; its version and checksum are recorded with the deliverable.
- No exact user-installed community source was executed on an Android device. The compatibility fix addresses the observed missing `URLSearchParams` capability and does not claim that every source works; sources requiring the separate browser `URL` API remain unsupported.

This is a local preview build, not a published GitHub release or production Play Store build.
