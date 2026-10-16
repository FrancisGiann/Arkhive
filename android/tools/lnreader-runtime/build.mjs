import { build } from 'esbuild';
import { copyFile, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('.', import.meta.url)));
const target = process.argv[2];
if (!target) throw new Error('Pass the canonical app/src/main/assets/lnreader output directory.');
await mkdir(target, { recursive: true });
await build({
  entryPoints: [resolve(root, 'src/helpers-entry.js')],
  outfile: resolve(target, 'helpers.js'),
  bundle: true,
  platform: 'browser',
  format: 'iife',
  target: ['es2020'],
  minify: false,
  legalComments: 'none',
  inject: [resolve(root, 'src/buffer-shim.mjs')],
  define: { 'process.env.NODE_ENV': '"production"' },
});
await copyFile(resolve(root, 'bootstrap.js'), resolve(target, 'bootstrap.js'));

const lock = JSON.parse(await readFile(resolve(root, 'package-lock.json'), 'utf8'));
const licenses = resolve(target, 'licenses');
await mkdir(licenses, { recursive: true });
const noticeRows = [];
for (const [packagePath, metadata] of Object.entries(lock.packages)) {
  if (!packagePath.startsWith('node_modules/')) continue;
  // Build-only esbuild and its platform binaries are not shipped in the app helper bundle.
  if (metadata.dev === true) continue;
  const relativePackagePath = packagePath.slice('node_modules/'.length);
  const packageDirectory = resolve(root, 'node_modules', relativePackagePath);
  const packageInfo = JSON.parse(await readFile(resolve(packageDirectory, 'package.json'), 'utf8'));
  let files = [];
  try {
    files = (await readdir(packageDirectory)).filter(name => /^(licen[cs]e|copying)(\.|$)/i.test(name));
  } catch { /* The package manifest below makes a missing notice explicit. */ }
  if (files.length === 0 && relativePackagePath === 'boolbase') {
    const name = 'boolbase-upstream-ISC.txt';
    await copyFile(resolve(root, 'licenses/boolbase-ISC.txt'), resolve(licenses, name));
    noticeRows.push(`- boolbase@${packageInfo.version} (${packageInfo.license}): licenses/${name}`);
    continue;
  }
  if (files.length === 0) throw new Error(`No shipped license file found for ${relativePackagePath}@${packageInfo.version}.`);
  const safePackage = relativePackagePath.replaceAll('/', '_').replaceAll('@', '');
  const copied = [];
  for (const sourceName of files) {
    const targetName = `${safePackage}-${sourceName}`;
    await copyFile(resolve(packageDirectory, sourceName), resolve(licenses, targetName));
    copied.push(`licenses/${targetName}`);
  }
  noticeRows.push(`- ${packageInfo.name}@${packageInfo.version} (${metadata.license || packageInfo.license || 'license in attached notice'}): ${copied.join(', ')}`);
}
for (const name of ['LNReader-app-MIT.txt', 'LNReader-plugins-MIT.txt']) {
  await copyFile(resolve(root, 'licenses', name), resolve(licenses, name));
}
await writeFile(resolve(target, 'THIRD-PARTY-NOTICES.txt'), [
  'Arkhive LNReader compatibility helper notices',
  'The bundled compatibility helpers adapt public LNReader contracts; upstream extension code is not included.',
  'Pinned npm packages and their transitive licenses:',
  ...noticeRows,
  '- LNReader app contracts/constants: licenses/LNReader-app-MIT.txt',
  '- LNReader plugin contracts/constants: licenses/LNReader-plugins-MIT.txt',
  '',
].join('\n'));
console.log(`Built pinned helper bundle and license notices under ${resolve(target)}`);
