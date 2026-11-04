// Pagine di un PDF nel formato JSON delle app Android (ExactPageJson), per provare il loro motore sul PC:
//   npx tsx scripts/dump-json.ts documento.pdf cartella_uscita
import { mkdirSync, readFileSync, writeFileSync } from 'fs';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import { extractPage } from '../src/pdf/extract';
import { fromK } from '../src/engine-types';
import { nodePlatform } from './node-platform';
const [, , input, out] = process.argv;
mkdirSync(out, { recursive: true });
const root = new URL('../node_modules/pdfjs-dist/', import.meta.url).pathname;
const pdf = await pdfjs.getDocument({ data: new Uint8Array(readFileSync(input)), fontExtraProperties: true, standardFontDataUrl: root + 'standard_fonts/' }).promise;
const r2 = (v: number) => Math.round(v * 100) / 100;
const files: string[] = [];
for (let n = 1; n <= pdf.numPages; n++) {
  const { page: p } = await extractPage(await pdf.getPage(n), pdfjs.OPS as any, { encodeImage: nodePlatform.encodeImage });
  const pics = p.pics.map((x, i) => { const name = `p${n}_img${i}.jpg`; writeFileSync(`${out}/${name}`, fromK(x.jpeg)); return [name, r2(x.l), r2(x.t), r2(x.r), r2(x.b), x.behind]; });
  const json = { w: p.w, h: p.h, fonts: p.fonts.map(f => [f.name, f.bold, f.italic]), g: p.glyphs.map(g => [g.ch, r2(g.x), r2(g.base), r2(g.w), r2(g.size), g.font, g.color]),
    fills: p.fills.map(f => [r2(f.l), r2(f.t), r2(f.r), r2(f.b), f.color ?? 0]), art: p.art.map(f => [r2(f.l), r2(f.t), r2(f.r), r2(f.b)]), pics };
  const f = `${out}/p${n}.json`; writeFileSync(f, JSON.stringify(json)); files.push(f);
}
console.log(files.join(','));
