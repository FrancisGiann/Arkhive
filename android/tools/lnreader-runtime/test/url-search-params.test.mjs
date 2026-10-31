import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const androidRoot = resolve(testDirectory, '../../..');
const assetRoot = resolve(androidRoot, 'app/src/main/assets/lnreader');
const bootstrapSource = await fs.readFile(resolve(androidRoot, 'tools/lnreader-runtime/bootstrap.js'), 'utf8');
const bootstrapAsset = await fs.readFile(resolve(assetRoot, 'bootstrap.js'), 'utf8');
assert.equal(bootstrapAsset, bootstrapSource, 'the packaged bootstrap must match its reviewed source');
const helpers = await fs.readFile(resolve(assetRoot, 'helpers.js'));

function bareContext() {
  const context = vm.createContext({});
  // AndroidX's isolate has no shared buffers, resizable/growable buffer flags, or text codecs.
  vm.runInContext('delete globalThis.SharedArrayBuffer; delete ArrayBuffer.prototype.resizable; delete ArrayBuffer.prototype.growable;', context);
  return context;
}

const context = bareContext();
const globalsBefore = vm.runInContext(`JSON.stringify({
  textEncoder: typeof TextEncoder, textDecoder: typeof TextDecoder,
  shared: Object.getOwnPropertyDescriptor(globalThis, 'SharedArrayBuffer') || null,
  resizable: Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'resizable') || null,
  growable: Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'growable') || null
})`, context);
vm.runInContext(helpers.toString('utf8'), context);
const globalsAfter = vm.runInContext(`JSON.stringify({
  textEncoder: typeof TextEncoder, textDecoder: typeof TextDecoder,
  shared: Object.getOwnPropertyDescriptor(globalThis, 'SharedArrayBuffer') || null,
  resizable: Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'resizable') || null,
  growable: Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'growable') || null,
  cleanup: typeof __arkhiveWhatwgInitCleanup,
  url: typeof __arkhiveLnReaderHelpers.URL,
  params: typeof __arkhiveLnReaderHelpers.URLSearchParams
})`, context);
const normalizeDescriptors = (snapshot) => {
  const value = JSON.parse(snapshot);
  for (const key of ['shared', 'resizable', 'growable']) {
    const descriptor = value[key];
    value[key] = descriptor && {
      configurable: descriptor.configurable,
      enumerable: descriptor.enumerable,
      writable: descriptor.writable,
      getter: descriptor.get ? 'function' : null,
      setter: descriptor.set ? 'function' : null,
      value: descriptor.value === undefined ? 'undefined' : descriptor.value,
    };
  }
  return value;
};
const before = normalizeDescriptors(globalsBefore);
const after = normalizeDescriptors(globalsAfter);
delete after.cleanup;
delete after.url;
delete after.params;
assert.deepEqual(after, before, 'temporary platform compatibility properties must be restored exactly');
assert.equal(JSON.parse(globalsAfter).cleanup, 'undefined', 'temporary initializer must be removed');

const nativeContext = vm.createContext({ URL, URLSearchParams });
const paramsCases = [
  `(() => { const p = new URLSearchParams('?a=1&a=2&&space=hello+world&raw=%zz&bad=%E2%28%A1&overlong=%F0%80%80%80&surrogate=%ED%A0%80&truncated=%E2%82&tail=%F0%9F%8C%99'); return [p.toString(), Array.from(p), p.size, p.get('missing')]; })()`,
  `(() => { const p = new URLSearchParams({ nil: null, list: ['one', 'two'], label: '雪 ☃' }); return [p.toString(), p.get('nil'), p.get('list'), p.size]; })()`,
  `(() => { const p = new URLSearchParams([['x', 1], ['x', 2], ['space', 'a b']]); return [p.toString(), p.getAll('x'), p.has('x', '2')]; })()`,
  `(() => { const p = new URLSearchParams('x=1&x=2&x=2&b=old&a=first&a=second'); p.delete('x', '2'); p.set('b', 'new value'); p.append('x', '3'); const before = [p.size, p.get('x'), p.getAll('x'), p.has('x'), p.has('x', '2')]; p.sort(); return [before, p.toString(), Array.from(p)]; })()`,
  `(() => { const p = new URLSearchParams('a=1&b=2'); const it = p.entries(); const first = it.next().value; p.append('c', '3'); return [first, Array.from(it)]; })()`,
  `(() => { const p = new URLSearchParams('a=1&b=2&c=3'); const it = p.entries(); const first = it.next().value; p.delete('a'); return [first, Array.from(it)]; })()`,
  `(() => { const p = new URLSearchParams('a=1&b=2'); const seen = []; p.forEach((value, key, receiver) => { seen.push([key, value, receiver === p]); if (key === 'a') p.append('c', '3'); }); return seen; })()`,
  `(() => { const p = new URLSearchParams('a=1&b=2&c=3'); const seen = []; p.forEach((value, key) => { seen.push(key); if (key === 'a') p.delete('b'); }); return seen; })()`,
  `(() => { const values = ['~', '!', "'", '(', ')', '*', '雪 ☃', '\\uD800', '\\uD83C\\uDF19']; return values.map(value => [value, new URLSearchParams([['v', value]]).toString()]); })()`,
  `(() => { const p = new URLSearchParams('a=1&b=2'); return [Object.prototype.toString.call(p), [...p.keys()], [...p.values()], [...p], [...p][Symbol.iterator]().next()]; })()`,
  `(() => { const p = new URLSearchParams('x=1&x=2'); p.delete('x', '1'); const q = new URLSearchParams('z=1&z=2'); q.delete('z'); return [p.toString(), q.size]; })()`,
  `(() => { const p = new URLSearchParams('a=1&a=undefined'); const q = new URLSearchParams('a=1&a=undefined'); p.delete('a', undefined); return [p.toString(), q.has('a', undefined), Object.keys(q), q.toString()]; })()`,
  `(() => { const proto = { inherited: 'skip' }; const record = Object.assign(Object.create(proto), { own: 'keep' }); return new URLSearchParams(record).toString(); })()`,
  `(() => { const p = new URLSearchParams(123); const q = new URLSearchParams(false); return [p.toString(), q.toString()]; })()`,
];

function prepareCompatibilityApi(target) {
  vm.runInContext(`globalThis.URL = __arkhiveLnReaderHelpers.URL;
    globalThis.URLSearchParams = __arkhiveLnReaderHelpers.URLSearchParams;`, target);
}
prepareCompatibilityApi(context);
for (const expression of paramsCases) {
  const actual = vm.runInContext(`JSON.stringify(${expression})`, context);
  const expected = vm.runInContext(`JSON.stringify(${expression})`, nativeContext);
  assert.equal(actual, expected, `URLSearchParams mismatch: ${expression}`);
}

const errorCases = [
  `new URLSearchParams([['only one']])`,
  `new URLSearchParams([['too', 'many', 'items']])`,
  `new URLSearchParams(['ab'])`,
  `new URLSearchParams({[Symbol('query')]: 'value'})`,
  `new URLSearchParams(Symbol('query'))`,
  `URLSearchParams.prototype.get.call({}, 'x')`,
  `new URLSearchParams(new String('a=1'))`,
  `new URLSearchParams().append('name')`,
  `new URLSearchParams().set('name')`,
  `new URLSearchParams().get()`,
  `new URLSearchParams().getAll()`,
  `new URLSearchParams().has()`,
  `new URLSearchParams().delete()`,
];
for (const expression of errorCases) {
  const errorName = target => {
    try { vm.runInContext(expression, target); return null; }
    catch (error) { return error.name; }
  };
  assert.equal(errorName(context), errorName(nativeContext), `URLSearchParams error mismatch: ${expression}`);
}
// WHATWG decodes bytes after UTF-8 encoding the full input; Node 24 has a bug for this mixed case.
assert.equal(vm.runInContext(`new URLSearchParams('a=%FF雪').get('a')`, context), '\uFFFD雪');

const urlCases = [
  `new URL('../next?x=hello world#top', 'https://example.test/a/b/').href`,
  `new URL('https://bücher.example:443/a').href`,
  `new URL('https://user:pass@[2001:db8::1]:443/a').href`,
  `new URL('?new=1', 'https://example.test/path?old=1').href`,
  `new URL('https://example.test:80/').port`,
  `new URL('https://example.test:443/').port`,
];
for (const expression of urlCases) {
  assert.equal(vm.runInContext(expression, context), vm.runInContext(expression, nativeContext), `URL mismatch: ${expression}`);
}
const identityExpression = `(() => {
    const url = new URL('/path?x=1&x=2', 'https://example.test/base');
    const same = url.searchParams instanceof URLSearchParams;
    url.searchParams.append('space', 'a b');
    const appended = url.search;
    url.search = '?next=3';
    return [same, appended, url.searchParams.get('next'), url.href];
  })()`;
const compatIdentity = vm.runInContext(identityExpression, context);
const nativeIdentity = vm.runInContext(identityExpression, nativeContext);
assert.deepEqual(JSON.parse(JSON.stringify(compatIdentity)), [true, '?x=1&x=2&space=a+b', '3', 'https://example.test/path?next=3']);
assert.equal(JSON.stringify(compatIdentity), JSON.stringify(nativeIdentity));
const invalidUrlName = target => { try { vm.runInContext(`new URL('https://[bad-ipv6/')`, target); return null; } catch (error) { return error.name; } };
assert.equal(invalidUrlName(context), invalidUrlName(nativeContext));

const plugin = Buffer.from(`
const { fetchApi } = require('@libs/fetch');
module.exports.default = {
  id: 'fixture-source', name: 'Authored fixture', site: 'https://fixture.invalid/', version: '1.0',
  async popularNovels(pageNo, options) {
    const form = new URLSearchParams();
    const filters = options.filters;
    for (const key of Object.keys(filters)) {
      const value = filters[key].value;
      if (Array.isArray(value)) for (const item of value) form.append('include[' + key + '][]', item);
      else if (value) form.append('include[' + key + '][]', value);
    }
    if (form.toString()) { form.append('search', '1'); return this.detailedSearch(form); }
    const response = await fetchApi(new URL('/top-novel/' + pageNo, this.site).href);
    return {data: await response.json(), isolated: [typeof URL, typeof URLSearchParams, typeof fetch]};
  },
  async searchNovels(term) {
    const form = new URLSearchParams();
    form.append('keyword', term); form.append('search', '1');
    return this.detailedSearch(form);
  },
  async detailedSearch(form) {
    const response = await fetchApi(new URL('/detailed-search', this.site).href, {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: form.toString()
    });
    return response.json();
  },
  async parseNovel(path) {
    const url = new URL(path, this.site);
    const params = new URLSearchParams({path: url.pathname});
    return (await fetchApi(new URL('/detail?' + params, this.site).href)).json();
  },
  async parseChapter(path) {
    const url = new URL(path, this.site);
    return (await fetchApi(new URL('/chapter?path=' + encodeURIComponent(url.pathname), this.site).href)).text();
  }
};
`);
const pluginMeta = { pluginId: 'fixture-source', pluginName: 'Authored fixture', pluginSite: 'https://fixture.invalid/', pluginVersion: '1.0' };
const requestsSeen = [];
const responses = {
  catalog: JSON.stringify({stage: 'catalog', title: '雪 月'}),
  search: JSON.stringify({stage: 'search', title: 'Blue Moon'}),
  detail: JSON.stringify({stage: 'detail', title: 'Fixture Novel'}),
  chapter: '<p>Fixture chapter text.</p>',
};

function assertBootstrappedBareRuntime(target) {
  const globals = JSON.parse(vm.runInContext(`JSON.stringify([
    typeof URL, typeof URLSearchParams,
    new URL('https://fixture.invalid/').searchParams instanceof URLSearchParams,
    typeof fetch, typeof process, typeof Buffer, typeof TextEncoder, typeof TextDecoder,
    typeof SharedArrayBuffer, typeof __arkhiveWhatwgInitCleanup,
    Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'resizable') || null,
    Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'growable') || null
  ])`, target));
  assert.deepEqual(globals, ['function', 'function', true, 'undefined', 'undefined', 'undefined', 'undefined', 'undefined', 'undefined', 'undefined', null, null]);
}

async function execute(operation, args, {
  pluginSource = plugin,
  metadata = pluginMeta,
  allowedOrigin = 'https://fixture.invalid',
  responseForRequest = (_request, url, route) => responses[route === 'detailed-search' ? 'search' : route],
} = {}) {
  const namedData = new Map([
    ['lr-helper-bundle', helpers],
    ['lr-plugin-bundle', pluginSource],
    ['lr-operation-input', Buffer.from(JSON.stringify({ ...metadata, operation, args, storage: {}, webStorageUtilized: false }))],
  ]);
  let responseCounter = 0;
  const runtimeContext = bareContext();
  runtimeContext.android = {
    async consumeNamedDataAsArrayBuffer(name) {
      const bytes = namedData.get(name);
      if (!bytes) throw new Error(`missing named data: ${name}`);
      return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    },
  };
  assert.equal(await vm.runInContext(bootstrapAsset, runtimeContext), 'ready');
  assertBootstrappedBareRuntime(runtimeContext);
  for (let pollIndex = 0; pollIndex < 1000; pollIndex++) {
    const report = JSON.parse(vm.runInContext('globalThis.__lnReaderPoll()', runtimeContext));
    for (const request of report.requests) {
      requestsSeen.push(request);
      const url = new globalThis.URL(request.url);
      assert.equal(url.origin, allowedOrigin);
      const route = url.pathname.startsWith('/top-novel/') ? 'catalog' : url.pathname.slice(1);
      const responseBody = await responseForRequest(request, url, route);
      assert.notEqual(responseBody, undefined, `unexpected bridge request ${request.url}`);
      const name = `lr-response-${request.id}-${++responseCounter}`;
      const bytes = Buffer.from(responseBody);
      namedData.set(name, bytes);
      const metadata = {status: 200, statusText: 'OK', headers: {'content-type': url.pathname === '/chapter' ? 'text/html' : 'application/json'}, url: request.url, requestedUrl: request.url};
      await vm.runInContext(`globalThis.__lnReaderDeliver(${request.id}, ${JSON.stringify(JSON.stringify(metadata))}, ${JSON.stringify(name)})`, runtimeContext);
    }
    if (report.done) {
      assert.equal(report.error, null, `${operation}: ${report.error}`);
      assertBootstrappedBareRuntime(runtimeContext);
      return report.result;
    }
    await new Promise(resolve => setTimeout(resolve, 1));
  }
  throw new Error(`${operation} did not finish after 1000 bridge polls`);
}

const popular = await execute('popularNovels', [3, {filters: {}}]);
assert.deepEqual(popular, {data: {stage: 'catalog', title: '雪 月'}, isolated: ['function', 'function', 'undefined']});
const filtered = await execute('popularNovels', [1, {filters: {genre: {value: ['2', '9']}}}]);
assert.deepEqual(filtered, {stage: 'search', title: 'Blue Moon'});
const search = await execute('searchNovels', ['blue moon']);
assert.deepEqual(search, {stage: 'search', title: 'Blue Moon'});
assert.equal((await execute('parseNovel', ['/story/月'])).stage, 'detail');
assert.equal(await execute('parseChapter', ['/chapter/月']), responses.chapter);
assert.deepEqual(requestsSeen.map(({url, method, body}) => [url, method, body]), [
  ['https://fixture.invalid/top-novel/3', 'GET', null],
  ['https://fixture.invalid/detailed-search', 'POST', 'include%5Bgenre%5D%5B%5D=2&include%5Bgenre%5D%5B%5D=9&search=1'],
  ['https://fixture.invalid/detailed-search', 'POST', 'keyword=blue+moon&search=1'],
  ['https://fixture.invalid/detail?path=%2Fstory%2F%25E6%259C%2588', 'GET', null],
  ['https://fixture.invalid/chapter?path=%2Fchapter%2F%25E6%259C%2588', 'GET', null],
]);
assert.equal(requestsSeen[1].headers['content-type'], 'application/x-www-form-urlencoded');

// Optional offline verification of the pinned, official Galaxy Novels extension. Supply the
// already-reviewed source file; this runs it only against synthetic HTML/JSON over the host bridge.
const officialGalaxyPath = process.env.ARKHIVE_GALAXYNOVELS_SOURCE;
if (officialGalaxyPath) {
  const officialGalaxy = await fs.readFile(officialGalaxyPath);
  const gitBlobHash = createHash('sha1').update(`blob ${officialGalaxy.length}\0`).update(officialGalaxy).digest('hex');
  assert.equal(gitBlobHash, 'd06b6b4d71d0c3606875e7058e1556cab42f3f18', 'official Galaxy source must match the reviewed upstream blob');
  const galaxyMeta = {pluginId: 'galaxynovels', pluginName: 'Galaxy Novels', pluginSite: 'https://galaxynovels.com/', pluginVersion: '1.1.1'};
  const galaxyOrigin = 'https://galaxynovels.com';
  const galaxyResponse = (_request, url) => {
    if (url.pathname.startsWith('/novels/')) return `<article class="wor-novel-card"><a class="wor-novel-card__cover" href="/novel-one/"></a><img class="wor-cover-img" data-src="/covers/one.jpg"><h3><a>Fixture Galaxy Novel</a></h3></article>`;
    if (url.pathname === '/novel-one/') return `<h1>Fixture Galaxy Novel</h1><img class="wor-cover-img" src="/covers/one.jpg"><p class="wor-single-hero__meta-text"><span>Fixture Author</span></p><div class="wor-single-summary__text">Public test summary</div><div data-wor-chapters-container data-index-url="/api/chapters.json"></div>`;
    if (url.pathname === '/api/chapters.json') return JSON.stringify({chapters: [{label: 'Chapter 7', title: 'Opening', url: '/novel-one/chapter-7/', position: 7, date_iso: '2026-10-29T00:00:00Z'}]});
    if (url.pathname === '/wp-json/wor-reader-app/v1/chapters/7') return JSON.stringify({data: {content_html: '<p>Controlled Galaxy chapter</p>'}});
    throw new Error(`Unexpected Galaxy fixture request: ${url.href}`);
  };
  const galaxyCatalog = await execute('popularNovels', [1, {showLatestNovels: false, filters: {sort: {value: 'popular'}, period: {value: 'month'}}}], {
    pluginSource: officialGalaxy, metadata: galaxyMeta, allowedOrigin: galaxyOrigin, responseForRequest: galaxyResponse,
  });
  assert.equal(galaxyCatalog[0].path, '/novel-one/', 'Galaxy resolves catalog hrefs against its source URL');
  const galaxyDetail = await execute('parseNovel', ['/novel-one/'], {
    pluginSource: officialGalaxy, metadata: galaxyMeta, allowedOrigin: galaxyOrigin, responseForRequest: galaxyResponse,
  });
  assert.equal(galaxyDetail.chapters[0].path, '/novel-one/chapter-7/');
  const galaxyChapter = await execute('parseChapter', ['/novel-one/chapter-7/'], {
    pluginSource: officialGalaxy, metadata: galaxyMeta, allowedOrigin: galaxyOrigin, responseForRequest: galaxyResponse,
  });
  assert.equal(galaxyChapter, '<p>Controlled Galaxy chapter</p>');
  console.log('PASS pinned official Galaxy Novels catalog/detail/chapter parsing through shipped bootstrap and controlled HTTP fixtures');
}

console.log(`PASS ${paramsCases.length} URLSearchParams semantics and ${errorCases.length} error cases against native Node`);
console.log(`PASS ${urlCases.length} URL resolution/serialization cases and URL.searchParams constructor identity/mutation`);
console.log('PASS missing-API bare isolate, restored temporary compatibility properties, and catalog/search/detail/chapter HTTP bridge');
