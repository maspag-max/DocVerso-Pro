/** Forma dei dati che il motore Kotlin/JS si aspetta (interfacce "external" in Api.kt). */

/** In Kotlin/JS un ByteArray è un Int8Array. */
export type KBytes = Int8Array;

export interface JsFont { name: string; bold: boolean; italic: boolean }
export interface JsGlyph { ch: string; x: number; base: number; w: number; size: number; font: number; color: number }
export interface JsBox { l: number; t: number; r: number; b: number; color?: number | null }
export interface JsPic { jpeg: KBytes; l: number; t: number; r: number; b: number; behind: boolean }
export interface JsPage { w: number; h: number; fonts: JsFont[]; glyphs: JsGlyph[]; fills: JsBox[]; art: JsBox[]; pics: JsPic[] }
export interface JsWord { text: string; l: number; t: number; r: number; b: number; line: number; base?: number | null; confidence?: number }

/** Byte normali → ByteArray di Kotlin (stessa memoria, nessuna copia). */
export const toK = (u: Uint8Array): KBytes => new Int8Array(u.buffer, u.byteOffset, u.byteLength);
/** ByteArray di Kotlin → byte normali (stessa memoria). */
export const fromK = (k: KBytes): Uint8Array => new Uint8Array(k.buffer, k.byteOffset, k.byteLength);
