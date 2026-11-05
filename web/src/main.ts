import './style.css';
import { baseName, imagesToDocx, imagesToPdf, pdfToDocx, pdfToImages, type OutFile, type PageSize } from './convert';
import { getOcr, ocrAvailable } from './ocr';
import { AUTO, LANGUAGES, makeTranslate, translationAvailable } from './translate';
import { docxTranslate } from './docx';
import { officeStatus, officeToPdf, type OfficeStatus } from './office';

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

// ------------------------------------------------------------------ tema chiaro / scuro / automatico
const themeButtons = document.querySelectorAll<HTMLButtonElement>('[data-theme-set]');
function setTheme(t: string, save = true) {
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
  themeButtons.forEach(b => b.setAttribute('aria-checked', String(b.dataset.themeSet === t)));
  if (save) try { localStorage.setItem('docverso-theme', t); } catch { /* memoria del browser non disponibile */ }
}
themeButtons.forEach(b => b.addEventListener('click', () => setTheme(b.dataset.themeSet!)));
let savedTheme = 'auto';
try { savedTheme = localStorage.getItem('docverso-theme') ?? 'auto'; } catch { /* idem */ }
setTheme(savedTheme, false);

// ------------------------------------------------------------------ file scelti
type Kind = 'pdf' | 'docx' | 'word' | 'ppt' | 'image';
const kindOf = (f: File): Kind | null => {
  const n = f.name.toLowerCase();
  if (f.type === 'application/pdf' || n.endsWith('.pdf')) return 'pdf';
  if (n.endsWith('.docx')) return 'docx';
  if (/\.(doc|docm|rtf|odt|dotx?)$/.test(n)) return 'word';
  if (/\.(pptx?|pptm|ppsx?|odp)$/.test(n)) return 'ppt';
  if (f.type.startsWith('image/') || /\.(jpe?g|png|webp|bmp|gif|tiff?)$/.test(n)) return 'image';
  return null;
};
const KIND_LABEL: Record<Kind, string> = { pdf: 'PDF', docx: 'Word', word: 'Word', ppt: 'PowerPoint', image: 'Immagine' };

let files: File[] = [];

function sizeText(b: number) {
  return b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} kB` : `${(b / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

function addFiles(list: FileList | File[]) {
  const rejected: string[] = [];
  for (const f of Array.from(list)) {
    if (!kindOf(f)) { rejected.push(f.name); continue; }
    files.push(f);
  }
  // Un PDF o un Word si convertono uno alla volta; le immagini anche in gruppo
  const docs = files.filter(f => kindOf(f) !== 'image');
  if (docs.length > 0) files = [docs[docs.length - 1]];
  $('files-hint').textContent = rejected.length
    ? `Non posso convertire ${rejected.join(', ')}: scegli PDF, Word, PowerPoint o immagini.`
    : files.length > 1 ? 'Le immagini vanno nel documento in quest’ordine: usa le frecce per cambiarlo.' : '';
  render();
}

function renderFiles() {
  const ol = $('files');
  ol.replaceChildren(...files.map((f, i) => {
    const li = document.createElement('li');
    const k = kindOf(f)!;
    li.innerHTML = `<span class="kind">${KIND_LABEL[k]}</span><span class="fname"></span><span class="fsize">${sizeText(f.size)}</span><span class="move"></span>`;
    (li.querySelector('.fname') as HTMLElement).textContent = f.name;
    const move = li.querySelector('.move')!;
    const btn = (label: string, title: string, fn: () => void, show = true) => {
      if (!show) return;
      const b = document.createElement('button'); b.type = 'button'; b.textContent = label; b.title = title; b.setAttribute('aria-label', `${title}: ${f.name}`);
      b.addEventListener('click', fn); move.append(b);
    };
    btn('↑', 'Sposta su', () => { [files[i - 1], files[i]] = [files[i], files[i - 1]]; render(); }, files.length > 1 && i > 0);
    btn('↓', 'Sposta giù', () => { [files[i + 1], files[i]] = [files[i], files[i + 1]]; render(); }, files.length > 1 && i < files.length - 1);
    btn('✕', 'Togli', () => { files.splice(i, 1); render(); });
    return li;
  }));
}

const drop = $('drop');
const pick = $<HTMLInputElement>('pick');
pick.addEventListener('change', () => { if (pick.files) addFiles(pick.files); pick.value = ''; });
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => { e.preventDefault(); drop.classList.remove('over'); if (e.dataTransfer?.files) addFiles(e.dataTransfer.files); });
// Si può trascinare il file in qualsiasi punto della pagina
window.addEventListener('dragover', e => e.preventDefault());
window.addEventListener('drop', e => { e.preventDefault(); if (e.dataTransfer?.files && !drop.contains(e.target as Node)) addFiles(e.dataTransfer.files); });

// ------------------------------------------------------------------ conversioni possibili
// Se l'Office del computer risulta mancante, la scelta "PDF" resta visibile ma disattivata, con il motivo
interface Choice { id: string; title: string; text: string; needs?: 'ocr' | 'translate' | 'word' | 'powerpoint'; extra?: 'dpi' | 'page' | 'lang' }

function choicesFor(): Choice[] {
  if (files.length === 0) return [];
  const k = kindOf(files[0]);
  const n = files.length;
  if (k === 'pdf') return [
    { id: 'pdf-word', title: 'Word con la stessa impaginazione', text: 'Tabelle, caratteri, colori e immagini al loro posto. Le pagine scansionate passano dal riconoscimento del testo.' },
    { id: 'pdf-word-tr', title: 'Word tradotto', text: 'Come sopra, con il testo tradotto nella lingua che scegli.', needs: 'translate', extra: 'lang' },
    { id: 'pdf-jpg', title: 'Immagini JPEG', text: 'Una per pagina. Più pagine arrivano in un archivio zip.', extra: 'dpi' },
    { id: 'pdf-png', title: 'Immagini PNG', text: 'Una per pagina, senza perdita di qualità: adatte ai testi piccoli.', extra: 'dpi' },
  ];
  if (k === 'docx') return [
    { id: 'office-pdf', title: 'PDF', text: 'Con Word di questo computer: identico a “Salva come PDF”.', needs: 'word' },
    { id: 'docx-tr', title: 'Word tradotto', text: 'Cambia solo il testo: stili, tabelle, immagini, intestazioni e piè di pagina restano identici.', needs: 'translate', extra: 'lang' },
  ];
  if (k === 'word') return [
    { id: 'office-pdf', title: 'PDF', text: 'Con Word di questo computer: identico a “Salva come PDF”.', needs: 'word' },
  ];
  if (k === 'ppt') return [
    { id: 'office-pdf', title: 'PDF', text: 'Con PowerPoint di questo computer: una pagina per diapositiva, identica a “Salva come PDF”.', needs: 'powerpoint' },
  ];
  return [
    { id: 'img-pdf', title: n > 1 ? `Un PDF con ${n} pagine` : 'PDF', text: 'Una pagina per immagine, nell’ordine dell’elenco.', extra: 'page' },
    { id: 'img-word', title: 'Word con il testo modificabile', text: 'Il testo viene riconosciuto e impaginato; foto, loghi e timbri restano immagini.', needs: 'ocr' },
    { id: 'img-word-tr', title: 'Word tradotto', text: 'Testo riconosciuto e tradotto nella lingua che scegli.', needs: 'translate', extra: 'lang' },
  ];
}

let office: OfficeStatus | null = null;
const officeOk = (n: 'word' | 'powerpoint') => !!office && (office[n] || office.libreoffice);
const available = (c: Choice) => (c.needs !== 'ocr' || ocrAvailable) && (c.needs !== 'translate' || (translationAvailable && (c.id !== 'img-word-tr' || ocrAvailable)))
  && (c.needs !== 'word' || officeOk('word')) && (c.needs !== 'powerpoint' || officeOk('powerpoint'));
/** Perché una scelta non è disponibile. */
function why(c: Choice): string {
  if (c.needs === 'word' || c.needs === 'powerpoint') {
    if (!office) return 'Disponibile quando DocVerso è avviato con DocVerso.bat.';
    return `Serve Microsoft ${c.needs === 'word' ? 'Word' : 'PowerPoint'} (o LibreOffice) installato su questo computer.`;
  }
  return 'In preparazione: arriverà con il prossimo aggiornamento.';
}
// Office del computer: si chiede al server locale all'avvio, poi si aggiornano le scelte
officeStatus().then(s => { office = s; render(); });
let selected = '';
const prefs = { dpi: 200, page: 'a4' as PageSize, from: AUTO, to: 'it' };
try { Object.assign(prefs, JSON.parse(localStorage.getItem('docverso-prefs') ?? '{}')); } catch { /* idem */ }
const savePrefs = () => { try { localStorage.setItem('docverso-prefs', JSON.stringify(prefs)); } catch { /* idem */ } };

function select(label: string, options: [string, string][], value: string, onChange: (v: string) => void) {
  const l = document.createElement('label'); l.className = 'field'; l.textContent = label;
  const s = document.createElement('select');
  for (const [v, t] of options) { const o = new Option(t, v); o.selected = v === value; s.append(o); }
  s.addEventListener('change', () => onChange(s.value));
  l.append(s);
  return l;
}

function renderExtra(c: Choice | undefined) {
  const box = $('extra');
  box.replaceChildren();
  if (!c?.extra) return;
  if (c.extra === 'dpi') box.append(select('Risoluzione', [['100', '100 dpi (leggera)'], ['200', '200 dpi'], ['300', '300 dpi (per la stampa)']], String(prefs.dpi), v => { prefs.dpi = +v; savePrefs(); }));
  if (c.extra === 'page') box.append(select('Formato pagina', [['a4', 'A4'], ['letter', 'Letter (USA)'], ['image', 'Come l’immagine']], prefs.page, v => { prefs.page = v as PageSize; savePrefs(); }));
  if (c.extra === 'lang') {
    const langs = LANGUAGES.map(l => [l.code, l.name] as [string, string]);
    box.append(select('Da', [[AUTO, 'Rileva automaticamente'], ...langs], prefs.from, v => { prefs.from = v; savePrefs(); }));
    box.append(select('A', langs, prefs.to, v => { prefs.to = v; savePrefs(); }));
  }
}

function render() {
  renderFiles();
  const cs = choicesFor();
  const fs = $<HTMLFieldSetElement>('choices');
  $('empty').hidden = cs.length > 0;
  fs.hidden = cs.length === 0;
  $('go').hidden = cs.length === 0;
  if (!cs.some(c => c.id === selected && available(c))) selected = cs.find(available)?.id ?? '';
  fs.querySelectorAll('.choice').forEach(e => e.remove());
  for (const c of cs) {
    const ok = available(c);
    const lab = document.createElement('label'); lab.className = 'choice';
    lab.innerHTML = `<input type="radio" name="choice" value="${c.id}"${c.id === selected ? ' checked' : ''}${ok ? '' : ' disabled'}><span><b></b><small></small></span>`;
    lab.querySelector('b')!.textContent = c.title;
    // Senza Office ma con LibreOffice: lo si dice (il risultato è molto simile, non sempre identico)
    const viaLibre = ok && office && (c.needs === 'word' || c.needs === 'powerpoint') && !office[c.needs];
    lab.querySelector('small')!.textContent = !ok ? why(c) : viaLibre ? 'Con LibreOffice di questo computer (Office non è installato): molto simile all’originale.' : c.text;
    lab.querySelector('input')!.addEventListener('change', () => { selected = c.id; renderExtra(c); });
    fs.append(lab);
  }
  renderExtra(cs.find(c => c.id === selected));
  $('error').hidden = true;
}

// ------------------------------------------------------------------ esecuzione
function progress(done: number, total: number, msg: string) {
  $('progress').hidden = false;
  $('bar').style.width = `${total ? Math.min(100, Math.round(done / total * 100)) : 0}%`;
  $('progress-text').textContent = msg;
}

const langSuffix = () => prefs.to.toUpperCase();

async function run(): Promise<OutFile[]> {
  const f = files[0];
  const status = (m: string) => progress(0, 1, m);
  const translate = () => makeTranslate(prefs.from, prefs.to, status);
  switch (selected) {
    case 'pdf-word': return [await pdfToDocx(f, { ocr: getOcr(prefs.from), progress })];
    case 'pdf-word-tr': return [await pdfToDocx(f, { ocr: getOcr(prefs.from), translate: translate(), suffix: langSuffix(), progress })];
    case 'pdf-jpg': return [await pdfToImages(f, 'jpg', prefs.dpi, progress)];
    case 'pdf-png': return [await pdfToImages(f, 'png', prefs.dpi, progress)];
    case 'office-pdf': return [await officeToPdf(f, progress)];
    case 'docx-tr': return [await docxTranslate(f, translate(), langSuffix(), progress)];
    case 'img-pdf': return [await imagesToPdf(files, prefs.page, progress)];
    case 'img-word': return [await imagesToDocx(files, { ocr: getOcr(prefs.from), progress })];
    case 'img-word-tr': return [await imagesToDocx(files, { ocr: getOcr(prefs.from), translate: translate(), suffix: langSuffix(), progress })];
  }
  return [];
}

let urls: string[] = [];
$('go').addEventListener('click', async () => {
  const go = $<HTMLButtonElement>('go');
  go.disabled = true;
  $('error').hidden = true;
  progress(0, 1, 'Preparazione…');
  try {
    const out = await run();
    urls.forEach(u => URL.revokeObjectURL(u));
    urls = [];
    $('results').replaceChildren(...out.map(o => {
      const url = URL.createObjectURL(new Blob([o.bytes as BlobPart], { type: o.type }));
      urls.push(url);
      const li = document.createElement('li');
      li.innerHTML = `<span class="rname"></span><span class="rsize">${sizeText(o.bytes.length)}</span><a class="download" download>Scarica</a>`;
      li.querySelector('.rname')!.textContent = o.name;
      const a = li.querySelector('a')!; a.href = url; a.download = o.name;
      return li;
    }));
    $('verso-inner').classList.add('turned');
    (document.querySelector('.download') as HTMLElement | null)?.focus({ preventScroll: true });
  } catch (e) {
    console.error(e);
    const err = $('error');
    err.textContent = e instanceof Error && e.message ? e.message : `Non sono riuscito a convertire ${baseName(files[0]?.name ?? '')}.`;
    err.hidden = false;
  } finally {
    go.disabled = false;
    $('progress').hidden = true;
  }
});

$('again').addEventListener('click', () => {
  $('verso-inner').classList.remove('turned');
  $('go').focus({ preventScroll: true });
});

render();
