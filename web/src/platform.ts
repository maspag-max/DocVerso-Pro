/** Piattaforma del browser: canvas per disegnare le pagine e codificare le immagini. */
import type { PDFPageProxy } from 'pdfjs-dist';
import type { PdfImage } from './pdf/extract';
import type { Platform, Raster, Rgba } from './pdf/pdfToWord';

function canvas(w: number, h: number) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  return c;
}

function dataUrlBytes(url: string): Uint8Array {
  const b64 = url.slice(url.indexOf(',') + 1);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function fromRgba(img: Rgba) {
  const c = canvas(img.width, img.height);
  c.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(img.data) as Uint8ClampedArray<ArrayBuffer>, img.width, img.height), 0, 0);
  return c;
}

/** Immagine del PDF su un canvas con fondo bianco (le trasparenti non diventano nere). */
function imageCanvas(img: PdfImage): HTMLCanvasElement | null {
  const { width: w, height: h } = img;
  if (!w || !h) return null;
  const c = canvas(w, h);
  const ctx = c.getContext('2d')!;
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h);
  if (img.bitmap) { ctx.drawImage(img.bitmap as CanvasImageSource, 0, 0); return c; }
  const d = img.data;
  if (!d) return null;
  const rgba = new Uint8ClampedArray(w * h * 4);
  if (img.kind === 3) rgba.set(d.subarray(0, rgba.length));
  else if (img.kind === 2) for (let i = 0, j = 0; i < w * h; i++) { rgba[i * 4] = d[j++]; rgba[i * 4 + 1] = d[j++]; rgba[i * 4 + 2] = d[j++]; rgba[i * 4 + 3] = 255; }
  else if (img.kind === 1) {
    const row = (w + 7) >> 3;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const v = (d[y * row + (x >> 3)] >> (7 - (x & 7))) & 1 ? 255 : 0;
      const i = (y * w + x) * 4; rgba[i] = rgba[i + 1] = rgba[i + 2] = v; rgba[i + 3] = 255;
    }
  } else return null;
  const tmp = canvas(w, h);
  tmp.getContext('2d')!.putImageData(new ImageData(rgba, w, h), 0, 0);
  ctx.drawImage(tmp, 0, 0);
  return c;
}

export const browserPlatform: Platform = {
  png: img => dataUrlBytes(fromRgba(img).toDataURL('image/png')),
  jpeg: (img, q) => dataUrlBytes(fromRgba(img).toDataURL('image/jpeg', q)),
  fromPixels(w, h, px) {
    const c = canvas(w, h);
    const ctx = c.getContext('2d', { willReadFrequently: true })!;
    const d = new Uint8ClampedArray(w * h * 4);
    for (let i = 0; i < px.length; i++) { d[i * 4] = px[i] >> 16 & 255; d[i * 4 + 1] = px[i] >> 8 & 255; d[i * 4 + 2] = px[i] & 255; d[i * 4 + 3] = 255; }
    ctx.putImageData(new ImageData(d, w, h), 0, 0);
    return rasterOf(c, ctx);
  },
  async encodeImage(img) {
    const c = imageCanvas(img);
    if (!c) return null;
    const scale = Math.min(1, 2000 / Math.max(c.width, c.height));
    let out = c;
    if (scale < 1) {
      out = canvas(Math.round(c.width * scale), Math.round(c.height * scale));
      out.getContext('2d')!.drawImage(c, 0, 0, out.width, out.height);
    }
    return dataUrlBytes(out.toDataURL('image/jpeg', 0.9));
  },
  async render(page: PDFPageProxy, scale: number): Promise<Raster> {
    const vp = page.getViewport({ scale });
    const c = canvas(Math.ceil(vp.width), Math.ceil(vp.height));
    const ctx = c.getContext('2d', { willReadFrequently: true })!;
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
    await page.render({ canvas: c, canvasContext: ctx, viewport: vp }).promise;
    return rasterOf(c, ctx);
  },
};

export function rasterOf(c: HTMLCanvasElement, ctx: CanvasRenderingContext2D): Raster {
  return {
    width: c.width, height: c.height,
    crop: (x, y, w, h) => { const d = ctx.getImageData(x, y, w, h); return { data: d.data, width: w, height: h }; },
    pixels: () => {
      const d = ctx.getImageData(0, 0, c.width, c.height).data;
      const px = new Int32Array(c.width * c.height);
      for (let i = 0; i < px.length; i++) px[i] = (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
      return px;
    },
    release: () => { c.width = c.height = 0; },
  };
}

/** Immagine (JPEG, PNG, …) del PC → raster, ridotta a [maxSide] pixel sul lato lungo. */
export async function rasterFromFile(file: Blob, maxSide = 3500): Promise<Raster> {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const s = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const c = canvas(Math.max(1, Math.round(bmp.width * s)), Math.max(1, Math.round(bmp.height * s)));
  const ctx = c.getContext('2d', { willReadFrequently: true })!;
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  ctx.drawImage(bmp, 0, 0, c.width, c.height);
  bmp.close();
  return rasterOf(c, ctx);
}
