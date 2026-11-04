/**
 * Le conversioni dell'app. Ogni funzione restituisce i file da scaricare.
 * Tutto avviene nel browser: i documenti non escono dal computer.
 */
import { zipSync } from 'fflate';
import * as engine from './engine/docverso-engine.mjs';
import { fromK, toK } from './engine-types';
import { openPdf, OPS } from './pdfjs';
import { browserPlatform, rasterFromFile } from './platform';
import { addScanPage, pdfToWord, translateBuilder, zipParts, type Ocr, type Progress, type TranslateBatch } from './pdf/pdfToWord';

export interface OutFile { name: string; bytes: Uint8Array; type: string }

export const MIME = {
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  pdf: 'application/pdf',
  zip: 'application/zip',
  jpg: 'image/jpeg',
  png: 'image/png',
};

/** Nome senza estensione, ripulito dai caratteri non ammessi nei nomi di file. */
export function baseName(name: string) {
  return (name.replace(/\.[^.]+$/, '').replace(/[\\/:*?"<>|]+/g, '-').trim() || 'Documento').slice(0, 100);
}

const read = async (f: File) => new Uint8Array(await f.arrayBuffer());

export async function pdfToDocx(file: File, opt: { ocr?: Ocr; translate?: TranslateBatch; suffix?: string; progress?: Progress }): Promise<OutFile> {
  const pdf = await openPdf(await read(file));
  try {
    const bytes = await pdfToWord(pdf, { platform: browserPlatform, OPS, ocr: opt.ocr, translate: opt.translate, title: baseName(file.name), progress: opt.progress });
    return { name: `${baseName(file.name)}${opt.suffix ? ` (${opt.suffix})` : ''}.docx`, bytes, type: MIME.docx };
  } finally {
    await pdf.loadingTask.destroy();
  }
}

/** PDF → un'immagine per pagina (JPEG o PNG); più pagine finiscono in un archivio zip. */
export async function pdfToImages(file: File, format: 'jpg' | 'png', dpi: number, progress?: Progress): Promise<OutFile> {
  const pdf = await openPdf(await read(file));
  const name = baseName(file.name);
  try {
    const out: Record<string, Uint8Array> = {};
    const n = pdf.numPages;
    for (let i = 1; i <= n; i++) {
      progress?.(i - 1, n, `Pagina ${i} di ${n}`);
      const page = await pdf.getPage(i);
      const r = await browserPlatform.render(page, dpi / 72);
      const img = r.crop(0, 0, r.width, r.height);
      out[n === 1 ? `${name}.${format}` : `${name}_p${String(i).padStart(2, '0')}.${format}`] =
        format === 'png' ? browserPlatform.png(img) : browserPlatform.jpeg(img, 0.9);
      r.release();
      page.cleanup();
    }
    const names = Object.keys(out);
    if (names.length === 1) return { name: names[0], bytes: out[names[0]], type: MIME[format] };
    return { name: `${name} (${format.toUpperCase()}).zip`, bytes: zipSync(out, { level: 0 }), type: MIME.zip };
  } finally {
    await pdf.loadingTask.destroy();
  }
}

export type PageSize = 'a4' | 'letter' | 'image';
const SIZES: Record<Exclude<PageSize, 'image'>, [number, number]> = { a4: [595.28, 841.89], letter: [612, 792] };

/** Più immagini → un PDF, una per pagina, nell'ordine dato. A4/Letter si girano in orizzontale per le foto larghe. */
export async function imagesToPdf(files: File[], size: PageSize, progress?: Progress): Promise<OutFile> {
  const b = new engine.ImagePdfBuilder();
  for (let i = 0; i < files.length; i++) {
    progress?.(i, files.length, `Immagine ${i + 1} di ${files.length}`);
    const r = await rasterFromFile(files[i], 3000);
    const jpeg = browserPlatform.jpeg(r.crop(0, 0, r.width, r.height), 0.88);
    if (size === 'image') b.addPage(toK(jpeg), r.width, r.height, 0, 0, 0);
    else {
      let [w, h] = SIZES[size];
      if (r.width > r.height) [w, h] = [h, w];
      b.addPage(toK(jpeg), r.width, r.height, w, h, 28);
    }
    r.release();
  }
  const name = baseName(files[0].name) + (files.length > 1 ? ` e altre ${files.length - 1}` : '');
  return { name: `${name}.pdf`, bytes: fromK(b.finish(name)), type: MIME.pdf };
}

/** Immagini → Word: testo riconosciuto e impaginato (con OCR), una pagina per immagine. */
export async function imagesToDocx(files: File[], opt: { ocr?: Ocr; translate?: TranslateBatch; suffix?: string; progress?: Progress }): Promise<OutFile> {
  const builder = new engine.WordBuilder();
  for (let i = 0; i < files.length; i++) {
    opt.progress?.(i, files.length, `Immagine ${i + 1} di ${files.length}: riconoscimento del testo`);
    const r = await rasterFromFile(files[i], 3500);
    await addScanPage(builder, r, browserPlatform, opt.ocr);
    r.release();
  }
  if (opt.translate) await translateBuilder(builder, opt.translate, (d, t) => opt.progress?.(files.length, files.length, `Traduzione: ${d} di ${t} frasi`));
  const name = baseName(files[0].name);
  return { name: `${name}${opt.suffix ? ` (${opt.suffix})` : ''}.docx`, bytes: zipParts(builder.build(name)), type: MIME.docx };
}
