/**
 * Legge da un PDF digitale tutto ciò che serve a riprodurlo fedelmente in Word: ogni lettera con
 * posizione, carattere, dimensione e colore; linee e riquadri disegnati; disegni; immagini.
 * Equivalente del PdfContentExtractor di TradScan (PdfBox), ma sulla lista dei comandi di pdf.js.
 * Misure in punti, origine in alto a sinistra della pagina (rotazione compresa).
 */
import type { PDFPageProxy } from 'pdfjs-dist';
import { toK, type JsBox, type JsFont, type JsGlyph, type JsPage, type JsPic } from '../engine-types';

type M = [number, number, number, number, number, number];

const MAX_GLYPHS = 60000;
const MAX_SHAPES = 20000;
const MAX_IMAGES = 30;

const mul = (m: M, n: M): M => [
  m[0] * n[0] + m[1] * n[2], m[0] * n[1] + m[1] * n[3],
  m[2] * n[0] + m[3] * n[2], m[2] * n[1] + m[3] * n[3],
  m[4] * n[0] + m[5] * n[2] + n[4], m[4] * n[1] + m[5] * n[3] + n[5],
];
const apply = (m: M, x: number, y: number): [number, number] => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];

function hexColor(v: unknown): number | null {
  if (typeof v === 'string' && /^#[0-9a-f]{6}$/i.test(v)) return parseInt(v.slice(1), 16);
  if (Array.isArray(v) && v.length >= 3 && v.every(x => typeof x === 'number'))
    return ((Math.round(v[0]) & 255) << 16) | ((Math.round(v[1]) & 255) << 8) | (Math.round(v[2]) & 255);
  return null;
}

/**
 * Caratteri "simbolo" dei font Symbol, Wingdings e OpenSymbol (Word e LibreOffice li usano per i pallini
 * degli elenchi e per le caselle): il PDF li dà nella zona d'uso privato (U+F0xx), illeggibili fuori
 * da quel font. Si sostituiscono con i caratteri Unicode equivalenti.
 */
const SYMBOLS: Record<number, string> = {
  0xf0b7: '•', 0xf0a7: '▪', 0xf06e: '■', 0xf06f: '□', 0xf071: '❑', 0xf076: '❖', 0xf0d8: '➢', 0xf0e8: '➔',
  0xf0fc: '✓', 0xf0fb: '✗', 0xf0fd: '☒', 0xf0fe: '☑', 0xf0a8: '☐', 0xf0a1: '○', 0xf09f: '•', 0xf0b2: '☐',
  0xf02d: '-', 0xf0e0: '→', 0xf0f0: '⇨', 0xf0a4: '●',
};
function symbolChar(ch: string, fontName: string): string {
  if (ch.length !== 1) return ch;
  const c = ch.charCodeAt(0);
  if (c < 0xe000 || c > 0xf8ff) return ch;
  const m = SYMBOLS[c];
  if (m) return m;
  // Altri simboli d'uso privato nei font da elenco: quasi sempre un pallino
  return /symbol|wingding|dingbat/i.test(fontName) && c >= 0xf000 ? '•' : ch;
}

const nearWhite = (c: number) => ((c >> 16) & 255) >= 246 && ((c >> 8) & 255) >= 246 && (c & 255) >= 246;

/** Immagine così come la consegna pdf.js (dati grezzi o bitmap). */
export interface PdfImage { width: number; height: number; data?: Uint8ClampedArray | Uint8Array; kind?: number; bitmap?: unknown }

export interface ExtractOptions {
  /** Converte un'immagine del PDF in JPEG (fondo bianco). null se non si può. */
  encodeImage(img: PdfImage): Promise<Uint8Array | null>;
}

export interface Extracted {
  page: JsPage;
  /** Lettere disegnate di cui il PDF non dice quale carattere sono. */
  missing: number;
  /** Lettere visibili con un carattere leggibile. */
  visibleChars: number;
  /** Lettere del testo invisibile (strato OCR di un PDF scansionato e reso ricercabile). */
  hidden: JsGlyph[];
}

interface State {
  ctm: M; fill: number | null; stroke: number | null; lineWidth: number;
  fontId: string | null; fontSize: number; charSpacing: number; wordSpacing: number; hScale: number;
  leading: number; rise: number; renderMode: number;
  /** Ritaglio attivo (rettangolo che lo contiene) e se ha una forma diversa da un rettangolo. */
  clip: JsBox | null; clipShaped: boolean;
}

export async function extractPage(page: PDFPageProxy, OPS: Record<string, number>, opt: ExtractOptions): Promise<Extracted> {
  const vp = page.getViewport({ scale: 1 });
  const VT = vp.transform as M;
  const ops = await page.getOperatorList();
  const fonts: JsFont[] = [];
  const fontIndex = new Map<string, number>();
  const glyphs: JsGlyph[] = [];
  const hidden: JsGlyph[] = [];
  const fills: JsBox[] = [];
  const art: JsBox[] = [];
  const pics: JsPic[] = [];
  let missing = 0;
  const pageArea = vp.width * vp.height;

  let st: State = { ctm: [1, 0, 0, 1, 0, 0], fill: 0, stroke: 0, lineWidth: 1, fontId: null, fontSize: 0, charSpacing: 0, wordSpacing: 0, hScale: 1, leading: 0, rise: 0, renderMode: 0, clip: null, clipShaped: false };
  const stack: State[] = [];
  let pendingClip = false;
  // Stato del testo (non fa parte dello stato grafico salvato)
  let tm: M = [1, 0, 0, 1, 0, 0];
  let tx = 0, ty = 0, lx = 0, ly = 0;

  const fontObj = (id: string): any => { try { return (page as any).commonObjs.get(id); } catch { return null; } };

  function fontIdx(id: string): number {
    let i = fontIndex.get(id);
    if (i !== undefined) return i;
    const f = fontObj(id);
    const name: string = f?.name || f?.loadedName || '?';
    const low = name.toLowerCase();
    const bold = !!(f?.bold || f?.black) || /bold|black|heavy|semibold|demi/.test(low);
    const italic = !!f?.italic || /italic|oblique/.test(low);
    fonts.push({ name, bold, italic });
    i = fonts.length - 1;
    fontIndex.set(id, i);
    return i;
  }

  const addArt = (b: JsBox) => {
    if (art.length >= MAX_SHAPES) return;
    if ((b.r - b.l) * (b.b - b.t) > pageArea * 0.9) return; // sfondo dell'intera pagina
    art.push(b);
  };

  // ------------------------------------------------------------------ testo
  function showText(items: any[]) {
    const f = st.fontId ? fontObj(st.fontId) : null;
    if (!f || st.fontSize === 0) return;
    const fm: number[] = f.fontMatrix || [0.001, 0, 0, 0.001, 0, 0];
    const vertical = !!f.vertical;
    const size = Math.abs(st.fontSize);
    const dir = st.fontSize < 0 ? -1 : 1;
    const invisible = st.renderMode === 3 || st.renderMode === 7;
    const idx = fontIdx(st.fontId!);
    // Matrice testo → pagina
    const full = mul(mul(tm, st.ctm), VT);
    const sx = Math.hypot(full[0], full[1]);
    const sy = Math.hypot(full[2], full[3]);
    // Testo dritto
    const horizontal = Math.abs(full[1]) < 0.02 * sx && full[0] > 0 && Math.abs(full[2]) < 0.02 * sy && full[3] < 0; // y verso il basso: testo dritto ha d < 0
    // Testo ruotato di un quarto di giro (intestazioni verticali): 90 = dal basso in alto, 270 = dall'alto in basso
    const rot = horizontal ? 0 : Math.abs(full[0]) < 0.02 * sx && Math.abs(full[3]) < 0.02 * sy ? (full[1] < 0 ? 90 : 270) : 0;
    let x = 0;
    for (const g of items) {
      if (typeof g === 'number') { x += (vertical ? 1 : -1) * g * size / 1000; continue; }
      const spacing = (g.isSpace ? st.wordSpacing : 0) + st.charSpacing;
      const adv = (g.width ?? 0) * fm[0] * size;
      // Testo invisibile sopra una scansione (OCR già fatto, es. OCRmyPDF): tenuto a parte
      if (invisible && horizontal && !vertical && hidden.length < MAX_GLYPHS) {
        const ch: string = g.unicode ?? '';
        if (ch) {
          const [px, py] = apply(full, tx + x * st.hScale, ty + st.rise);
          hidden.push({ ch, x: px, base: py, w: Math.abs(adv * st.hScale * sx), size: size * sy, font: idx, color: 0 });
        }
      }
      if (!vertical && (horizontal || rot) && !invisible && glyphs.length < MAX_GLYPHS) {
        const ch: string = symbolChar(g.unicode ?? '', fonts[idx].name);
        if (!ch) { if (horizontal) missing++; }
        else {
          const [px, py] = apply(full, tx + x * st.hScale, ty + st.rise);
          glyphs.push({ ch, x: px, base: py, w: Math.abs(adv * st.hScale * sx), size: size * sy, font: idx, color: (st.fill ?? 0) & 0xffffff, ...(rot ? { rot } : {}) });
        }
      }
      x += vertical ? adv - spacing * dir : adv + spacing * dir;
    }
    if (vertical) ty -= x; else tx += x * st.hScale;
  }

  // ------------------------------------------------------------------ tracciati
  type P = [number, number];
  function paths(data: Float32Array | number[]): { subs: P[][]; curves: boolean } {
    const m = mul(st.ctm, VT);
    const subs: P[][] = [];
    let cur: P[] | null = null;
    let curves = false;
    let last: P = [0, 0];
    const pt = (x: number, y: number): P => apply(m, x, y);
    for (let i = 0; i < data.length;) {
      const op = data[i++];
      if (op === 0) { last = pt(data[i++], data[i++]); cur = [last]; subs.push(cur); }
      else if (op === 1) { if (!cur) { cur = [last]; subs.push(cur); } last = pt(data[i++], data[i++]); cur.push(last); }
      else if (op === 2) { if (!cur) { cur = [last]; subs.push(cur); } curves = true; for (let k = 0; k < 3; k++) cur.push(pt(data[i++], data[i++])); last = cur[cur.length - 1]; }
      else if (op === 3) { if (!cur) { cur = [last]; subs.push(cur); } curves = true; for (let k = 0; k < 2; k++) cur.push(pt(data[i++], data[i++])); last = cur[cur.length - 1]; }
      else if (op === 4) { if (cur && cur.length) cur.push([cur[0][0], cur[0][1]]); cur = null; }
      else break;
    }
    return { subs, curves };
  }

  const bbox = (pts: P[]): JsBox => {
    let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity;
    for (const [x, y] of pts) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; }
    return { l, t, r, b, color: 0 };
  };

  /** Rettangolo dritto (4 angoli su due x e due y)? */
  function axisRect(pts: P[]): JsBox | null {
    const uniq: P[] = [];
    for (const p of pts) if (!uniq.some(q => Math.abs(q[0] - p[0]) < 0.01 && Math.abs(q[1] - p[1]) < 0.01)) uniq.push(p);
    if (uniq.length < 2 || uniq.length > 4) return null;
    const b = bbox(uniq);
    const ok = uniq.every(p => (Math.abs(p[0] - b.l) < 0.3 || Math.abs(p[0] - b.r) < 0.3) && (Math.abs(p[1] - b.t) < 0.3 || Math.abs(p[1] - b.b) < 0.3));
    return ok ? b : null;
  }

  function fill(subs: P[][], curves: boolean) {
    if (fills.length >= MAX_SHAPES) return;
    const c = st.fill;
    for (const s of subs) {
      if (s.length < 2) continue;
      const b = !curves ? axisRect(s) : null;
      if (b && c !== null) fills.push({ ...b, color: c });
      else if (c === null || !nearWhite(c)) addArt(bbox(s)); // forma o riempimento a motivo/sfumatura
    }
  }

  function stroke(subs: P[][], curves: boolean) {
    if (fills.length >= MAX_SHAPES) return;
    const c = st.stroke;
    if (c !== null && nearWhite(c)) return;
    const m = mul(st.ctm, VT);
    const lw = Math.max(st.lineWidth * Math.max(Math.hypot(m[0], m[1]), Math.hypot(m[2], m[3])), 0.25);
    const h = lw / 2;
    if (curves || c === null) { subs.filter(s => s.length > 1).forEach(s => addArt(bbox(s))); return; }
    for (const s of subs) for (let k = 0; k < s.length - 1; k++) {
      const [ax, ay] = s[k], [bx, by] = s[k + 1];
      if (Math.abs(ax - bx) < 0.01 && Math.abs(ay - by) < 0.01) continue;
      if (Math.abs(ay - by) < 0.5) fills.push({ l: Math.min(ax, bx) - h, t: ay - h, r: Math.max(ax, bx) + h, b: ay + h, color: c });
      else if (Math.abs(ax - bx) < 0.5) fills.push({ l: ax - h, t: Math.min(ay, by) - h, r: ax + h, b: Math.max(ay, by) + h, color: c });
      else addArt({ l: Math.min(ax, bx) - h, t: Math.min(ay, by) - h, r: Math.max(ax, bx) + h, b: Math.max(ay, by) + h, color: 0 });
    }
  }

  // ------------------------------------------------------------------ immagini
  const imageJobs: Promise<void>[] = [];
  function imageBox(): JsBox {
    const m = mul(st.ctm, VT);
    return bbox([apply(m, 0, 0), apply(m, 1, 0), apply(m, 0, 1), apply(m, 1, 1)]);
  }
  function image(src: PdfImage | Promise<PdfImage | null> | null) {
    if (!src || imageJobs.length >= MAX_IMAGES) return;
    const b = imageBox();
    if (b.r - b.l < 2 || b.b - b.t < 2) return;
    // Immagine ritagliata da una forma (loghi fatti di pezzi, foto in un riquadro sagomato): l'immagine intera
    // sarebbe sbagliata, quindi si prende la zona dalla pagina disegnata
    if (st.clip) {
      const c = st.clip;
      const v = { l: Math.max(b.l, c.l), t: Math.max(b.t, c.t), r: Math.min(b.r, c.r), b: Math.min(b.b, c.b) };
      if (v.r - v.l < 1 || v.b - v.t < 1) return;
      if (st.clipShaped || (v.r - v.l) * (v.b - v.t) < 0.9 * (b.r - b.l) * (b.b - b.t)) { addArt(v); return; }
    }
    const behind = (b.r - b.l) * (b.b - b.t) > pageArea * 0.85;
    const order = imageJobs.length;
    imageJobs.push(Promise.resolve(src)
      .then(img => (img ? opt.encodeImage(img) : null))
      .then(jpeg => { if (jpeg) pics.push({ jpeg: toK(jpeg), l: b.l, t: b.t, r: b.r, b: b.b, behind, order } as JsPic & { order: number }); })
      .catch(() => {}));
  }
  /** Immagine del PDF: nel browser pdf.js la decodifica a parte, quindi può arrivare un po' dopo. */
  const getObj = (id: string): Promise<PdfImage | null> => {
    const store = id.startsWith('g_') ? (page as any).commonObjs : (page as any).objs;
    return new Promise(resolve => {
      const timer = setTimeout(() => resolve(null), 15000);
      try {
        if (store.has(id)) { clearTimeout(timer); resolve(store.get(id)); return; }
        store.get(id, (v: PdfImage) => { clearTimeout(timer); resolve(v); });
      } catch { clearTimeout(timer); resolve(null); }
    });
  };

  // ------------------------------------------------------------------ scorrimento dei comandi
  const { fnArray, argsArray } = ops;
  for (let i = 0; i < fnArray.length; i++) {
    const fn = fnArray[i];
    const a: any[] = argsArray[i] as any[];
    switch (fn) {
      case OPS.save: stack.push({ ...st }); break;
      case OPS.restore: if (stack.length) st = stack.pop()!; break;
      case OPS.transform: st.ctm = mul(a as unknown as M, st.ctm); break;
      case OPS.paintFormXObjectBegin: stack.push({ ...st }); if (a?.[0]) st.ctm = mul(Array.from(a[0]) as M, st.ctm); break;
      case OPS.paintFormXObjectEnd: if (stack.length) st = stack.pop()!; break;
      case OPS.setLineWidth: st.lineWidth = a[0]; break;
      case OPS.setFillRGBColor: st.fill = hexColor(a[0]) ?? hexColor(a); break;
      case OPS.setStrokeRGBColor: st.stroke = hexColor(a[0]) ?? hexColor(a); break;
      case OPS.setFillColorN: if (a?.[0] === 'TilingPattern' || a?.[0] === 'Shading' || typeof a?.[0] !== 'number') st.fill = null; break;
      case OPS.setStrokeColorN: if (typeof a?.[0] !== 'number') st.stroke = null; break;
      case OPS.setGState:
        for (const [k, v] of (a?.[0] ?? []) as [string, any][]) {
          if (k === 'LW') st.lineWidth = v;
          else if (k === 'Font') { st.fontId = v[0]; st.fontSize = v[1]; }
        }
        break;
      case OPS.beginText: tm = [1, 0, 0, 1, 0, 0]; tx = ty = lx = ly = 0; break;
      case OPS.setFont: st.fontId = a[0]; st.fontSize = a[1]; break;
      case OPS.setCharSpacing: st.charSpacing = a[0]; break;
      case OPS.setWordSpacing: st.wordSpacing = a[0]; break;
      case OPS.setHScale: st.hScale = a[0] / 100; break;
      case OPS.setLeading: st.leading = -a[0]; break;
      case OPS.setTextRise: st.rise = a[0]; break;
      case OPS.setTextRenderingMode: st.renderMode = a[0]; break;
      case OPS.setTextMatrix: tm = Array.from(a[0] ?? a) as M; tx = lx = 0; ty = ly = 0; break;
      case OPS.moveText: tx = lx += a[0]; ty = ly += a[1]; break;
      case OPS.setLeadingMoveText: st.leading = a[1]; tx = lx += a[0]; ty = ly += a[1]; break;
      case OPS.nextLine: tx = lx; ty = ly += st.leading; break;
      case OPS.showText: case OPS.showSpacedText: showText(a[0]); break;
      case OPS.nextLineShowText: tx = lx; ty = ly += st.leading; showText(a[0]); break;
      case OPS.nextLineSetSpacingShowText: st.wordSpacing = a[0]; st.charSpacing = a[1]; tx = lx; ty = ly += st.leading; showText(a[2]); break;
      case OPS.clip: case OPS.eoClip: pendingClip = true; break;
      case OPS.constructPath: {
        const paint = a[0];
        const data = a[1]?.[0];
        if (pendingClip && data) {
          pendingClip = false;
          const { subs, curves } = paths(data);
          const pts = subs.flat();
          if (pts.length) {
            const box = bbox(pts);
            // Rettangolo allineato agli assi? (4 angoli, eventualmente ripetuto il primo)
            const rect = !curves && subs.length === 1 && pts.length <= 5 && pts.every(([x, y]) => (Math.abs(x - box.l) < 0.5 || Math.abs(x - box.r) < 0.5) && (Math.abs(y - box.t) < 0.5 || Math.abs(y - box.b) < 0.5));
            const c = st.clip;
            st.clip = c ? { l: Math.max(c.l, box.l), t: Math.max(c.t, box.t), r: Math.min(c.r, box.r), b: Math.min(c.b, box.b) } : box;
            st.clipShaped = st.clipShaped || !rect;
          }
        }
        if (!data || paint === OPS.endPath) break;
        const { subs, curves } = paths(data);
        const closeIt = paint === OPS.closeStroke || paint === OPS.closeFillStroke || paint === OPS.closeEOFillStroke;
        if (closeIt) subs.forEach(s => { if (s.length) s.push([s[0][0], s[0][1]]); });
        const doFill = paint === OPS.fill || paint === OPS.eoFill || paint === OPS.fillStroke || paint === OPS.eoFillStroke || paint === OPS.closeFillStroke || paint === OPS.closeEOFillStroke;
        const doStroke = paint === OPS.stroke || paint === OPS.closeStroke || paint === OPS.fillStroke || paint === OPS.eoFillStroke || paint === OPS.closeFillStroke || paint === OPS.closeEOFillStroke;
        if (doFill) fill(subs, curves);
        if (doStroke) stroke(subs, curves);
        break;
      }
      case OPS.paintImageXObject: case OPS.paintImageXObjectRepeat: image(getObj(a[0])); break;
      case OPS.paintInlineImageXObject: image(a[0]); break;
      case OPS.paintImageMaskXObject: case OPS.paintSolidColorImageMask: { const b = imageBox(); if (b.r - b.l >= 1 || b.b - b.t >= 1) addArt(b); break; }
      default: break;
    }
  }
  await Promise.all(imageJobs);
  // Stesso ordine del PDF (le immagini sono pronte in tempi diversi): quelle disegnate dopo stanno sopra
  pics.sort((a, b) => (a as any).order - (b as any).order);

  const visibleChars = glyphs.filter(g => g.ch.trim().length > 0).length;
  return { page: { w: vp.width, h: vp.height, fonts, glyphs, fills, art, pics }, missing, visibleChars, hidden };
}
