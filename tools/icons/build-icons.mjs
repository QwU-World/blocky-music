#!/usr/bin/env node
// Builds the Workshop UI icon atlases from Lucide (ISC). The outputs are committed; run this only when the
// icon list changes. Keep ICONS in the same order as ru.gora.musicbattle.client.ui.UiIcon (the ui-toolkit
// check compares them).
//
//   node tools/icons/build-icons.mjs --sharp-from /path/to/a/project/with/node_modules/sharp
//
// Output, under src/main/resources/assets/music_battle/textures/gui/workshop/:
//   icons_16.png, icons_32.png, icons_64.png  white glyphs, alpha only, drawn tinted with LINEAR sampling
//   icons.json                                grid, cell and gutter sizes, icon order, source hashes
// Every pixel is white (RGB 255) and only alpha varies, so linear filtering never pulls in a dark fringe.
// Cells are separated by a transparent gutter of size/8 on each side so filtering never bleeds a neighbour.

import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = join(ROOT, 'src/main/resources/assets/music_battle/textures/gui/workshop');
const CACHE = join(ROOT, 'build/icons-cache');
const VERSION = '1.47.0';
const TARBALL = `https://registry.npmjs.org/lucide-static/-/lucide-static-${VERSION}.tgz`;
const INTEGRITY = 'sha512-yWIrkdXc688Feq5VjOktsKmV5Ikc7y5Nu3rrdtbr8nWjkJWk8QlnZfVtIak22Af+fNhZ7k4cTJpZo1zmj7X5sA==';
const SIZES = [16, 32, 64];
const COLUMNS = 8;

// [atlas name, lucide file, filled]. Filled draws the outline shape solid (play/pause buttons, favourite).
const ICONS = [
  ['play', 'play', true],
  ['pause', 'pause', true],
  ['download', 'download', false],
  ['upload', 'upload', false],
  ['heart', 'heart', false],
  ['heart_filled', 'heart', true],
  ['star', 'star', false],
  ['star_filled', 'star', true],
  ['message_circle', 'message-circle', false],
  ['search', 'search', false],
  ['external_link', 'external-link', false],
  ['link', 'link', false],
  ['copy', 'copy', false],
  ['x', 'x', false],
  ['check', 'check', false],
  ['chevron_down', 'chevron-down', false],
  ['chevron_left', 'chevron-left', false],
  ['chevron_right', 'chevron-right', false],
  ['arrow_left', 'arrow-left', false],
  ['music', 'music', false],
  ['user_round', 'user-round', false],
  ['settings', 'settings', false],
  ['bell', 'bell', false],
  ['flag', 'flag', false],
  ['trash', 'trash-2', false],
  ['folder_open', 'folder-open', false],
  ['refresh', 'refresh-cw', false],
  ['loader', 'loader-circle', false],
  ['info', 'info', false],
  ['alert', 'triangle-alert', false],
  ['eye_off', 'eye-off', false],
  ['filter', 'list-filter', false],
];

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

async function tarball() {
  const file = join(CACHE, `lucide-static-${VERSION}.tgz`);
  if (!existsSync(file)) {
    mkdirSync(CACHE, { recursive: true });
    console.log(`download ${TARBALL}`);
    const response = await fetch(TARBALL);
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${TARBALL}`);
    writeFileSync(file, Buffer.from(await response.arrayBuffer()));
  }
  const bytes = readFileSync(file);
  const integrity = 'sha512-' + createHash('sha512').update(bytes).digest('base64');
  if (integrity !== INTEGRITY) throw new Error(`lucide-static ${VERSION}: integrity ${integrity}, expected ${INTEGRITY}`);
  return gunzipSync(bytes);
}

/** Minimal ustar reader: regular files only, which is all an npm tarball holds. */
function untar(tar) {
  const files = new Map();
  for (let offset = 0; offset + 512 <= tar.length;) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every((b) => b === 0)) break;
    const field = (start, length) => header.subarray(start, start + length).toString('utf8').replace(/\0.*$/s, '');
    const name = (field(345, 155) ? field(345, 155) + '/' : '') + field(0, 100);
    const size = parseInt(field(124, 12).trim() || '0', 8);
    const type = field(156, 1);
    if (type === '0' || type === '') files.set(name, tar.subarray(offset + 512, offset + 512 + size));
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  return files;
}

function prepareSvg(source, filled) {
  let svg = source.toString('utf8').replace(/<!--.*?-->/gs, '').replaceAll('currentColor', '#ffffff');
  if (filled) svg = svg.replace('fill="none"', 'fill="#ffffff"');
  return svg;
}

async function main() {
  const from = arg('--sharp-from');
  const require = createRequire(from ? join(resolve(from), 'package.json') : import.meta.url);
  const sharp = require('sharp');
  const files = untar(await tarball());
  mkdirSync(OUT, { recursive: true });

  const rows = Math.ceil(ICONS.length / COLUMNS);
  const manifest = { source: `lucide-static@${VERSION}`, license: 'ISC', columns: COLUMNS, rows, atlases: {}, icons: [] };
  for (const [name, file, filled] of ICONS) {
    const svg = files.get(`package/icons/${file}.svg`);
    if (!svg) throw new Error(`lucide-static ${VERSION} has no ${file}.svg`);
    manifest.icons.push({ name, lucide: file, filled, sha256: createHash('sha256').update(svg).digest('hex') });
  }

  for (const size of SIZES) {
    const gutter = size / 8;
    const cell = size + 2 * gutter;
    const width = cell * COLUMNS;
    const height = cell * rows;
    const atlas = Buffer.alloc(width * height * 4);
    for (let i = 3; i < atlas.length; i += 4) {
      atlas[i - 3] = 255; atlas[i - 2] = 255; atlas[i - 1] = 255; atlas[i] = 0;
    }
    for (let index = 0; index < ICONS.length; index++) {
      const [, file, filled] = ICONS[index];
      const svg = prepareSvg(files.get(`package/icons/${file}.svg`), filled);
      const { data, info } = await sharp(Buffer.from(svg), { density: (72 * size) / 24 })
        .resize(size, size, { fit: 'fill' })
        .ensureAlpha()
        .raw()
        .toBuffer({ resolveWithObject: true });
      if (info.width !== size || info.height !== size || info.channels !== 4) throw new Error(`unexpected raster for ${file}`);
      const ox = (index % COLUMNS) * cell + gutter;
      const oy = Math.floor(index / COLUMNS) * cell + gutter;
      for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
          atlas[((oy + y) * width + ox + x) * 4 + 3] = data[(y * size + x) * 4 + 3];
        }
      }
    }
    const png = `icons_${size}.png`;
    await sharp(atlas, { raw: { width, height, channels: 4 } }).png({ compressionLevel: 9 }).toFile(join(OUT, png));
    writeFileSync(join(OUT, `${png}.mcmeta`), JSON.stringify({ texture: { blur: true, clamp: true } }, null, 2) + '\n');
    manifest.atlases[size] = { file: png, width, height, cell, gutter };
    console.log(`${png}  ${width}x${height}`);
  }
  writeFileSync(join(OUT, 'icons.json'), JSON.stringify(manifest, null, 2) + '\n');
  writeFileSync(join(OUT, 'LUCIDE-LICENSE.txt'), files.get('package/LICENSE'));
  console.log(`${ICONS.length} icons`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
