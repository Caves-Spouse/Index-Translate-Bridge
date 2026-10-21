#!/usr/bin/env node
/**
 * 生成应用图标（纯手写 PNG，不依赖任何图形库）：
 *   android/res/drawable/ic_launcher.png   192x192
 * 用法: node tools/make_icon.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZE = 192;
const RADIUS = 40;

function px(x, y) {
  // 圆角矩形遮罩
  const cx = Math.min(Math.max(x, RADIUS), SIZE - RADIUS);
  const cy = Math.min(Math.max(y, RADIUS), SIZE - RADIUS);
  const dx = x - cx;
  const dy = y - cy;
  const insideBg = x >= 0 && y >= 0 && x < SIZE && y < SIZE && dx * dx + dy * dy <= RADIUS * RADIUS;
  if (!insideBg) return [0, 0, 0, 0];

  // 背景竖向渐变 #1E88E5 -> #0D47A1
  const t = y / (SIZE - 1);
  let r = Math.round(0x1e + (0x0d - 0x1e) * t);
  let g = Math.round(0x88 + (0x47 - 0x88) * t);
  let b = Math.round(0xe5 + (0xa1 - 0xe5) * t);

  const inBar1 = y >= 74 && y <= 90 && x >= 52 && x <= 138;
  const inHead1 = x >= 130 && x <= 156 && Math.abs(y - 82) <= (156 - x) * 0.62;
  const inBar2 = y >= 102 && y <= 118 && x >= 54 && x <= 140;
  const inHead2 = x >= 36 && x <= 62 && Math.abs(y - 110) <= (x - 36) * 0.62;

  if (inBar1 || inHead1) {
    r = 255;
    g = 255;
    b = 255;
  } else if (inBar2 || inHead2) {
    // 第二条箭头半透明白，形成层次
    r = Math.round(r * 0.45 + 255 * 0.55);
    g = Math.round(g * 0.45 + 255 * 0.55);
    b = Math.round(b * 0.45 + 255 * 0.55);
  }
  return [r, g, b, 255];
}

function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c;
    }
    return t;
  })());
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
let o = 0;
for (let y = 0; y < SIZE; y++) {
  raw[o++] = 0; // filter type: none
  for (let x = 0; x < SIZE; x++) {
    const [r, g, b, a] = px(x, y);
    raw[o++] = r;
    raw[o++] = g;
    raw[o++] = b;
    raw[o++] = a;
  }
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type RGBA
ihdr[10] = 0;
ihdr[11] = 0;
ihdr[12] = 0;

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

const out = path.join(__dirname, '..', 'android', 'res', 'drawable', 'ic_launcher.png');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, png);
console.log(`icon -> ${out} (${png.length} bytes)`);
