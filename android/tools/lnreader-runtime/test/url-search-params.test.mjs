import assert from 'node:assert/strict';
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
const shimSource = await fs.readFile(resolve(androidRoot, 'tools/lnreader-runtime/src/url-search-params.js'), 'utf8');
const assetShim = await fs.readFile(resolve(assetRoot, 'url-search-params.js'), 'utf8');
assert.equal(assetShim, shimSource, 'the packaged runtime asset must match its reviewed source');

const polyfillContext = vm.createContext({});
vm.runInContext(shimSource, polyfillContext);
const nativeContext = vm.createContext({ URLSearchParams });
const conformanceCases = [
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
  `(() => { const p = new URLSearchParams('a=1&a=undefined'); const q = new URLSearchParams('a=1&a=undefined'); p.delete('a', undefined); const ownKeys = Object.keys(q); q._entries = []; return [p.toString(), q.has('a', undefined), ownKeys, q.toString()]; })()`,
  `(() => { const proto = { inherited: 'skip' }; const record = Object.assign(Object.create(proto), { own: 'keep' }); return new URLSearchParams(record).toString(); })()`,
  `(() => { const p = new URLSearchParams(123); const q = new URLSearchParams(false); return [p.toString(), q.toString()]; })()`,
];

for (const expression of conformanceCases) {
  const actual = vm.runInContext(`JSON.stringify(${expression})`, polyfillContext);
  const expected = vm.runInContext(`JSON.stringify(${expression})`, nativeContext);
  assert.equal(actual, expected, `URLSearchParams conformance mismatch: ${expression}`);
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
  const errorName = context => {
    try { vm.runInContext(expression, context); return null; }
    catch (error) { return error.name; }
  };
  assert.equal(errorName(polyfillContext), errorName(nativeContext), `URLSearchParams error mismatch: ${expression}`);
}
// WHATWG form decoding UTF-8 encodes raw non-ASCII input before percent-decoding.
// Node 24's native implementation instead replaces the raw character after a bad byte.
assert.equal(vm.runInContext(`new URLSearchParams('a=%FF雪').get('a')`, polyfillContext), '\uFFFD雪');
const bootstrap = bootstrapAsset;
const helpers = await fs.readFile(resolve(assetRoot, 'helpers.js'));
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
    const response = await fetchApi('https://fixture.invalid/top-novel/' + pageNo);
    return {data: await response.json(), isolated: [typeof URL, typeof fetch, typeof globalThis.android]};
  },
  async searchNovels(term) {
    const form = new URLSearchParams();
    form.append('keyword', term); form.append('search', '1');
    return this.detailedSearch(form);
  },
  async detailedSearch(form) {
    const response = await fetchApi('https://fixture.invalid/detailed-search', {
      method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: form.toString()
    });
    return response.json();
  },
  async parseNovel(path) {
    const params = new URLSearchParams({path});
    return (await fetchApi('https://fixture.invalid/detail?' + params)).json();
  },
  async parseChapter(path) {
    const params = new URLSearchParams(); params.set('path', path);
    return (await fetchApi('https://fixture.invalid/chapter?' + params.toString())).text();
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

async function execute(operation, args) {
  const namedData = new Map([
    ['lr-helper-bundle', helpers],
    ['lr-url-search-params', Buffer.from(assetShim)],
    ['lr-plugin-bundle', plugin],
    ['lr-operation-input', Buffer.from(JSON.stringify({ ...pluginMeta, operation, args, storage: {}, webStorageUtilized: false }))],
  ]);
  let responseCounter = 0;
  const context = vm.createContext({ android: {
    async consumeNamedDataAsArrayBuffer(name) {
      const bytes = namedData.get(name);
      if (!bytes) throw new Error(`missing named data: ${name}`);
      return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    },
  }});
  assert.equal(await vm.runInContext(bootstrap, context), 'ready');
  for (let pollIndex = 0; pollIndex < 1000; pollIndex++) {
    const report = JSON.parse(vm.runInContext('globalThis.__lnReaderPoll()', context));
    for (const request of report.requests) {
      requestsSeen.push(request);
      const url = new globalThis.URL(request.url);
      assert.equal(url.origin, 'https://fixture.invalid');
      const route = url.pathname.startsWith('/top-novel/') ? 'catalog' : url.pathname.slice(1);
      const responseBody = responses[route === 'detailed-search' ? 'search' : route];
      assert.notEqual(responseBody, undefined, `unexpected bridge request ${request.url}`);
      const name = `lr-response-${request.id}-${++responseCounter}`;
      const bytes = Buffer.from(responseBody);
      namedData.set(name, bytes);
      const metadata = {status: 200, statusText: 'OK', headers: {'content-type': url.pathname === '/chapter' ? 'text/html' : 'application/json'}, url: request.url, requestedUrl: request.url};
      await vm.runInContext(`globalThis.__lnReaderDeliver(${request.id}, ${JSON.stringify(JSON.stringify(metadata))}, ${JSON.stringify(name)})`, context);
    }
    if (report.done) {
      assert.equal(report.error, null, `${operation}: ${report.error}`);
      return report.result;
    }
    await new Promise(resolve => setTimeout(resolve, 1));
  }
  throw new Error(`${operation} did not finish after 1000 bridge polls`);
}

const popular = await execute('popularNovels', [3, {filters: {}}]);
assert.deepEqual(popular, {data: {stage: 'catalog', title: '雪 月'}, isolated: ['undefined', 'undefined', 'undefined']});
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
  ['https://fixture.invalid/detail?path=%2Fstory%2F%E6%9C%88', 'GET', null],
  ['https://fixture.invalid/chapter?path=%2Fchapter%2F%E6%9C%88', 'GET', null],
]);
assert.equal(requestsSeen[1].headers['content-type'], 'application/x-www-form-urlencoded');
console.log(`PASS ${conformanceCases.length} differential semantics and ${errorCases.length} error cases against native Node URLSearchParams`);
console.log('PASS popular/search/detail/chapter requests through shipped bootstrap, helpers, and the named-data HTTP bridge');
