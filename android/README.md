# Arkhive Android source — 0.4.8

This is the implementation snapshot corresponding to the 0.4.8 preview APK. It is a modified Mihon v0.21.1 project, upstream commit `e9670d8a12ff4526d9ca09daa73280b8c42b9bb0`. The upstream README is retained as [UPSTREAM-README.md](UPSTREAM-README.md), with its Apache-2.0 license in [LICENSE](LICENSE). Readium, Noto, LNReader helper and other bundled notices remain in `licenses/` and `app/src/main/assets/lnreader/`.

## Build

Use JDK 21 with `javac`, Android SDK platform 37.2, NDK 29.0.14206865 and build tools 36.0.0 or newer. Dependencies must be available through the configured repositories or a previously populated Gradle cache. Set `JAVA_HOME` and `READINGAPP_SDK_ROOT` (or `ANDROID_HOME`), then run from this directory:

```sh
nice -n 10 scripts/build-private.sh --no-daemon --max-workers=1 --no-parallel \
  -Dorg.gradle.parallel=false -Dorg.gradle.tooling.parallel=false \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=640m -XX:ReservedCodeCacheSize=192m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug
```

The helper retains its historical filename and sets local-distribution flags with upstream telemetry/updater endpoints disabled. Stop an emulator before compiling on memory-constrained hosts; run one build at a time. `READINGAPP_GRADLE_HOME` and `READINGAPP_ANDROID_USER_HOME` optionally select existing local caches. The helper generates ignored `local.properties`.

Signing keys and service credentials are not included. A local debug build uses the builder's debug certificate unless explicitly configured with their own signing setup. It will not update the distributed preview unless signed with the same certificate. Do not put keys or passwords into Git. The disabled upstream telemetry service configuration is intentionally omitted.

The distributed APK was built and verified before this source-publication commit. Generated Git/time metadata and the private retained signer prevent a promise of byte-for-byte reproducibility; the published SHA-256 identifies the exact tested APK. Runtime implementation files match the reviewed snapshot. Tests include authored compatibility fixtures and do not promise every external source works.
