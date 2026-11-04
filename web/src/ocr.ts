/** Riconoscimento del testo (OCR) delle pagine scansionate e delle foto: Tesseract.js (fase 4). */
import type { Ocr } from './pdf/pdfToWord';

export const ocrAvailable = false;
export function getOcr(_lang: string): Ocr | undefined { return undefined; }
