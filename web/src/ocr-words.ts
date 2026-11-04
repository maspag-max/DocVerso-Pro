/** Risultato di Tesseract → parole con riquadro e numero di riga, come le vuole il LayoutAnalyzer. */
import type { JsWord } from './engine-types';
import * as engine from './engine/docverso-engine.mjs';

interface BBox { x0: number; y0: number; x1: number; y1: number }
interface TWord { text: string; confidence: number; bbox: BBox }
interface TLine { words: TWord[]; baseline?: { x0: number; y0: number; x1: number; y1: number; has_baseline?: boolean } }
interface TPar { lines: TLine[] }
interface TBlock { paragraphs: TPar[] }

/** Lingue dell'OCR incluse nell'app (codice Tesseract per codice ISO). */
export const OCR_LANGS: Record<string, string> = { it: 'ita', en: 'eng', fr: 'fra', de: 'deu', es: 'spa', pt: 'por' };

/** Lingue da caricare: quella del documento se nota, sempre con italiano e inglese. */
export function ocrLangs(docLang: string | undefined): string {
  const set = new Set(['ita', 'eng']);
  const t = docLang ? OCR_LANGS[docLang] : undefined;
  if (t) set.add(t);
  return [...set].join('+');
}

/** Segni che Tesseract legge sulle linee delle tabelle e sulle macchie: da soli non sono testo. */
const NOISE = /^[|¦\[\]{}()!:;,.'"`~_\-—–=+*\\/]+$/;

/** Parole riconosciute (pixel), con la linea di base della loro riga. Via il rumore delle linee e delle macchie. */
export function wordsFrom(blocks: TBlock[] | null | undefined, minConfidence = 35): JsWord[] {
  const out: JsWord[] = [];
  (blocks ?? []).forEach((b, bi) => b.paragraphs.forEach((p, pi) => p.lines.forEach((l, li) => {
    const line = bi * 10000 + pi * 100 + li;
    const bl = l.baseline;
    for (const w of l.words) {
      // Barre verticali attaccate alla parola (bordo di cella letto come testo); virgolette spurie davanti a una
      // parola letta con poca sicurezza (il bordo sinistro della cella attaccato alla prima lettera)
      let text = w.text.trim().replace(/^[|¦]+|[|¦]+$/g, '').trim();
      if (w.confidence < 75 && /^["'‘’“”`]\p{Lu}\p{Ll}{2,}/u.test(text) && !/["'‘’“”]$/.test(text)) text = text.slice(1);
      if (!text || w.confidence < minConfidence) continue;
      if (NOISE.test(text) && (text.length > 1 ? w.confidence < 80 : !/^[-–—]$/.test(text) || w.confidence < 60)) continue;
      const cx = (w.bbox.x0 + w.bbox.x1) / 2;
      const base = bl && bl.x1 !== bl.x0 ? bl.y0 + (bl.y1 - bl.y0) * (cx - bl.x0) / (bl.x1 - bl.x0) : null;
      out.push({ text, l: w.bbox.x0, t: w.bbox.y0, r: w.bbox.x1, b: w.bbox.y1, line, confidence: w.confidence, base: base !== null && base > w.bbox.y0 && base <= w.bbox.y1 + 2 ? base : null });
    }
  })));
  return out;
}

/**
 * Riquadri allargati in alto e in basso di [pad] volte la loro altezza: l'impaginazione "a flusso" (da TradScan)
 * è tarata sui riquadri del riconoscimento di Google, più alti di quelli di Tesseract.
 */
export const padWords = (ws: JsWord[], pad = 0.25): JsWord[] =>
  ws.map(w => { const h = w.b - w.t; return { ...w, t: w.t - h * pad, b: w.b + h * pad }; });

/**
 * Immagine per l'OCR: scala di grigi e linee lunghe (bordi di tabella, righe da compilare) tolte, perché
 * Tesseract le leggerebbe come lettere e sbaglierebbe le righe vicine. Restituisce una copia.
 */
export function prepareForOcr(img: { data: Uint8ClampedArray; width: number; height: number }) {
  const { width: w, height: h, data } = img;
  const g = new Uint8ClampedArray(w * h);
  for (let i = 0; i < w * h; i++) g[i] = (data[i * 4] * 77 + data[i * 4 + 1] * 150 + data[i * 4 + 2] * 29) >> 8;
  // Soglia: un po' sotto la luminosità tipica della carta
  const hist = new Uint32Array(256); for (let i = 0; i < g.length; i += 7) hist[g[i]]++;
  let acc = 0, paper = 255; const tot = Math.ceil(g.length / 7);
  for (let v = 255; v >= 0; v--) { acc += hist[v]; if (acc > tot * 0.5) { paper = v; break; } }
  const thr = Math.min(paper - 60, 170);
  const dark = (i: number) => g[i] < thr;
  const erase = new Uint8Array(w * h);
  const minH = Math.max(40, Math.round(w * 0.06)), minV = Math.max(30, Math.round(h * 0.025));
  // Tratti orizzontali lunghi
  for (let y = 0; y < h; y++) {
    let x = 0;
    while (x < w) {
      if (!dark(y * w + x)) { x++; continue; }
      let e = x; while (e + 1 < w && dark(y * w + e + 1)) e++;
      if (e - x + 1 >= minH) for (let k = x; k <= e; k++) erase[y * w + k] = 1;
      x = e + 1;
    }
  }
  // Tratti verticali lunghi
  for (let x = 0; x < w; x++) {
    let y = 0;
    while (y < h) {
      if (!dark(y * w + x)) { y++; continue; }
      let e = y; while (e + 1 < h && dark((e + 1) * w + x)) e++;
      if (e - y + 1 >= minV) for (let k = y; k <= e; k++) erase[k * w + x] = 1;
      y = e + 1;
    }
  }
  const out = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    // Linee tolte, sfondi colorati schiariti (le intestazioni di tabella azzurre confondono l'OCR)
    let v = erase[i] ? paper : g[i];
    if (!erase[i] && v >= thr) v = Math.max(v, Math.min(255, paper));
    out[i * 4] = out[i * 4 + 1] = out[i * 4 + 2] = v; out[i * 4 + 3] = 255;
  }
  return { data: out, width: w, height: h };
}

/**
 * Riconoscimento di una pagina con un secondo passaggio: le zone con testo che Tesseract ha saltato
 * vengono rilette come blocco singolo. [recognize] legge la pagina intera (zona null) o una zona.
 */
export async function recognizeWithRescue(
  width: number, height: number, pixels: Int32Array,
  recognize: (zone: { left: number; top: number; width: number; height: number } | null) => Promise<TBlock[] | null | undefined>,
): Promise<JsWord[]> {
  const words = wordsFrom(await recognize(null));
  const zones = engine.missedTextZones(width, height, pixels, words as any);
  if ((globalThis as any).DOCVERSO_DEBUG) console.error('parole in alto:', words.filter(w => w.t < 240).map(w => `${w.text}@${Math.round(w.l)},${Math.round(w.t)}-${Math.round(w.r)},${Math.round(w.b)}`).join(' '));
  if ((globalThis as any).DOCVERSO_DEBUG) console.error('zone da rileggere:', zones.map(z => Array.from(z).join(',')).join(' | '));
  let k = 0;
  for (const z of zones) {
    const pad = 6;
    const zone = { left: Math.max(0, z[0] - pad), top: Math.max(0, z[1] - pad), width: Math.min(width - Math.max(0, z[0] - pad), z[2] + 2 * pad), height: Math.min(height - Math.max(0, z[1] - pad), z[3] + 2 * pad) };
    const extra = wordsFrom(await recognize(zone), 60);
    k++;
    for (const w of extra) words.push({ ...w, line: 9000000 + k * 1000 + (w.line % 1000) });
  }
  return words;
}
