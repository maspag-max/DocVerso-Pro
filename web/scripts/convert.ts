/**
 * Prova su documenti veri, dalla riga di comando (stesso codice della web app):
 *   npx tsx scripts/convert.ts originale.pdf uscita.docx [--fake-translate]
 * --fake-translate allunga ogni frase di circa il 25%, come farebbe una traduzione.
 */
import { readFileSync, writeFileSync } from 'fs';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { pdfToWord } from '../src/pdf/pdfToWord';
import { nodePlatform } from './node-platform';

const [, , input, output, ...flags] = process.argv;
const root = new URL('../node_modules/pdfjs-dist/', import.meta.url).pathname;
const pdf = await pdfjs.getDocument({
  data: new Uint8Array(readFileSync(input)),
  fontExtraProperties: true,
  standardFontDataUrl: root + 'standard_fonts/',
  cMapUrl: root + 'cmaps/', cMapPacked: true,
}).promise;
const fake = flags.includes('--fake-translate')
  ? async (texts: string[]) => texts.map(t => t + ' ' + t.slice(0, Math.floor(t.length / 4)).trim())
  : undefined;
const t0 = Date.now();
const docx = await pdfToWord(pdf, {
  platform: nodePlatform, OPS: pdfjs.OPS as any, translate: fake, title: 'Prova',
  progress: (d, t, m) => process.stderr.write(`\r${m}                    `),
});
writeFileSync(output, docx);
console.error(`\n${output}: ${docx.length} byte in ${Date.now() - t0} ms`);
