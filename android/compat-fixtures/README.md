# Controlled source compatibility fixtures

These are self-authored test APKs and original synthetic reader content. No extension repository or third-party APK is fetched, installed, or executed by this harness. The APKs request no Android permissions and expose no Android components. All content and source implementations here are original fixture material under the enclosing project's Apache 2.0 license.

The official Mihon v0.21.1 loader and `source-api` are retained unchanged. Four `:compat-fixtures:extension` debug flavors exercise:

| Flavor | Metadata contract | Expected retained host behavior |
| --- | --- | --- |
| legacy | `versionName=1.4.1`, old `tachiyomi.extension.class` factory | Signature trust gate, then legacy `CatalogueSource` Rx-to-suspend bridge |
| modern | `versionName=1.6.1`, source factory | Signature trust gate, then direct suspend `Source` calls |
| unsupported | `versionName=1.5.1` | `UnsupportedLibVersion`, before source factory runs |
| malformed | `versionName=1.6.1`, empty source class | `Malformed`, after signature is deliberately trusted for this test |

Both implementations are **compiled against this pinned host**, with the API/runtime supplied using `compileOnly`. The legacy fixture exercises the old-shaped Rx interface through today's bridge. It does not prove binary compatibility with all historical Kotlin compiler versions, third-party dependencies, or real provider parsers.

The app also includes two explicitly labeled `ReadingApp Originals` sources: two paged manga titles and two vertical webtoon titles, each with three chapters and six pages. Android Canvas generates original geometric covers and panels; no recognizable anime character or copyrighted page is copied. Covers are 480×720; manga pages 720×1080; vertical pages 720×2160. The encoded PNG cache is capped at 4 MiB, image generation is serialized, and the offline OkHttp interceptor rejects every host except `fixtures.readingapp.invalid`. PNG decode allocations and real long-webtoon performance still need on-device profiling. Latest/trending feeds are unavailable. Intended reader modes are described honestly in title metadata.

## Run on a clean, isolated ReadingApp emulator

The parent build wires the four assembled fixture APKs into the instrumentation assets as `readingapp/compat/{legacy,modern,unsupported,malformed}.apk`. Debug-signing is for controlled local tests only. The instrumentation test refuses a target package without `readingapp` in its name and refuses to overwrite an existing fixture archive. It copies only these generated assets into the app's private extension archive folder, verifies they request no permissions and have signatures, exercises the retained loader by exact package name, and restores trust preferences/removes test archives afterward. It never asks the loader to enumerate and execute other installed extensions.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=mihon.readingapp.compat.ControlledExtensionRuntimeTest,mihon.readingapp.compat.OriginalDemoReaderContractTest
```

Missing assets fail the instrumentation run; they are not silently skipped. The harness includes no result file claiming a passed device run. Root build/run logs are the evidence.

## Scope and remaining proof

- JVM tests cover original catalog paths/search separation and retained legacy Rx bridge dispatch, partial-update flags, and parser-error propagation.
- Instrumentation covers real APK archive detection, signatures, explicit trust/untrust, API version rejection, malformed class metadata, runtime classloader binding, source factory, catalog/search/chapter/page contracts, and original PNG responses through `HttpSource.getImage`. A retained `HttpPageLoader` test checks actual ready-page streams for manga and webtoon, page-list persistence through `recycle`, and reopening from an isolated disk cache with source requests deliberately forbidden.
- It does **not** yet cover the private installer downgrade/signer-change rejection, source permission sandboxing (the unchanged host is not a sandbox), arbitrary real provider compatibility, WebView/anti-bot flows, actual rendered reader gestures, offline download persistence/removal, or 120 Hz frame timing.
- Any real-extension comparison requires the specific extension APK, its origin, version and signer to be reviewed/approved first. Acceptance should compare an approved working reference extension in stock Mihon v0.21.1 and this fork, pinned to the same version/content. Broken/offline providers, unauthorized sources and future extension changes cannot be promised.
