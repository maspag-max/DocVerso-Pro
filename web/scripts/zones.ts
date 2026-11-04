// Zone da rileggere trovate senza parole (prova del secondo passaggio dell'OCR)
import { readFileSync } from 'fs';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import * as engine from '../src/engine/docverso-engine.mjs';
const im = await loadImage(readFileSync(process.argv[2]));
const c = createCanvas(im.width, im.height); const ctx = c.getContext('2d'); ctx.drawImage(im, 0, 0);
const d = ctx.getImageData(0, 0, c.width, c.height).data;
const px = new Int32Array(c.width * c.height);
for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
console.log(engine.missedTextZones(c.width, c.height, px, []).map(z => Array.from(z).join(',')).slice(0, 12).join(' | '));
