package it.spagnuolo.docverso.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pagina scansionata ricostruita "esatta", come un PDF digitale: dai pixel si ricavano linee (tabelle),
 * sfondi colorati, foto e caselle; dall'OCR le parole con posizione, dimensione, grassetto e colore.
 * Poi lavora [ExactLayout], lo stesso dei PDF digitali (tabelle con celle unite, testo in posizione).
 */
object ScanExact {

    /** Solo per le prove: cosa succede alle parole (attivare con [debug]). */
    var debug = false
    val log = ArrayList<String>()
    private fun note(m: String) { if (debug) log += m }

    /** Parola riconosciuta, in pixel. [base] = linea di base della sua riga (se nota). */
    class OcrWord(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val line: Int, val base: Float?, val confidence: Float = 90f)

    private fun lum(c: Int) = (77 * (c shr 16 and 255) + 150 * (c shr 8 and 255) + 29 * (c and 255)) shr 8

    /** Luminosità della carta (mediana dei pixel) e soglia del "nero". */
    private fun paperAndThreshold(px: IntArray): Pair<Int, Int> {
        val hist = IntArray(256)
        var i = 0
        while (i < px.size) { hist[lum(px[i])]++; i += 7 }
        val tot = hist.sum()
        var acc = 0; var paper = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc > tot / 2) { paper = v; break } }
        return paper to min(paper - 60, 170)
    }

    // ---------------------------------------------------------------- raddrizzamento

    /**
     * Inclinazione della pagina in gradi (positiva = ruotata in senso orario), cercata fra -3° e +3°:
     * con l'angolo giusto le righe di testo e le linee sono orizzontali, e i profili delle righe sono più netti.
     */
    fun skewAngle(w: Int, h: Int, px: IntArray): Float {
        val (_, thr) = paperAndThreshold(px)
        val step = max(1, w / 900)
        // Punti scuri di una pagina ridotta (solo interi: in JavaScript Long e HashMap sono lenti)
        var n = 0
        val xs = IntArray(150000); val ys = IntArray(150000)
        var y = 0
        outer@ while (y < h) {
            var x = 0
            while (x < w) {
                if (lum(px[y * w + x]) < thr) { if (n >= xs.size) break@outer; xs[n] = x / step; ys[n] = y / step; n++ }
                x += step
            }
            y += step
        }
        if (n < 200) return 0f
        val sw = w / step + 1; val sh = h / step + 1
        val margin = (sw * 0.06f).toInt() + 2
        val bins = IntArray(sh + 2 * margin)
        fun score(deg: Double): Double {
            val t = kotlin.math.tan(deg * PI / 180.0)
            bins.fill(0)
            for (i in 0 until n) {
                val b = (ys[i] - xs[i] * t).roundToInt() + margin
                if (b in bins.indices) bins[b]++
            }
            var s = 0.0
            for (v in bins) s += v.toDouble() * v
            return s
        }
        var best = 0.0; var bestS = score(0.0)
        var d = -3.0
        while (d <= 3.0001) { val s = score(d); if (s > bestS) { bestS = s; best = d }; d += 0.25 }
        // Affinamento intorno al migliore
        var lo = best - 0.25
        while (lo <= best + 0.25001) { val s = score(lo); if (s > bestS) { bestS = s; best = lo }; lo += 0.05 }
        return if (abs(best) < 0.08) 0f else best.toFloat()
    }

    /** Pagina ruotata di [deg] gradi intorno al centro (bordi bianchi), stessa misura. */
    fun rotate(w: Int, h: Int, px: IntArray, deg: Float): IntArray {
        if (deg == 0f) return px
        val a = -deg * PI / 180.0
        val ca = cos(a); val sa = sin(a)
        val cx = w / 2.0; val cy = h / 2.0
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val sx = ca * dx - sa * dy + cx; val sy = sa * dx + ca * dy + cy
            val ix = sx.toInt(); val iy = sy.toInt()
            out[y * w + x] = if (ix < 0 || iy < 0 || ix >= w - 1 || iy >= h - 1) 0xFFFFFF else {
                // Interpolazione bilineare
                val fx = sx - ix; val fy = sy - iy
                val c00 = px[iy * w + ix]; val c10 = px[iy * w + ix + 1]; val c01 = px[(iy + 1) * w + ix]; val c11 = px[(iy + 1) * w + ix + 1]
                fun ch(sh: Int): Int {
                    val v = (c00 shr sh and 255) * (1 - fx) * (1 - fy) + (c10 shr sh and 255) * fx * (1 - fy) + (c01 shr sh and 255) * (1 - fx) * fy + (c11 shr sh and 255) * fx * fy
                    return v.roundToInt().coerceIn(0, 255)
                }
                (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- linee, sfondi, caselle

    private class Run(val a: Int, val b: Int, val pos: Int)

    /**
     * Linee dritte (bordi di tabella, righe da compilare) come riquadri sottili, in pixel.
     * [exclude] = zone da ignorare (parole riconosciute, foto): le aste delle lettere e i bordi delle foto non sono linee.
     */
    fun rules(w: Int, h: Int, px: IntArray, exclude: List<ExactLayout.Box> = emptyList(), isolated: Boolean = true): List<ExactLayout.Box> {
        val (_, thr) = paperAndThreshold(px)
        val dark = BooleanArray(w * h) { lum(px[it]) < thr }
        for (e in exclude) {
            val x0 = e.l.toInt().coerceIn(0, w); val x1 = e.r.toInt().coerceIn(0, w)
            val y0 = e.t.toInt().coerceIn(0, h); val y1 = e.b.toInt().coerceIn(0, h)
            for (y in y0 until y1) for (x in x0 until x1) dark[y * w + x] = false
        }
        val out = ArrayList<ExactLayout.Box>()
        val minH = max(40, (w * 0.035f).toInt()); val minV = max(30, (h * 0.012f).toInt())
        fun collect(horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val minLen = if (horizontal) minH else minV
            val runs = ArrayList<Run>()
            for (p in 0 until lines) {
                var i = 0
                while (i < len) {
                    val d = if (horizontal) dark[p * w + i] else dark[i * w + p]
                    if (!d) { i++; continue }
                    // Fine del tratto: ultimo pixel scuro, ammettendo buchi di 2 pixel
                    var last = i; var k = i + 1
                    while (k < len && k - last <= 3) {
                        val dd = if (horizontal) dark[p * w + k] else dark[k * w + p]
                        if (dd) last = k
                        k++
                    }
                    // Linee orizzontali anche a pezzi (foto); verticali solo intere: le aste delle lettere non sono linee
                    if (last - i + 1 >= (if (horizontal) minLen / 2 else minLen)) runs += Run(i, last, p)
                    i = last + 1
                }
            }
            // Tratti su righe (o colonne) vicine che si sovrappongono: una linea sola, con il suo spessore
            val used = BooleanArray(runs.size)
            for (k in runs.indices) {
                if (used[k]) continue
                used[k] = true
                var a = runs[k].a; var b = runs[k].b; var p0 = runs[k].pos; var p1 = runs[k].pos
                var j = k + 1
                while (j < runs.size && runs[j].pos <= p1 + 1) {
                    val r = runs[j]
                    if (!used[j] && r.pos >= p1 && min(r.b, b) - max(r.a, a) > (b - a) * 0.5f) { used[j] = true; a = min(a, r.a); b = max(b, r.b); p1 = r.pos }
                    j++
                }
                if (isolated && p1 - p0 + 1 > 12) continue // troppo spesso: è una zona piena, non una linea
                // Isolata: appena sopra e sotto (o ai lati) la carta è quasi sempre chiara
                var light = 0; var probes = 0
                var q = a
                val stepQ = max(1, (b - a) / 24)
                while (q <= b) {
                    for (off in intArrayOf(p0 - 3, p1 + 3)) {
                        if (off < 0 || off >= lines) continue
                        probes++
                        val d = if (horizontal) dark[off * w + q] else dark[q * w + off]
                        if (!d) light++
                    }
                    q += stepQ
                }
                if (isolated && probes > 0 && light < probes * 0.75f) continue
                // Colore: mediana dei pixel della linea; grigio o quasi nero → nero
                val cols = ArrayList<Int>()
                var qq = a
                while (qq <= b) { for (pp in p0..p1) cols += if (horizontal) px[pp * w + qq] else px[qq * w + pp]; qq += max(1, (b - a) / 40) }
                cols.sortBy { lum(it) }
                val dk = cols.subList(0, max(1, cols.size / 2))
                fun med(sh: Int) = dk.map { it shr sh and 255 }.sorted()[dk.size / 2]
                val cr = med(16); val cg = med(8); val cb = med(0)
                val color = if (maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 40) 0 else (cr shl 16) or (cg shl 8) or cb
                out += if (horizontal) ExactLayout.Box(a.toFloat(), p0.toFloat(), b + 1f, p1 + 1f, color)
                else ExactLayout.Box(p0.toFloat(), a.toFloat(), p1 + 1f, b + 1f, color)
            }
        }
        collect(true); collect(false)
        return joinCollinear(out, minH, minV)
    }

    /**
     * Pezzi di linea quasi allineati (foto di documenti: linee un po' curve o in prospettiva) uniti in una linea sola;
     * poi via i pezzi rimasti troppo corti.
     */
    private fun joinCollinear(parts: List<ExactLayout.Box>, minH: Int, minV: Int): List<ExactLayout.Box> {
        fun join(list: List<ExactLayout.Box>, horizontal: Boolean, minLen: Int): List<ExactLayout.Box> {
            val pos = { b: ExactLayout.Box -> if (horizontal) (b.t + b.b) / 2f else (b.l + b.r) / 2f }
            val a0 = { b: ExactLayout.Box -> if (horizontal) b.l else b.t }
            val a1 = { b: ExactLayout.Box -> if (horizontal) b.r else b.b }
            val cur = list.sortedBy { a0(it) }.toMutableList()
            var changed = true
            while (changed) {
                changed = false
                val res = ArrayList<ExactLayout.Box>()
                for (b in cur) {
                    val gapMax = if (horizontal) 14f else 4f
                    val k = res.indexOfFirst { o -> abs(pos(o) - pos(b)) <= 5f && a0(b) - a1(o) <= gapMax && a0(o) - a1(b) <= gapMax }
                    if (k < 0) { res += b; continue }
                    val o = res[k]
                    val n = ExactLayout.Box(min(o.l, b.l), min(o.t, b.t), max(o.r, b.r), max(o.b, b.b), o.color)
                    // Lo spessore resta quello di un pezzo (la deriva verticale non è spessore)
                    val th = max(if (horizontal) o.b - o.t else o.r - o.l, if (horizontal) b.b - b.t else b.r - b.l)
                    val c = (pos(o) * (a1(o) - a0(o)) + pos(b) * (a1(b) - a0(b))) / (a1(o) - a0(o) + a1(b) - a0(b))
                    res[k] = if (horizontal) ExactLayout.Box(n.l, c - th / 2f, n.r, c + th / 2f, o.color) else ExactLayout.Box(c - th / 2f, n.t, c + th / 2f, n.b, o.color)
                    changed = true
                }
                cur.clear(); cur += res.sortedBy { a0(it) }
            }
            return cur.filter { a1(it) - a0(it) >= minLen }
        }
        return join(parts.filter { it.w >= it.h }, true, minH) + join(parts.filter { it.h > it.w }, false, minV)
    }

    /** Zone colorate chiare e uniformi (intestazioni di tabella, fasce): riquadri in pixel con il loro colore. */
    fun tints(w: Int, h: Int, px: IntArray): List<ExactLayout.Box> {
        val cell = 6
        val gw = w / cell; val gh = h / cell
        if (gw < 2 || gh < 2) return emptyList()
        val color = IntArray(gw * gh) { -1 }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var r = 0; var g = 0; var b = 0; var n = 0
            var mn = 255; var mx = 0
            for (y in gy * cell until gy * cell + cell step 2) for (x in gx * cell until gx * cell + cell step 2) {
                val c = px[y * w + x]; val cr = c shr 16 and 255; val cg = c shr 8 and 255; val cb = c and 255
                r += cr; g += cg; b += cb; n++
                val l = lum(c); if (l < mn) mn = l; if (l > mx) mx = l
            }
            r /= n; g /= n; b /= n
            val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
            // Colorata (non bianca né grigia), chiara e uniforme
            if (mx - mn < 40 && hi - lo >= 12 && lum((r shl 16) or (g shl 8) or b) > 140) color[gy * gw + gx] = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        }
        val seen = BooleanArray(gw * gh)
        val out = ArrayList<ExactLayout.Box>()
        val stack = IntArray(gw * gh)
        for (s in color.indices) {
            if (color[s] < 0 || seen[s]) continue
            val key = color[s]
            var sp = 0; stack[sp++] = s; seen[s] = true
            var l = gw; var t = gh; var r = -1; var b = -1; var n = 0
            var sr = 0L; var sg = 0L; var sb = 0L
            while (sp > 0) {
                val i = stack[--sp]; val x = i % gw; val y = i / gw; n++
                sr += (key shr 8 and 15); sg += (key shr 4 and 15); sb += (key and 15)
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val j = ny * gw + nx
                    val cj = color[j]
                    if (cj < 0 || seen[j]) continue
                    // Colore simile (quantizzato) al primo
                    if (abs((cj shr 8 and 15) - (key shr 8 and 15)) + abs((cj shr 4 and 15) - (key shr 4 and 15)) + abs((cj and 15) - (key and 15)) > 2) continue
                    seen[j] = true; stack[sp++] = j
                }
            }
            val bw = r - l + 1; val bh = b - t + 1
            if (bw < 4 || bh < 2 || n < bw * bh * 0.6f) continue
            // Il colore vero: mediana dei pixel chiari della zona
            val cols = ArrayList<Int>()
            var yy = t * cell + 1
            while (yy < (b + 1) * cell) { var xx = l * cell + 1; while (xx < (r + 1) * cell) { val c = px[yy * w + xx]; if (lum(c) > 140) cols += c; xx += 5 }; yy += 5 }
            if (cols.isEmpty()) continue
            fun med(sh: Int) = cols.map { it shr sh and 255 }.sorted()[cols.size / 2]
            out += ExactLayout.Box((l * cell).toFloat(), (t * cell).toFloat(), ((r + 1) * cell).toFloat(), ((b + 1) * cell).toFloat(), (med(16) shl 16) or (med(8) shl 8) or med(0))
        }
        return out
    }

    /** Caselle stampate vuote (quadratini): centro e lato in pixel. */
    fun checkboxes(w: Int, h: Int, px: IntArray, minSide: Int, maxSide: Int): List<ExactLayout.Box> {
        val (_, thr) = paperAndThreshold(px)
        val dark = BooleanArray(w * h) { lum(px[it]) < thr }
        val seen = BooleanArray(w * h)
        val out = ArrayList<ExactLayout.Box>()
        val stack = IntArray(maxSide * maxSide * 8)
        for (s in dark.indices) {
            if (!dark[s] || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            var l = w; var t = h; var r = -1; var b = -1; var n = 0; var big = false
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w; n++
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                if (r - l > maxSide || b - t > maxSide) big = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (dark[j] && !seen[j]) { seen[j] = true; if (sp < stack.size) stack[sp++] = j else big = true }
                }
            }
            if (big) continue
            val bw = r - l + 1; val bh = b - t + 1
            if (bw < minSide || bh < minSide || bw.toFloat() / bh !in 0.8f..1.25f) continue
            // Cava: i pixel scuri stanno sul bordo, l'interno è chiaro
            val fill = n.toFloat() / (bw * bh)
            if (fill > 0.5f || fill < 0.12f) continue
            var inner = 0; var innerDark = 0
            for (y in t + bh / 4..b - bh / 4) for (x in l + bw / 4..r - bw / 4) { inner++; if (dark[y * w + x]) innerDark++ }
            if (inner > 0 && innerDark > inner * 0.1f) continue
            // I quattro lati presenti
            fun side(cond: (Int) -> Boolean, count: Int) = (0 until count).count(cond) > count * 0.7f
            val top = side({ x -> (t..t + 2).any { dark[it * w + l + x] } }, bw)
            val bot = side({ x -> (b - 2..b).any { dark[it * w + l + x] } }, bw)
            val lft = side({ y -> (l..l + 2).any { dark[(t + y) * w + it] } }, bh)
            val rgt = side({ y -> (r - 2..r).any { dark[(t + y) * w + it] } }, bh)
            if (top && bot && lft && rgt) out += ExactLayout.Box(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
        }
        return out
    }

    // ---------------------------------------------------------------- pagina

    /**
     * Ricostruisce la pagina. [cropJpeg] ritaglia una zona (pixel) e la restituisce in JPEG (foto, loghi, timbri).
     * Misura della pagina: A4 (o Letter) se le proporzioni corrispondono, altrimenti larga quanto un A4.
     */
    fun build(scan: ScanPage, px: IntArray, words: List<OcrWord>, cropJpeg: (ScanPage.Rect) -> ByteArray?): DocItem.ExactPage {
        val w = scan.width; val h = scan.height
        val ratio = h.toFloat() / w
        val (pw, ph) = when {
            abs(ratio - 1.4142f) < 0.05f -> 595.28f to 841.89f
            abs(ratio - 1 / 1.4142f) < 0.03f -> 841.89f to 595.28f
            abs(ratio - 792f / 612f) < 0.03f -> 612f to 792f
            w < h -> 595.28f to 595.28f * ratio
            else -> 841.89f to 841.89f * ratio
        }
        val s = pw / w
        fun pt(b: ExactLayout.Box, color: Int = b.color) = ExactLayout.Box(b.l * s, b.t * s, b.r * s, b.b * s, color)

        val (_, thr) = paperAndThreshold(px)
        fun inkFraction(l: Float, t: Float, r: Float, b: Float): Float {
            val x0 = l.toInt().coerceIn(0, w - 1); val x1 = r.toInt().coerceIn(x0 + 1, w)
            val y0 = t.toInt().coerceIn(0, h - 1); val y1 = b.toInt().coerceIn(y0 + 1, h)
            var n = 0; var d = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) { n++; if (lum(px[y * w + x]) < thr) d++ }
            return if (n == 0) 0f else d.toFloat() / n
        }

        // Parole: via le macchie (codici a barre, foto lette come testo); i pallini degli elenchi diventano •
        val hs = words.map { it.b - it.t }.sorted()
        val medH = if (hs.isEmpty()) 30f else hs[hs.size / 2]
        val good = ArrayList<OcrWord>()
        for (it in words) {
            if (it.r <= it.l || it.b <= it.t || it.text.isBlank()) continue
            val ink = inkFraction(it.l, it.t, it.r, it.b)
            val ww = it.r - it.l; val hh = it.b - it.t
            // Frammenti corti letti con poca sicurezza (puntini, macchie, caselle mal lette): non sono testo
            if (it.confidence < 55f && it.text.count { c -> c.isLetterOrDigit() } <= 3) continue
            val small = ww < medH * 0.6f && hh < medH * 0.6f && ww / hh in 0.6f..1.6f
            if (ink >= 0.55f && small && it.text.length <= 2) { good += OcrWord("•", it.l, it.t, it.r, it.b, it.line, it.base); continue }
            // Macchia (foto, logo, codice a barre letti come testo): zona molto piena letta con poca sicurezza,
            // o "parola" senza lettere né cifre. Le parole lette con sicurezza restano anche se in grassetto.
            val letters = it.text.count { c -> c.isLetterOrDigit() }
            // Trattino di un elenco o di una frase (corto, basso e largo): è testo, non una macchia
            if (it.text.trim() in setOf("-", "–", "—", "‒", "−") && hh < medH * 0.45f && ww > hh * 1.3f) { good += OcrWord("-", it.l, it.t, it.r, it.b, it.line, it.base, it.confidence); continue }
            if ((ink >= 0.6f && it.confidence < 80f && letters <= 3) || ink >= 0.85f || (ink >= 0.5f && letters == 0)) continue
            good += it
        }
        // Pallino letto come "e", "°", "*"… all'inizio di una riga, staccato dal resto
        for ((_, ws) in good.groupBy { it.line }) {
            val first = ws.minByOrNull { it.l } ?: continue
            val next = ws.filter { it !== first }.minByOrNull { it.l } ?: continue
            if (first.text in setOf("e", "°", "*", "«", "»", "o", "©", "®", "+", "•") && next.l - first.r > medH * 0.6f && (first.r - first.l) < medH * 0.7f && inkFraction(first.l, first.t, first.r, first.b) > 0.35f) {
                good[good.indexOf(first)] = OcrWord("•", first.l, first.t, first.r, first.b, first.line, first.base)
            }
        }

        // Pallini disegnati che l'OCR non ha letto: macchia piena e tonda poco prima della prima parola della riga
        val (_, thr0) = paperAndThreshold(px)
        for ((line, ws) in good.groupBy { it.line }) {
            val first = ws.minByOrNull { it.l } ?: continue
            if (first.text == "•") continue
            val hh = first.b - first.t
            val x0 = (first.l - hh * 2.5f).toInt().coerceAtLeast(0); val x1 = (first.l - 2f).toInt()
            val y0 = first.t.toInt().coerceAtLeast(0); val y1 = first.b.toInt().coerceAtMost(h)
            if (x1 - x0 < 4) continue
            var l = Int.MAX_VALUE; var r = -1; var t = Int.MAX_VALUE; var b = -1; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) if (lum(px[y * w + x]) < thr0) { n++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
            if (n == 0) continue
            val bw = r - l + 1; val bh = b - t + 1
            if (bw in 3..(hh * 0.5f).toInt() && bh in 3..(hh * 0.5f).toInt() && bw.toFloat() / bh in 0.6f..1.6f && n >= bw * bh * 0.55f && good.none { o -> o !== first && o.r > l && o.l < r && o.b > t && o.t < b })
                good += OcrWord("•", l.toFloat(), (t - bh * 0.6f), r + 1f, (b + bh * 0.6f), line, first.base)
        }

        // Codici a barre: tante barre verticali parallele fitte → immagine
        val barcodes = barcodeZones(rules(w, h, px, isolated = false).filter { it.h > it.w }, medH)

        // Foto, loghi, timbri (zone scure o colorate senza testo)
        val inBar = { x: Float, y: Float -> barcodes.any { x >= it.l && x <= it.r && y >= it.t - medH && y <= it.b + medH * 0.2f } }
        good.removeAll { inBar((it.l + it.r) / 2f, (it.t + it.b) / 2f) && !Regex("[A-Za-z]{2,}|\\d{3,}").containsMatchIn(it.text) }
        val regions = scan.imageRegions(good.map { LayoutAnalyzer.Box(it.l, it.t, it.r, it.b) }).filter { r ->
            barcodes.none { b -> r.l < b.r && r.l + r.w > b.l && r.t < b.b && r.t + r.h > b.t } } +
            barcodes.map { ScanPage.Rect(it.l.toInt() - 4, it.t.toInt() - 4, (it.r - it.l).toInt() + 8, (it.b - it.t).toInt() + 8) }
        val pics = ArrayList<ExactLayout.Pic>()
        for (r in regions) {
            val jpeg = cropJpeg(r) ?: continue
            pics += ExactLayout.Pic(jpeg, r.l * s, r.t * s, (r.l + r.w) * s, (r.t + r.h) * s)
        }
        val inPic = { x: Float, y: Float -> regions.any { x >= it.l && x <= it.l + it.w && y >= it.t && y <= it.t + it.h } }
        val text = good.filter { !inPic((it.l + it.r) / 2f, (it.t + it.b) / 2f) }
        note("parole OCR ${words.size}, dopo macchie ${good.size}, fuori dalle foto ${text.size}; zone foto ${regions.map { "${it.l},${it.t} ${it.w}x${it.h}" }}")
        note("tolte nelle foto: " + good.filter { inPic((it.l + it.r) / 2f, (it.t + it.b) / 2f) }.joinToString(" ") { it.text })
        note("tolte come macchie: " + words.filter { wd -> good.none { g -> g.l == wd.l && g.t == wd.t } }.joinToString(" ") { it.text })

        // Caselle stampate: quelle lette dall'OCR come "o", "0", "[]"… diventano ☐
        val boxes = checkboxes(w, h, px, max(7, (3f / s).toInt()), max(14, (16f / s).toInt()))
        val boxLike = Regex("^[oO0Q°DdUuCcaeg©®\\[\\]{}()|m口□☐ロ]{1,3}$")
        val onBox = HashSet<OcrWord>()
        for (wd in text) if (boxLike.matches(wd.text) && boxes.any { c ->
                val ow = min(c.r, wd.r) - max(c.l, wd.l); val oh = min(c.b, wd.b) - max(c.t, wd.t)
                ow > 0 && oh > 0 && ow * oh >= (wd.r - wd.l) * (wd.b - wd.t) * 0.4f }) onBox += wd
        val words2 = text.filter { it !in onBox }

        // Righe di Tesseract divise dove c'è un grande vuoto (titolo a sinistra, numero di modulo a destra)
        class Seg(val ws: List<OcrWord>, val para: Int)
        val segs = ArrayList<Seg>()
        for ((line, ws) in words2.groupBy { it.line }) {
            val sorted = ws.sortedBy { it.l }
            val hMed = sorted.map { it.b - it.t }.sorted()[sorted.size / 2]
            var cur = arrayListOf(sorted[0])
            for (k in 1 until sorted.size) {
                if (sorted[k].l - cur.last().r > hMed * 3f) { segs += Seg(cur, line / 100); cur = arrayListOf() }
                cur += sorted[k]
            }
            segs += Seg(cur, line / 100)
        }
        val inks = words2.map { inkFraction(it.l, it.t, it.r, it.b) }.sorted()
        val medInk = if (inks.isEmpty()) 0.2f else inks[inks.size / 2]
        // Immagini a bassa risoluzione (lettere piccole in pixel, contorni sfumati): fra normale e grassetto
        // la differenza d'inchiostro è minore
        val boldRatio = if (medH < 22f) 1.2f else 1.32f
        val fonts = listOf(ExactLayout.Font("Arial", false, false), ExactLayout.Font("Arial", true, false), ExactLayout.Font("Segoe UI Symbol", false, false))

        class Measured(val seg: Seg, val base: Float, val sizeH: Float, val sizeW: Float, val bold: Boolean)
        val measured = segs.map { sg ->
            val ws = sg.ws
            val base = ws.mapNotNull { it.base }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] } ?: ws.map { it.b }.sorted()[ws.size / 2]
            // Corpo dall'altezza: dalla cima delle lettere più alte alla linea di base ≈ 72% del corpo
            val top = ws.map { it.t }.sorted().let { it[min(it.size - 1, it.size / 5)] }
            val sizeH = ((base - top) / 0.72f).coerceAtLeast(4f) * s
            // Corpo dalla larghezza: il testo in Arial deve occupare quanto nell'originale (i caratteri veri sono spesso più stretti)
            val real = ws.sumOf { ((it.r - it.l) * s).toDouble() }.toFloat()
            val bold = ws.map { inkFraction(it.l, it.t, it.r, it.b) }.average().toFloat() > medInk * boldRatio && ws.size <= 12
            val est = ws.sumOf { Widths.arial(it.text, bold).toDouble() }.toFloat()
            val sizeW = if (est > 0f) real / est else sizeH
            Measured(sg, base, sizeH, sizeW, bold)
        }
        // Stesso corpo per le righe dello stesso paragrafo (mediana), mai più largo dell'originale
        val byPara = measured.groupBy { it.seg.para }
        val glyphs = ArrayList<ExactLayout.Glyph>()
        // Trattino o pallino da solo (inizio di un elenco): la sua altezza non dice il corpo; si usa quello
        // del testo che lo segue sulla stessa riga
        // Corpi della pagina: i più usati (testo, tabelle, note). Un corpo vicino a uno di questi diventa quello:
        // le misure dall'immagine oscillano, il documento originale ne usa pochi (altrimenti le tabelle si
        // allungano e il testo scivola alla pagina dopo)
        val weights = HashMap<Float, Int>()
        for (m in measured) { val k = (min(m.sizeH, m.sizeW * 1.03f) * 2f).roundToInt() / 2f; weights[k] = (weights[k] ?: 0) + m.seg.ws.sumOf { it.text.length } }
        val totalW = weights.values.sum().coerceAtLeast(1)
        val common = weights.entries.filter { it.value >= totalW * 0.08f }.sortedByDescending { it.value }.take(4).map { it.key }
        fun snap(v: Float): Float {
            val c = common.minByOrNull { abs(it - v) } ?: return v
            return if (abs(c - v) <= c * 0.2f) c else v
        }
        fun lone(m: Measured) = m.seg.ws.size == 1 && m.seg.ws[0].text in setOf("-", "•", "–")
        for (m in measured) {
            val mate = if (lone(m)) measured.filter { o -> o !== m && !lone(o) && abs(o.base - m.base) < o.sizeH / s * 0.4f && o.seg.ws.minOf { it.l } > m.seg.ws[0].r }.minByOrNull { o -> o.seg.ws.minOf { it.l } } else null
            val peers = byPara[(mate ?: m).seg.para].orEmpty().filter { abs(it.sizeH - (mate ?: m).sizeH) < (mate ?: m).sizeH * 0.25f }
            val hs = peers.map { it.sizeH }.sorted()
            val sizeH = if (hs.isEmpty()) (mate ?: m).sizeH else hs[hs.size / 2]
            val sizeW = if (mate != null) mate.sizeW else if (m.seg.ws.sumOf { it.text.length } >= 4) m.sizeW else sizeH
            val size = snap((min(sizeH, sizeW * 1.03f) * 2f).roundToInt() / 2f)
            note("riga ${m.seg.ws.joinToString(" ") { it.text }.take(40)}: corpo ${size} (H ${sizeH}, W ${sizeW}) base ${m.base * s}")
            for (wd in m.seg.ws) glyphs += ExactLayout.Glyph(wd.text, wd.l * s, m.base * s, (wd.r - wd.l) * s, size.coerceIn(5f, 72f), if (m.bold) 1 else 0, wordColor(px, w, h, wd, thr))
        }

        // Caselle: un ☐ per ogni quadratino stampato senza altro testo sopra
        for (c in boxes) {
            if (words2.any { it.l < c.r && it.r > c.l && it.t < c.b && it.b > c.t }) continue
            val side = (c.r - c.l) * s
            glyphs += ExactLayout.Glyph("☐", c.l * s - side * 0.08f, c.b * s - side * 0.02f, side * 1.15f, (side * 1.25f * 2f).roundToInt() / 2f, 2, 0)
        }

        // Linee e sfondi in punti (le linee nere restano nere: il grigio della scansione non conta)
        val fills = ArrayList<ExactLayout.Box>()
        val exclude = regions.map { ExactLayout.Box(it.l - 4f, it.t - 4f, it.l + it.w + 4f, it.t + it.h + 4f) } +
            boxes.map { ExactLayout.Box(it.l - 2f, it.t - 2f, it.r + 2f, it.b + 2f) }
        val found = rules(w, h, px, exclude)
        // Via i tratti dentro una parola (aste e barre delle lettere) e le asticelle verticali isolate
        val horiz = found.filter { it.w >= it.h }
        val kept = found.filter { r ->
            val inside = words2.any { r.l >= it.l - 3f && r.r <= it.r + 3f && r.t >= it.t - 3f && r.b <= it.b + 3f }
            if (inside) return@filter false
            if (r.h > r.w && r.h < medH * 2.2f) horiz.any { hr -> hr.l <= r.r + 4f && hr.r >= r.l - 4f && (abs(hr.t - r.t) < 6f || abs(hr.b - r.b) < 6f) }
            else true
        }
        kept.forEach { r -> fills += pt(r) }
        tints(w, h, px).forEach { t -> fills += pt(t) }
        return ExactLayout.build(ExactLayout.Page(pw, ph, fonts, glyphs, fills, emptyList(), pics)) { null }
    }

    /** Gruppi di almeno 10 barre verticali vicine con la stessa altezza: codici a barre (riquadri in pixel). */
    private fun barcodeZones(vertical: List<ExactLayout.Box>, medH: Float): List<ExactLayout.Box> {
        val out = ArrayList<ExactLayout.Box>()
        val sorted = vertical.sortedBy { it.l }
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            val a = sorted[i]
            val group = arrayListOf(a)
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if (b.l - group.last().r > medH * 0.8f) break
                if (abs(b.t - a.t) < medH * 0.4f && abs(b.b - a.b) < medH * 0.4f) group += b
            }
            if (group.size >= 10) {
                group.forEach { g -> used[sorted.indexOf(g)] = true }
                out += ExactLayout.Box(group.minOf { it.l }, group.minOf { it.t }, group.maxOf { it.r }, group.maxOf { it.b })
            }
        }
        return out
    }

    /** Colore della parola: i pixel più scuri; quasi neri o grigi → nero. */
    private fun wordColor(px: IntArray, w: Int, h: Int, wd: OcrWord, thr: Int): Int {
        val cols = ArrayList<Int>()
        val x0 = wd.l.toInt().coerceIn(0, w - 1); val x1 = wd.r.toInt().coerceIn(x0 + 1, w)
        val y0 = wd.t.toInt().coerceIn(0, h - 1); val y1 = wd.b.toInt().coerceIn(y0 + 1, h)
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) { val c = px[y * w + x]; if (lum(c) < thr) cols += c }
        if (cols.size < 15) return 0 // parola piccola: troppo pochi pixel per capire il colore
        cols.sortBy { lum(it) }
        // Solo il cuore delle lettere (il 20% più scuro): i bordi sfumati o colorati restano fuori
        val darkest = cols.subList(0, max(3, cols.size / 5).coerceAtMost(cols.size))
        // Media, non mediana per canale: i bordi colorati delle lettere (ClearType delle catture dello schermo,
        // sbavature del JPEG) sono metà blu e metà arancio, e nella media si annullano in grigio
        fun avg(sh: Int) = darkest.sumOf { it shr sh and 255 } / darkest.size
        val r = avg(16); val g = avg(8); val b = avg(0)
        val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
        // Colore vero solo se netto (testo rosso, blu…), non una sfumatura
        // Colori molto scuri (canale più forte sotto 100) a occhio sono neri: di solito sono sbavature
        return if (hi - lo < 50 || hi - lo < hi * 0.35f || hi < 100) 0 else (r shl 16) or (g shl 8) or b
    }
}
