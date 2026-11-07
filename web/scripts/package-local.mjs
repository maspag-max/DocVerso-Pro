// Pacchetto per usare DocVerso Pro in locale (senza GitHub): DocVerso-Pro-locale.zip con
// DocVerso-Pro/app (la web app compilata), DocVerso-Pro.bat, server.ps1 e LEGGIMI.txt. Eseguire dopo "npm run build".
import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'fs';
import { join, relative } from 'path';
import { zipSync } from 'fflate';

const web = new URL('..', import.meta.url).pathname;
const dist = join(web, 'dist');
const local = join(web, '..', 'local');
if (!existsSync(join(dist, 'index.html'))) { console.error('Prima: npm run build'); process.exit(1); }

const files = {};
const walk = d => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else files['DocVerso-Pro/app/' + relative(dist, p).split('\\').join('/')] = readFileSync(p); } };
walk(dist);
// Modelli di traduzione inglese ↔ italiano (preparati con: node scripts/fetch-models.mjs)
// --aggiornamento: senza i modelli (per chi ha già la cartella completa: si estrae sopra quella)
const update = process.argv.includes('--aggiornamento');
const models = join(web, 'models-cache');
if (update) console.log('Pacchetto di aggiornamento: senza modelli');
else if (existsSync(models)) for (const pair of readdirSync(models)) for (const f of readdirSync(join(models, pair)))
  files[`DocVerso-Pro/app/modelli/${pair}/${f}`] = readFileSync(join(models, pair, f));
else console.warn('Attenzione: senza modelli di traduzione (node scripts/fetch-models.mjs)');
for (const f of ['DocVerso-Pro.bat', 'server.ps1', 'office2pdf.ps1', 'ScaricaModelloAccurato.bat', 'LEGGIMI.txt']) files['DocVerso-Pro/' + f] = readFileSync(join(local, f));
const out = process.argv.slice(2).find(a => !a.startsWith('--')) ?? join(web, update ? 'DocVerso-Pro-aggiornamento.zip' : 'DocVerso-Pro-locale.zip');
writeFileSync(out, zipSync(Object.fromEntries(Object.entries(files).map(([k, v]) => [k, k.includes('/modelli/') ? [v, { level: 1 }] : v])), { level: 6 }));
console.log(`${out}: ${Object.keys(files).length} file, ${(statSync(out).size / 1048576).toFixed(1)} MB`);
