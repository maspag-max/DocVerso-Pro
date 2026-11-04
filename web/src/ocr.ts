/**
 * Riconoscimento del testo (OCR) delle pagine scansionate e delle foto, con Tesseract.js.
 * Motore e lingue sono serviti dall'app stessa: le immagini non escono dal computer.
 */
import { createWorker, type Worker } from 'tesseract.js';
import type { Ocr, Raster } from './pdf/pdfToWord';
import { ocrLangs, prepareForOcr, recognizeWithRescue } from './ocr-words';

export const ocrAvailable = true;

const base = import.meta.env.BASE_URL + 'tesseract/';
let worker: Worker | null = null;
let loaded = '';

async function getWorker(langs: string, status?: (m: string) => void): Promise<Worker> {
  if (worker && loaded === langs) return worker;
  if (worker) { await worker.terminate(); worker = null; }
  status?.('Preparazione del riconoscimento del testo…');
  worker = await createWorker(langs.split('+'), 1, {
    workerPath: new URL(base + 'worker.min.js', location.href).href,
    corePath: new URL(base, location.href).href,
    langPath: new URL(base + 'lang', location.href).href,
    gzip: true,
    workerBlobURL: false,
  });
  loaded = langs;
  return worker;
}

function toCanvas(r: Raster): HTMLCanvasElement {
  const img = prepareForOcr(r.crop(0, 0, r.width, r.height));
  const c = document.createElement('canvas');
  c.width = r.width; c.height = r.height;
  c.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(img.data) as Uint8ClampedArray<ArrayBuffer>, img.width, img.height), 0, 0);
  return c;
}

/** OCR per la lingua del documento [docLang] (codice ISO, o "auto"). */
export function getOcr(docLang: string, status?: (m: string) => void): Ocr {
  return async (r: Raster) => {
    const w = await getWorker(ocrLangs(docLang), status);
    const c = toCanvas(r);
    try {
      return await recognizeWithRescue(r.width, r.height, r.pixels(), async zone => {
        await w.setParameters({ tessedit_pageseg_mode: (zone ? '6' : '3') as any });
        const res = await w.recognize(c, zone ? { rectangle: zone } : {}, { blocks: true, text: false });
        return res.data.blocks as any;
      });
    } finally {
      c.width = c.height = 0;
    }
  };
}
