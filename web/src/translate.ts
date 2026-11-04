/** Traduzione offline nel browser: Bergamot (fase 5). */
import type { TranslateBatch } from './pdf/pdfToWord';

export const AUTO = 'auto';
export const translationAvailable = false;
export interface Lang { code: string; name: string }
export const LANGUAGES: Lang[] = [{ code: 'it', name: 'Italiano' }, { code: 'en', name: 'Inglese' }];
/** Traduttore da [from] (o rilevata dal testo, se AUTO) a [to]. [status] riceve i messaggi d'attesa. */
export function makeTranslate(_from: string, _to: string, _status: (m: string) => void): TranslateBatch {
  return async () => { throw new Error('La traduzione non è ancora disponibile.'); };
}
