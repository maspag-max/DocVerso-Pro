package it.spagnuolo.docverso.engine

import kotlin.math.roundToLong

/**
 * Scrittore PDF minimale (da TradScan): ogni pagina contiene un'immagine JPEG incorporata così com'è
 * (filtro DCTDecode), senza ricompressione. I PDF restano leggeri.
 */
class MiniPdfWriter {
    private val out = Png.Bytes()
    private val offsets = HashMap<Int, Int>()
    private val pageObjs = mutableListOf<Int>()
    private var nextObj = 3 // 1 = Catalog, 2 = Pages

    init {
        str("%PDF-1.4\n")
        out.addAll(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    private fun str(s: String) = out.addAll(s.encodeToByteArray())
    private fun begin(n: Int) { offsets[n] = out.size; str("$n 0 obj\n") }
    private fun f(v: Float): String {
        val c = (v * 100.0).roundToLong()
        val sign = if (c < 0) "-" else ""
        val a = kotlin.math.abs(c)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    /** Aggiunge una pagina [pageW]×[pageH] punti con l'immagine nel riquadro (x, y, w, h); y dal basso. */
    fun addPage(jpeg: ByteArray, imgW: Int, imgH: Int, pageW: Float, pageH: Float, x: Float, y: Float, w: Float, h: Float, gray: Boolean = false) {
        val pageObj = nextObj++
        val contentObj = nextObj++
        val imgObj = nextObj++
        begin(imgObj)
        str("<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace /${if (gray) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        out.addAll(jpeg)
        str("\nendstream\nendobj\n")
        val content = "q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(y)} cm /Im0 Do Q\n".encodeToByteArray()
        begin(contentObj)
        str("<< /Length ${content.size} >>\nstream\n")
        out.addAll(content)
        str("endstream\nendobj\n")
        begin(pageObj)
        str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] /Resources << /XObject << /Im0 $imgObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n")
        pageObjs += pageObj
    }

    fun finish(title: String): ByteArray {
        val infoObj = nextObj++
        begin(1); str("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2); str("<< /Type /Pages /Kids [${pageObjs.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjs.size} >>\nendobj\n")
        begin(infoObj); str("<< /Title ${textString(title)} /Producer (DocVerso) /Creator (DocVerso - Massimo Spagnuolo) >>\nendobj\n")
        val xref = out.size
        val count = nextObj
        val sb = StringBuilder("xref\n0 $count\n0000000000 65535 f \n")
        for (i in 1 until count) sb.append((offsets[i] ?: 0).toString().padStart(10, '0')).append(" 00000 n \n")
        sb.append("trailer\n<< /Size $count /Root 1 0 R /Info $infoObj 0 R >>\nstartxref\n$xref\n%%EOF\n")
        str(sb.toString())
        return out.toByteArray()
    }

    /** Titolo come testo UTF-16 (accenti compresi). */
    private fun textString(s: String): String = buildString {
        append("<FEFF")
        for (c in s) append(c.code.toString(16).uppercase().padStart(4, '0'))
        append(">")
    }
}
