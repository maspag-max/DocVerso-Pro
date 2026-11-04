package it.spagnuolo.docverso.engine

/**
 * Pagina scansionata o fotografata come pixel 0xRRGGBB (riga per riga), per l'impaginazione dall'OCR:
 * linee da compilare, caselle, sfondi colorati e zone immagine (foto, loghi, timbri, firme).
 * Stessa logica della versione Android di TradScan, in Kotlin puro.
 */
class ScanPage(val width: Int, val height: Int, private val px: IntArray) {

    private fun lum(c: Int) = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)
    private fun at(x: Int, y: Int) = px[y * width + x]

    private class Area(val x0: Int, val y0: Int, val w: Int, val h: Int)

    private fun area(l: Float, t: Float, r: Float, b: Float, minW: Int = 1): Area? {
        val x0 = l.toInt().coerceIn(0, width - 1); val x1 = r.toInt().coerceIn(x0 + 1, width)
        val y0 = t.toInt().coerceIn(0, height - 1); val y1 = b.toInt().coerceIn(y0 + 1, height)
        val w = x1 - x0; val h = y1 - y0
        return if (w < minW || h < 1) null else Area(x0, y0, w, h)
    }

    val pageImage = object : LayoutAnalyzer.PageImage {
        override val width = this@ScanPage.width.toFloat()

        override fun underline(l: Float, t: Float, r: Float, b: Float): Float {
            val a = area(l, t, r, b, 2) ?: return 0f
            var covered = 0
            for (x in 0 until a.w) {
                for (y in 0 until a.h) if (lum(at(a.x0 + x, a.y0 + y)) < 175f) { covered++; break }
            }
            return covered.toFloat()
        }

        override fun boxes(l: Float, t: Float, r: Float, b: Float): List<Float> {
            val a = area(l, t, r, b, 2) ?: return emptyList()
            return CellMarks.findBoxes(a.w, a.h) { x, y -> lum(at(a.x0 + x, a.y0 + y)) }.map { it + a.x0 }
        }

        override fun background(l: Float, t: Float, r: Float, b: Float): Int? {
            val a = area(l, t, r, b, 2) ?: return null
            // Colore tipico dei pixel chiari (lo sfondo, non il testo)
            val light = ArrayList<Int>()
            var i = 0
            val n = a.w * a.h
            while (i < n) { val c = at(a.x0 + i % a.w, a.y0 + i / a.w); if (lum(c) > 150f) light += c; i += 7 }
            if (light.size < 20) return null
            val rs = light.map { it shr 16 and 255 }.sorted(); val gs = light.map { it shr 8 and 255 }.sorted(); val bs = light.map { it and 255 }.sorted()
            val m = light.size / 2
            val cr = rs[m]; val cg = gs[m]; val cb = bs[m]
            val mx = maxOf(cr, cg, cb); val mn = minOf(cr, cg, cb)
            // Bianco o quasi bianco (anche carta un po' grigia) → nessuno sfondo
            if (mn >= 238 || (mx - mn < 10 && mx >= 225)) return null
            return (cr shl 16) or (cg shl 8) or cb
        }
    }

    /** C'è una casella stampata (o un segno) nel riquadro dato della pagina? */
    fun hasMark(l: Float, t: Float, r: Float, b: Float): Boolean {
        val a = area(l, t, r, b) ?: return false
        return CellMarks.hasMark(a.w, a.h) { x, y -> lum(at(a.x0 + x, a.y0 + y)) }
    }

    /** Riquadro in pixel della pagina. */
    class Rect(val l: Int, val t: Int, val w: Int, val h: Int)

    /**
     * Zone immagine: ciò che è scuro o colorato e non è testo riconosciuto ([textBoxes], in pixel).
     * Dall'alto in basso.
     */
    fun imageRegions(textBoxes: List<LayoutAnalyzer.Box>): List<Rect> {
        val gw = 120
        val gh = (gw * height / width.toFloat()).toInt().coerceAtLeast(1)
        val sx = gw / width.toFloat()
        val sy = gh / height.toFloat()
        // Pagina ridotta: media dei pixel di ogni cella
        val ink = BooleanArray(gw * gh) { i ->
            val gx = i % gw; val gy = i / gw
            val x0 = (gx / sx).toInt(); val x1 = (((gx + 1) / sx).toInt()).coerceIn(x0 + 1, width)
            val y0 = (gy / sy).toInt(); val y1 = (((gy + 1) / sy).toInt()).coerceIn(y0 + 1, height)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val stepX = maxOf(1, (x1 - x0) / 6); val stepY = maxOf(1, (y1 - y0) / 6)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { val c = at(x, y); r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++; x += stepX }
                y += stepY
            }
            val cr = (r / n).toInt(); val cg = (g / n).toInt(); val cb = (b / n).toInt()
            val max = maxOf(cr, cg, cb); val min = minOf(cr, cg, cb)
            val lum = 0.299f * cr + 0.587f * cg + 0.114f * cb
            val sat = if (max == 0) 0f else (max - min) / max.toFloat()
            lum < 165f || (sat > 0.28f && max > 60)
        }
        // Toglie il testo riconosciuto (con un piccolo margine)
        for (r in textBoxes) {
            val l = ((r.l * sx).toInt() - 1).coerceAtLeast(0)
            val t = ((r.t * sy).toInt() - 1).coerceAtLeast(0)
            val rr = ((r.r * sx).toInt() + 1).coerceAtMost(gw - 1)
            val bb = ((r.b * sy).toInt() + 1).coerceAtMost(gh - 1)
            for (y in t..bb) for (x in l..rr) ink[y * gw + x] = false
        }
        return ImageRegions.find(ink, gw, gh).map { box ->
            val l = (box.left / sx).toInt().coerceIn(0, width - 1)
            val t = (box.top / sy).toInt().coerceIn(0, height - 1)
            val w = (box.width / sx).toInt().coerceIn(1, width - l)
            val h = (box.height / sy).toInt().coerceIn(1, height - t)
            Rect(l, t, w, h)
        }
    }

    /**
     * Zone con inchiostro "da testo" dove l'OCR non ha trovato parole (Tesseract a volte salta un blocco intero,
     * per esempio un titolo): da rileggere. Esclude le foto e le linee. Riquadri in pixel.
     */
    fun missedText(words: List<LayoutAnalyzer.Box>): List<Rect> {
        val gw = 160
        val gh = (gw * height / width.toFloat()).toInt().coerceAtLeast(1)
        val sx = gw / width.toFloat(); val sy = gh / height.toFloat()
        val ink = BooleanArray(gw * gh) { i ->
            val gx = i % gw; val gy = i / gw
            val x0 = (gx / sx).toInt(); val x1 = ((gx + 1) / sx).toInt().coerceIn(x0 + 1, width)
            val y0 = (gy / sy).toInt(); val y1 = ((gy + 1) / sy).toInt().coerceIn(y0 + 1, height)
            var d = 0; var n = 0
            var y = y0
            while (y < y1) { var x = x0; while (x < x1) { n++; if (lum(at(x, y)) < 150f) d++; x += 2 }; y += 2 }
            d >= 2 && d < n * 0.92f
        }
        for (r in words) {
            val l = ((r.l * sx).toInt() - 1).coerceAtLeast(0); val t = ((r.t * sy).toInt() - 1).coerceAtLeast(0)
            val rr = ((r.r * sx).toInt() + 1).coerceAtMost(gw - 1); val bb = ((r.b * sy).toInt() + 1).coerceAtMost(gh - 1)
            for (y in t..bb) for (x in l..rr) ink[y * gw + x] = false
        }
        val pics = imageRegions(words)
        val seen = BooleanArray(ink.size)
        val out = ArrayList<Rect>()
        val stack = IntArray(ink.size)
        for (s0 in ink.indices) {
            if (!ink[s0] || seen[s0]) continue
            var sp = 0; stack[sp++] = s0; seen[s0] = true
            var l = gw; var t = gh; var r = -1; var b = -1; var n = 0
            while (sp > 0) {
                val i = stack[--sp]; val x = i % gw; val y = i / gw; n++
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                for (dy in -1..1) for (dx in -2..2) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val j = ny * gw + nx
                    if (ink[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            val bw = r - l + 1; val bh = b - t + 1
            // Forma da riga di testo: abbastanza larga, non troppo alta, piena quanto basta (le linee sono sottili)
            if (ScanExact.debug && bw >= 4) ScanExact.log += "componente ${(l / sx).toInt()},${(t / sy).toInt()} ${bw}x${bh} n=$n"
            if (bw < 4 || bh < 2 || bh > gh * 0.08f || n < bw * bh * 0.3f || bw * bh > gw * gh * 0.1f) continue
            val rect = Rect((l / sx).toInt(), (t / sy).toInt(), (bw / sx).toInt().coerceAtMost(width - (l / sx).toInt()), (bh / sy).toInt().coerceAtMost(height - (t / sy).toInt()))
            if (pics.any { p -> rect.l < p.l + p.w && rect.l + rect.w > p.l && rect.t < p.t + p.h && rect.t + rect.h > p.t }) continue
            out += rect
        }
        return out
    }
}
