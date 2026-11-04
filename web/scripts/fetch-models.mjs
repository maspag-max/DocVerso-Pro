// Scarica i modelli di traduzione da includere nel pacchetto locale (funzionano senza internet,
// anche dietro proxy aziendali). Ogni file è verificato con l'impronta SHA-256 dell'elenco ufficiale
// di Mozilla. Uso: node scripts/fetch-models.mjs [coppie...]   (predefinite: en-it it-en)
import { createHash } from 'crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';

const MIRROR = 'https://huggingface.co/midudev/firefox-translations';
const RECORDS = 'https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records';
const pairs = process.argv.slice(2).length ? process.argv.slice(2) : ['en-it', 'it-en'];
const outRoot = new URL('../models-cache/', import.meta.url);

const official = new Set((await (await fetch(RECORDS)).json()).data.map(r => r.attachment?.hash).filter(Boolean));
const kindOf = n => n.startsWith('model.') ? 'model' : n.startsWith('lex.') ? 'lex' : n.startsWith('srcvocab') ? 'srcvocab'
  : n.startsWith('trgvocab') ? 'trgvocab' : n.startsWith('vocab') ? 'vocab' : null;

for (const pair of pairs) {
  const list = await (await fetch(`${MIRROR.replace('huggingface.co/', 'huggingface.co/api/models/')}/tree/main/${pair}`)).json();
  const dir = new URL(`${pair}/`, outRoot);
  mkdirSync(dir, { recursive: true });
  const files = {};
  for (const f of list) {
    const name = f.path.split('/').pop();
    const kind = kindOf(name);
    const sha = f.lfs?.oid;
    if (!kind || !sha) continue;
    if (!official.has(sha)) throw new Error(`${pair}/${name}: impronta non presente nell'elenco di Mozilla`);
    const dest = new URL(name, dir);
    const have = existsSync(dest) && createHash('sha256').update(readFileSync(dest)).digest('hex') === sha;
    if (!have) {
      const buf = Buffer.from(await (await fetch(`${MIRROR}/resolve/main/${f.path}`)).arrayBuffer());
      if (createHash('sha256').update(buf).digest('hex') !== sha) throw new Error(`${pair}/${name}: file scaricato diverso dall'originale`);
      writeFileSync(dest, buf);
    }
    files[kind] = { name, size: f.size, sha256: sha };
  }
  writeFileSync(new URL('index.json', dir), JSON.stringify({ pair, files }, null, 1));
  console.log(`${pair}: ${Object.values(files).map(f => f.name).join(', ')}`);
}
