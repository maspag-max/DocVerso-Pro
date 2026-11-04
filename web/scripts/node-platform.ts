/** Piattaforma Node per i test: stessa conversione del browser, con @napi-rs/canvas al posto del canvas. */
import { createCanvas, ImageData, type Canvas } from '@napi-rs/canvas';
import type { PDFPageProxy } from 'pdfjs-dist';
import type { PdfImage } from '../src/pdf/extract';
import type { Platform, Raster, Rgba } from '../src/pdf/pdfToWord';

function imageToCanvas(img: PdfImage): Canvas | null {
  const { width: w, height: h } = img;
  if (!w || !h) return null;
  const c = createCanvas(w, h);
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h);
  if (img.bitmap) { ctx.drawImage(img.bitmap as any, 0, 0); return c; }
  const d = img.data; if (!d) return null;
  const rgba = new Uint8ClampedArray(w * h * 4);
  if (img.kind === 3) rgba.set(d.subarray(0, rgba.length));
  else if (img.kind === 2) for (let i = 0, j = 0; i < w * h; i++) { rgba[i * 4] = d[j++]; rgba[i * 4 + 1] = d[j++]; rgba[i * 4 + 2] = d[j++]; rgba[i * 4 + 3] = 255; }
  else if (img.kind === 1) { const row = (w + 7) >> 3; for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const v = (d[y * row + (x >> 3)] >> (7 - (x & 7))) & 1 ? 255 : 0; const i = (y * w + x) * 4; rgba[i] = rgba[i + 1] = rgba[i + 2] = v; rgba[i + 3] = 255; } }
  else return null;
  const tmp = createCanvas(w, h); tmp.getContext('2d').putImageData(new ImageData(rgba, w, h), 0, 0);
  ctx.drawImage(tmp, 0, 0);
  return c;
}

function toCanvas(img: Rgba): Canvas {
  const c = createCanvas(img.width, img.height);
  c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(img.data.buffer, img.data.byteOffset, img.data.length), img.width, img.height), 0, 0);
  return c;
}

export const nodePlatform: Platform = {
  png: img => new Uint8Array(toCanvas(img).toBuffer('image/png')),
  jpeg: (img, q) => new Uint8Array(toCanvas(img).toBuffer('image/jpeg', Math.round(q * 100))),
  fromPixels(w, h, px) {
    const c = createCanvas(w, h); const ctx = c.getContext('2d');
    const d = new Uint8ClampedArray(w * h * 4);
    for (let i = 0; i < px.length; i++) { d[i * 4] = px[i] >> 16 & 255; d[i * 4 + 1] = px[i] >> 8 & 255; d[i * 4 + 2] = px[i] & 255; d[i * 4 + 3] = 255; }
    ctx.putImageData(new ImageData(d, w, h), 0, 0);
    return {
      width: w, height: h,
      crop: (x, y, cw, ch) => { const g = ctx.getImageData(x, y, cw, ch); return { data: g.data as any, width: cw, height: ch }; },
      pixels: () => px, release: () => {},
    };
  },
  async encodeImage(img) {
    const c = imageToCanvas(img);
    if (!c) return null;
    const scale = Math.min(1, 2000 / Math.max(c.width, c.height));
    let out = c;
    if (scale < 1) { out = createCanvas(Math.round(c.width * scale), Math.round(c.height * scale)); out.getContext('2d').drawImage(c, 0, 0, out.width, out.height); }
    return new Uint8Array(out.toBuffer('image/jpeg', 90));
  },
  async render(page: PDFPageProxy, scale: number): Promise<Raster> {
    const vp = page.getViewport({ scale });
    const canvas = createCanvas(Math.ceil(vp.width), Math.ceil(vp.height));
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    await page.render({ canvasContext: ctx as any, canvas: canvas as any, viewport: vp }).promise;
    return {
      width: canvas.width, height: canvas.height,
      crop: (x, y, w, h) => { const d = ctx.getImageData(x, y, w, h); return { data: d.data as any, width: w, height: h }; },
      pixels: () => {
        const d = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        const px = new Int32Array(canvas.width * canvas.height);
        for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
        return px;
      },
      release: () => {},
    };
  },
};
