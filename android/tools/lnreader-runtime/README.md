# LNReader helper bundle

This directory builds Arkhive's pinned, self-authored CommonJS helper layer. It does not contain or execute any community extension. Runtime package versions are pinned in `package.json` and `package-lock.json`; esbuild is build-only.

From this directory, reproduce the assets with:

```sh
npm ci --ignore-scripts
node build.mjs /path/to/Arkhive/android/app/src/main/assets/lnreader
```

Run the host conformance and bridge regression with `npm run test:url-search-params`. It compares URL APIs with the host implementation, checks behavior in a bare isolate without text codecs or shared-buffer globals, and exercises catalog, POST search, detail, and chapter requests through the shipped bootstrap and helper assets.

The compatibility surface is limited to the imports explicitly mapped in `bootstrap.js`: Cheerio `load`, `htmlparser2.Parser`, Day.js, URL encoding, the documented fetch/text helpers, bounded plugin storage, LNReader status/filter constants, `isUrlAbsolute`, and `defaultCover`. The isolate receives the pinned `whatwg-url` implementation of the standard `URL` and `URLSearchParams` data APIs for URL resolution and query/form encoding. Those constructors do not provide network access; broker requests still require reviewed public HTTPS origins and remain bounded in the native host. No browser storage, cookies, or extra native bridge APIs are granted. JavaScript executes only in the AndroidX JavaScriptSandbox.

Unsupported features include arbitrary `require` modules, browser/Node globals, plugin-defined JavaScript or CSS, WebView local/session storage capture, cookies or authenticated requests, custom native modules, login/challenge flows, and DRM/paywall bypasses. A source that needs one of these capabilities is shown as unavailable; no weaker execution fallback is used.

`THIRD-PARTY-NOTICES.txt` and `licenses/` are generated with the bundle. They include pinned runtime dependencies, LNReader contract licenses, and the upstream ISC notice for `boolbase`, whose npm archive omits its license file.
