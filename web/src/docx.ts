/** Word → Word tradotto (fase 5): si sostituisce solo il testo dentro il file. */
import type { OutFile } from './convert';
import type { Progress, TranslateBatch } from './pdf/pdfToWord';

export async function docxTranslate(_file: File, _translate: TranslateBatch, _suffix: string, _progress?: Progress): Promise<OutFile> {
  throw new Error('La traduzione dei file Word non è ancora disponibile.');
}
