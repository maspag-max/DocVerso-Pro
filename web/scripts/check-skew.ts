// Verifica del raddrizzamento: l'inclinazione residua dopo la rotazione deve essere ~0
import { readFileSync } from 'fs';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import * as engine from '../src/engine/docverso-engine.mjs';
const im = await loadImage(readFileSync(process.argv[2]));
const c = createCanvas(im.width, im.height); const ctx = c.getContext('2d'); ctx.drawImage(im, 0, 0);
const d = ctx.getImageData(0, 0, c.width, c.height).data;
const px = new Int32Array(c.width * c.height);
for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
const a = engine.skewAngle(c.width, c.height, px);
for (const sign of [1, -1]) {
  const r = engine.rotatePixels(c.width, c.height, px, a * sign);
  console.log(`stimata ${a.toFixed(2)}°, ruotata di ${(a * sign).toFixed(2)}° → residua ${engine.skewAngle(c.width, c.height, r).toFixed(2)}°`);
}
