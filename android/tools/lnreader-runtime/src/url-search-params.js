// Pure URLSearchParams compatibility for the isolated LNReader JavaScript runtime.
// It intentionally provides query-data operations only; it does not add URL, fetch,
// browser storage, cookies, or any Android bridge capability to extension globals.
(() => {
  'use strict';

  const root = globalThis;
  if (typeof root.URLSearchParams === 'function') return;

  const entriesByInstance = new WeakMap();
  const replacement = '\uFFFD';

  function domString(value) {
    if (typeof value === 'symbol') throw new TypeError('Cannot convert a Symbol value to a string.');
    return String(value);
  }

  function usvString(value) {
    const input = domString(value);
    let output = '';
    for (let index = 0; index < input.length; index++) {
      const unit = input.charCodeAt(index);
      if (unit >= 0xd800 && unit <= 0xdbff) {
        const next = input.charCodeAt(index + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          output += input[index] + input[index + 1];
          index++;
        } else {
          output += replacement;
        }
      } else if (unit >= 0xdc00 && unit <= 0xdfff) {
        output += replacement;
      } else {
        output += input[index];
      }
    }
    return output;
  }

  function utf8Encode(input) {
    const bytes = [];
    for (let index = 0; index < input.length; index++) {
      let point = input.charCodeAt(index);
      if (point >= 0xd800 && point <= 0xdbff) {
        point = 0x10000 + ((point - 0xd800) << 10) + (input.charCodeAt(++index) - 0xdc00);
      }
      if (point <= 0x7f) {
        bytes.push(point);
      } else if (point <= 0x7ff) {
        bytes.push(0xc0 | (point >> 6), 0x80 | (point & 0x3f));
      } else if (point <= 0xffff) {
        bytes.push(0xe0 | (point >> 12), 0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f));
      } else {
        bytes.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 0x3f), 0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f));
      }
    }
    return bytes;
  }

  function utf8Decode(bytes) {
    let output = '';
    for (let index = 0; index < bytes.length;) {
      const first = bytes[index];
      if (first <= 0x7f) {
        output += String.fromCharCode(first);
        index++;
        continue;
      }

      let point;
      let needed;
      if (first >= 0xc2 && first <= 0xdf) {
        point = first & 0x1f;
        needed = 1;
      } else if (first >= 0xe0 && first <= 0xef) {
        point = first & 0x0f;
        needed = 2;
      } else if (first >= 0xf0 && first <= 0xf4) {
        point = first & 0x07;
        needed = 3;
      } else {
        output += replacement;
        index++;
        continue;
      }

      let cursor = index + 1;
      let seen = 0;
      let valid = true;
      while (seen < needed) {
        if (cursor >= bytes.length) {
          valid = false;
          break;
        }
        const next = bytes[cursor];
        if (next < 0x80 || next > 0xbf ||
          (seen === 0 && ((first === 0xe0 && next < 0xa0) ||
            (first === 0xed && next > 0x9f) ||
            (first === 0xf0 && next < 0x90) ||
            (first === 0xf4 && next > 0x8f)))) {
          valid = false;
          break;
        }
        point = (point << 6) | (next & 0x3f);
        cursor++;
        seen++;
      }

      if (!valid) {
        output += replacement;
        // Reprocess the byte that made the sequence invalid; consume a valid
        // partial prefix when the input ends in the middle of a sequence.
        index = cursor;
        continue;
      }

      output += String.fromCodePoint(point);
      index = cursor;
    }
    return output;
  }

  function formEncode(value) {
    return encodeURIComponent(usvString(value))
      .replace(/[!'()~]/g, character => `%${character.charCodeAt(0).toString(16).toUpperCase()}`)
      .replace(/%20/g, '+');
  }

  function formDecode(value) {
    const input = usvString(value).replace(/\+/g, ' ');
    const bytes = [];
    for (let index = 0; index < input.length;) {
      if (input[index] === '%' && /^[0-9a-fA-F]{2}$/.test(input.slice(index + 1, index + 3))) {
        bytes.push(parseInt(input.slice(index + 1, index + 3), 16));
        index += 3;
      } else {
        const first = input.charCodeAt(index);
        if (first >= 0xd800 && first <= 0xdbff && input.charCodeAt(index + 1) >= 0xdc00 && input.charCodeAt(index + 1) <= 0xdfff) {
          bytes.push(...utf8Encode(input.slice(index, index + 2)));
          index += 2;
        } else {
          bytes.push(...utf8Encode(input[index]));
          index++;
        }
      }
    }
    return utf8Decode(bytes);
  }

  function assertReceiver(receiver) {
    const entries = entriesByInstance.get(receiver);
    if (!entries) throw new TypeError('Illegal invocation');
    return entries;
  }

  function parseQuery(entries, input) {
    let query = usvString(input);
    if (query.startsWith('?')) query = query.slice(1);
    if (query === '') return;
    for (const sequence of query.split('&')) {
      if (sequence === '') continue;
      const separator = sequence.indexOf('=');
      entries.push(separator < 0
        ? [formDecode(sequence), '']
        : [formDecode(sequence.slice(0, separator)), formDecode(sequence.slice(separator + 1))]);
    }
  }

  function readSequencePair(pair) {
    if ((typeof pair !== 'object' && typeof pair !== 'function') || pair === null) {
      throw new TypeError('Each query pair must be an object sequence.');
    }
    const iteratorMethod = pair[Symbol.iterator];
    if (typeof iteratorMethod !== 'function') throw new TypeError('Each query pair must be an iterable sequence.');
    const values = Array.from({ [Symbol.iterator]: () => iteratorMethod.call(pair) });
    if (values.length !== 2) throw new TypeError('Each query pair must contain exactly two items.');
    return [usvString(values[0]), usvString(values[1])];
  }

  function makeIterator(owner, kind) {
    let index = 0;
    return {
      next() {
        const entries = assertReceiver(owner);
        if (index >= entries.length) return { value: undefined, done: true };
        const pair = entries[index++];
        if (kind === 'keys') return { value: pair[0], done: false };
        if (kind === 'values') return { value: pair[1], done: false };
        return { value: [pair[0], pair[1]], done: false };
      },
      [Symbol.iterator]() { return this; },
    };
  }

  class URLSearchParamsShim {
    constructor(init = '') {
      const entries = [];
      entriesByInstance.set(this, entries);
      if (init === undefined || init === null) return;

      if (typeof init === 'string') {
        parseQuery(entries, init);
        return;
      }

      if (typeof init !== 'object' && typeof init !== 'function') {
        parseQuery(entries, init);
        return;
      }

      const iteratorMethod = init[Symbol.iterator];
      if (iteratorMethod !== undefined && iteratorMethod !== null) {
        if (typeof iteratorMethod !== 'function') throw new TypeError('Query parameter initializer is not iterable.');
        for (const pair of { [Symbol.iterator]: () => iteratorMethod.call(init) }) {
          entries.push(readSequencePair(pair));
        }
        return;
      }

      const record = Object(init);
      if (Object.getOwnPropertySymbols(record).some(symbol => Object.prototype.propertyIsEnumerable.call(record, symbol))) {
        throw new TypeError('Record initializer has an enumerable Symbol key.');
      }
      for (const key of Object.keys(record)) {
        entries.push([usvString(key), usvString(record[key])]);
      }
    }

    append(name, value) {
      if (arguments.length < 2) throw new TypeError('append requires both a name and a value.');
      assertReceiver(this).push([usvString(name), usvString(value)]);
    }

    delete(name, value) {
      const entries = assertReceiver(this);
      if (arguments.length < 1) throw new TypeError('delete requires a name.');
      const key = usvString(name);
      const hasValue = arguments.length > 1 && value !== undefined;
      const wanted = hasValue ? usvString(value) : null;
      for (let index = entries.length - 1; index >= 0; index--) {
        if (entries[index][0] === key && (!hasValue || entries[index][1] === wanted)) entries.splice(index, 1);
      }
    }

    get(name) {
      if (arguments.length < 1) throw new TypeError('get requires a name.');
      const key = usvString(name);
      const pair = assertReceiver(this).find(entry => entry[0] === key);
      return pair ? pair[1] : null;
    }

    getAll(name) {
      if (arguments.length < 1) throw new TypeError('getAll requires a name.');
      const key = usvString(name);
      return assertReceiver(this).filter(entry => entry[0] === key).map(entry => entry[1]);
    }

    has(name, value) {
      const entries = assertReceiver(this);
      if (arguments.length < 1) throw new TypeError('has requires a name.');
      const key = usvString(name);
      if (arguments.length > 1 && value !== undefined) {
        const wanted = usvString(value);
        return entries.some(entry => entry[0] === key && entry[1] === wanted);
      }
      return entries.some(entry => entry[0] === key);
    }

    set(name, value) {
      if (arguments.length < 2) throw new TypeError('set requires both a name and a value.');
      const entries = assertReceiver(this);
      const key = usvString(name);
      const replacementValue = usvString(value);
      let found = false;
      for (let index = 0; index < entries.length;) {
        if (entries[index][0] !== key) {
          index++;
        } else if (!found) {
          entries[index][1] = replacementValue;
          found = true;
          index++;
        } else {
          entries.splice(index, 1);
        }
      }
      if (!found) entries.push([key, replacementValue]);
    }

    sort() {
      assertReceiver(this).sort((left, right) => left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0);
    }

    entries() { assertReceiver(this); return makeIterator(this, 'entries'); }
    keys() { assertReceiver(this); return makeIterator(this, 'keys'); }
    values() { assertReceiver(this); return makeIterator(this, 'values'); }

    forEach(callback, thisArg) {
      const entries = assertReceiver(this);
      if (typeof callback !== 'function') throw new TypeError('The callback must be callable.');
      for (let index = 0; index < entries.length; index++) {
        callback.call(thisArg, entries[index][1], entries[index][0], this);
      }
    }

    toString() {
      return assertReceiver(this).map(([key, value]) => `${formEncode(key)}=${formEncode(value)}`).join('&');
    }

    [Symbol.iterator]() { return this.entries(); }

    get size() { return assertReceiver(this).length; }
  }

  Object.defineProperty(URLSearchParamsShim.prototype, Symbol.toStringTag, {
    configurable: true,
    value: 'URLSearchParams',
  });
  Object.defineProperty(root, 'URLSearchParams', {
    configurable: true,
    enumerable: false,
    writable: true,
    value: URLSearchParamsShim,
  });
})();
