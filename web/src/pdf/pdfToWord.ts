/**
 * PDF → Word fedele. Pagine digitali: ricostruzione esatta (ExactLayout del motore Kotlin).
 * Pagine scansionate o con testo illeggibile: riconoscimento del testo (OCR) se disponibile,
 * altrimenti la pagina come immagine.
 */
import type { PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist';
import { zipSync } from 'fflate';
import { extractPage, type PdfImage } from './extract';
import { fromK, toK, type JsWord } from '../engine-types';
import * as engine from '../engine/docverso-engine.mjs';

/** Pixel RGBA (come ImageData). */
export interface Rgba { data: Uint8ClampedArray; width: number; height: number }

/** Pagina disegnata in pixel, da cui si ritagliano disegni, codici a barre e zone immagine. */
export interface Raster {
  width: number;
  height: number;
  crop(x: number, y: number, w: number, h: number): Rgba;
  /** Pixel 0xRRGGBB riga per riga (per l'OCR). */
  pixels(): Int32Array;
  release(): void;
}

/** Ciò che cambia fra browser e Node (canvas, codifica immagini). Le codifiche sono sincrone. */
export interface Platform {
  encodeImage(img: PdfImage): Promise<Uint8Array | null>;
  render(page: PDFPageProxy, scale: number): Promise<Raster>;
  png(img: Rgba): Uint8Array;
  jpeg(img: Rgba, quality: number): Uint8Array;
}

/**
 * Cancella i riquadri dati (pixel del ritaglio) dipingendoli con il colore più comune del loro bordo:
 * le lettere spariscono dal disegno (logo, fascia colorata) e restano solo come testo vero.
 */
export function eraseRects(img: Rgba, rects: number[][]) {
  const { data, width: w, height: h } = img;
  for (const [l0, t0, r0, b0] of rects) {
    const l = Math.max(0, Math.floor(l0)), t = Math.max(0, Math.floor(t0));
    const r = Math.min(w - 1, Math.ceil(r0)), b = Math.min(h - 1, Math.ceil(b0));
    if (r < l || b < t) continue;
    const counts = new Map<number, number>();
    const take = (x: number, y: number) => {
      if (x < 0 || y < 0 || x >= w || y >= h) return;
      const i = (y * w + x) * 4;
      const key = ((data[i] >> 3) << 10) | ((data[i + 1] >> 3) << 5) | (data[i + 2] >> 3);
      counts.set(key, (counts.get(key) ?? 0) + 1);
    };
    for (let x = l - 2; x <= r + 2; x++) { take(x, t - 2); take(x, b + 2); }
    for (let y = t - 2; y <= b + 2; y++) { take(l - 2, y); take(r + 2, y); }
    let best = -1, n = -1;
    for (const [k, c] of counts) if (c > n) { best = k; n = c; }
    if (best < 0) continue;
    const cr = ((best >> 10) & 31) * 8 + 4, cg = ((best >> 5) & 31) * 8 + 4, cb = (best & 31) * 8 + 4;
    for (let y = t; y <= b; y++) for (let x = l; x <= r; x++) {
      const i = (y * w + x) * 4; data[i] = cr; data[i + 1] = cg; data[i + 2] = cb; data[i + 3] = 255;
    }
  }
}

/** Riconoscimento del testo di una pagina disegnata: parole in pixel con il numero di riga. */
export type Ocr = (r: Raster) => Promise<JsWord[] | null>;

export interface Progress { (done: number, total: number, message: string): void }

/** Traduttore di elenchi di frasi (Bergamot o altro). */
export type TranslateBatch = (texts: string[], onProgress?: (done: number, total: number) => void) => Promise<string[]>;

export interface PdfToWordOptions {
  platform: Platform;
  OPS: Record<string, number>;
  ocr?: Ocr;
  translate?: TranslateBatch;
  title?: string;
  progress?: Progress;
  maxPages?: number;
}

/** Risoluzione dei ritagli: 3 = circa 216 dpi, abbastanza nitidi per i codici a barre. */
const CROP_SCALE = 3;
const OCR_SCALE = 300 / 72;

export async function pdfToWord(pdf: PDFDocumentProxy, opt: PdfToWordOptions): Promise<Uint8Array> {
  const total = Math.min(pdf.numPages, opt.maxPages ?? 300);
  const builder = new engine.WordBuilder();
  for (let n = 1; n <= total; n++) {
    opt.progress?.(n - 1, total, `Pagina ${n} di ${total}: lettura`);
    const page = await pdf.getPage(n);
    const ex = await extractPage(page, opt.OPS, { encodeImage: opt.platform.encodeImage });
    const scanned = ex.visibleChars < 3 || engine.looksGarbled(ex.page as any, ex.missing);
    if (!scanned) {
      const raster = await opt.platform.render(page, CROP_SCALE);
      const s = raster.width / ex.page.w;
      const P = opt.platform;
      const box = (l: number, t: number, r: number, b: number) => {
        const x = Math.max(0, Math.floor(l * s)), y = Math.max(0, Math.floor(t * s));
        const w = Math.min(raster.width - x, Math.ceil((r - l) * s)), h = Math.min(raster.height - y, Math.ceil((b - t) * s));
        return w < 1 || h < 1 ? null : { x, y, w, h };
      };
      builder.addExactPage(ex.page as any, (l, t, r, b) => {
        const c = box(l, t, r, b);
        return c ? toK(P.png(raster.crop(c.x, c.y, c.w, c.h))) : null;
      }, (l, t, r, b, erase) => {
        const c = box(l, t, r, b);
        if (!c) return null;
        const img = raster.crop(c.x, c.y, c.w, c.h);
        eraseRects(img, erase.map((e: ArrayLike<number>) => [e[0] * s - c.x, e[1] * s - c.y, e[2] * s - c.x, e[3] * s - c.y]));
        return toK(P.png(img));
      });
      raster.release();
    } else {
      opt.progress?.(n - 1, total, `Pagina ${n} di ${total}: riconoscimento del testo`);
      const raster = await opt.platform.render(page, OCR_SCALE);
      await addScanPage(builder, raster, opt.platform, opt.ocr);
      raster.release();
    }
    page.cleanup();
  }
  if (opt.translate) await translateBuilder(builder, opt.translate, (d, t) => opt.progress?.(total, total, `Traduzione: ${d} di ${t} frasi`));
  opt.progress?.(total, total, 'Scrittura del file Word');
  return zipParts(builder.build(opt.title ?? 'Documento'));
}

/** Pagina da immagine: OCR e impaginazione, oppure (senza OCR o senza testo) la pagina come immagine. */
export async function addScanPage(builder: engine.WordBuilder, raster: Raster, platform: Platform, ocr?: Ocr) {
  const words = ocr ? await ocr(raster) : null;
  if (words && words.length > 0) {
    builder.addOcrPage(words as any, raster.width, raster.height, raster.pixels(), (l, t, w, h) => toK(platform.jpeg(raster.crop(l, t, w, h), 0.88)));
  } else {
    builder.addImagePage(toK(platform.jpeg(raster.crop(0, 0, raster.width, raster.height), 0.85)), raster.width, raster.height, 1);
  }
}

export async function translateBuilder(builder: engine.WordBuilder, translate: TranslateBatch, progress?: (done: number, total: number) => void) {
  const req = builder.translationRequests();
  if (req.length === 0) return;
  const out = await translate(req, progress);
  req.forEach((s, i) => { if (out[i]) builder.setTranslation(s, out[i]); });
  builder.applyTranslation();
}

export function zipParts(parts: engine.DocPart[]): Uint8Array {
  const files: Record<string, Uint8Array> = {};
  for (const p of parts) files[p.name] = fromK(p.data);
  return zipSync(files, { level: 6 });
}
