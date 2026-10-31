import { Buffer } from 'buffer';
import { decodeFast, encode } from '../node_modules/@exodus/bytes/fallback/utf8.js';

// whatwg-url's UTF-8 helpers normally use ambient TextEncoder/TextDecoder, which are absent in
// AndroidX's bare isolate. Buffer is already bundled for the LNReader compatibility helpers.
export function utf8Encode(value) {
  return Buffer.from(encode(String(value), true));
}

export function utf8DecodeWithoutBOM(bytes) {
  return decodeFast(bytes, true);
}
