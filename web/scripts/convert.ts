/**
 * Prova su documenti veri, dalla riga di comando (stesso codice della web app):
 *   npx tsx scripts/convert.ts originale.pdf|immagine.jpg uscita.docx [--fake-translate] [--no-ocr]
 * --fake-translate allunga ogni frase di circa il 25%, come farebbe una traduzione.
 */
import { readFileSync, writeFileSync } from 'fs';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { createWorker } from 'tesseract.js';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import { addScanPage, pdfToWord, translateBuilder, zipParts, type Ocr, type Raster, type TranslateBatch } from '../src/pdf/pdfToWord';
import { ocrLangs, prepareForOcr, recognizeWithRescue } from '../src/ocr-words';
import * as engine from '../src/engine/docverso-engine.mjs';
import { nodePlatform } from './node-platform';

const [, , input, output, ...flags] = process.argv;
const root = new URL('../node_modules/pdfjs-dist/', import.meta.url).pathname;
const fake: TranslateBatch | undefined = flags.includes('--fake-translate')
  ? async texts => texts.map(t => t + ' ' + t.slice(0, Math.floor(t.length / 4)).trim())
  : undefined;

let ocr: Ocr | undefined;
let worker: Awaited<ReturnType<typeof createWorker>> | undefined;
if (!flags.includes('--no-ocr')) {
  ocr = async (r: Raster) => {
    worker ??= await createWorker(ocrLangs('it').split('+'), 1, {
      langPath: new URL('../public/tesseract/lang', import.meta.url).pathname, gzip: true, cachePath: '/tmp/docverso-tess',
    });
    const img = prepareForOcr(r.crop(0, 0, r.width, r.height));
    if (flags.includes('--save-ocr-input')) writeFileSync(output + '.ocr.png', nodePlatform.png(img));
    const png = Buffer.from(nodePlatform.png(img));
    const w = worker;
    const ws = await recognizeWithRescue(r.width, r.height, r.pixels(), async zone => {
      await w.setParameters({ tessedit_pageseg_mode: (zone ? '6' : '3') as any });
      const res = await w.recognize(png, zone ? { rectangle: zone } : {}, { blocks: true, text: false });
      return res.data.blocks as any;
    });
    if (flags.includes('--debug')) console.error('OCR:', ws.filter(x => x.line >= 9000000).map(x => x.text).join(' '), '| grandi:', ws.filter(x => x.b - x.t > 60 || x.r - x.l > 600).map(x => `${x.text} ${Math.round(x.l)},${Math.round(x.t)}-${Math.round(x.r)},${Math.round(x.b)}`).join(' ; '));
    return ws;
  };
}

if (flags.includes('--debug')) { engine.debugScanLog(true); (globalThis as any).DOCVERSO_DEBUG = true; }
const progress = (_d: number, _t: number, m: string) => process.stderr.write(`\r${m}                    `);
const t0 = Date.now();
let docx: Uint8Array;
if (/\.pdf$/i.test(input)) {
  const pdf = await pdfjs.getDocument({
    data: new Uint8Array(readFileSync(input)), fontExtraProperties: true,
    standardFontDataUrl: root + 'standard_fonts/', cMapUrl: root + 'cmaps/', cMapPacked: true,
  }).promise;
  docx = await pdfToWord(pdf, { platform: nodePlatform, OPS: pdfjs.OPS as any, ocr, translate: fake, title: 'Prova', progress, scanMode: flags.includes('--flow') ? 'flow' : 'exact' });
} else {
  // Immagine: come "Immagini → Word" della web app
  const im = await loadImage(readFileSync(input));
  const c = createCanvas(im.width, im.height); const ctx = c.getContext('2d'); ctx.drawImage(im, 0, 0);
  const raster: Raster = {
    width: c.width, height: c.height,
    crop: (x, y, w, h) => { const d = ctx.getImageData(x, y, w, h); return { data: d.data as any, width: w, height: h }; },
    pixels: () => { const d = ctx.getImageData(0, 0, c.width, c.height).data; const px = new Int32Array(c.width * c.height); for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2]; return px; },
    release: () => {},
  };
  const b = new engine.WordBuilder();
  progress(0, 1, 'Riconoscimento del testo');
  await addScanPage(b, raster, nodePlatform, ocr, flags.includes('--flow') ? 'flow' : 'exact');
  if (fake) await translateBuilder(b, fake);
  docx = zipParts(b.build('Prova'));
}
await worker?.terminate();
if (flags.includes('--debug')) console.error(engine.debugScanLog(false));
writeFileSync(output, docx);
console.error(`\n${output}: ${docx.length} byte in ${Date.now() - t0} ms`);
