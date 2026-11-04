// Tempi dei passaggi dell'analisi di una scansione (per trovare quelli lenti)
import { readFileSync } from 'fs';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import * as engine from '../src/engine/docverso-engine.mjs';
const im = await loadImage(readFileSync(process.argv[2]));
const c = createCanvas(im.width, im.height); const ctx = c.getContext('2d'); ctx.drawImage(im, 0, 0);
const d = ctx.getImageData(0, 0, c.width, c.height).data;
const px = new Int32Array(c.width * c.height);
for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
let t = Date.now();
const a = engine.skewAngle(c.width, c.height, px); console.log('inclinazione', a, Date.now() - t, 'ms'); t = Date.now();
const r = engine.rotatePixels(c.width, c.height, px, a); console.log('rotazione', Date.now() - t, 'ms'); t = Date.now();
console.log(engine.debugScanSteps(c.width, c.height, r));
const b = new engine.WordBuilder();
b.addScanExactPage([], c.width, c.height, r, () => null); console.log('pagina senza parole', Date.now() - t, 'ms');
