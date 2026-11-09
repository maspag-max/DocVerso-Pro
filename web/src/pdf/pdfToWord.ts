/**
 * PDF → Word fedele. Pagine digitali: ricostruzione esatta (ExactLayout del motore Kotlin).
 * Pagine scansionate o con testo illeggibile: riconoscimento del testo (OCR) se disponibile,
 * altrimenti la pagina come immagine.
 */
import type { PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist';
import { zipSync } from 'fflate';
import { extractPage, type PdfImage } from './extract';
import { fromK, toK, type JsGlyph, type JsWord } from '../engine-types';
import { padWords } from '../ocr-words';
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
  /** Raster da pixel 0xRRGGBB (pagina raddrizzata). */
  fromPixels(width: number, height: number, px: Int32Array): Raster;
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
  /** Ricostruzione delle pagine scansionate: esatta (predefinita) o a flusso. */
  scanMode?: 'exact' | 'flow';
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
      // PDF scansionato e già reso ricercabile (OCRmyPDF, PDF_Scanner, Acrobat…): si usa il suo testo,
      // riconosciuto sull'immagine originale, invece di rifare l'OCR sull'immagine ridisegnata
      const layer = ex.hidden.filter(g => g.ch.trim()).length >= 20 ? layerWords(ex.hidden, raster, OCR_SCALE) : null;
      await addScanPage(builder, raster, opt.platform, opt.ocr, opt.scanMode, layer);
      raster.release();
    }
    page.cleanup();
  }
  if (opt.translate) await translateBuilder(builder, opt.translate, (d, t) => opt.progress?.(total, total, `Traduzione: ${d} di ${t} frasi`));
  opt.progress?.(total, total, 'Scrittura del file Word');
  return zipParts(builder.build(opt.title ?? 'Documento'));
}

/**
 * Pagina da immagine (scansione o foto). Raddrizzata, poi riconoscimento del testo e ricostruzione:
 * "exact" = come un PDF digitale (tabelle dalle linee, testo in posizione); "flow" = impaginazione a flusso
 * di TradScan (più adatta alle foto storte o curve). Senza OCR o senza testo: la pagina come immagine.
 */
export async function addScanPage(builder: engine.WordBuilder, raster: Raster, platform: Platform, ocr?: Ocr, mode: 'exact' | 'flow' = 'exact', given?: JsWord[] | null) {
  let r = raster;
  let px = raster.pixels();
  // Con il testo già riconosciuto nel PDF la pagina non si raddrizza: le posizioni delle sue parole sono su
  // questa immagine. L'OCR di DocVerso legge di solito meglio; dal testo del PDF si prendono le parole che
  // l'OCR ha saltato (trattini degli elenchi, parole corte, numeri)
  if (given?.length) {
    const mine = ocr ? await ocr(r) : null;
    const words = mine?.length ? mergeWords(mine, given) : given;
    const crop = (l: number, t: number, w: number, h: number) => toK(platform.jpeg(r.crop(l, t, w, h), 0.88));
    if (mode === 'exact') builder.addScanExactPage(words as any, r.width, r.height, px, crop);
    else builder.addOcrPage(padWords(words) as any, r.width, r.height, px, crop);
    return;
  }
  if (ocr && mode === 'exact') {
    const angle = engine.skewAngle(r.width, r.height, px);
    // Ruotando dell’angolo opposto la pagina si raddrizza
    if (angle !== 0) { px = engine.rotatePixels(r.width, r.height, px, -angle); r = platform.fromPixels(r.width, r.height, px); }
  }
  const words = ocr ? await ocr(r) : null;
  if (words && words.length > 0) {
    const crop = (l: number, t: number, w: number, h: number) => toK(platform.jpeg(r.crop(l, t, w, h), 0.88));
    if (mode === 'exact') builder.addScanExactPage(words as any, r.width, r.height, px, crop);
    else builder.addOcrPage(padWords(words) as any, r.width, r.height, px, crop);
  } else {
    builder.addImagePage(toK(platform.jpeg(r.crop(0, 0, r.width, r.height), 0.85)), r.width, r.height, 1);
  }
  if (r !== raster) r.release();
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

/**
 * Parole dallo strato di testo invisibile, in pixel dell'immagine ridisegnata ([scale] pixel per punto).
 * Il riquadro di ogni parola si stringe sull'inchiostro vero (altezza delle lettere, per dimensione e grassetto).
 */
export function layerWords(hidden: JsGlyph[], raster: Raster, scale: number): JsWord[] {
  const gs = hidden.filter(g => g.size > 1).sort((a, b) => a.base - b.base || a.x - b.x);
  // Righe: stessa linea di base
  const rows: JsGlyph[][] = [];
  for (const g of gs) {
    const row = rows.find(r => Math.abs(r[0].base - g.base) < 0.3 * Math.min(r[0].size, g.size));
    if (row) row.push(g); else rows.push([g]);
  }
  rows.sort((a, b) => a[0].base - b[0].base);
  const W = raster.width, H = raster.height;
  const px = raster.pixels();
  const dark = (x: number, y: number) => { const c = px[y * W + x]; return ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10 < 150; };
  const out: JsWord[] = [];
  rows.forEach((row, line) => {
    row.sort((a, b) => a.x - b.x);
    let cur: JsGlyph[] = [];
    const flush = () => {
      const vis = cur.filter(g => g.ch.trim());
      cur = [];
      if (!vis.length) return;
      const size = Math.max(...vis.map(g => g.size));
      const base = vis.map(g => g.base).sort((a, b) => a - b)[vis.length >> 1];
      let l = Math.max(0, Math.floor(vis[0].x * scale)), r = Math.min(W - 1, Math.ceil((vis[vis.length - 1].x + vis[vis.length - 1].w) * scale));
      let t = Math.max(0, Math.floor((base - size * 0.85) * scale)), b = Math.min(H - 1, Math.ceil((base + size * 0.3) * scale));
      // Inchiostro dentro il riquadro: righe e colonne con pixel scuri
      let it = -1, ib = -1, il = -1, ir = -1;
      for (let y = t; y <= b; y++) for (let x = l; x <= r; x++) if (dark(x, y)) {
        if (it < 0) it = y; ib = y; if (il < 0 || x < il) il = x; if (x > ir) ir = x;
      }
      if (it >= 0 && ib - it >= 2) { t = it; b = ib; l = il; r = ir; }
      out.push({ text: vis.map(g => g.ch).join(''), l, t, r: r + 1, b: b + 1, line, base: base * scale, confidence: 95 } as JsWord);
    };
    for (const g of row) {
      const prev = cur[cur.length - 1];
      if (!g.ch.trim()) { flush(); continue; }
      if (prev && g.x - (prev.x + prev.w) > 0.15 * g.size) flush();
      cur.push(g);
    }
    flush();
  });
  return out;
}

/** Parole dell'OCR più quelle del testo del PDF che non si sovrappongono a nessuna parola dell'OCR. */
function mergeWords(ocr: JsWord[], layer: JsWord[]): JsWord[] {
  const out = [...ocr];
  const lineOf = (w: JsWord) => {
    // Riga dell'OCR alla stessa altezza (per mettere la parola aggiunta sulla riga giusta)
    const cy = (w.t + w.b) / 2;
    const near = ocr.filter(o => cy > o.t && cy < o.b).sort((a, b) => Math.abs((a.t + a.b) / 2 - cy) - Math.abs((b.t + b.b) / 2 - cy))[0];
    return near ? near.line : -1;
  };
  let extraLine = Math.max(0, ...ocr.map(o => o.line)) + 1;
  for (const w of layer) {
    const ix = (o: JsWord) => Math.max(0, Math.min(o.r, w.r) - Math.max(o.l, w.l)) * Math.max(0, Math.min(o.b, w.b) - Math.max(o.t, w.t));
    const area = Math.max(1, (w.r - w.l) * (w.b - w.t));
    if (ocr.some(o => ix(o) > 0.15 * area)) continue;
    const line = lineOf(w);
    out.push({ ...w, line: line >= 0 ? line : extraLine++, confidence: 80 } as JsWord);
  }
  return out;
}