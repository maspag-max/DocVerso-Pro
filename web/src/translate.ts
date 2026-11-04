/**
 * Traduzione offline nel browser con Bergamot: i modelli di Firefox Translations (Mozilla).
 * Il testo da tradurre non esce dal computer: si scaricano solo i modelli della lingua, una volta,
 * poi restano nella cache del browser.
 *
 * Da dove arrivano i modelli: Mozilla li pubblica senza permessi per i siti (CORS), quindi si
 * scaricano da una copia identica su Hugging Face; ogni file è verificato con l'impronta SHA-256
 * dell'elenco ufficiale di Mozilla prima di essere usato.
 */
import { BatchTranslator, TranslatorBacking } from '@mkljczk/bergamot-translator/translator.js';
import { franc } from 'franc-min';
import type { TranslateBatch } from './pdf/pdfToWord';

export const AUTO = 'auto';
export const translationAvailable = true;

const MIRROR = 'https://huggingface.co/midudev/firefox-translations';
const MOZILLA_RECORDS = 'https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records';
const CACHE = 'docverso-modelli-traduzione';

export interface Lang { code: string; name: string }

/** Lingue dei modelli (tutte passano dall'inglese). Nome in italiano. */
const CODES = ['af', 'ar', 'az', 'be', 'bg', 'bn', 'bs', 'ca', 'cs', 'da', 'de', 'el', 'en', 'es', 'et', 'eu', 'fa', 'fi', 'fr', 'gl', 'gu', 'he', 'hi', 'hr', 'hu', 'id', 'is', 'it', 'ja', 'kn', 'ko', 'lt', 'lv', 'ml', 'mr', 'ms', 'nb', 'nl', 'nn', 'no', 'pl', 'pt', 'ro', 'ru', 'sk', 'sl', 'sq', 'sr', 'sv', 'ta', 'te', 'th', 'tr', 'uk', 'ur', 'vi', 'zh'];
const TOP = ['it', 'en', 'fr', 'de', 'es', 'pt'];
const names = new Intl.DisplayNames(['it'], { type: 'language' });
const nameOf = (c: string) => { const n = names.of(c) ?? c; return n.charAt(0).toUpperCase() + n.slice(1); };
export const LANGUAGES: Lang[] = CODES.map(code => ({ code, name: nameOf(code) }))
  .sort((a, b) => (TOP.includes(a.code) ? TOP.indexOf(a.code) : 99) - (TOP.includes(b.code) ? TOP.indexOf(b.code) : 99) || a.name.localeCompare(b.name, 'it'));
export const languageName = (c: string) => (c === AUTO ? 'Rileva automaticamente' : nameOf(c));

// ------------------------------------------------------------------ riconoscimento della lingua

/** franc restituisce codici a tre lettere: quelli delle lingue che sappiamo tradurre. */
const ISO3: Record<string, string> = {
  afr: 'af', arb: 'ar', azj: 'az', bel: 'be', bul: 'bg', ben: 'bn', bos: 'bs', cat: 'ca', ces: 'cs', dan: 'da', deu: 'de', ell: 'el', eng: 'en',
  spa: 'es', est: 'et', eus: 'eu', pes: 'fa', fin: 'fi', fra: 'fr', glg: 'gl', guj: 'gu', heb: 'he', hin: 'hi', hrv: 'hr', hun: 'hu', ind: 'id',
  isl: 'is', ita: 'it', jpn: 'ja', kan: 'kn', kor: 'ko', lit: 'lt', lvs: 'lv', mal: 'ml', mar: 'mr', zlm: 'ms', nob: 'nb', nld: 'nl', nno: 'nn',
  pol: 'pl', por: 'pt', ron: 'ro', rus: 'ru', slk: 'sk', slv: 'sl', als: 'sq', srp: 'sr', swe: 'sv', tam: 'ta', tel: 'te', tha: 'th', tur: 'tr',
  ukr: 'uk', urd: 'ur', vie: 'vi', cmn: 'zh',
};

export function detectLanguage(text: string): string | null {
  const t = text.slice(0, 4000);
  const code = franc(t, { minLength: 20, only: Object.keys(ISO3) });
  return ISO3[code] ?? null;
}

// ------------------------------------------------------------------ modelli

interface MirrorFile { name: string; size: number; expectedSha256Hash: string }
type Files = Record<string, MirrorFile>;

let mozillaHashes: Promise<Set<string>> | null = null;
function officialHashes(): Promise<Set<string>> {
  mozillaHashes ??= fetch(MOZILLA_RECORDS, { credentials: 'omit' })
    .then(r => { if (!r.ok) throw new Error(); return r.json(); })
    .then(j => new Set<string>((j.data as any[]).map(r => r.attachment?.hash).filter(Boolean)))
    .catch(e => { mozillaHashes = null; throw new Error('Non riesco a leggere l’elenco dei modelli di traduzione di Mozilla: serve internet la prima volta che si usa una lingua.', { cause: e }); });
  return mozillaHashes;
}

/** File di una coppia di lingue sulla copia, controllati contro l'elenco ufficiale di Mozilla. */
async function pairFiles(from: string, to: string): Promise<Files | null> {
  const r = await fetch(`${MIRROR.replace('huggingface.co/', 'huggingface.co/api/models/')}/tree/main/${from}-${to}`, { credentials: 'omit' });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error('Non riesco a raggiungere i modelli di traduzione: controlla la connessione a internet.');
  const list = await r.json() as { path: string; size: number; lfs?: { oid: string } }[];
  const official = await officialHashes();
  const files: Files = {};
  for (const f of list) {
    const name = f.path.split('/').pop()!;
    const sha = f.lfs?.oid;
    if (!sha) continue;
    if (!official.has(sha)) throw new Error(`Il modello ${from}→${to} non corrisponde a quello pubblicato da Mozilla: non lo uso.`);
    const kind = name.startsWith('model.') ? 'model' : name.startsWith('lex.') ? 'lex'
      : name.startsWith('srcvocab') ? 'srcvocab' : name.startsWith('trgvocab') ? 'trgvocab' : name.startsWith('vocab') ? 'vocab' : null;
    if (kind) files[kind] = { name: `${MIRROR}/resolve/main/${f.path}`, size: f.size, expectedSha256Hash: sha };
  }
  return files.model && files.lex && (files.vocab || (files.srcvocab && files.trgvocab)) ? files : null;
}

/** Avanzamento dei download (byte scaricati / totali), per i messaggi d'attesa. */
let onBytes: ((done: number, total: number) => void) | null = null;

class DocVersoBacking extends TranslatorBacking {
  private pairs: string[];
  constructor(pairs: string[], options: any) { super({ ...options, registryUrl: 'about:blank' }); this.pairs = pairs; this.registry = this.loadModelRegistry(); }

  async loadModelRegistry(): Promise<any[]> {
    if (!this.pairs) return []; // chiamata dal costruttore della classe base, prima che pairs esista
    const out: any[] = [];
    for (const p of this.pairs) {
      const [from, to] = p.split('-');
      const files = await pairFiles(from, to);
      if (files) out.push({ from, to, files });
    }
    return out;
  }

  /** Download con verifica dell'impronta e cache del browser: ogni modello si scarica una volta sola. */
  async fetch(url: string, checksum: string | undefined): Promise<ArrayBuffer> {
    let cache: Cache | null = null;
    try { cache = await caches.open(CACHE); } catch { /* cache non disponibile (finestra privata) */ }
    const hit = await cache?.match(url);
    if (hit) return hit.arrayBuffer();
    const res = await fetch(url, { credentials: 'omit', integrity: checksum ? `sha256-${this.hexToBase64(checksum)}` : undefined });
    if (!res.ok) throw new Error(`download non riuscito (${res.status})`);
    const total = Number(res.headers.get('content-length')) || 0;
    const reader = res.body!.getReader();
    const chunks: Uint8Array[] = [];
    let done = 0;
    for (;;) {
      const { value, done: end } = await reader.read();
      if (end) break;
      chunks.push(value); done += value.length;
      onBytes?.(done, total);
    }
    const buf = new Uint8Array(done);
    let o = 0; for (const c of chunks) { buf.set(c, o); o += c.length; }
    try { await cache?.put(url, new Response(buf.slice().buffer)); } catch { /* spazio esaurito: si riscaricherà */ }
    return buf.buffer;
  }
}

let translator: BatchTranslator | null = null;
let loadedPairs = '';

function getTranslator(from: string, to: string) {
  const pairs = from === 'en' || to === 'en' ? [`${from}-${to}`] : [`${from}-en`, `en-${to}`];
  const key = pairs.join(',');
  if (translator && loadedPairs === key) return translator;
  translator?.delete();
  const workerUrl = new URL(import.meta.env.BASE_URL + 'bergamot/translator-worker.js', location.href).href;
  const backing = new DocVersoBacking(pairs, { workerUrl, downloadTimeout: 0, pivotLanguage: 'en' });
  translator = new BatchTranslator({ workers: Math.min(4, Math.max(1, (navigator.hardwareConcurrency || 2) - 1)), batchSize: 16 }, backing);
  loadedPairs = key;
  return translator;
}

/** Traduttore da [from] (o rilevata dal testo, se AUTO) a [to]. [status] riceve i messaggi d'attesa. */
export function makeTranslate(from: string, to: string, status: (m: string) => void): TranslateBatch {
  return async (texts, progress) => {
    let src = from;
    if (src === AUTO) {
      status('Riconoscimento della lingua del documento…');
      const d = detectLanguage(texts.join(' '));
      if (!d) throw new Error('Non riesco a riconoscere la lingua del documento: sceglila nel campo «Da».');
      src = d;
    }
    if (src === to) throw new Error(`Il documento è già in ${languageName(to).toLowerCase()}.`);
    onBytes = (d, t) => status(`Scarico il modello ${languageName(src)} → ${languageName(to)} (solo la prima volta): ${Math.round(d / 1048576)}${t ? ` di ${Math.round(t / 1048576)}` : ''} MB`);
    const tr = getTranslator(src, to);
    let done = 0;
    status(`Traduzione ${languageName(src)} → ${languageName(to)}…`);
    try {
      return await Promise.all(texts.map(text =>
        tr.translate({ from: src, to, text, html: false }).then(r => {
          done++; progress?.(done, texts.length);
          return r.target.text;
        })));
    } finally {
      onBytes = null;
    }
  };
}
