# Documenti di prova

Documenti sintetici per le prove di conversione (nessun dato reale):
- `permit_en.pdf` — modulo in inglese (reportlab, font Type 1 standard): logo vettoriale con testo, tabella con celle unite, sfondi, caselle ☐, foto, codice a barre Code 128.
- `verbale_it.pdf` — verbale in italiano creato in Word (python-docx) e convertito con LibreOffice: font TrueType incorporati, elenco puntato, tabella, foto.

Prova dalla riga di comando (stesso codice della web app):
```
cd web
npx tsx scripts/convert.ts ../testdocs/permit_en.pdf /tmp/permit.docx [--fake-translate]
scripts/compare.sh ../testdocs/permit_en.pdf /tmp/permit.docx /tmp/confronto   # immagini affiancate originale ↔ Word
```
