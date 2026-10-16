import { load } from 'cheerio';
import { Parser } from 'htmlparser2';
import dayjs from 'dayjs';
import { encode, decode } from 'urlencode';
import { Buffer } from 'buffer';
import iconv from 'iconv-lite';

globalThis.__arkhiveLnReaderHelpers = Object.freeze({
  cheerio: Object.freeze({ load }),
  htmlparser2: Object.freeze({ Parser }),
  dayjs,
  urlencode: Object.freeze({ encode, decode }),
  Buffer,
  iconv,
});
