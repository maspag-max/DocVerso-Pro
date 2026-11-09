/** pdf.js nel browser: lavoratore separato e risorse servite dall'app stessa (nessuna richiesta esterna). */
// Versione "legacy" di pdf.js: funziona anche con Chrome/Edge non aggiornatissimi (la versione moderna
// usa funzioni del 2025, come Uint8Array.toHex, che i browser più vecchi non hanno)
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

const base = import.meta.env.BASE_URL + 'pdfjs/';

export const OPS = pdfjs.OPS as unknown as Record<string, number>;

/**
 * Un solo lavoratore per tutta la sessione: si carica alla prima conversione e poi si riusa
 * (prima se ne creava uno nuovo per ogni PDF, e ogni volta lo si richiedeva al server).
 */
let worker: InstanceType<typeof pdfjs.PDFWorker> | null = null;

export async function openPdf(bytes: Uint8Array) {
  if (worker?.destroyed) worker = null;
  worker ??= new pdfjs.PDFWorker();
  try {
    await worker.promise;
  } catch (e) {
    worker.destroy(); worker = null;
    throw new Error('Non riesco a caricare una parte di DocVerso: la finestra nera di DocVerso Pro è ancora aperta? Se l\'hai chiusa, riavvia DocVerso-Pro.bat e ricarica la pagina (F5).', { cause: e });
  }
  const task = pdfjs.getDocument({
    worker,
    data: bytes,
    fontExtraProperties: true,
    standardFontDataUrl: base + 'standard_fonts/',
    cMapUrl: base + 'cmaps/',
    cMapPacked: true,
  });
  // PDF che si apre solo con una password: la si chiede (resta sul computer come il resto)
  let cancelled = false;
  task.onPassword = (update: (password: string) => void, reason: number) => {
    const wrong = reason === pdfjs.PasswordResponses.INCORRECT_PASSWORD;
    (askPassword ? askPassword(wrong) : Promise.resolve(null)).then(p => {
      if (p == null) { cancelled = true; void task.destroy(); } else update(p);
    });
  };
  try {
    return await task.promise;
  } catch (e) {
    if (cancelled || (e as Error)?.name === 'PasswordException')
      throw new Error('Questo PDF si apre solo con una password: senza la password non si può convertire.', { cause: e });
    throw e;
  }
}

/** Chiede la password di un PDF protetto ([wrong]: quella data prima era sbagliata); null = annullato. */
export type AskPassword = (wrong: boolean) => Promise<string | null>;
let askPassword: AskPassword | null = null;
export function setPasswordAsker(f: AskPassword) { askPassword = f; }
