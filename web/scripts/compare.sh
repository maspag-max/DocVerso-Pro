#!/bin/bash
# Confronto visivo originale ↔ Word: compare.sh originale.pdf convertito.docx cartella_uscita
set -e
orig="$1"; docx="$2"; out="$3"; mkdir -p "$out"
tmp=$(mktemp -d)
cp "$docx" "$tmp/word.docx"
(cd "$tmp" && soffice --headless --convert-to pdf word.docx >/dev/null 2>&1)
pdftoppm -r 70 -png "$orig" "$tmp/a"
pdftoppm -r 70 -png "$tmp/word.pdf" "$tmp/b"
echo "pagine originale: $(ls $tmp/a-*.png | wc -l)  pagine Word: $(ls $tmp/b-*.png | wc -l)"
python3 - "$tmp" "$out" <<'PY'
import sys, glob, os
from PIL import Image, ImageDraw
tmp, out = sys.argv[1], sys.argv[2]
A = sorted(glob.glob(tmp + "/a-*.png")); B = sorted(glob.glob(tmp + "/b-*.png"))
for i in range(max(len(A), len(B))):
    ims = [Image.open(x).convert("RGB") if x else None for x in (A[i] if i < len(A) else None, B[i] if i < len(B) else None)]
    w = sum(im.width if im else 400 for im in ims) + 30; h = max(im.height if im else 0 for im in ims) + 24
    c = Image.new("RGB", (w, h), (120, 120, 120)); d = ImageDraw.Draw(c); x = 10
    for lbl, im in zip(("ORIGINALE", "WORD"), ims):
        d.text((x, 4), lbl, fill=(255, 255, 255))
        if im: c.paste(im, (x, 20)); x += im.width + 10
        else: x += 410
    c.save(os.path.join(out, f"pagina{i + 1}.png"))
PY
cp "$tmp/word.pdf" "$out/word.pdf"; rm -rf "$tmp"
