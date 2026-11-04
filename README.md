# DocVerso

Web app per PC che converte e traduce documenti **interamente nel browser**: i file non escono dal computer, niente pubblicità.

Deriva dalle app Android [ScanDoc](https://github.com/maspag-max/ScanDoc) e [TradScan](https://github.com/maspag-max/TradScan): il motore di impaginazione (Kotlin puro) è lo stesso, compilato in JavaScript con Kotlin/JS.

Indirizzo (dopo l'attivazione di GitHub Pages): https://maspag-max.github.io/DocVerso/

## Funzioni (prima versione)
- PDF → Word fedele (tabelle dalle linee, testo in posizione, immagini e codici a barre ancorati), con o senza traduzione
- Word → Word tradotto (impaginazione intatta)
- JPEG/PNG → PDF o Word (OCR con Tesseract.js)
- PDF → JPEG/PNG, più immagini → un PDF

## Come funziona
- **PDF digitali** (pdf.js): ogni lettera con posizione, carattere e colore, linee, riquadri, disegni e
  immagini → pagina Word esatta (ExactLayout di TradScan): tabelle con celle unite e sfondi, testo in posizione.
- **Scansioni e foto** (Tesseract.js): pagina raddrizzata, linee, sfondi, foto, codici a barre e caselle
  dai pixel, parole dall'OCR → stessa ricostruzione esatta dei PDF digitali.
- **Traduzione** (Bergamot, modelli di Firefox Translations): tutte le lingue via inglese, rilevamento
  automatico. I modelli si scaricano una volta (circa 35 MB per lingua) da una copia su Hugging Face e
  sono verificati con le impronte SHA-256 dell'elenco ufficiale di Mozilla; poi restano nella cache del browser.
- Nel Word tradotto il carattere si riduce un poco dove il testo non sta nelle righe originali.

Il testo dei documenti non esce mai dal computer; da internet arrivano solo l'app e i modelli di traduzione.

## Struttura
- `engine/` — motore Kotlin Multiplatform (target JVM per i test, JS per la web app)
- `web/` — web app (Vite + TypeScript): pdf.js, Tesseract.js, Bergamot

## Sviluppo
```
./gradlew :engine:jvmTest                                # test del motore
./gradlew :engine:jsBrowserProductionLibraryDistribution # motore JS
cd web && npm ci && npm run dev                          # app in locale
npx tsx scripts/convert.ts documento.pdf uscita.docx    # stessa conversione dalla riga di comando
scripts/compare.sh documento.pdf uscita.docx confronto   # confronto visivo originale ↔ Word
```
La pubblicazione su GitHub Pages è automatica a ogni push su `main` (`.github/workflows/pages.yml`).

Realizzata da Massimo Spagnuolo.
