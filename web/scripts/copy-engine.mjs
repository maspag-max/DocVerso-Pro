// Copia il motore Kotlin/JS compilato dentro src/engine (eseguire prima la build Gradle)
import { cpSync, existsSync, mkdirSync, readdirSync } from 'fs';
const src = new URL('../../engine/build/dist/js/productionLibrary/', import.meta.url);
const dst = new URL('../src/engine/', import.meta.url);
if (!existsSync(src)) { console.error('Motore non compilato: ./gradlew :engine:jsBrowserProductionLibraryDistribution'); process.exit(1); }
mkdirSync(dst, { recursive: true });
cpSync(src, dst, { recursive: true });
// TypeScript cerca i tipi di un .mjs in un .d.mts
cpSync(new URL('docverso-engine.d.ts', src), new URL('docverso-engine.d.mts', dst));
console.log('Motore copiato in src/engine');

// Caratteri standard e mappe dei caratteri di pdf.js (serviti dall'app, niente richieste esterne)
const pdfjsDir = new URL('../node_modules/pdfjs-dist/', import.meta.url);
const pub = new URL('../public/pdfjs/', import.meta.url);
for (const d of ['standard_fonts', 'cmaps']) cpSync(new URL(d + '/', pdfjsDir), new URL(d + '/', pub), { recursive: true });
console.log('Risorse di pdf.js copiate in public/pdfjs');

// OCR (Tesseract.js): lavoratore, motore WebAssembly e lingue serviti dall'app (nessun CDN)
const nm = new URL('../node_modules/', import.meta.url);
const tess = new URL('../public/tesseract/', import.meta.url);
mkdirSync(new URL('lang/', tess), { recursive: true });
cpSync(new URL('tesseract.js/dist/worker.min.js', nm), new URL('worker.min.js', tess));
for (const f of readdirSync(new URL('tesseract.js-core/', nm))) if (f.includes('lstm')) cpSync(new URL('tesseract.js-core/' + f, nm), new URL(f, tess));
for (const l of ['ita', 'eng', 'fra', 'deu', 'spa', 'por'])
  cpSync(new URL(`@tesseract.js-data/${l}/4.0.0_best_int/${l}.traineddata.gz`, nm), new URL(`lang/${l}.traineddata.gz`, tess));
console.log('OCR copiato in public/tesseract');

// Traduzione (Bergamot): lavoratore e motore WebAssembly serviti dall'app; i modelli si scaricano a parte
const berg = new URL('../public/bergamot/', import.meta.url);
mkdirSync(berg, { recursive: true });
for (const f of ['translator-worker.js', 'bergamot-translator-worker.js', 'bergamot-translator-worker.wasm'])
  cpSync(new URL('@mkljczk/bergamot-translator/worker/' + f, nm), new URL(f, berg));
console.log('Traduzione copiata in public/bergamot');
