# DocVerso Pro

DocVerso con in più la **traduzione accurata**: [TranslateGemma 4B](https://huggingface.co/onnx-community/translategemma-text-4b-it-ONNX)
di Google, eseguito nel browser con WebGPU (Transformers.js + ONNX Runtime). Come in DocVerso, i
documenti non escono mai dal computer.

- Conversioni: PDF → Word fedele, Word → Word tradotto, immagini → PDF/Word, PDF → JPEG/PNG,
  più immagini → un PDF, Word/PowerPoint → PDF (con Office del computer).
- Traduzione **veloce** (Bergamot, Mozilla) oppure **accurata** (TranslateGemma, ~3 GB, lenta).
- Glossario dei termini tecnici inglese ↔ italiano, valido con entrambi i motori:
  con Bergamot tramite parole vicarie dello stesso genere e numero; con TranslateGemma inserendo il
  termine già tradotto nel testo, con un secondo tentativo tra «» se il modello lo ritraduce.

## Uso in locale

Scarica `DocVerso-Pro-locale.zip` (pagina Actions, ultima esecuzione, sezione Artifacts), estrai la
cartella e fai doppio clic su `DocVerso-Pro.bat`. Per la traduzione accurata senza internet lancia
una volta `ScaricaModelloAccurato.bat` (scarica il modello in `app/modelli/translategemma`).

Realizzata da Massimo Spagnuolo.
