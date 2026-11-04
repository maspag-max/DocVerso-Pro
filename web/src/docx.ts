/**
 * Word → Word tradotto: si sostituisce solo il testo dentro il file; impaginazione, stili, tabelle,
 * immagini, intestazioni e piè di pagina restano identici. Riscrittura del DocxTranslator di TradScan
 * (javax.xml) con il DOMParser del browser; la protezione di codici, numeri, ____ e ☐ resta nel motore.
 */
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import * as engine from './engine/docverso-engine.mjs';
import { baseName, MIME, type OutFile } from './convert';
import type { Progress, TranslateBatch } from './pdf/pdfToWord';

const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
const XML_NS = 'http://www.w3.org/XML/1998/namespace';
const PARTS = /^word\/(document|header\d*|footer\d*|footnotes|endnotes|comments)\.xml$/;
/** Elementi che interrompono una frase (dopo di loro il testo si traduce a parte). */
const BREAKERS = new Set(['tab', 'ptab', 'br', 'cr', 'drawing', 'pict', 'object', 'sym', 'footnoteReference', 'endnoteReference', 'fldChar']);
/** Elementi da non toccare: codici di campo, testo cancellato, caselle di testo (hanno paragrafi propri). */
const SKIP = new Set(['instrText', 'delText', 'delInstrText', 'txbxContent', 'p']);

/** Pezzi di testo (<w:t>) di ogni frase, paragrafo per paragrafo. */
function segmentsOf(doc: Document): Element[][] {
  const out: Element[][] = [];
  for (const p of Array.from(doc.getElementsByTagNameNS(W, 'p'))) {
    let cur: Element[] = [];
    const visit = (n: Node) => {
      for (let c = n.firstChild; c; c = c.nextSibling) {
        if (c.nodeType !== 1) continue;
        const e = c as Element;
        if (e.namespaceURI === W) {
          const name = e.localName;
          if (name === 't') cur.push(e);
          else if (BREAKERS.has(name)) { if (cur.length) { out.push(cur); cur = []; } }
          else if (!SKIP.has(name)) visit(e);
        } else if (e.localName !== 'Fallback') visit(e); // contenuti alternativi: si guarda dentro
      }
    };
    visit(p);
    if (cur.length) out.push(cur);
  }
  return out;
}

function setText(e: Element, text: string) {
  e.textContent = text;
  e.setAttributeNS(XML_NS, 'xml:space', 'preserve');
}

/** Frasi del documento: intere se un pezzo prevale (≥ 60% del testo), altrimenti pezzo per pezzo. */
function plan(seg: Element[]): { whole: boolean; main: Element; full: string } {
  const full = seg.map(e => e.textContent ?? '').join('');
  const main = seg.reduce((a, b) => ((b.textContent ?? '').length > (a.textContent ?? '').length ? b : a));
  return { whole: seg.length === 1 || (main.textContent ?? '').length >= full.length * 0.6, main, full };
}

/** Un po' di testo del documento, per riconoscere la lingua. */
export function docxSample(bytes: Uint8Array, max = 3000): string {
  const xml = unzipSync(bytes, { filter: f => f.name === 'word/document.xml' })['word/document.xml'];
  if (!xml) return '';
  const doc = new DOMParser().parseFromString(strFromU8(xml), 'application/xml');
  return Array.from(doc.getElementsByTagNameNS(W, 't')).map(t => t.textContent).join(' ').slice(0, max);
}

export async function docxTranslate(file: File, translate: TranslateBatch, suffix: string, progress?: Progress): Promise<OutFile> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let files: Record<string, Uint8Array>;
  try { files = unzipSync(bytes); } catch { throw new Error('Il file non sembra un documento Word (.docx) valido.'); }
  if (!files['word/document.xml']) throw new Error('Il file non sembra un documento Word (.docx) valido.');

  // Lettura delle parti con testo
  const docs: [string, Document][] = [];
  for (const name of Object.keys(files)) {
    if (!PARTS.test(name)) continue;
    const doc = new DOMParser().parseFromString(strFromU8(files[name]), 'application/xml');
    if (doc.getElementsByTagName('parsererror').length) continue; // parte illeggibile: resta com'è
    docs.push([name, doc]);
  }
  const segs = docs.flatMap(([, d]) => segmentsOf(d)).filter(s => s.some(e => (e.textContent ?? '').trim()));

  // Primo passaggio: testi da tradurre (frasi intere o pezzi), poi le frasi che servono al motore
  const texts: string[] = [];
  for (const s of segs) { const p = plan(s); if (p.whole) texts.push(p.full); else s.forEach(e => texts.push(e.textContent ?? '')); }
  const tt = new engine.TextTranslation();
  const req = tt.requests(texts);
  progress?.(0, 1, `Traduzione di ${req.length} frasi…`);
  const out = req.length ? await translate(req, (d, t) => progress?.(d, t, `Traduzione: ${d} di ${t} frasi`)) : [];
  req.forEach((s, i) => { if (out[i]) tt.put(s, out[i]); });

  // Secondo passaggio: testo tradotto al suo posto
  for (const s of segs) {
    const p = plan(s);
    if (p.whole) {
      const t = tt.translate(p.full);
      if (t === p.full) continue;
      // Tutta la frase nel pezzo principale (ne tiene lo stile), gli altri si svuotano
      for (const e of s) setText(e, e === p.main ? t : '');
    } else {
      for (const e of s) { const o = e.textContent ?? ''; const t = tt.translate(o); if (t !== o) setText(e, t); }
    }
  }
  const ser = new XMLSerializer();
  for (const [name, doc] of docs) {
    let xml = ser.serializeToString(doc);
    if (!xml.startsWith('<?xml')) xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + xml;
    files[name] = strToU8(xml);
  }
  progress?.(1, 1, 'Scrittura del file Word');
  return { name: `${baseName(file.name)} (${suffix}).docx`, bytes: zipSync(files, { level: 6 }), type: MIME.docx };
}
