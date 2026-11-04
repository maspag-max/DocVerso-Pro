// Parole dell'OCR (dopo il filtro del rumore) che contengono il testo cercato, con riquadro e confidenza
import { readFileSync } from 'fs';
import { createWorker } from 'tesseract.js';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import { prepareForOcr, wordsFrom } from '../src/ocr-words';
import { nodePlatform } from './node-platform';
const im = await loadImage(readFileSync(process.argv[2]));
const c = createCanvas(im.width, im.height); const ctx = c.getContext('2d'); ctx.drawImage(im, 0, 0);
const img = prepareForOcr({ data: ctx.getImageData(0, 0, c.width, c.height).data as any, width: c.width, height: c.height });
const w = await createWorker(['ita', 'eng'], 1, { langPath: new URL('../public/tesseract/lang', import.meta.url).pathname, gzip: true, cachePath: '/tmp/docverso-tess' });
const res = await w.recognize(Buffer.from(nodePlatform.png(img)), {}, { blocks: true, text: false });
const raw = (res.data.blocks ?? []).flatMap(b => b.paragraphs.flatMap(p => p.lines.flatMap(l => l.words)));
const want = process.argv.slice(3);
for (const x of raw) if (want.some(s => x.text.includes(s))) console.log('grezza', JSON.stringify(x.text), Math.round(x.confidence), x.bbox);
console.log('dopo il filtro:', wordsFrom(res.data.blocks as any).filter(x => want.some(s => x.text.includes(s))).map(x => x.text));
await w.terminate();
