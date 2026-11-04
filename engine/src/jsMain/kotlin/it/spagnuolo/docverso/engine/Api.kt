@file:OptIn(ExperimentalJsExport::class)

package it.spagnuolo.docverso.engine

// ------------------------------------------------------------------ dati in ingresso (da pdf.js)

/** Carattere usato nella pagina. */
external interface JsFont { val name: String; val bold: Boolean; val italic: Boolean }

/** Lettera: [x] sinistra, [base] linea di base, [w] larghezza, [size] corpo; tutto in punti, origine in alto a sinistra. */
external interface JsGlyph { val ch: String; val x: Double; val base: Double; val w: Double; val size: Double; val font: Int; val color: Int }

/** Riquadro pieno (linea sottile o sfondo) con colore 0xRRGGBB. */
external interface JsBox { val l: Double; val t: Double; val r: Double; val b: Double; val color: Int? }

/** Immagine incorporata, già in JPEG. */
external interface JsPic { val jpeg: ByteArray; val l: Double; val t: Double; val r: Double; val b: Double; val behind: Boolean }

external interface JsPage {
    val w: Double; val h: Double
    val fonts: Array<JsFont>
    val glyphs: Array<JsGlyph>
    val fills: Array<JsBox>
    val art: Array<JsBox>
    val pics: Array<JsPic>
}

/** Parola riconosciuta dall'OCR (pixel dell'immagine) con il numero della sua riga. */
external interface JsWord { val text: String; val l: Double; val t: Double; val r: Double; val b: Double; val line: Int }

/** File dentro il pacchetto Word. */
@JsExport
class DocPart(val name: String, val data: ByteArray)

private fun JsPage.toPage() = ExactLayout.Page(
    w.toFloat(), h.toFloat(),
    fonts.map { ExactLayout.Font(it.name, it.bold, it.italic) },
    glyphs.map { ExactLayout.Glyph(it.ch, it.x.toFloat(), it.base.toFloat(), it.w.toFloat(), it.size.toFloat(), it.font, it.color) },
    fills.map { ExactLayout.Box(it.l.toFloat(), it.t.toFloat(), it.r.toFloat(), it.b.toFloat(), it.color ?: 0) },
    art.map { ExactLayout.Box(it.l.toFloat(), it.t.toFloat(), it.r.toFloat(), it.b.toFloat()) },
    pics.map { ExactLayout.Pic(it.jpeg, it.l.toFloat(), it.t.toFloat(), it.r.toFloat(), it.b.toFloat(), it.behind) },
)

// ------------------------------------------------------------------ funzioni

/** Vero se il testo del PDF è codificato male (meglio passare all'OCR per quella pagina). */
@JsExport
fun looksGarbled(page: JsPage, missing: Int): Boolean = TextQuality.looksGarbled(page.toPage(), missing)

/**
 * Documento Word in costruzione: una pagina Word per pagina del documento d'origine.
 * Uso: aggiungere le pagine; se serve tradurre, [translationRequests] → tradurre fuori →
 * [setTranslation] per ciascuna → [applyTranslation]; infine [build].
 */
@JsExport
class WordBuilder(private val pageWidthMm: Double = 210.0, private val pageHeightMm: Double = 297.0) {

    private var pages = ArrayList<List<DocItem>>()
    private var batch = BatchTranslation()

    /**
     * Pagina di PDF digitale riprodotta esattamente. [crop] ritaglia una zona (punti) e la restituisce in PNG o JPEG;
     * [cropErasing] fa lo stesso cancellando i riquadri [erase] (l, t, r, b in punti) con il colore che li circonda.
     */
    fun addExactPage(
        page: JsPage,
        crop: (l: Double, t: Double, r: Double, b: Double) -> ByteArray?,
        cropErasing: (l: Double, t: Double, r: Double, b: Double, erase: Array<DoubleArray>) -> ByteArray?,
    ) {
        pages += listOf(ExactLayout.build(page.toPage(), { b, er ->
            cropErasing(b.l.toDouble(), b.t.toDouble(), b.r.toDouble(), b.b.toDouble(),
                er.map { doubleArrayOf(it.l.toDouble(), it.t.toDouble(), it.r.toDouble(), it.b.toDouble()) }.toTypedArray())
        }) { b -> crop(b.l.toDouble(), b.t.toDouble(), b.r.toDouble(), b.b.toDouble()) })
    }

    /**
     * Pagina ricostruita dall'OCR. [pixels] = pagina come 0xRRGGBB riga per riga ([width]×[height]);
     * [words] in pixel della pagina; [crop] ritaglia una zona immagine (pixel) e la restituisce in JPEG.
     */
    fun addOcrPage(
        words: Array<JsWord>, width: Int, height: Int, pixels: IntArray,
        crop: (l: Int, t: Int, w: Int, h: Int) -> ByteArray?,
    ) {
        val ws = words.map { LayoutAnalyzer.Word(it.text, it.l.toFloat(), it.t.toFloat(), it.r.toFloat(), it.b.toFloat(), it.line) }
        val scan = ScanPage(width, height, pixels)
        val res = LayoutAnalyzer.analyze(ws, { l, t, r, b -> scan.hasMark(l, t, r, b) }, scan.pageImage)
        val items = res.items.toMutableList()
        val text = ws.map { LayoutAnalyzer.Box(it.l, it.t, it.r, it.b) }
        for (r in scan.imageRegions(text)) {
            val jpeg = crop(r.l, r.t, r.w, r.h) ?: continue
            items += r.t.toFloat() to DocItem.Image(jpeg, r.w, r.h, r.w / width.toFloat())
        }
        pages += items.sortedBy { it.first }.map { it.second }
    }

    /** Pagina con una sola immagine (foto senza testo riconosciuto). */
    fun addImagePage(jpeg: ByteArray, width: Int, height: Int, widthFraction: Double) {
        pages += listOf(DocItem.Image(jpeg, width, height, widthFraction.toFloat()))
    }

    val pageCount get() = pages.size

    /** Testo del documento (per riconoscere la lingua). */
    fun sampleText(max: Int = 3000): String {
        val sb = StringBuilder()
        batch.collect { tr -> pages.forEach { tr.items(it) } }.forEach { if (sb.length < max) sb.append(it).append(' ') }
        return sb.toString().take(max)
    }

    /** Frasi da tradurre (nell'ordine in cui compaiono, senza ripetizioni). */
    fun translationRequests(): Array<String> = batch.collect { tr -> pages.forEach { tr.items(it) } }.toTypedArray()

    fun setTranslation(source: String, translated: String) = batch.put(source, translated)

    /** Sostituisce il testo con le traduzioni ricevute (impaginazione conservata). */
    fun applyTranslation() {
        pages = ArrayList(batch.apply { tr -> pages.map { tr.items(it) } })
        batch = BatchTranslation()
    }

    /** File del pacchetto Word (da comprimere in zip così come sono). */
    fun build(title: String): Array<DocPart> {
        val w = MiniDocxWriter(pageWidthMm.toFloat(), pageHeightMm.toFloat())
        pages.forEach { w.addItems(it) }
        return w.parts(title).map { DocPart(it.first, it.second) }.toTypedArray()
    }
}

/**
 * Traduttore di testo singolo, per il Word → Word: stessa protezione di codici, numeri, ____ e ☐.
 * Anche qui in due passaggi: [requests] su tutti i testi, tradurre fuori, poi [translate].
 */
@JsExport
class TextTranslation {
    private val batch = BatchTranslation()
    fun requests(texts: Array<String>): Array<String> = batch.collect { tr -> texts.forEach { tr.text(it) } }.toTypedArray()
    fun put(source: String, translated: String) = batch.put(source, translated)
    fun translate(text: String): String = batch.apply { it.text(text) }
}

/**
 * PDF di immagini JPEG, una per pagina. [pageWidthPt]/[pageHeightPt] = 0 → pagina grande quanto l'immagine
 * (a 150 dpi); altrimenti l'immagine è adattata e centrata nella pagina, con [marginPt] di margine.
 */
@JsExport
class ImagePdfBuilder {
    private val w = MiniPdfWriter()
    fun addPage(jpeg: ByteArray, imgW: Int, imgH: Int, pageWidthPt: Double, pageHeightPt: Double, marginPt: Double) {
        if (pageWidthPt <= 0.0 || pageHeightPt <= 0.0) {
            val pw = imgW * 72f / 150f; val ph = imgH * 72f / 150f
            w.addPage(jpeg, imgW, imgH, pw, ph, 0f, 0f, pw, ph)
            return
        }
        val pw = pageWidthPt.toFloat(); val ph = pageHeightPt.toFloat(); val m = marginPt.toFloat()
        val s = minOf((pw - 2 * m) / imgW, (ph - 2 * m) / imgH)
        val dw = imgW * s; val dh = imgH * s
        w.addPage(jpeg, imgW, imgH, pw, ph, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
    }
    fun finish(title: String): ByteArray = w.finish(title)
}
