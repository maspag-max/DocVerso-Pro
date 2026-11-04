# DocVerso

Web app per PC che converte e traduce documenti **interamente nel browser**: i file non escono dal computer, niente pubblicità.

Deriva dalle app Android [ScanDoc](https://github.com/maspag-max/ScanDoc) e [TradScan](https://github.com/maspag-max/TradScan): il motore di impaginazione (Kotlin puro) è lo stesso, compilato in JavaScript con Kotlin/JS.

## Funzioni (prima versione)
- PDF → Word fedele (tabelle dalle linee, testo in posizione, immagini e codici a barre ancorati), con o senza traduzione
- Word → Word tradotto (impaginazione intatta)
- JPEG/PNG → PDF o Word (OCR con Tesseract.js)
- PDF → JPEG/PNG, più immagini → un PDF

## Struttura
- `engine/` — motore Kotlin Multiplatform (target JVM per i test, JS per la web app)
- `web/` — web app (Vite + TypeScript): pdf.js, Tesseract.js, Bergamot

## Sviluppo
```
./gradlew :engine:jvmTest                                # test del motore
./gradlew :engine:jsBrowserProductionLibraryDistribution # motore JS
```

Realizzata da Massimo Spagnuolo.
