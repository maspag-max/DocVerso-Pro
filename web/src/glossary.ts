/**
 * Glossario dei termini fissi (inglese ↔ italiano): termini tecnici che il traduttore generico rende male
 * (es. "confined space" → "spazio ristretto" invece di "ambiente confinato").
 *
 * Come funziona: prima della traduzione ogni termine del glossario è sostituito da un segnaposto (ZQX0,
 * ZQX1…) che il traduttore lascia com'è; dopo, il segnaposto diventa il termine scelto. In italiano si
 * sistema anche l'articolo davanti (il/lo/l'/la, del/dello/dell'/della, nel/nello…, un/uno/una…), che il
 * traduttore aveva scelto senza conoscere il termine.
 */

export interface Term {
  en: string;
  it: string;
  /** Genere e numero del termine italiano: m/f, singolare/plurale. */
  gender: 'm' | 'f';
  plural: boolean;
}

/** Glossario di partenza: sicurezza sul lavoro, ambienti confinati, normativa, ricerca. */
export const DEFAULT_GLOSSARY = `# Una riga per termine:  termine inglese = termine italiano
# Genere e numero del termine italiano si capiscono dalla parola (es. -o maschile, -a femminile);
# se non è chiaro aggiungi alla fine (m), (f), (m pl) o (f pl). Le righe che iniziano con # sono note.

# Ambienti confinati
confined space = ambiente confinato (m)
confined spaces = ambienti confinati (m pl)
confined environment = ambiente confinato (m)
confined environments = ambienti confinati (m pl)
confined and/or suspected of pollution spaces = ambienti confinati e/o sospetti di inquinamento (m pl)
confined and/or suspected of pollution environments = ambienti confinati e/o sospetti di inquinamento (m pl)
confined and/or suspected of pollution environment = ambiente confinato e/o sospetto di inquinamento (m)
confined and/or suspected polluted environments = ambienti confinati e/o sospetti di inquinamento (m pl)
confined and/or suspected polluted environment = ambiente confinato e/o sospetto di inquinamento (m)
confined and/or pollution suspected spaces = ambienti confinati e/o sospetti di inquinamento (m pl)
confined and/or pollution suspected space = ambiente confinato e/o sospetto di inquinamento (m)
confined and/or suspected of pollution = confinati e/o sospetti di inquinamento (m pl)
suspected or confined environments = ambienti sospetti di inquinamento o confinati (m pl)
suspected polluted environments = ambienti sospetti di inquinamento (m pl)
suspected polluted environment = ambiente sospetto di inquinamento (m)
environments suspected of pollution = ambienti sospetti di inquinamento (m pl)
oxygen deficiency = carenza di ossigeno
entry permit = permesso di ingresso (m)
permit to work = permesso di lavoro (m)
work permit = permesso di lavoro (m)
gas detector = rilevatore di gas (m)
multi-gas detector = rilevatore multigas (m)
multigas detector = rilevatore multigas (m)
rescue plan = piano di soccorso (m)
rescue personnel = personale di soccorso (m)
manhole = passo d'uomo (m)
manholes = passi d'uomo (m pl)

# Sicurezza sul lavoro
occupational safety and health = salute e sicurezza sul lavoro (f)
occupational health and safety = salute e sicurezza sul lavoro (f)
health and safety at work = salute e sicurezza sul lavoro (f)
safety and health at work = salute e sicurezza sul lavoro (f)
risk assessment = valutazione dei rischi (f)
risk assessments = valutazioni dei rischi (f pl)
hazard identification = identificazione dei pericoli (f)
risk factor = fattore di rischio (m)
risk factors = fattori di rischio (m pl)
personal protective equipment = dispositivi di protezione individuale (m pl)
PPE = DPI (m pl)
safety data sheet = scheda di dati di sicurezza (f)
safety data sheets = schede di dati di sicurezza (f pl)
near miss = mancato infortunio (m)
near misses = mancati infortuni (m pl)
occupational accident = infortunio sul lavoro (m)
occupational accidents = infortuni sul lavoro (m pl)
accident at work = infortunio sul lavoro (m)
accidents at work = infortuni sul lavoro (m pl)
work-related accident = infortunio sul lavoro (m)
work-related accidents = infortuni sul lavoro (m pl)
occupational disease = malattia professionale (f)
occupational diseases = malattie professionali (f pl)
operating procedure = procedura operativa (f)
operating procedures = procedure operative (f pl)
emergency procedure = procedura di emergenza (f)
emergency procedures = procedure di emergenza (f pl)
lockout/tagout = lockout/tagout (m)
self-employed workers = lavoratori autonomi (m pl)
self-employed worker = lavoratore autonomo (m)
employer = datore di lavoro (m)
employers = datori di lavoro (m pl)
training course = corso di formazione (m)
training courses = corsi di formazione (m pl)

# Normativa
Legislative Decree = Decreto Legislativo (m)
Presidential Decree = Decreto del Presidente della Repubblica (m)
technical standard = norma tecnica (f)
technical standards = norme tecniche (f pl)

# Impianti e ricerca
predictive maintenance = manutenzione predittiva (f)
waste-to-energy plant = impianto di termovalorizzazione (m)
waste-to-energy plants = impianti di termovalorizzazione (m pl)
virtual reality = realtà virtuale (f)
augmented reality = realtà aumentata (f)
`;

const KEY = 'docverso-glossario';
const ON_KEY = 'docverso-glossario-attivo';

export function loadGlossaryText(): string {
  try { return localStorage.getItem(KEY) ?? DEFAULT_GLOSSARY; } catch { return DEFAULT_GLOSSARY; }
}
export function saveGlossaryText(text: string) {
  try { if (text.trim() === DEFAULT_GLOSSARY.trim()) localStorage.removeItem(KEY); else localStorage.setItem(KEY, text); } catch { /* memoria del browser non disponibile */ }
}
export function glossaryEnabled(): boolean {
  try { return localStorage.getItem(ON_KEY) !== 'no'; } catch { return true; }
}
export function setGlossaryEnabled(on: boolean) {
  try { localStorage.setItem(ON_KEY, on ? 'si' : 'no'); } catch { /* idem */ }
}

/** Righe "inglese = italiano (m pl)" → termini. Le righe sbagliate sono ignorate. */
export function parseGlossary(text: string): Term[] {
  const out: Term[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const en = line.slice(0, eq).trim();
    let it = line.slice(eq + 1).trim();
    let gender: 'm' | 'f' | null = null;
    let plural: boolean | null = null;
    const tag = it.match(/\(\s*([mf])\s*(pl)?\s*\)\s*$/i);
    if (tag) { gender = tag[1].toLowerCase() as 'm' | 'f'; plural = !!tag[2]; it = it.slice(0, tag.index).trim(); }
    if (!en || !it) continue;
    // Genere e numero dalla prima parola italiana, se non indicati
    const head = it.split(/\s+/)[0].toLowerCase();
    if (gender === null) gender = /a$|e$/.test(head) && !/ente$|ore$|ere$/.test(head) ? 'f' : 'm';
    if (plural === null) plural = /[ie]$/.test(head) && !/ente$/.test(head) && /s$/i.test(en);
    out.push({ en, it, gender, plural });
  }
  return out;
}

// ------------------------------------------------------------------ segnaposti

/**
 * Due tipi di segnaposto.
 * - "Parole vicarie" (prima scelta): il termine diventa una parola comune dello stesso genere e numero
 *   (document/documento, window/finestra…) che il traduttore rende sempre allo stesso modo. Così concorda
 *   bene aggettivi e participi ("gli ambienti confinati sono pericolosi"); dopo la si sostituisce col termine.
 * - Sigle ZQX0, ZQX1… (riserva): il traduttore le lascia intatte ma ne indovina il genere.
 */
const PROXIES: Record<'m' | 'f', { en: string; enPl: string; it: string; itPl: string }[]> = {
  m: [
    { en: 'document', enPl: 'documents', it: 'documento', itPl: 'documenti' },
    { en: 'instrument', enPl: 'instruments', it: 'strumento', itPl: 'strumenti' },
    { en: 'element', enPl: 'elements', it: 'elemento', itPl: 'elementi' },
    { en: 'object', enPl: 'objects', it: 'oggetto', itPl: 'oggetti' },
    { en: 'ticket', enPl: 'tickets', it: 'biglietto', itPl: 'biglietti' },
    { en: 'book', enPl: 'books', it: 'libro', itPl: 'libri' },
    { en: 'mirror', enPl: 'mirrors', it: 'specchio', itPl: 'specchi' },
    { en: 'notebook', enPl: 'notebooks', it: 'quaderno', itPl: 'quaderni' },
    { en: 'carpet', enPl: 'carpets', it: 'tappeto', itPl: 'tappeti' },
  ],
  f: [
    { en: 'window', enPl: 'windows', it: 'finestra', itPl: 'finestre' },
    { en: 'chair', enPl: 'chairs', it: 'sedia', itPl: 'sedie' },
    { en: 'machine', enPl: 'machines', it: 'macchina', itPl: 'macchine' },
    { en: 'structure', enPl: 'structures', it: 'struttura', itPl: 'strutture' },
    { en: 'candle', enPl: 'candles', it: 'candela', itPl: 'candele' },
    { en: 'bottle', enPl: 'bottles', it: 'bottiglia', itPl: 'bottiglie' },
    { en: 'shirt', enPl: 'shirts', it: 'camicia', itPl: 'camicie' },
    { en: 'lamp', enPl: 'lamps', it: 'lampada', itPl: 'lampade' },
    { en: 'box', enPl: 'boxes', it: 'scatola', itPl: 'scatole' },
  ],
};
type Proxy = (typeof PROXIES)['m'][number];

const TOKEN = (k: number) => `ZQX${k}`;
const TOKEN_RE = /ZQX(\d+)/g;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const word = (w: string) => new RegExp(`(?<![\\p{L}\\p{N}])${esc(w)}(?![\\p{L}\\p{N}])`, 'iu');

export interface Protected {
  text: string;
  mode: 'proxy' | 'token';
  terms: { term: Term; caps: 'lower' | 'upper'; proxy?: Proxy }[];
}

/** Sostituisce i termini del glossario (lingua [from]) con segnaposti. */
export function protect(text: string, terms: Term[], from: 'en' | 'it', mode: 'proxy' | 'token' = 'proxy'): Protected {
  const sorted = [...terms].sort((a, b) => b[from].length - a[from].length);
  if (sorted.length === 0) return { text, mode, terms: [] };
  // Spazi e trattini flessibili; parola intera (niente lettere o cifre attaccate)
  const flex = (t: string) => esc(t).replace(/\s*\/\s*|\\-|\s+/g, m => (m.includes('/') ? '\\s*\\/\\s*' : m === '\\-' ? '[-\\s]?' : '[\\s-]+'));
  const re = new RegExp(`(?<![\\p{L}\\p{N}])(${sorted.map(t => flex(t[from])).join('|')})(?![\\p{L}\\p{N}])`, 'giu');
  // Parole vicarie libere: quelle che non compaiono già nel testo
  const free = { m: PROXIES.m.filter(p => ![p.en, p.enPl, p.it, p.itPl].some(w => word(w).test(text))), f: PROXIES.f.filter(p => ![p.en, p.enPl, p.it, p.itPl].some(w => word(w).test(text))) };
  const used: Protected['terms'] = [];
  let out = '';
  let last = 0;
  for (const mt of text.matchAll(re)) {
    const m = mt[0];
    const term = sorted.find(t => new RegExp(`^${flex(t[from])}$`, 'iu').test(m));
    if (!term) continue;
    // Sigle (PPE, OSH): solo se scritte uguali
    if (term[from] === term[from].toUpperCase() && m !== term[from]) continue;
    const caps: 'lower' | 'upper' = m === m.toUpperCase() && /\p{L}{2}/u.test(m) && m !== term[from] ? 'upper' : 'lower';
    let before = out + text.slice(last, mt.index);
    let repl: string;
    if (mode === 'proxy') {
      // Stesso termine → stessa parola vicaria (un paragrafo può ripetere molte volte lo stesso termine)
      const proxy = used.find(u => u.term === term)?.proxy ?? free[term.gender].shift();
      if (!proxy) {
        // Parole vicarie finite: per questo termine una sigla
        used.push({ term, caps });
        out = before + TOKEN(used.length - 1);
        last = mt.index! + m.length;
        continue;
      }
      used.push({ term, caps, proxy });
      const w = from === 'en' ? (term.plural ? proxy.enPl : proxy.en) : term.plural ? proxy.itPl : proxy.it;
      if (from === 'it') before = fixArticle(before, { en: '', it: w, gender: term.gender, plural: term.plural });
      else before = fixEnglishArticle(before, w);
      repl = caps === 'upper' ? w.toUpperCase() : /^\p{Lu}/u.test(m) && !/^\p{Lu}/u.test(term[from]) ? w[0].toUpperCase() + w.slice(1) : w;
    } else {
      used.push({ term, caps });
      repl = TOKEN(used.length - 1);
    }
    out = before + repl;
    last = mt.index! + m.length;
  }
  return { text: out + text.slice(last), mode, terms: used };
}

// ------------------------------------------------------------------ articoli italiani

/** Il termine vuole "lo/gli" (s impura, z, gn, ps, x, y, pn) o l'apostrofo (vocale)? */
function startKind(word: string): 'vowel' | 'lo' | 'plain' {
  const w = word.toLowerCase();
  if (/^[aeiouàèéìòóù]/.test(w) || /^h[aeiou]/.test(w)) return 'vowel';
  if (/^(s[bcdfghjklmnpqrstvwxz]|z|gn|ps|pn|x|y|i[aeiou])/.test(w)) return 'lo';
  return 'plain';
}

/** Articolo determinativo giusto per il termine. */
function definite(t: Term): string {
  const k = startKind(t.it);
  if (t.gender === 'm') return t.plural ? (k === 'plain' ? 'i' : 'gli') : k === 'vowel' ? "l'" : k === 'lo' ? 'lo' : 'il';
  return t.plural ? 'le' : k === 'vowel' ? "l'" : 'la';
}

/** Preposizioni articolate: radice + articolo (del, dello, dell', della, dei, degli, delle…). */
const PREP: Record<string, string> = { di: 'de', a: 'a', da: 'da', in: 'ne', su: 'su' };

function articulate(prep: string, art: string): string {
  // il → l, lo → llo, l' → ll', la → lla, i → i, gli → gli, le → lle
  const map: Record<string, string> = { il: 'l', lo: 'llo', "l'": "ll'", la: 'lla', i: 'i', gli: 'gli', le: 'lle' };
  return PREP[prep] + map[art];
}

/** Riconosce l'articolo (semplice, articolato o indeterminativo) che precede il segnaposto. */
const ART_BEFORE = /(?<![\p{L}])(il|lo|la|l['’]|i|gli|le|un|uno|una|un['’]|(?:de|a|da|ne|su)(?:l|llo|lla|ll['’]|i|gli|lle))(\s*)$/iu;

function fixArticle(before: string, t: Term): string {
  const m = before.match(ART_BEFORE);
  if (!m) return before;
  const found = m[1];
  const low = found.toLowerCase().replace('’', "'");
  const cap = /^\p{Lu}/u.test(found);
  const k = startKind(t.it);
  let repl: string;
  if (['un', 'uno', 'una', "un'"].includes(low)) {
    if (t.plural) return before; // "un" davanti a un plurale: meglio non toccare
    repl = t.gender === 'm' ? (k === 'lo' ? 'uno' : 'un') : k === 'vowel' ? "un'" : 'una';
  } else if (['il', 'lo', 'la', "l'", 'i', 'gli', 'le'].includes(low)) {
    repl = definite(t);
  } else {
    const prep = Object.entries(PREP).find(([, root]) => low.startsWith(root))?.[0];
    if (!prep) return before;
    repl = articulate(prep, definite(t));
  }
  if (cap) repl = repl[0].toUpperCase() + repl.slice(1);
  const space = repl.endsWith("'") ? '' : ' ';
  return before.slice(0, m.index) + repl + space;
}

/** Articolo inglese a/an davanti al termine. */
function fixEnglishArticle(before: string, word: string): string {
  return before.replace(/(?<![\p{L}])(a|an|A|An)(\s+)$/u, (_, a: string, sp: string) => {
    const an = /^[aeiou]/i.test(word) && !/^(uni|eu|one|use)/i.test(word);
    const out = an ? 'an' : 'a';
    return (/^A/.test(a) ? out[0].toUpperCase() + out.slice(1) : out) + sp;
  });
}

/** Rimette i termini al posto dei segnaposti (lingua [to]); null se il traduttore ne ha perso qualcuno. */
export function restore(translated: string, p: Protected, to: 'en' | 'it'): string | null {
  if (p.terms.length === 0) return translated;
  // Posizioni dei segnaposti nella traduzione
  const hits: { k: number; index: number; len: number }[] = [];
  for (const m of translated.matchAll(TOKEN_RE)) {
    if (!p.terms[+m[1]] || p.terms[+m[1]].proxy) return null;
    hits.push({ k: +m[1], index: m.index!, len: m[0].length });
  }
  {
    // Ogni parola vicaria deve comparire tante volte quante i termini che sostituisce, nello stesso ordine
    for (const px of new Set(p.terms.filter(t => t.proxy).map(t => t.proxy!))) {
      const slots = p.terms.map((t, k) => (t.proxy === px ? k : -1)).filter(k => k >= 0);
      const forms = to === 'it' ? [px.itPl, px.it] : [px.enPl, px.en];
      const re = new RegExp(`(?<![\\p{L}\\p{N}])(${forms.map(esc).join('|')})(?![\\p{L}\\p{N}])`, 'giu');
      const found = [...translated.matchAll(re)];
      if (found.length !== slots.length) return null; // persa o duplicata: meglio rinunciare
      found.forEach((f, i) => hits.push({ k: slots[i], index: f.index!, len: f[0].length }));
    }
    hits.sort((a, b) => a.index - b.index);
  }
  if (new Set(hits.map(h => h.k)).size !== p.terms.length || hits.length !== p.terms.length) return null;
  let out = '';
  let last = 0;
  for (const h of hits) {
    const entry = p.terms[h.k];
    let before = out + translated.slice(last, h.index);
    let w = entry.term[to];
    if (to === 'it') before = fixArticle(before, entry.term);
    else before = fixEnglishArticle(before, w);
    // Tutto maiuscolo se lo era nell'originale; iniziale maiuscola solo a inizio frase
    const startOfSentence = /(^|[.!?:]\s+)$/.test(before) || before.trim() === '';
    if (entry.caps === 'upper') w = w.toUpperCase();
    else if (startOfSentence) w = w[0].toUpperCase() + w.slice(1);
    out = before + w;
    last = h.index + h.len;
  }
  return out + translated.slice(last);
}

// ------------------------------------------------------------------ per la traduzione accurata

/**
 * TranslateGemma capisce anche la lingua d'arrivo: invece dei segnaposti (che reinterpreta) si scrive il
 * termine già tradotto dentro la frase d'origine; il modello lo tiene e ci concorda articoli e aggettivi.
 * [quoted]: termini da mettere tra «» (secondo tentativo, se al primo il modello li ha ritradotti).
 */
export function inject(text: string, terms: Term[], from: 'en' | 'it', to: 'en' | 'it', quoted = new Set<Term>()) {
  const p = protect(text, terms, from, 'token');
  const used: Term[] = [];
  const out = p.text.replace(TOKEN_RE, (_, k) => {
    const { term, caps } = p.terms[+k];
    used.push(term);
    const w = caps === 'upper' ? term[to].toUpperCase() : term[to];
    return quoted.has(term) ? `«${w}»` : w;
  });
  return { text: out, used };
}

/** Termini inseriti che nella traduzione non compaiono più (il modello li ha ritradotti o tolti). */
export function missingTerms(translated: string, used: Term[], to: 'en' | 'it'): Term[] {
  const low = translated.toLowerCase().replace(/[’]/g, "'");
  return [...new Set(used)].filter(t => !low.includes(t[to].toLowerCase()));
}

/** Toglie le «» aggiunte da [inject] (solo attorno ai termini messi tra virgolette da noi). */
export function unquote(translated: string, quoted: Set<Term>, to: 'en' | 'it'): string {
  let s = translated;
  for (const t of quoted) s = s.replace(new RegExp(`[«"“]\\s*(${esc(t[to])})\\s*[»"”]`, 'giu'), '$1');
  return s;
}
