// Copia il motore Kotlin/JS compilato dentro src/engine (eseguire prima la build Gradle)
import { cpSync, existsSync, mkdirSync } from 'fs';
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
