package it.spagnuolo.docverso.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ricostruzione fedele di una pagina di PDF digitale: il PDF dice esattamente dove sta ogni lettera,
 * con quale carattere e dimensione, e quali linee e riquadri sono disegnati. Da qui:
 * - le linee che formano griglie diventano tabelle Word con le stesse misure e gli stessi bordi;
 * - il testo resta nella sua posizione (spaziatura e tabulazioni esatte, stesso carattere);
 * - codici a barre, disegni, immagini, linee isolate e sfondi colorati sono messi nella posizione esatta.
 * Tutto in punti tipografici, origine in alto a sinistra.
 */
object ExactLayout {

    class Font(val name: String, val bold: Boolean, val italic: Boolean)

    /** Lettera: [x] sinistra, [base] linea di base, [w] larghezza, [size] corpo in punti. */
    class Glyph(val ch: String, val x: Float, val base: Float, val w: Float, val size: Float, val font: Int, val color: Int)

    /** Riquadro pieno (le linee sottili sono riquadri sottili). [color] 0xRRGGBB. */
    class Box(val l: Float, val t: Float, val r: Float, val b: Float, val color: Int = 0) {
        val w get() = r - l
        val h get() = b - t
    }

    /** Immagine incorporata nel PDF, già in JPEG, con la sua posizione. */
    class Pic(val jpeg: ByteArray, val l: Float, val t: Float, val r: Float, val b: Float, val behind: Boolean = false)

    class Page(
        val w: Float, val h: Float,
        val fonts: List<Font>,
        val glyphs: List<Glyph>,
        val fills: List<Box>,
        /** Zone con disegni (curve, forme oblique): riprodotte come immagine ritagliata dalla pagina. */
        val art: List<Box>,
        val pics: List<Pic> = emptyList(),
    )

    private const val THIN = 2.5f

    /**
     * [crop] ritaglia una zona della pagina disegnata e la restituisce in PNG o JPEG (null se non disponibile).
     * [cropErasing] (facoltativo) ritaglia una zona cancellando le lettere che ci stanno sopra (riquadri dati):
     * serve per i disegni con testo sopra (loghi, intestazioni colorate), che vanno dietro al testo vero.
     */
    fun build(page: Page, cropErasing: ((Box, List<Box>) -> ByteArray?)? = null, crop: (Box) -> ByteArray?): DocItem.ExactPage {
        val anchors = ArrayList<DocItem.Anchor>()

        // --- Linee e sfondi (gli sfondi delle celle diventano sfondi di cella, gli altri riquadri ancorati)
        val rules = ArrayList<Box>()
        val solids = ArrayList<Box>()
        for (f in page.fills) {
            if (f.w <= 0f && f.h <= 0f) continue
            val thin = min(f.w, f.h) <= THIN && max(f.w, f.h) >= 2f * max(min(f.w, f.h), 0.5f)
            if (thin) rules += f
            else if (!nearWhite(f.color) && f.w * f.h < page.w * page.h * 0.9f) solids += f
        }

        // --- Parole (lettere raggruppate) e codici a barre
        val words = words(page)
        val (barcodes, text) = words.partition { isBarcode(it, page.fonts) }
        val artBoxes = ArrayList<Box>()
        barcodes.forEach { w -> artBoxes += Box(w.l, w.base - w.size * 0.95f, w.r, w.base + w.size * 0.08f) }
        // Disegni senza testo: in primo piano. Con testo sopra (logo, fascia colorata): dietro al testo,
        // con le lettere cancellate dal ritaglio (il testo resta modificabile e traducibile)
        for (a in mergeBoxes(page.art)) {
            val inside = text.filter { it.cx in a.l..a.r && it.cy in a.t..a.b }
            if (inside.isEmpty()) { artBoxes += a; continue }
            val c = pad(a, 0.5f, page)
            val erase = inside.map { w -> Box(w.l - 0.8f, w.base - w.size * 0.95f, w.r + 0.8f, w.base + w.size * 0.25f) }
            cropErasing?.invoke(c, erase)?.let { anchors += DocItem.Anchor(it, isPng(it), c.l, c.t, c.w, c.h, behind = true) }
        }
        for (a in artBoxes) {
            val c = pad(a, 0.5f, page)
            crop(c)?.let { anchors += DocItem.Anchor(it, isPng(it), c.l, c.t, c.w, c.h, behind = false) }
        }
        for (p in page.pics) anchors += DocItem.Anchor(p.jpeg, false, p.l, p.t, p.r - p.l, p.b - p.t, behind = p.behind)

        // --- Griglie (tabelle) dalle linee che si toccano
        val grids = ArrayList<GridShape>()
        for (group in groupRules(rules)) {
            val g = gridOf(group)
            if (g != null && text.any { g.contains(it) }) grids += g
            else group.forEach { anchors += solid(it, behind = false) }
        }

        // Sfondi che stanno dentro una tabella: diventano sfondi di cella (si spostano con la tabella)
        val inGrid = solids.filter { f -> grids.any { g -> f.l >= g.xs.first() - 2f && f.r <= g.xs.last() + 2f && f.t >= g.ys.first() - 2f && f.b <= g.ys.last() + 2f } }
        solids.filter { it !in inGrid }.forEach { anchors += solid(it, behind = true) }

        // --- Righe di testo fuori dalle tabelle
        val free = text.filter { w -> grids.none { it.contains(w) } }
        val lines = lines(free)

        // Margini: il testo deve stare dove sta nell'originale
        val xs = lines.map { it.l } + grids.map { it.xs.first() }
        val xr = lines.map { it.r } + grids.map { it.xs.last() }
        val ys = lines.map { it.top } + grids.map { it.ys.first() }
        val yb = lines.map { it.bottom } + grids.map { it.ys.last() }
        val mL = (xs.minOrNull() ?: 56f).coerceIn(0f, 56f).let { kotlin.math.floor(it) }
        val mR = (page.w - (xr.maxOrNull() ?: (page.w - 56f)) - 1f).coerceIn(0f, 56f)
        val mT = ((ys.minOrNull() ?: 56f) - 2f).coerceIn(0f, 56f)
        // Margine inferiore minimo: le posizioni si misurano dall'alto, quindi non sposta nulla, ma lascia spazio
        // di riserva se Word impagina una riga in più (altrimenti finirebbe nella colonna o nella pagina dopo)
        val mB = (page.h - (yb.maxOrNull() ?: (page.h - 56f)) - 4f).coerceIn(0f, 6f)
        val textR = page.w - mR

        // --- Colonne: pagine a due colonne (articoli, riviste) → vere colonne Word, una zona per volta
        val split = columnSplit(free, grids, page.w)
        val blocks = ArrayList<DocItem.Block>()
        if (split == null) {
            blocks += flow(itemsOf(lines, grids), mL, textR, mT, page.w / 2f, text, page.fonts, inGrid).first
            if (blocks.isEmpty() || blocks.last() is DocItem.Grid) blocks += DocItem.Para(emptyList(), 0f, 0.6f)
        } else {
            var cursor = mT
            for (z in zonesOf(split, free, grids)) {
                if (z.right == null) {
                    // Zona a una colonna (titolo, tabella o figura larga)
                    val (bs, end) = flow(itemsOf(lines(z.left), z.grids), mL, textR, cursor, page.w / 2f, text, page.fonts, inGrid)
                    blocks += bs
                    if (bs.isEmpty() || bs.last() is DocItem.Grid) blocks += DocItem.Para(emptyList(), 0f, 0.6f)
                    blocks += DocItem.SectionMark(emptyList(), emptyList())
                    cursor = end
                } else {
                    val lGrids = z.grids.filter { it.xs.last() <= split.gx }
                    val rGrids = z.grids.filter { it.xs.first() >= split.gx }
                    val leftLines = lines(z.left); val rightLines = lines(z.right)
                    val colL = mL
                    val colLR = ((leftLines.map { it.r } + lGrids.map { it.xs.last() }).maxOrNull() ?: split.gx - 6f)
                    val colRL = ((rightLines.map { it.l } + rGrids.map { it.xs.first() }).minOrNull() ?: split.gx + 6f)
                    // Lo spazio vuoto prima delle colonne va nella fascia precedente: così entrambe le colonne
                    // cominciano in cima alla loro sezione (Word e LibreOffice trattano diversamente lo spazio
                    // prima del primo paragrafo di una colonna)
                    val firstTop = (leftLines.map { it.top } + rightLines.map { it.top } + z.grids.map { it.ys.first() }).minOrNull() ?: cursor
                    val gap = (firstTop - cursor - 3f).coerceAtLeast(0f)
                    val markAt = blocks.indexOfLast { it is DocItem.SectionMark }
                    if (gap > 0.5f && markAt >= 0) { blocks.add(markAt, DocItem.Para(emptyList(), 0f, gap)); cursor += gap }
                    val (lb, le) = flow(itemsOf(leftLines, lGrids), colL, colLR, cursor, (colL + colLR) / 2f, text, page.fonts, inGrid)
                    val (rb, re) = flow(itemsOf(rightLines, rGrids), colRL, textR, cursor, (colRL + textR) / 2f, text, page.fonts, inGrid)
                    blocks += lb
                    if (lb.isEmpty() || lb.last() is DocItem.Grid) blocks += DocItem.Para(emptyList(), 0f, 0.6f)
                    blocks += DocItem.ColumnBreak
                    blocks += rb
                    if (rb.isEmpty() || rb.last() is DocItem.Grid) blocks += DocItem.Para(emptyList(), 0f, 0.6f)
                    blocks += DocItem.SectionMark(listOf(colLR - colL, textR - colRL), listOf(max(colRL - colLR, 4f)))
                    cursor = max(le, re)
                }
            }
        }
        return DocItem.ExactPage(page.w, page.h, mL, mT, mR, mB, blocks, anchors)
    }

    private class Item(val top: Float, val line: TLine? = null, val grid: GridShape? = null)

    private fun itemsOf(lines: List<TLine>, grids: List<GridShape>) =
        (lines.map { Item(it.top, line = it) } + grids.map { Item(it.ys.first(), grid = it) }).sortedBy { it.top }

    /**
     * Testo e tabelle di una colonna (o della pagina) nell'ordine verticale, fra [colL] e [colR], dal punto
     * [start]. Restituisce i blocchi e il punto in cui finiscono.
     */
    private fun flow(
        items: List<Item>, colL: Float, colR: Float, start: Float, centerX: Float,
        text: List<Word>, fonts: List<Font>, inGrid: List<Box>,
    ): Pair<List<DocItem.Block>, Float> {
        val blocks = ArrayList<DocItem.Block>()
        var cursor = start
        var i = 0
        var lastWasGrid = false
        while (i < items.size) {
            val it = items[i]
            if (it.grid != null) {
                val g = it.grid
                val gap = g.ys.first() - cursor
                // Fra due tabelle (o prima della prima) serve un paragrafo: alto quanto lo spazio vuoto
                if (gap >= 0.5f || lastWasGrid || blocks.isEmpty()) {
                    val h = max(gap, 0.6f)
                    blocks += DocItem.Para(emptyList(), 0f, h)
                    cursor += h
                }
                blocks += gridBlock(g, text, colL, fonts, inGrid)
                cursor = max(cursor, g.ys.last())
                lastWasGrid = true
                i++
                continue
            }
            val line = it.line!!
            // Riga accanto a qualcosa di già messo (sovrapposta): in posizione assoluta
            if (line.top < cursor - 1.5f) {
                blocks += framed(line, fonts)
                i++
                continue
            }
            // Righe di un paragrafo che va a capo: un solo paragrafo, Word le rimanda a capo uguale.
            // La prima riga può essere rientrata (rientro della prima riga), le altre allineate fra loro.
            val group = arrayListOf(line)
            val reach = { ln: TLine -> ln.r > colR - max(4f * ln.size, (colR - colL) * 0.12f) }
            while (i + group.size < items.size) {
                val nx = items[i + group.size].line ?: break
                val pv = group.last()
                val step = nx.base - pv.base
                val sameStep = group.size < 2 || abs(step - (group[1].base - group[0].base)) < 0.6f
                val bodyL = if (group.size >= 2) group[1].l else nx.l
                val aligned = abs(nx.l - bodyL) < 2f && (group.size >= 2 || abs(nx.l - line.l) < 2f || (line.l - nx.l in 2f..(6f * line.size)))
                val ok = pv.segs.size == 1 && nx.segs.size == 1 && aligned &&
                    abs(nx.size - pv.size) < 0.6f && step > 0f && step < pv.size * 1.8f && sameStep && reach(pv)
                if (!ok) break
                group += nx
            }
            val size = group.maxOf { it.size }
            val nextBase = items.getOrNull(i + group.size)?.line?.base
            val lineH = if (group.size > 1) group[1].base - group[0].base
            else (size * 1.15f).let { h -> if (nextBase != null && nextBase - line.base in size..h) nextBase - line.base else h }
            val before = (lineTop(line.base, size, lineH) - cursor).coerceAtLeast(0f)
            if (group.size > 1) {
                val runs = ArrayList<DocItem.Run>()
                group.forEachIndexed { k, ln ->
                    val rs = ln.segs[0].runs(fonts, single = true)
                    if (k > 0 && runs.isNotEmpty()) {
                        val last = runs.removeAt(runs.size - 1)
                        // A capo dopo un trattino d'unione: la parola si ricompone
                        runs += if (last.text.endsWith("-") && last.text.length > 1 && !last.text.endsWith(" -")) last.copy(text = last.text.dropLast(1)) else last.copy(text = last.text + " ")
                    }
                    runs += rs
                }
                val bodyL = group[1].l
                val colRight = group.dropLast(1).maxOf { it.r }
                val justify = group.size > 2 && group.dropLast(1).all { abs(it.r - colRight) < 1.5f }
                blocks += DocItem.Para(
                    listOf(DocItem.Piece(bodyL - colL, mergeRuns(runs))), before, lineH,
                    rightPt = (colR - colRight).coerceAtLeast(0f), justify = justify, firstLinePt = line.l - bodyL,
                )
            } else {
                blocks += lineBlock(line, before, lineH, colL, colR, centerX, fonts)
            }
            cursor = lineTop(line.base, size, lineH).coerceAtLeast(cursor) + lineH * group.size
            lastWasGrid = false
            i += group.size
        }
        return blocks to cursor
    }

    // ---------------------------------------------------------------- colonne

    /** Confine fra le colonne: [gx] = metà dello spazio vuoto fra colonna sinistra e destra. */
    private class Split(val gx: Float)

    /**
     * Pagina a due colonne? Si cerca una linea verticale (al centro della pagina) che quasi nessuna riga
     * attraversa con testo continuo, con molte righe a sinistra e a destra.
     */
    private fun columnSplit(words: List<Word>, grids: List<GridShape>, pageW: Float): Split? {
        if (words.size < 40) return null
        val rows = lines(words)
        fun crosses(ln: TLine, x: Float): Boolean {
            val ws = ln.segs.flatMap { it.words }.sortedBy { it.l }
            if (ws.any { it.l < x && it.r > x }) return true
            val before = ws.lastOrNull { it.r <= x } ?: return false
            val after = ws.firstOrNull { it.l >= x } ?: return false
            return after.l - before.r < 1.5f * ln.size
        }
        val minL = words.minOf { it.l }; val maxR = words.maxOf { it.r }
        fun median(v: List<Float>) = if (v.isEmpty()) 0f else v.sorted()[v.size / 2]
        var best: Split? = null; var bestScore = 0f
        var x = pageW * 0.3f
        while (x <= pageW * 0.7f) {
            var cross = 0
            val lw = ArrayList<Float>(); val rw = ArrayList<Float>()
            for (ln in rows) {
                if (crosses(ln, x)) { cross++; continue }
                val ws = ln.segs.flatMap { it.words }
                val l = ws.filter { it.r <= x }; val r = ws.filter { it.l >= x }
                if (l.isNotEmpty()) lw += l.maxOf { it.r } - l.minOf { it.l }
                if (r.isNotEmpty()) rw += r.maxOf { it.r } - r.minOf { it.l }
            }
            cross += grids.count { it.xs.first() < x - 2f && it.xs.last() > x + 2f }
            // Vere colonne di testo: righe che riempiono buona parte della colonna da entrambe le parti
            // (non un indice con i numeri di pagina a destra, né etichette e valori di un modulo)
            val full = median(lw) >= (x - minL) * 0.55f && median(rw) >= (maxR - x) * 0.55f
            val score = min(lw.size, rw.size) - 2f * cross
            if (full && min(lw.size, rw.size) >= 8 && cross <= rows.size * 0.25f && score > bestScore) { bestScore = score; best = Split(x) }
            x += 2f
        }
        // Al centro dello spazio vuoto, non sul suo bordo
        return best?.let { b ->
            val gapL = words.filter { it.r <= b.gx + 0.01f }.maxOfOrNull { w -> if (w.r > b.gx - pageW * 0.2f) w.r else 0f } ?: b.gx
            val gapR = words.filter { it.l >= b.gx }.minOfOrNull { w -> if (w.l < b.gx + pageW * 0.2f) w.l else pageW } ?: b.gx
            Split((gapL + gapR) / 2f)
        }
    }

    /** Fascia verticale della pagina: a una colonna ([right] = null) o a due. */
    private class Zone(val left: List<Word>, val right: List<Word>?, val grids: List<GridShape>)

    /**
     * Divide la pagina in fasce dall'alto: le righe che attraversano il confine (titoli, tabelle larghe)
     * formano fasce a una colonna, il resto fasce a due colonne.
     */
    private fun zonesOf(split: Split, words: List<Word>, grids: List<GridShape>): List<Zone> {
        val gx = split.gx
        class Band(val top: Float, val bottom: Float, val wide: Boolean, val words: List<Word>, val grid: GridShape?)
        val bands = ArrayList<Band>()
        for (ln in lines(words)) {
            val ws = ln.segs.flatMap { it.words }.sortedBy { it.l }
            val before = ws.lastOrNull { it.r <= gx }; val after = ws.firstOrNull { it.l >= gx }
            val wide = ws.any { it.l < gx && it.r > gx } || (before != null && after != null && after.l - before.r < 1.5f * ln.size)
            bands += Band(ln.top, ln.bottom, wide, ws, null)
        }
        for (g in grids) bands += Band(g.ys.first(), g.ys.last(), g.xs.first() < gx - 2f && g.xs.last() > gx + 2f, emptyList(), g)
        bands.sortBy { it.top }
        // Righe corte da una parte sola (ultima riga di un titolo o di un paragrafo largo, titolo di sezione):
        // seguono la fascia più vicina, sopra o sotto, fra quelle certe (larghe o con testo da entrambe le parti)
        val sure = bands.map { b -> b.wide || b.grid != null || (b.words.any { it.cx < gx } && b.words.any { it.cx >= gx }) }
        val wideOf = BooleanArray(bands.size) { bands[it].wide }
        for (k in bands.indices) {
            if (sure[k]) continue
            val p = (k - 1 downTo 0).firstOrNull { sure[it] }
            val n = (k + 1 until bands.size).firstOrNull { sure[it] }
            val gp = p?.let { bands[k].top - bands[it].bottom } ?: Float.MAX_VALUE
            val gn = n?.let { bands[it].top - bands[k].bottom } ?: Float.MAX_VALUE
            val from = if (gp <= gn) p else n
            wideOf[k] = from != null && bands[from].wide
        }
        // Una riga larga in mezzo alle colonne (es. un titolo di sezione centrato su due colonne) chiude la fascia
        val zones = ArrayList<Zone>()
        var cur = ArrayList<Band>(); var curWide: Boolean? = null
        fun close() {
            if (cur.isEmpty()) return
            val ws = cur.flatMap { it.words }; val gs = cur.mapNotNull { it.grid }
            zones += if (curWide == true) Zone(ws, null, gs) else Zone(ws.filter { it.cx < gx }, ws.filter { it.cx >= gx }, gs)
            cur = ArrayList()
        }
        bands.forEachIndexed { k, b ->
            if (curWide != null && wideOf[k] != curWide) close()
            curWide = wideOf[k]; cur += b
        }
        close()
        return zones
    }

    /** Con interlinea esatta il testo poggia su una linea di base a circa l'80% dell'altezza della riga. */
    private fun lineTop(base: Float, size: Float, lineH: Float) = base - (lineH - BASE_DESC * size)

    /** Con interlinea esatta la linea di base sta a questa frazione del corpo sopra il fondo della riga. */
    var BASE_DESC = 0.21f

    // ---------------------------------------------------------------- parole e righe

    private class Word(val glyphs: List<Glyph>) {
        val l = glyphs.first().x
        val r = glyphs.maxOf { it.x + it.w }
        val base = glyphs.map { it.base }.sorted()[glyphs.size / 2]
        val size = glyphs.maxOf { it.size }
        val top get() = base - size * 0.8f
        val bottom get() = base + size * 0.2f
        val cx get() = (l + r) / 2f
        val cy get() = base - size * 0.35f
        val text get() = glyphs.joinToString("") { it.ch }
    }

    /** Lettere → parole (spezza dove c'è spazio o cambia la riga). */
    private fun words(page: Page): List<Word> {
        val gs = page.glyphs.filter { it.ch.isNotBlank() && it.size > 0.5f }.sortedWith(compareBy({ it.base }, { it.x }))
        // Prima le righe (stessa linea di base), poi le parole lungo la riga
        val rows = ArrayList<ArrayList<Glyph>>()
        for (g in gs) {
            val row = rows.lastOrNull()
            if (row != null && abs(g.base - row[0].base) <= 0.3f * min(g.size, row[0].size)) row += g else rows += arrayListOf(g)
        }
        val out = ArrayList<Word>()
        for (row in rows) {
            row.sortBy { it.x }
            var cur = ArrayList<Glyph>()
            var right = 0f
            for (g in row) {
                if (cur.isNotEmpty() && (g.x - right > 0.15f * max(g.size, cur.last().size) || g.x < right - g.size)) {
                    out += Word(cur); cur = ArrayList()
                }
                cur += g
                right = max(right, g.x + g.w)
            }
            if (cur.isNotEmpty()) out += Word(cur)
        }
        return out
    }

    private val barcodeFont = Regex("code.?128|code.?39|3of9|barcode|ean.?13|upc|interleaved|i2of5|codabar|datamatrix|qrcode|idautomation", RegexOption.IGNORE_CASE)

    private fun isBarcode(w: Word, fonts: List<Font>): Boolean {
        val f = fonts.getOrNull(w.glyphs[0].font)?.name ?: ""
        if (barcodeFont.containsMatchIn(f)) return true
        val t = w.text
        return t.length >= 4 && t.first() in "ÌÍÎÏ" && t.last() in "ÎÓ" // caratteri di controllo dei font Code 128
    }

    /** Pezzo di riga: parole vicine. */
    private class Seg(val words: List<Word>) {
        val l get() = words.first().l
        val r get() = words.last().r
        /** [single] = un solo spazio fra le parole (testo che Word rimanda a capo, anche giustificato). */
        fun runs(fonts: List<Font>, single: Boolean = false): List<DocItem.Run> {
            val out = ArrayList<DocItem.Run>()
            words.forEachIndexed { k, w ->
                if (k > 0) {
                    val gap = w.l - words[k - 1].r
                    // Spazi in più dove l'originale lascia più spazio (allineamenti a mano)
                    val n = if (single) 1 else (gap / (0.278f * w.size)).roundToInt().coerceIn(1, 6)
                    val prev = out.removeAt(out.size - 1)
                    out += prev.copy(text = prev.text + " ".repeat(n))
                }
                for (g in w.glyphs) {
                    val f = fonts.getOrNull(g.font)
                    val run = DocItem.Run(g.ch, family(f?.name), roundSize(g.size), f?.bold == true, f?.italic == true, g.color)
                    val last = out.lastOrNull()
                    if (last != null && last.font == run.font && last.sizePt == run.sizePt && last.bold == run.bold && last.italic == run.italic && last.color == run.color)
                        out[out.size - 1] = last.copy(text = last.text + g.ch)
                    else out += run
                }
            }
            return out
        }
    }

    private class TLine(val segs: List<Seg>) {
        val l get() = segs.first().l
        val r get() = segs.last().r
        val base = segs.flatMap { it.words }.map { it.base }.sorted().let { it[it.size / 2] }
        val size = segs.flatMap { it.words }.maxOf { it.size }
        val top get() = segs.flatMap { it.words }.minOf { it.top }
        val bottom get() = segs.flatMap { it.words }.maxOf { it.bottom }
        val desc get() = size * 0.21f
    }

    private fun lines(words: List<Word>): List<TLine> {
        val rows = ArrayList<ArrayList<Word>>()
        for (w in words.sortedWith(compareBy({ it.base }, { it.l }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(w.base - row[0].base) <= 0.35f * min(w.size, row[0].size)) row += w else rows += arrayListOf(w)
        }
        return rows.map { row ->
            row.sortBy { it.l }
            val segs = ArrayList<ArrayList<Word>>()
            for (w in row) {
                val s = segs.lastOrNull()
                if (s != null && w.l - s.last().r < max(1.2f * max(w.size, s.last().size), 6f)) s += w else segs += arrayListOf(w)
            }
            TLine(segs.map { Seg(it) })
        }
    }

    private fun roundSize(s: Float) = ((s * 2f).roundToInt() / 2f).coerceIn(1f, 200f)

    private fun mergeRuns(runs: List<DocItem.Run>): List<DocItem.Run> {
        val out = ArrayList<DocItem.Run>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.font == r.font && last.sizePt == r.sizePt && last.bold == r.bold && last.italic == r.italic && last.color == r.color)
                out[out.size - 1] = last.copy(text = last.text + r.text)
            else out += r
        }
        return out
    }

    /** Nome del carattere PDF → famiglia Word con le stesse misure. */
    fun family(pdfName: String?): String? {
        if (pdfName.isNullOrBlank()) return null
        val n = pdfName.substringAfter('+').replace(Regex("[-,](bold|italic|oblique|regular|roman|book|medium|light|semibold|black|heavy|condensed|narrow|bd|it)+.*$", RegexOption.IGNORE_CASE), "")
        val low = n.lowercase()
        return when {
            low.startsWith("arialnarrow") || low.startsWith("liberationsansnarrow") -> "Arial Narrow"
            "helvetica" in low || low.startsWith("arial") || "liberationsans" in low -> "Arial"
            low.startsWith("times") || "liberationserif" in low -> "Times New Roman"
            low.startsWith("courier") || "liberationmono" in low -> "Courier New"
            low.startsWith("carlito") -> "Calibri"
            low.startsWith("caladea") -> "Cambria"
            low.startsWith("liberationsansnarrow") -> "Arial Narrow"
            low.startsWith("calibri") -> "Calibri"
            low.startsWith("cambria") -> "Cambria"
            low.startsWith("verdana") -> "Verdana"
            low.startsWith("tahoma") -> "Tahoma"
            low.startsWith("georgia") -> "Georgia"
            low.startsWith("garamond") -> "Garamond"
            low.startsWith("dejavusans") -> "DejaVu Sans"
            low.startsWith("symbol") -> "Symbol"
            low.startsWith("zapfdingbats") -> "Wingdings"
            low.startsWith("opensymbol") -> "Arial" // pallini degli elenchi di LibreOffice (già convertiti in •)
            else -> n.removeSuffix("MT").removeSuffix("PS").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()
        }
    }

    private fun lineBlock(line: TLine, before: Float, lineH: Float, colL: Float, colR: Float, centerX: Float, fonts: List<Font>): DocItem.Para {
        val pieces = line.segs.mapIndexed { k, s ->
            val runs = s.runs(fonts)
            val last = k == line.segs.lastIndex
            when {
                // Ultimo pezzo che finisce sul bordo destro: tabulazione a destra
                last && line.segs.size > 1 && abs(s.r - colR) < 6f -> DocItem.Piece(s.r - colL, runs, DocItem.Align.RIGHT)
                else -> DocItem.Piece(s.l - colL, runs)
            }
        }
        // Riga sola centrata (nella pagina o nella colonna)
        val center = line.segs.size == 1 && abs((line.l + line.r) / 2f - centerX) < 3f && line.l - colL > 20f
        return if (center) DocItem.Para(listOf(DocItem.Piece(0f, pieces[0].runs)), before, lineH, align = DocItem.Align.CENTER)
        else DocItem.Para(pieces, before, lineH)
    }

    private fun framed(line: TLine, fonts: List<Font>): DocItem.Para {
        val lineH = line.size * 1.15f
        val pieces = line.segs.map { DocItem.Piece(it.l - line.l, it.runs(fonts)) }
        val top = lineTop(line.base, line.size, lineH)
        return DocItem.Para(pieces, 0f, lineH, frame = floatArrayOf(line.l, top, line.r - line.l + line.size, lineH))
    }

    // ---------------------------------------------------------------- tabelle dalle linee

    private class GridShape(val xs: List<Float>, val ys: List<Float>, val rules: List<Box>) {
        fun contains(w: Word) = w.cx > xs.first() && w.cx < xs.last() && w.cy > ys.first() && w.cy < ys.last()
        // Una linea può essere disegnata a pezzi: conta se i pezzi insieme coprono il tratto
        fun hRule(y: Float, x0: Float, x1: Float): Box? =
            covered(rules.filter { it.w >= it.h && abs((it.t + it.b) / 2f - y) < 1.6f }.map { Triple(it.l, it.r, it) }, x0, x1)
        fun vRule(x: Float, y0: Float, y1: Float): Box? =
            covered(rules.filter { it.h > it.w && abs((it.l + it.r) / 2f - x) < 1.6f }.map { Triple(it.t, it.b, it) }, y0, y1)
        private fun covered(parts: List<Triple<Float, Float, Box>>, a: Float, b: Float): Box? {
            var reach = a + 1.5f
            var best: Box? = null
            for ((s, e, box) in parts.sortedBy { it.first }) {
                if (s > reach + 1.5f) break
                if (e > reach) reach = e
                if (best == null || min(box.w, box.h) > min(best.w, best.h)) best = box
            }
            return if (reach >= b - 1.5f) best else null
        }
    }

    private fun groupRules(rules: List<Box>): List<List<Box>> {
        val parent = IntArray(rules.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (a in rules.indices) for (b in a + 1 until rules.size) {
            val p = rules[a]; val q = rules[b]
            if (p.l <= q.r + 1.5f && q.l <= p.r + 1.5f && p.t <= q.b + 1.5f && q.t <= p.b + 1.5f) parent[find(a)] = find(b)
        }
        return rules.indices.groupBy { find(it) }.values.map { ids -> ids.map { rules[it] } }
    }

    private fun cluster(vals: List<Float>, tol: Float): List<Float> {
        val out = ArrayList<ArrayList<Float>>()
        for (v in vals.sorted()) { val c = out.lastOrNull(); if (c != null && v - c.last() <= tol) c += v else out += arrayListOf(v) }
        return out.map { it.average().toFloat() }
    }

    private fun gridOf(group: List<Box>): GridShape? {
        val v = group.filter { it.h > it.w }
        val h = group.filter { it.w >= it.h }
        if (v.size < 2 || h.size < 2) return null
        val xs = cluster(v.map { (it.l + it.r) / 2f }, 1.6f)
        val ys = cluster(h.map { (it.t + it.b) / 2f }, 1.6f)
        if (xs.size < 2 || ys.size < 2) return null
        // I bordi esterni devono chiudere la tabella
        if (h.none { it.l <= xs.first() + 2f } && v.none { abs((it.l + it.r) / 2f - xs.first()) < 2f }) return null
        return GridShape(xs, ys, group)
    }

    private fun gridBlock(g: GridShape, words: List<Word>, mL: Float, fonts: List<Font>, solids: List<Box> = emptyList()): DocItem.Grid {
        val nc = g.xs.size - 1
        val nr = g.ys.size - 1
        // Celle unite: manca la linea verticale fra due colonne o quella orizzontale fra due righe
        class Region(val r0: Int, val c0: Int, var r1: Int, var c1: Int)
        val owner = Array(nr) { arrayOfNulls<Region>(nc) }
        for (r in 0 until nr) {
            val y0 = g.ys[r]; val y1 = g.ys[r + 1]
            var c = 0
            while (c < nc) {
                var c1 = c
                while (c1 + 1 < nc && g.vRule(g.xs[c1 + 1], y0 + 0.5f, y1 - 0.5f) == null) c1++
                val above = if (r > 0) owner[r - 1][c] else null
                val merge = above != null && above.c0 == c && above.c1 == c1 && g.hRule(y0, g.xs[c] + 0.5f, g.xs[c1 + 1] - 0.5f) == null
                val reg = if (merge) above!!.also { it.r1 = r } else Region(r, c, r, c1)
                for (k in c..c1) owner[r][k] = reg
                c = c1 + 1
            }
        }
        // Testo di ogni cella
        val inCell = HashMap<Region, ArrayList<Word>>()
        for (w in words) {
            if (!g.contains(w)) continue
            val c = (0 until nc).firstOrNull { w.cx < g.xs[it + 1] } ?: continue
            val r = (0 until nr).firstOrNull { w.cy < g.ys[it + 1] } ?: continue
            val reg = owner[r][c] ?: continue
            inCell.getOrPut(reg) { ArrayList() } += w
        }
        val rows = (0 until nr).map { r ->
            val cells = ArrayList<DocItem.GridCell>()
            var c = 0
            while (c < nc) {
                val reg = owner[r][c]!!
                val x0 = g.xs[reg.c0]; val x1 = g.xs[reg.c1 + 1]
                val y0 = g.ys[r]; val y1 = g.ys[r + 1]
                val paras = if (reg.r0 == r) cellParas(inCell[reg].orEmpty(), x0, x1, g.ys[reg.r0], fonts) else emptyList()
                val top = if (reg.r0 == r) g.hRule(y0, x0 + 0.5f, x1 - 0.5f) else null
                val bottom = if (reg.r1 == r) g.hRule(y1, x0 + 0.5f, x1 - 0.5f) else null
                val left = g.vRule(x0, y0 + 0.5f, y1 - 0.5f)
                val right = g.vRule(x1, y0 + 0.5f, y1 - 0.5f)
                val color = listOfNotNull(top, left, bottom, right).firstOrNull()?.color ?: 0
                // Sfondo: il riquadro colorato che copre la maggior parte della cella
                val cy0 = g.ys[reg.r0]; val cy1 = g.ys[reg.r1 + 1]
                val cellArea = (x1 - x0) * (cy1 - cy0)
                val shade = solids.filter { f ->
                    val ow = min(f.r, x1) - max(f.l, x0); val oh = min(f.b, cy1) - max(f.t, cy0)
                    ow > 0f && oh > 0f && ow * oh >= cellArea * 0.6f
                }.lastOrNull()?.color
                cells += DocItem.GridCell(
                    span = reg.c1 - reg.c0 + 1,
                    vMerge = if (reg.r0 == reg.r1) 0 else if (reg.r0 == r) 1 else 2,
                    paras = paras,
                    top = top?.let { min(it.w, it.h) } ?: 0f, left = left?.let { min(it.w, it.h) } ?: 0f,
                    bottom = bottom?.let { min(it.w, it.h) } ?: 0f, right = right?.let { min(it.w, it.h) } ?: 0f,
                    borderColor = color,
                    shading = shade,
                )
                c = reg.c1 + 1
            }
            DocItem.GridRow(g.ys[r + 1] - g.ys[r], cells)
        }
        return DocItem.Grid(0f, g.xs.first() - mL, g.xs.zipWithNext { a, b -> b - a }, rows)
    }

    private fun cellParas(words: List<Word>, x0: Float, x1: Float, y0: Float, fonts: List<Font>): List<DocItem.Para> {
        if (words.isEmpty()) return emptyList()
        val lines = lines(words)
        var cursor = y0
        return lines.mapIndexed { k, line ->
            val lineH = if (k + 1 < lines.size) min(lines[k + 1].base - line.base, line.size * 1.25f).coerceAtLeast(line.size * 1.0f) else line.size * 1.15f
            val top = lineTop(line.base, line.size, lineH)
            val before = (top - cursor).coerceAtLeast(0f)
            cursor = max(cursor, top) + lineH
            val cw = x1 - x0
            val mid = (line.l + line.r) / 2f
            when {
                line.segs.size == 1 && abs(mid - (x0 + x1) / 2f) < max(2f, cw * 0.03f) && line.l - x0 > max(3f, min(8f, cw * 0.15f)) ->
                    DocItem.Para(listOf(DocItem.Piece(0f, line.segs[0].runs(fonts))), before, lineH, align = DocItem.Align.CENTER)
                line.segs.size == 1 && x1 - line.r < 3f && line.l - x0 > cw * 0.3f ->
                    DocItem.Para(listOf(DocItem.Piece(0f, line.segs[0].runs(fonts))), before, lineH, align = DocItem.Align.RIGHT)
                else -> DocItem.Para(line.segs.map { DocItem.Piece(it.l - x0, it.runs(fonts)) }, before, lineH)
            }
        }
    }

    // ---------------------------------------------------------------- disegni

    private fun mergeBoxes(boxes: List<Box>): List<Box> {
        var cur = boxes.filter { it.w > 0.5f || it.h > 0.5f }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val out = ArrayList<Box>()
            for (b in cur) {
                val k = out.indexOfFirst { it.l <= b.r + 2f && b.l <= it.r + 2f && it.t <= b.b + 2f && b.t <= it.b + 2f }
                if (k >= 0) { val o = out[k]; out[k] = Box(min(o.l, b.l), min(o.t, b.t), max(o.r, b.r), max(o.b, b.b)); changed = true } else out += b
            }
            cur = out
        }
        return cur.filter { it.w >= 3f && it.h >= 3f }
    }

    private fun pad(b: Box, p: Float, page: Page) = Box((b.l - p).coerceAtLeast(0f), (b.t - p).coerceAtLeast(0f), (b.r + p).coerceAtMost(page.w), (b.b + p).coerceAtMost(page.h))

    private fun isPng(b: ByteArray) = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()

    private fun nearWhite(c: Int) = (c shr 16 and 255) >= 246 && (c shr 8 and 255) >= 246 && (c and 255) >= 246

    private fun solid(b: Box, behind: Boolean) = DocItem.Anchor(solidPng(b.color), true, b.l, b.t, max(b.w, 0.25f), max(b.h, 0.25f), behind)

    private val pngCache = HashMap<Int, ByteArray>()

    /** PNG di un pixel del colore dato (allargato alla misura del riquadro). */
    fun solidPng(color: Int): ByteArray = pngCache.getOrPut(color and 0xFFFFFF) { Png.solid(color) }
}

/**
 * Controllo del testo di un PDF digitale: alcuni PDF hanno i caratteri codificati male (a vista sono
 * giusti, ma il testo estratto è illeggibile). Solo in quel caso la pagina passa al riconoscimento del
 * testo (OCR); i PDF normali non sono toccati.
 */
object TextQuality {
    private val barcodeFont = Regex("code.?128|code.?39|3of9|barcode|ean.?13|upc|interleaved|i2of5|codabar|datamatrix|qrcode|idautomation|symbol|dingbat|wingding", RegexOption.IGNORE_CASE)

    /** Carattere senza un significato leggibile: mancante, di uso privato, di controllo o "sostituzione". */
    fun isBad(ch: String?): Boolean {
        if (ch.isNullOrEmpty()) return true
        return ch.any { c -> c == '\uFFFD' || c in '\uE000'..'\uF8FF' || (c.code < 0x20 && c != '\t') }
    }

    /**
     * [missing] = lettere disegnate di cui il PDF non dice quale carattere sono.
     * Illeggibile se: più del 25% di caratteri senza significato, oppure un testo lungo
     * senza quasi vocali (lettere "spostate" o mescolate).
     */
    fun looksGarbled(page: ExactLayout.Page, missing: Int): Boolean {
        val text = page.glyphs.filter { g -> page.fonts.getOrNull(g.font)?.name?.let { barcodeFont.containsMatchIn(it) } != true && g.ch.isNotBlank() }
        val total = text.size + missing
        if (total < 20) return false
        val bad = missing + text.count { isBad(it.ch) }
        if (bad > total * 0.25f) return true
        val letters = text.flatMap { it.ch.toList() }.filter { it.isLetter() && it.code < 0x250 }
        if (letters.size < 200) return false
        val vowels = letters.count { it.lowercaseChar() in "aeiouyàèéìíòóùú" }
        return vowels < letters.size * 0.15f
    }
}
