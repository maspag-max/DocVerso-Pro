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
import { gemmaSupports, gemmaTranslate, loadGemma } from './gemma';
import { glossaryEnabled, inject, loadGlossaryText, missingTerms, parseGlossary, protect, restore, unquote } from './glossary';

export const AUTO = 'auto';
export const translationAvailable = true;

const MIRROR = 'https://huggingface.co/midudev/firefox-translations';
const MOZILLA_RECORDS = 'https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records';
const CACHE = 'docverso-modelli-traduzione';
const OFFLINE = 'Non riesco a scaricare il modello di traduzione per queste lingue: serve internet, e alcune reti aziendali bloccano il sito dei modelli. Inglese ↔ italiano funziona senza internet con il pacchetto DocVerso aggiornato.';

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
  mozillaHashes ??= fetch(MOZILLA_RECORDS, { credentials: 'omit', signal: AbortSignal.timeout(20000) })
    .then(r => { if (!r.ok) throw new Error(); return r.json(); })
    .then(j => new Set<string>((j.data as any[]).map(r => r.attachment?.hash).filter(Boolean)))
    .catch(e => { mozillaHashes = null; throw new Error(OFFLINE, { cause: e }); });
  return mozillaHashes;
}

/**
 * Modelli inclusi nel pacchetto locale (cartella "modelli" accanto all'app): verificati quando il
 * pacchetto è stato preparato, funzionano senza internet e dietro i proxy aziendali.
 */
async function localPairFiles(from: string, to: string): Promise<Files | null> {
  try {
    const base = new URL(`${import.meta.env.BASE_URL}modelli/${from}-${to}/`, location.href);
    const r = await fetch(new URL('index.json', base), { cache: 'no-cache', signal: AbortSignal.timeout(10000) });
    if (!r.ok) return null;
    const idx = await r.json() as { files: Record<string, { name: string; size: number; sha256: string }> };
    const files: Files = {};
    for (const [kind, f] of Object.entries(idx.files)) files[kind] = { name: new URL(f.name, base).href, size: f.size, expectedSha256Hash: f.sha256 };
    return files.model && files.lex && (files.vocab || (files.srcvocab && files.trgvocab)) ? files : null;
  } catch {
    return null;
  }
}

/** File di una coppia di lingue: prima quelli del pacchetto, poi la copia online controllata con l'elenco di Mozilla. */
async function pairFiles(from: string, to: string): Promise<Files | null> {
  const local = await localPairFiles(from, to);
  if (local) return local;
  let r: Response;
  try { r = await fetch(`${MIRROR.replace('huggingface.co/', 'huggingface.co/api/models/')}/tree/main/${from}-${to}`, { credentials: 'omit', signal: AbortSignal.timeout(20000) }); }
  catch (e) { throw new Error(OFFLINE, { cause: e }); }
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(OFFLINE);
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
/** Messaggi delle fasi di preparazione (per capire dove si ferma, se si ferma). */
let stage: (m: string) => void = () => {};

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
      stage(files && files.model.name.startsWith(location.origin) ? 'Modello trovato nel pacchetto, caricamento…' : 'Modello trovato online, download…');
    }
    return out;
  }

  /** Download con verifica dell'impronta e cache del browser: ogni modello si scarica una volta sola. */
  async fetch(url: string, checksum: string | undefined): Promise<ArrayBuffer> {
    let cache: Cache | null = null;
    try { cache = await caches.open(CACHE); } catch { /* cache non disponibile (finestra privata) */ }
    if (url.startsWith(location.origin)) cache = null; // modelli del pacchetto: già sul disco
    const hit = await cache?.match(url);
    if (hit) { stage('Avvio del traduttore (alcuni secondi)…'); return hit.arrayBuffer(); }
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
    stage('Avvio del traduttore (alcuni secondi)…');
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
export type Engine = 'veloce' | 'accurata';

/** Traduttore da [from] (o rilevata dal testo, se AUTO) a [to]. [engine]: Bergamot (veloce) o TranslateGemma (accurata). */
export function makeTranslate(from: string, to: string, status: (m: string) => void, engine: Engine = 'veloce'): TranslateBatch {
  return async (texts, progress) => {
    let src = from;
    if (src === AUTO) {
      status('Riconoscimento della lingua del documento…');
      const d = detectLanguage(texts.join(' '));
      if (!d) throw new Error('Non riesco a riconoscere la lingua del documento: sceglila nel campo «Da».');
      src = d;
    }
    if (src === to) throw new Error(`Il documento è già in ${languageName(to).toLowerCase()}.`);
    const accurate = engine === 'accurata';
    if (accurate && !(gemmaSupports(src) && gemmaSupports(to))) throw new Error(`La traduzione accurata non è disponibile per ${languageName(src).toLowerCase()} → ${languageName(to).toLowerCase()}: usa la traduzione veloce.`);
    onBytes = (d, t) => status(t > 0 && d >= t ? `Caricamento del modello ${languageName(src)} → ${languageName(to)}…` :`Scarico il modello ${languageName(src)} → ${languageName(to)} (solo la prima volta): ${Math.round(d / 1048576)}${t ? ` di ${Math.round(t / 1048576)}` : ''} MB`);
    stage = (m: string) => status(m);
    let one: (text: string) => Promise<string>;
    if (accurate) {
      status('Preparazione della traduzione accurata…');
      const g = await loadGemma(status);
      // La scheda grafica traduce un paragrafo alla volta: coda
      let queue: Promise<unknown> = Promise.resolve();
      one = text => { const r = queue.then(() => gemmaTranslate(g, text, src, to)); queue = r.catch(() => {}); return r; };
    } else {
      status(`Ricerca del modello ${languageName(src)} → ${languageName(to)}…`);
      const tr = getTranslator(src, to);
      one = text => tr.translate({ from: src, to, text, html: false }).then(r => r.target.text as string);
    }
    let done = 0;
    // Glossario (solo inglese ↔ italiano): termini fissi protetti da segnaposti
    const pair = (src === 'en' && to === 'it') || (src === 'it' && to === 'en');
    const terms = pair && glossaryEnabled() ? parseGlossary(loadGlossaryText()) : [];
    /** Una frase col glossario: parole vicarie, poi sigle ZQX, poi senza glossario. */
    const withGlossary = async (text: string, split: boolean): Promise<string> => {
      if (accurate) {
        // TranslateGemma: termini già tradotti dentro il testo; se ne ritraduce qualcuno, si riprova con «»
        const a = inject(text, terms, src as 'en' | 'it', to as 'en' | 'it');
        if (!a.used.length) return one(text);
        const first = await one(a.text);
        const miss = missingTerms(first, a.used, to as 'en' | 'it');
        if (!miss.length) return first;
        const quoted = new Set(miss);
        const second = unquote(await one(inject(text, terms, src as 'en' | 'it', to as 'en' | 'it', quoted).text), quoted, to as 'en' | 'it');
        return missingTerms(second, a.used, to as 'en' | 'it').length < miss.length ? second : first;
      }
      const p = protect(text, terms, src as 'en' | 'it');
      if (!p.terms.length) return one(text);
      const r = restore(await one(p.text), p, to as 'en' | 'it');
      if (r !== null) return r;
      // Paragrafo lungo: si riprova frase per frase (meno termini per volta, meno errori)
      const parts = split ? text.split(/(?<=[.!?;:])\s+(?=[\p{Lu}\d"“‘(])/u) : [];
      if (parts.length > 1) return (await Promise.all(parts.map(t => withGlossary(t, false)))).join(' ');
      const q = protect(text, terms, src as 'en' | 'it', 'token');
      return restore(await one(q.text), q, to as 'en' | 'it') ?? one(text);
    };
    const t0 = performance.now();
    try {
      return await Promise.all(texts.map(async text => {
        const out = terms.length ? await withGlossary(text, true) : await one(text);
        done++; progress?.(done, texts.length);
        if (accurate) {
          // Stima del tempo che manca (la traduzione accurata è lenta)
          const per = (performance.now() - t0) / done;
          const left = Math.round(per * (texts.length - done) / 60000);
          status(`Traduzione accurata ${languageName(src)} → ${languageName(to)}: ${done} di ${texts.length} paragrafi${done < texts.length ? ` (mancano circa ${left < 1 ? 'meno di un minuto' : left === 1 ? 'un minuto' : left + ' minuti'})` : ''}`);
        } else if (done === 1) status(`Traduzione ${languageName(src)} → ${languageName(to)}: ${texts.length} frasi…`);
        return out;
      }));
    } catch (e) {
      // Errore (di solito la rete): il prossimo tentativo riparte da zero
      translator?.delete(); translator = null; loadedPairs = '';
      const msg = e instanceof Error ? e.message : String(e);
      throw new Error(/Could not fetch|Failed to fetch|NetworkError|download/i.test(msg) ? OFFLINE : msg, { cause: e });
    } finally {
      onBytes = null;
    }
  };
}

/** Solo per le prove automatiche: traduzione diretta di un elenco di frasi. */
(globalThis as any).docversoTranslate = (from: string, to: string, texts: string[], engine: Engine = 'veloce') => makeTranslate(from, to, m => console.log(m), engine)(texts);
