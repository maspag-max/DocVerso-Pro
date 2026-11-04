// Pacchetto per usare DocVerso in locale (senza GitHub): DocVerso-locale.zip con
// DocVerso/app (la web app compilata), DocVerso.bat, server.ps1 e LEGGIMI.txt. Eseguire dopo "npm run build".
import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'fs';
import { join, relative } from 'path';
import { zipSync } from 'fflate';

const web = new URL('..', import.meta.url).pathname;
const dist = join(web, 'dist');
const local = join(web, '..', 'local');
if (!existsSync(join(dist, 'index.html'))) { console.error('Prima: npm run build'); process.exit(1); }

const files = {};
const walk = d => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else files['DocVerso/app/' + relative(dist, p).split('\\').join('/')] = readFileSync(p); } };
walk(dist);
for (const f of ['DocVerso.bat', 'server.ps1', 'LEGGIMI.txt']) files['DocVerso/' + f] = readFileSync(join(local, f));
const out = process.argv[2] ?? join(web, 'DocVerso-locale.zip');
writeFileSync(out, zipSync(files, { level: 6 }));
console.log(`${out}: ${Object.keys(files).length} file, ${(statSync(out).size / 1048576).toFixed(1)} MB`);
