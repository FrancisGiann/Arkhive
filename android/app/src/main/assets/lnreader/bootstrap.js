// AndroidX JavaScriptSandbox entry protocol. This file is evaluated once in a fresh isolate.
(async () => {
  'use strict';
  const bridge = globalThis.android;
  if (!bridge || typeof bridge.consumeNamedDataAsArrayBuffer !== 'function') {
    throw new Error('AndroidX named-data support is unavailable.');
  }
  const utf8 = (arrayBuffer) => {
    const bytes = new Uint8Array(arrayBuffer);
    // The bare AndroidX isolate has no TextDecoder. Decode code points directly so multibyte
    // characters crossing an internal chunk boundary are preserved.
    const chunks = [];
    let chunk = '';
    for (let index = 0; index < bytes.length;) {
      const first = bytes[index++];
      let point;
      let continuationCount;
      if (first < 0x80) { point = first; continuationCount = 0; }
      else if ((first & 0xe0) === 0xc0) { point = first & 0x1f; continuationCount = 1; }
      else if ((first & 0xf0) === 0xe0) { point = first & 0x0f; continuationCount = 2; }
      else if ((first & 0xf8) === 0xf0) { point = first & 0x07; continuationCount = 3; }
      else { point = 0xfffd; continuationCount = 0; }
      let valid = true;
      for (let part = 0; part < continuationCount; part++) {
        if (index >= bytes.length || (bytes[index] & 0xc0) !== 0x80) { valid = false; break; }
        point = (point << 6) | (bytes[index++] & 0x3f);
      }
      if (!valid || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) point = 0xfffd;
      chunk += String.fromCodePoint(point);
      if (chunk.length >= 2048) { chunks.push(chunk); chunk = ''; }
    }
    if (chunk) chunks.push(chunk);
    return chunks.join('');
  };

  const urlSearchParamsSource = utf8(await bridge.consumeNamedDataAsArrayBuffer('lr-url-search-params'));
  (new Function(`'use strict';\n${urlSearchParamsSource}`))();
  if (typeof globalThis.URLSearchParams !== 'function') {
    throw new Error('LNReader URLSearchParams compatibility failed to initialize.');
  }

  const helpersSource = utf8(await bridge.consumeNamedDataAsArrayBuffer('lr-helper-bundle'));
  (new Function(`'use strict';\n${helpersSource}`))();
  const helpers = globalThis.__arkhiveLnReaderHelpers;
  if (!helpers || !helpers.Buffer || !helpers.iconv) throw new Error('LNReader helper bundle failed to initialize.');
  const Buffer = helpers.Buffer;
  const pluginSource = Buffer.from(await bridge.consumeNamedDataAsArrayBuffer('lr-plugin-bundle')).toString('utf8');
  const input = JSON.parse(Buffer.from(await bridge.consumeNamedDataAsArrayBuffer('lr-operation-input')).toString('utf8'));
  if (input.webStorageUtilized === true) {
    throw new Error('Unsupported capability: this source requires browser Web Storage capture.');
  }

  const MAX_STORAGE_BYTES = 64 * 1024;
  const MAX_FETCHES = 24;
  const MAX_BODY_BYTES = 2_000_000;
  const storageRows = Object.assign(Object.create(null), input.storage || {});
  let nextRequestId = 1;
  let requestCount = 0;
  const pending = new Map();
  const requestQueue = [];
  let finished = false;
  let output;
  let failure;

  const storage = Object.freeze({
    set(key, value, expires) {
      const name = String(key);
      if (!name || name.length > 200) throw new Error('Storage key exceeds its limit.');
      let copy;
      try { copy = JSON.parse(JSON.stringify(value)); } catch (_) { throw new Error('Storage values must be bounded JSON values.'); }
      if (copy === undefined) throw new Error('Storage values must be bounded JSON values.');
      const expiry = expires instanceof Date ? expires.getTime() : Number(expires || 0);
      // JSON.stringify(new Date()) in the official helper stores an ISO timestamp.
      const entry = { created: new Date().toJSON(), value: copy };
      if (expires !== undefined) entry.expires = Number.isFinite(expiry) ? expiry : undefined;
      const prior = storageRows[name];
      storageRows[name] = entry;
      if (JSON.stringify(storageRows).length > MAX_STORAGE_BYTES) {
        if (prior === undefined) delete storageRows[name]; else storageRows[name] = prior;
        throw new Error('Plugin storage exceeds its 64 KiB limit.');
      }
    },
    get(key, raw) {
      const name = String(key);
      const entry = storageRows[name];
      if (!entry) return undefined;
      if (entry.expires > 0 && entry.expires <= Date.now()) { delete storageRows[name]; return undefined; }
      return raw === true ? entry : entry.value;
    },
    delete(key) { delete storageRows[String(key)]; },
    clearAll() { for (const key of Object.keys(storageRows)) delete storageRows[key]; },
    getAllKeys() { return Object.keys(storageRows); },
  });
  const unsupportedWebStorage = Object.freeze({
    get() { throw new Error('Unsupported capability: browser Web Storage capture is unavailable.'); },
  });

  class HeadersShim {
    constructor(source = {}) {
      this._map = new Map();
      const pairs = source instanceof HeadersShim ? source.entries() : Array.isArray(source) ? source : Object.entries(source || {});
      for (const [name, value] of pairs) this.append(name, value);
    }
    append(name, value) { const key = String(name).trim().toLowerCase(); const val = String(value).trim(); this._map.set(key, this._map.has(key) ? `${this._map.get(key)}, ${val}` : val); }
    set(name, value) { this._map.set(String(name).trim().toLowerCase(), String(value).trim()); }
    get(name) { return this._map.get(String(name).toLowerCase()) ?? null; }
    has(name) { return this._map.has(String(name).toLowerCase()); }
    delete(name) { this._map.delete(String(name).toLowerCase()); }
    entries() { return this._map.entries(); }
    keys() { return this._map.keys(); }
    values() { return this._map.values(); }
    forEach(callback, thisArg) { this._map.forEach((value, key) => callback.call(thisArg, value, key, this)); }
    [Symbol.iterator]() { return this.entries(); }
  }

  function request(url, init = {}) {
    if (++requestCount > MAX_FETCHES) throw new Error('This operation exceeded its request limit.');
    if (init.credentials && init.credentials !== 'omit') throw new Error('Unsupported capability: authenticated requests are unavailable.');
    const id = String(nextRequestId++);
    const headers = new HeadersShim({
      Accept: '*/*', 'Accept-Language': '*', 'Sec-Fetch-Mode': 'cors',
      'Cache-Control': 'max-age=0', 'User-Agent': input.userAgent || 'Arkhive/0.4.1 LNReader isolated source client',
    });
    new HeadersShim(init.headers || {}).forEach((value, name) => headers.set(name, value));
    const method = String(init.method || 'GET').toUpperCase();
    const body = typeof init.body === 'string' ? init.body : null;
    if (!['GET', 'HEAD', 'POST'].includes(method)) throw new Error('Unsupported capability: this HTTP method is unavailable.');
    if (body !== null && (method !== 'POST' || Buffer.byteLength(body, 'utf8') > 64 * 1024)) {
      throw new Error('Request body is unsupported or exceeds its limit.');
    }
    const record = { id, url: String(url), method, headers: Object.fromEntries(headers.entries()), body };
    if (!record.url || record.url.length > 4096) throw new Error('The source URL exceeds its limit.');
    return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); requestQueue.push(record); });
  }

  class BrokerResponse {
    constructor(meta, bodyBytes) {
      this.status = Number(meta.status);
      this.statusText = String(meta.statusText || '').slice(0, 128);
      this.ok = this.status >= 200 && this.status < 300;
      this.headers = new HeadersShim(meta.headers || {});
      this.url = String(meta.url || '');
      this.redirected = this.url !== meta.requestedUrl;
      this.type = 'basic';
      this.bodyUsed = false;
      this._bytes = new Uint8Array(bodyBytes);
    }
    async text() { this.bodyUsed = true; return Buffer.from(this._bytes).toString('utf8'); }
    async json() { return JSON.parse(await this.text()); }
    async arrayBuffer() { this.bodyUsed = true; return this._bytes.slice().buffer; }
    async blob() { throw new Error('Unsupported capability: Blob responses are unavailable.'); }
    clone() { return new BrokerResponse({ status: this.status, statusText: this.statusText, headers: Object.fromEntries(this.headers.entries()), url: this.url }, this._bytes); }
  }
  function fetchApi(url, init) { return request(url, init); }
  async function fetchText(url, init, encoding) {
    try {
      const response = await request(url, init);
      if (!response.ok) return '';
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (!encoding || String(encoding).toLowerCase() === 'utf-8' || String(encoding).toLowerCase() === 'utf8') {
        return Buffer.from(bytes).toString('utf8');
      }
      return helpers.iconv.decode(Buffer.from(bytes), String(encoding));
    } catch (_) { return ''; }
  }

  const NovelStatus = Object.freeze({
    Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus',
    STUB: 'STUB', Inactive: 'Inactive',
  });
  const FilterTypes = Object.freeze({
    TextInput: 'Text', Picker: 'Picker', CheckboxGroup: 'Checkbox', Switch: 'Switch',
    ExcludableCheckboxGroup: 'XCheckbox',
  });
  const modules = Object.freeze({
    cheerio: helpers.cheerio,
    htmlparser2: helpers.htmlparser2,
    dayjs: helpers.dayjs,
    urlencode: helpers.urlencode,
    '@libs/fetch': Object.freeze({ fetchApi, fetchText }),
    '@libs/storage': Object.freeze({ storage, localStorage: unsupportedWebStorage, sessionStorage: unsupportedWebStorage }),
    '@libs/novelStatus': Object.freeze({ NovelStatus }),
    '@libs/filterInputs': Object.freeze({ FilterTypes }),
    '@libs/isAbsoluteUrl': Object.freeze({ isUrlAbsolute: value => String(value).startsWith('//') || /^[a-zA-Z][a-zA-Z\d+.-]*:/.test(String(value)) }),
    '@libs/defaultCover': Object.freeze({ defaultCover: 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true' }),
  });
  const controlledRequire = name => {
    if (!Object.prototype.hasOwnProperty.call(modules, name)) throw new Error(`Unsupported module: ${String(name)}`);
    return modules[name];
  };

  // Extension code has no bridge/global object, browser APIs, Node globals or ambient fetch.
  try { delete globalThis.android; } catch (_) { globalThis.android = undefined; }
  delete globalThis.__arkhiveLnReaderHelpers;
  const module = { exports: {} };
  try {
    (new Function('require', 'module', 'exports', `'use strict';\n${pluginSource}`))(controlledRequire, module, module.exports);
    const plugin = module.exports && (module.exports.default || module.exports);
    if (!plugin) throw new Error('The source returned no plugin object.');
    for (const key of ['pluginId', 'pluginName', 'pluginSite', 'pluginVersion']) {
      const property = key.slice('plugin'.length).toLowerCase();
      if (plugin[property] !== input[key]) throw new Error(`Plugin metadata mismatch: ${property}.`);
    }
    if (plugin.webStorageUtilized === true) throw new Error('Unsupported capability: this source requires browser Web Storage capture.');
    if (plugin.customJS || plugin.customCSS) throw new Error('Unsupported capability: custom JavaScript and CSS extensions are not executed.');
    // Do not await here: the host polls and resolves any brokered network promises.
    let operationResult;
    if (input.operation === 'describe') {
      const filters = plugin.filters && typeof plugin.filters === 'object' ? plugin.filters : {};
      const imageInit = plugin.imageRequestInit && typeof plugin.imageRequestInit === 'object' ? plugin.imageRequestInit : {};
      const imageHeaders = new Map([['user-agent', input.userAgent || 'Arkhive/0.4.1 LNReader isolated source client']]);
      if (imageInit.headers !== undefined && imageInit.headers !== null) {
        if (typeof imageInit.headers !== 'object' || Array.isArray(imageInit.headers)) {
          throw new Error('Unsupported capability: imageRequestInit headers must be a plain string map.');
        }
        for (const [name, value] of Object.entries(imageInit.headers)) {
          if (typeof value !== 'string') throw new Error('Unsupported capability: imageRequestInit header values must be strings.');
          imageHeaders.set(name.toLowerCase(), value);
        }
      }
      operationResult = {
        hasParsePage: typeof plugin.parsePage === 'function',
        hasResolveUrl: typeof plugin.resolveUrl === 'function',
        filters,
        imageRequestInit: {
          method: imageInit.method === undefined ? 'GET' : imageInit.method,
          headers: Object.fromEntries(imageHeaders),
          body: imageInit.body === undefined ? null : imageInit.body,
        },
      };
    } else {
      if (typeof plugin[input.operation] !== 'function') throw new Error(`Unsupported plugin operation: ${input.operation}`);
      operationResult = plugin[input.operation](...(Array.isArray(input.args) ? input.args : []));
    }
    Promise.resolve(operationResult).then(value => {
      try { output = JSON.parse(JSON.stringify(value ?? null)); finished = true; }
      catch (_) { failure = 'Plugin result must be bounded JSON data.'; finished = true; }
    }, error => { failure = String(error && error.message || error).slice(0, 2000); finished = true; });
  } catch (error) { failure = String(error && error.message || error).slice(0, 2000); finished = true; }

  Object.defineProperty(globalThis, '__lnReaderPoll', { configurable: false, value: () => {
    const result = finished ? output : null;
    const storageJson = finished && !failure ? JSON.stringify(storageRows) : '{}';
    if (storageJson.length > MAX_STORAGE_BYTES) { failure = 'Plugin storage exceeds its 64 KiB limit.'; finished = true; }
    return JSON.stringify({ requests: requestQueue.splice(0, requestQueue.length), done: finished,
      error: failure || null, result: finished && !failure ? result : null,
      storage: finished && !failure ? storageRows : null, requestCount });
  }});
  Object.defineProperty(globalThis, '__lnReaderDeliver', { configurable: false, value: async (id, metaJson, dataName) => {
    const waiter = pending.get(String(id));
    if (!waiter) return;
    pending.delete(String(id));
    try {
      const meta = JSON.parse(metaJson);
      const bytes = await bridge.consumeNamedDataAsArrayBuffer(dataName);
      if (bytes.byteLength > MAX_BODY_BYTES) throw new Error('The source response exceeds its size limit.');
      waiter.resolve(new BrokerResponse(meta, bytes));
    } catch (error) { waiter.reject(error); }
  }});
  Object.defineProperty(globalThis, '__lnReaderReject', { configurable: false, value: (id, message) => {
    const waiter = pending.get(String(id));
    if (!waiter) return;
    pending.delete(String(id));
    waiter.reject(new Error(String(message).slice(0, 1000)));
  }});
  return 'ready';
})()
