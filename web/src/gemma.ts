/**
 * Traduzione accurata con TranslateGemma 4B (Google), nel browser con WebGPU (Transformers.js + ONNX).
 * Come Bergamot, il testo non esce dal computer. Il modello pesa circa 3 GB: si cerca prima nella
 * cartella del pacchetto (app/modelli/translategemma), altrimenti si scarica una volta da Hugging Face
 * e resta nella cache del browser.
 */
import type { TextGenerationPipeline } from '@huggingface/transformers';

export const GEMMA_ID = 'onnx-community/translategemma-text-4b-it-ONNX';
const LOCAL_DIR = 'modelli/translategemma/';
/** Peso dei file del modello (q4): config, tokenizer, onnx e i due file di dati. */
const GEMMA_BYTES = 3_111_894_696;

/** Codici di lingua di TranslateGemma (quelli con variante regionale vogliono la regione). */
const CODES: Record<string, string> = {
  en: 'en', it: 'it_IT', fr: 'fr_FR', de: 'de_DE', es: 'es', pt: 'pt_PT', nl: 'nl_NL', pl: 'pl_PL',
  ro: 'ro_RO', cs: 'cs_CZ', sk: 'sk_SK', sl: 'sl_SI', hr: 'hr_HR', hu: 'hu_HU', bg: 'bg_BG', el: 'el_GR',
  da: 'da_DK', sv: 'sv_SE', fi: 'fi_FI', et: 'et_EE', lv: 'lv_LV', lt: 'lt_LT', ru: 'ru_RU', uk: 'uk_UA',
  tr: 'tr_TR', ja: 'ja_JP', ko: 'ko_KR', zh: 'zh_TW', ar: 'ar_SA', he: 'he_IL', fa: 'fa_IR', hi: 'hi_IN',
  id: 'id_ID', vi: 'vi_VN', th: 'th_TH', ca: 'ca_ES', is: 'is_IS', no: 'no_NO', nb: 'no_NO', sr: 'sr_RS',
};
export const gemmaSupports = (lang: string) => lang in CODES;

/** Modello mancante o incompleto nella cartella del programma (messaggio già pronto per chi usa l'app). */
export class GemmaMissing extends Error {}

export async function webgpuAvailable(): Promise<boolean> {
  try { return !!(await (navigator as any).gpu?.requestAdapter()); } catch { return false; }
}

let gen: Promise<TextGenerationPipeline> | null = null;
/** Scheda grafica usata (per i messaggi d'errore). */
export let gpuName = '';

/** Dimentica il modello caricato (dopo un errore della scheda grafica si ricarica da capo). */
export async function resetGemma() {
  const g = gen; gen = null;
  try { await (await g)?.dispose?.(); } catch { /* già perso */ }
}

/** Errore della scheda grafica (memoria esaurita, tempo massimo superato, dispositivo perso). */
export const isGpuError = (e: unknown) => /OrtRun|webgpu|GPUBuffer|BufferManager|device (was )?lost|mapAsync|out of memory|Aborted/i.test(e instanceof Error ? e.message : String(e));

/** Carica il modello (una volta). [status] riceve i messaggi di attesa con la percentuale. */
export function loadGemma(status: (m: string) => void): Promise<TextGenerationPipeline> {
  if (gen) return gen;
  gen = (async () => {
    if (!(await webgpuAvailable())) throw new Error('La traduzione accurata richiede WebGPU: usa Chrome o Edge aggiornati. Su questo computer usa la traduzione veloce.');
    try {
      const ad: any = await (navigator as any).gpu.requestAdapter({ powerPreference: 'high-performance' });
      const i = ad?.info ?? {};
      gpuName = [i.vendor, i.architecture, i.description].filter(Boolean).join(' ') || 'scheda grafica';
      gpuName += ` (buffer max ${Math.round((ad?.limits?.maxBufferSize ?? 0) / 1048576)} MB)`;
    } catch { gpuName = 'scheda grafica'; }
    const { pipeline, env } = await import('@huggingface/transformers');
    // Prima la copia nel pacchetto (funziona anche senza internet e dietro il proxy dell'ufficio)
    const local = new URL(import.meta.env.BASE_URL + LOCAL_DIR, location.href).href;
    // Il modello deve essere nella cartella del programma: scaricarlo dal browser (3 GB nella cache) è lento e
    // si interrompe facilmente, quindi lo si scarica una volta con ScaricaModelloAccurato.bat
    const has = (f: string) => fetch(local + GEMMA_ID + '/' + f, { method: 'HEAD' }).then(r => r.ok, () => false); // solo l'intestazione, non i 2 GB
    const files = ['config.json', 'tokenizer.json', 'tokenizer_config.json', 'onnx/model_q4.onnx', 'onnx/model_q4.onnx_data', 'onnx/model_q4.onnx_data_1'];
    const missing = [];
    for (const f of files) if (!(await has(f))) missing.push(f);
    if (missing.length) throw new GemmaMissing(missing.length === files.length
      ? 'Il modello della traduzione accurata non è nella cartella di DocVerso Pro. Chiudi DocVerso Pro, fai doppio clic su ScaricaModelloAccurato.bat (nella stessa cartella di DocVerso-Pro.bat), aspetta che finisca e riprova.'
      : `Il modello della traduzione accurata è incompleto (mancano: ${missing.join(', ')}). Rilancia ScaricaModelloAccurato.bat nella cartella di DocVerso Pro: riprende da dove era arrivato.`);
    const hasLocal = true;
    // Percorso relativo, non un indirizzo completo: con un indirizzo http Transformers.js (4.3) non controlla
    // i file locali del tokenizer e si ferma con "Cannot read properties of null (reading 'add_bos_token')"
    env.localModelPath = import.meta.env.BASE_URL + LOCAL_DIR;
    env.allowLocalModels = hasLocal;
    env.allowRemoteModels = !hasLocal;
    // Con la copia locale non serve duplicarla (3 GB) nella cache del browser
    env.useBrowserCache = !hasLocal;
    const ort = new URL(import.meta.env.BASE_URL + 'ort/', location.href).href;
    env.backends.onnx.wasm!.wasmPaths = { mjs: ort + 'ort-wasm-simd-threaded.asyncify.mjs', wasm: ort + 'ort-wasm-simd-threaded.asyncify.wasm' };
    const loaded = new Map<string, [number, number]>();
    let lastPct = -1;
    const p = await pipeline('text-generation', GEMMA_ID, {
      device: 'webgpu',
      dtype: 'q4',
      progress_callback: (e: any) => {
        if (e.status !== 'progress' || !e.total) return;
        loaded.set(e.file, [e.loaded, e.total]);
        // I file arrivano uno dopo l'altro: la percentuale si calcola sul peso totale noto del modello
        let d = 0;
        for (const [a] of loaded.values()) d += a;
        const pct = Math.min(100, Math.floor(d / GEMMA_BYTES * 100));
        if (pct !== lastPct) {
          lastPct = pct;
          status(hasLocal ? `Caricamento del modello di traduzione accurata: ${pct}%` : `Scarico il modello di traduzione accurata (circa 3 GB, solo la prima volta): ${pct}%`);
        }
      },
    });
    return p as TextGenerationPipeline;
  })();
  gen.catch(() => { gen = null; });
  return gen;
}

/** Traduce un testo (un paragrafo). */
/**
 * Traduce un testo. I paragrafi lunghi si traducono a pezzi di qualche frase: meno lavoro per volta sulla
 * scheda grafica (le schede integrate dei portatili altrimenti esauriscono la memoria o il tempo massimo).
 */
export async function gemmaTranslate(p: TextGenerationPipeline, text: string, from: string, to: string, maxChars = 400): Promise<string> {
  if (!text.trim()) return text;
  const parts: string[] = [];
  let cur = '';
  for (const s of text.split(/(?<=[.!?;:])\s+/)) {
    if (cur && cur.length + s.length + 1 > maxChars) { parts.push(cur); cur = s; } else cur = cur ? cur + ' ' + s : s;
  }
  if (cur) parts.push(cur);
  const out: string[] = [];
  for (const part of parts) out.push(await gemmaOne(p, part, from, to));
  return out.join(' ');
}

async function gemmaOne(p: TextGenerationPipeline, text: string, from: string, to: string): Promise<string> {
  const messages = [{ role: 'user', content: [{ type: 'text', source_lang_code: CODES[from], target_lang_code: CODES[to], text }] }];
  // Lunghezza massima: circa il doppio dei token del testo d'ingresso
  const max = Math.min(1024, Math.max(48, Math.ceil(text.length / 2)));
  const out: any = await (p as any)(messages, { max_new_tokens: max, do_sample: false });
  return String(out[0].generated_text.at(-1).content).trim();
}

/** Solo per le prove: carica il tokenizer come fa la traduzione accurata e conta i token di una frase. */
(globalThis as any).docversoTokenizerTest = async () => {
  const { AutoTokenizer, env } = await import('@huggingface/transformers');
  env.localModelPath = import.meta.env.BASE_URL + LOCAL_DIR;
  env.allowLocalModels = true; env.allowRemoteModels = false; env.useBrowserCache = false;
  const tok: any = await AutoTokenizer.from_pretrained(GEMMA_ID);
  const msgs = [{ role: 'user', content: [{ type: 'text', source_lang_code: 'en', target_lang_code: 'it_IT', text: 'Workers entering the confined space.' }] }];
  const text = tok.apply_chat_template(msgs, { tokenize: false, add_generation_prompt: true });
  return { bos: tok.add_bos_token, text };
};
