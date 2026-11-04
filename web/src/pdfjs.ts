/** pdf.js nel browser: lavoratore separato e risorse servite dall'app stessa (nessuna richiesta esterna). */
// Versione "legacy" di pdf.js: funziona anche con Chrome/Edge non aggiornatissimi (la versione moderna
// usa funzioni del 2025, come Uint8Array.toHex, che i browser più vecchi non hanno)
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

const base = import.meta.env.BASE_URL + 'pdfjs/';

export const OPS = pdfjs.OPS as unknown as Record<string, number>;

export async function openPdf(bytes: Uint8Array) {
  return pdfjs.getDocument({
    data: bytes,
    fontExtraProperties: true,
    standardFontDataUrl: base + 'standard_fonts/',
    cMapUrl: base + 'cmaps/',
    cMapPacked: true,
  }).promise;
}
