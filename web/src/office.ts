/**
 * Word / PowerPoint → PDF con il programma Office di questo computer (tramite il piccolo server locale
 * di DocVerso.bat): il risultato è identico a "Salva come PDF" di Word e PowerPoint. Se Office non c'è,
 * il server usa LibreOffice. Il file va solo dal browser al server su questo stesso computer.
 */
import { baseName, MIME, type OutFile } from './convert';

export interface OfficeStatus { word: boolean; powerpoint: boolean; libreoffice: boolean }

let status: Promise<OfficeStatus | null> | null = null;

/** Che cosa può convertire il computer (null = server locale non disponibile, es. app aperta in altro modo). */
export function officeStatus(): Promise<OfficeStatus | null> {
  status ??= fetch('api/office-status', { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    .then(r => (r.ok ? r.json() as Promise<OfficeStatus> : null))
    .catch(() => null);
  return status;
}

export const isPowerPoint = (name: string) => /\.(pptx?|pptm|ppsx?|odp)$/i.test(name);

export async function officeToPdf(file: File, progress?: (d: number, t: number, m: string) => void): Promise<OutFile> {
  const ppt = isPowerPoint(file.name);
  const what = ppt ? 'PowerPoint' : 'Word';
  const t0 = Date.now();
  const tick = () => progress?.(0, 1, `Conversione con ${what} del computer… ${Math.round((Date.now() - t0) / 1000)} s` +
    (Date.now() - t0 > 30000 ? ' (la prima volta Office può metterci di più; dopo 2 minuti si ferma e spiega perché)' : ''));
  tick();
  const timer = setInterval(tick, 1000);
  let r: Response;
  try {
    r = await fetch('api/office-pdf', {
      method: 'POST',
      headers: { 'X-Nome-File': encodeURIComponent(file.name), 'Content-Type': 'application/octet-stream' },
      body: file,
      signal: AbortSignal.timeout(300000),
    });
  } catch {
    clearInterval(timer);
    throw new Error('Non riesco a contattare DocVerso sul computer: avvia l’app con DocVerso.bat e riprova.');
  }
  finally { clearInterval(timer); }
  if (!r.ok) throw new Error((await r.text()) || 'La conversione in PDF non è riuscita.');
  const bytes = new Uint8Array(await r.arrayBuffer());
  return { name: `${baseName(file.name)}.pdf`, bytes, type: MIME.pdf };
}
