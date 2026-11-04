package it.spagnuolo.docverso.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ricostruisce l'impaginazione di una pagina a partire dalle parole e dalla loro posizione
 * (riconoscimento del testo per le scansioni, testo vero per i PDF digitali):
 * righe → colonne → tabelle, titoli e paragrafi. Codice puro, senza Android, così è testabile.
 */
object LayoutAnalyzer {

    /** Parola con il suo riquadro (coordinate della pagina, y verso il basso). */
    /** [line] = riga a cui il riconoscimento ha assegnato la parola (-1 = sconosciuta). */
    data class Word(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val line: Int = -1) {
        val h get() = b - t
        val cy get() = (t + b) / 2f
    }

    /** Riquadro di una tabella trovata (per non scambiarne bordi e caselle per immagini). */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float)

    class Result(val items: List<Pair<Float, DocItem>>, val tables: List<Box>)

    private class Seg(val text: String, val l: Float, val r: Float)

    private class Row(val words: MutableList<Word>) {
        val t get() = words.minOf { it.t }
        val b get() = words.maxOf { it.b }
        val l get() = words.minOf { it.l }
        val r get() = words.maxOf { it.r }
        val h get() = b - t
        var segs: List<Seg> = emptyList()
        val cy get() = words.map { it.cy }.sorted()[words.size / 2]
        /** Altezza tipica del testo della riga (dalle parole, non dall'ingombro: le righe storte sono più "alte"). */
        val wh get() = words.map { it.h }.sorted()[words.size / 2]
        /** Linea centrale della riga all'ascissa x (tiene conto delle righe un po' storte). */
        fun cyAt(x: Float): Float = words.minBy { abs((it.l + it.r) / 2f - x) }.cy
    }

    /** Simboli che il riconoscimento restituisce al posto di una casella da spuntare. */
    private val checkboxLike = setOf("□", "☐", "☑", "☒", "口", "O", "o", "0", "D", "[]", "[", "]", "Q", "©", "®", "ロ", "▢", "■", "❑", "❒")

    /** Informazioni dall'immagine della pagina (scansioni): larghezza, linee da compilare, colori di sfondo. */
    interface PageImage {
        val width: Float
        /**
         * Quanta parte della larghezza del riquadro è coperta da segni scuri (in pixel): una linea
         * orizzontale, anche sbiadita o spezzata, copre quasi tutta la larghezza.
         */
        fun underline(l: Float, t: Float, r: Float, b: Float): Float
        /** Colore di sfondo del riquadro come 0xRRGGBB, o null se è bianco. */
        fun background(l: Float, t: Float, r: Float, b: Float): Int?
        /** Centri orizzontali delle caselle stampate (quadratini) trovate nella fascia data. */
        fun boxes(l: Float, t: Float, r: Float, b: Float): List<Float>
    }

    /**
     * [cellMark] (facoltativo): dice se nel riquadro dato della pagina c'è un segno (es. una casella
     * stampata) quando lì non è stato riconosciuto testo. [page] (facoltativo): vedi [PageImage].
     */
    fun analyze(words: List<Word>, cellMark: ((Float, Float, Float, Float) -> Boolean)? = null, page: PageImage? = null): Result {
        val clean = words.filter { it.text.isNotBlank() && it.r > it.l && it.b > it.t }
        if (clean.isEmpty()) return Result(emptyList(), emptyList())

        val wordH = median(clean.map { it.h })
        val rows = buildRows(clean, wordH)
        rows.forEach { it.segs = segments(it, wordH) }
        val rowH = median(rows.map { it.wh })

        val items = ArrayList<Pair<Float, DocItem>>()
        val tables = ArrayList<Box>()
        val plain = ArrayList<Row>()
        var i = 0
        while (i < rows.size) {
            val table = (if (page != null) boxTable(rows, i, wordH, rowH, page) else null)
                ?: (if (cellMark != null) checkboxTable(rows, i, wordH, rowH, cellMark, page) else null)
                ?: tryTable(rows, i, wordH, rowH, cellMark, page)
            if (table != null) {
                table.consumed.forEach { plain.remove(it) }
                flushParagraphs(plain, rowH, wordH, page, items); plain.clear()
                items += table.top to table.item
                tables += table.box
                i = table.nextRow
            } else {
                plain += rows[i]; i++
            }
        }
        flushParagraphs(plain, rowH, wordH, page, items)
        val fixed = items.map { (y, it) ->
            y to when (it) {
                is DocItem.Text -> it.copy(text = fixRefs(it.text))
                is DocItem.Table -> it.copy(rows = it.rows.map { r -> r.map { c -> fixRefs(c) } })
                else -> it
            }
        }
        return Result(fixed.sortedBy { it.first }, tables)
    }

    // ---------- righe e segmenti ----------

    /**
     * Raggruppa le parole in righe per la loro linea centrale. Le parole con un riquadro molto più
     * alto del normale (segni sul margine, bordi) non fondono più righe: finiscono in righe a parte.
     */
    private fun buildRows(words: List<Word>, wordH: Float): MutableList<Row> {
        val (tall, normal) = words.partition { it.h > 2.2f * wordH }
        val rows = ArrayList<Row>()
        if (normal.any { it.line >= 0 }) {
            // Righe già riconosciute: si tengono insieme e si uniscono solo i pezzi sulla stessa linea
            val pieces = normal.groupBy { if (it.line >= 0) it.line else -1000 - normal.indexOf(it) }.values.map { Row(it.toMutableList()) }
            for (p in pieces.sortedBy { it.cy }) {
                val row = rows.takeLast(4).filter { r ->
                    val overlap = min(r.b, p.b) - max(r.t, p.t)
                    overlap >= 0.5f * min(r.h, p.h) && abs(r.cy - p.cy) < 0.6f * wordH &&
                        r.words.none { w -> p.words.any { q -> q.l < w.r && q.r > w.l } }
                }.minByOrNull { abs(it.cy - p.cy) }
                if (row != null) row.words += p.words else rows += p
            }
        } else {
            for (w in normal.sortedBy { it.cy }) {
                val row = rows.takeLast(3).filter { abs(it.cy - w.cy) < 0.55f * wordH }.minByOrNull { abs(it.cy - w.cy) }
                if (row != null) row.words += w else rows += Row(mutableListOf(w))
            }
        }
        tall.filter { it.text.length > 1 }.forEach { rows += Row(mutableListOf(it)) }
        rows.forEach { r -> r.words.sortBy { it.l } }
        rows.sortBy { it.t }
        return rows
    }

    /** Errori tipici del riconoscimento nei riferimenti normativi: "(UNI $5.3d]" → "[UNI §5.3d]". */
    /** "ldoneità" → "Idoneità": in italiano nessuna parola inizia con "l" + consonante, è una I maiuscola letta male. */
    fun fixCapitalI(t: String): String = t.replace(Regex("(?<![\\p{L}'’])l(?=[bcdfghjkmnpqrstvwxzBCDFGHJKLMNPQRSTVWXZ])"), "I")

    fun fixRefs(t: String): String = fixCapitalI(t)
        .replace(Regex("\\((UNI\\s)"), "[$1")
        .replace(Regex("(UNI\\s?)[\$S9]\\s?(?=\\d+\\.\\d)"), "$1§")
        .replace(Regex("(?<=[\\s(,\\[])[\$](?=\\d+\\.\\d)"), "§")

    /** Etichette d'intestazione lette male dal riconoscimento (es. "S" per "Sì", "NJA" per "N/A"). */
    private fun normLabel(t: String): String = when (t.trim().uppercase().replace(".", "")) {
        "S", "SI", "SÌ", "SL", "SÍ", "5I", "SJ" -> "Sì"
        "NA", "N/A", "NJA", "NIA", "N/4", "NFA", "N7A", "NLA", "N|A" -> "N/A"
        "NO", "N0" -> "No"
        else -> t.trim()
    }

    /** Divide un'etichetta fusa dal riconoscimento (es. "NoNJA") in [k] etichette, se possibile. */
    private fun splitLabel(t: String, k: Int): List<String> {
        if (k <= 1) return listOf(normLabel(t))
        val known = listOf("Sì", "Si", "SI", "S", "No", "NO", "N/A", "NJA", "NIA", "NA", "Note")
        val out = ArrayList<String>()
        var rest = t.trim()
        while (rest.isNotEmpty() && out.size < k - 1) {
            val m = known.filter { rest.startsWith(it, ignoreCase = true) }.maxByOrNull { it.length } ?: break
            out += normLabel(rest.substring(0, m.length)); rest = rest.substring(m.length).trim()
        }
        out += normLabel(rest)
        while (out.size < k) out += ""
        return out.take(k)
    }

    /**
     * Moduli con caselle, metodo principale: le colonne di caselle si trovano dalle caselle stesse
     * nell'immagine (posizione dei quadratini riga per riga); le etichette dell'intestazione servono
     * solo come nomi delle colonne. Così non dipende da come il riconoscimento legge "Sì No N/A".
     */
    private fun boxTable(rows: List<Row>, start: Int, wordH: Float, rowH: Float, page: PageImage): Found? {
        val header = rows[start]
        val after = rows.subList(start + 1, minOf(rows.size, start + 8))
        if (after.size < 2) return null
        val entryL = after.minOf { it.l }
        // L'intestazione ha brevi etichette a destra, oltre la fine del testo delle voci
        val entryEnds = after.map { it.segs.first().r }.sorted()
        val textEnd = entryEnds[(entryEnds.size * 0.7f).toInt().coerceAtMost(entryEnds.size - 1)]
        val headLabels = header.segs.filter { sg ->
            sg.l > textEnd - wordH && sg.l > entryL + 6f * wordH && sg.r - sg.l < 10f * wordH &&
                sg.text.length <= 6 && sg.text.any { it.isLetter() } && !sg.text.contains('[')
        }
        if (headLabels.size < 2) return null
        val searchL = headLabels.first().l - 2f * wordH
        val searchR = min(page.width, headLabels.last().r + 3f * wordH)

        // Caselle riga per riga (solo a destra della fine del testo della voce)
        class R(val row: Row, val boxes: List<Float>)
        val scanned = ArrayList<R>()
        var j = start + 1
        var prevB = header.b
        var quiet = 0
        while (j < rows.size) {
            val row = rows[j]
            if (row.t - prevB > 2.2f * rowH) break
            if (row.l < entryL - 3f * wordH || row.wh > 1.5f * rowH) break
            val textR = row.segs.filter { it.l < searchL }.maxOfOrNull { it.r } ?: row.l
            val from = max(searchL, textR + wordH)
            val cy = row.cyAt((from + searchR) / 2f)
            val bx = if (from < searchR) page.boxes(from, cy - 0.75f * rowH, searchR, cy + 0.75f * rowH) else emptyList()
            scanned += R(row, bx)
            if (bx.isEmpty() && !continues(row.segs.first().text)) quiet++ else quiet = 0
            if (quiet >= 2) break
            prevB = row.b
            j++
        }
        // Colonne: centri delle caselle raggruppati
        val all = scanned.flatMap { it.boxes }.sorted()
        if (all.isEmpty()) return null
        val clusters = ArrayList<MutableList<Float>>()
        for (x in all) {
            val c = clusters.lastOrNull()
            if (c != null && x - c.average() < 0.9f * rowH) c += x else clusters += mutableListOf(x)
        }
        val withBoxes = scanned.count { it.boxes.isNotEmpty() }
        val cols = clusters.filter { it.size >= max(2f, 0.3f * withBoxes) }.map { it.average().toFloat() }
        if (cols.isEmpty() || withBoxes < 2) return null

        // Nomi delle colonne dalle etichette che le coprono; etichette oltre l'ultima colonna = colonne di testo (es. "Note")
        val names = MutableList(cols.size) { "" }
        val textCols = ArrayList<Seg>()
        for (lb in headLabels) {
            val covered = cols.indices.filter { cols[it] >= lb.l - 0.8f * rowH && cols[it] <= lb.r + 0.8f * rowH && names[it].isEmpty() }
            if (covered.isEmpty()) { if (lb.l > cols.last()) textCols += lb; continue }
            splitLabel(lb.text, covered.size).forEachIndexed { q, t -> names[covered[q]] = t }
        }
        val leftLabel = header.segs.filter { it.r < searchL }.joinToString(" ") { it.text }.trim().trimEnd(':', ';').trim()
        val n = 1 + cols.size + textCols.size

        // Righe
        val cells = ArrayList<Array<String>>()
        val tops = ArrayList<Float>(); val bottoms = ArrayList<Float>(); val marked = ArrayList<Boolean>()
        cells += Array(n) { k -> when { k == 0 -> leftLabel; k <= cols.size -> names[k - 1]; else -> textCols[k - 1 - cols.size].text } }
        tops += header.t; bottoms += header.b; marked += false
        for (sr in scanned) {
            val row = sr.row
            val line = Array(n) { "" }
            for (sg in row.segs) {
                val c = (sg.l + sg.r) / 2f
                val tk = textCols.indexOfFirst { c >= it.l - wordH && sg.l >= cols.last() + 0.6f * rowH }
                val k = if (tk >= 0) 1 + cols.size + tk else 0
                if (k == 0 && sg.l >= searchL && sg.text.trim() in checkboxLike) continue // simbolo di casella letto come testo
                line[k] = if (line[k].isEmpty()) sg.text else line[k] + " " + sg.text
            }
            var any = false
            cols.forEachIndexed { k, cx -> if (sr.boxes.any { abs(it - cx) < 0.7f * rowH }) { line[k + 1] = "☐"; any = true } }
            val cont = cells.size > 1 && continues(line[0])
            if (cont) {
                val prev = cells.last()
                prev[0] = TextJoin.joinLines(listOf(prev[0], line[0]))
                for (k in 1 until n) if (prev[k].isEmpty()) prev[k] = line[k]
                bottoms[bottoms.size - 1] = row.b
                if (any) marked[marked.size - 1] = true
            } else {
                if (line[0].isEmpty() && !any) continue
                cells += line; tops += row.t; bottoms += row.b; marked += any
            }
        }
        while (cells.size > 1 && !marked.last()) {
            cells.removeAt(cells.size - 1); tops.removeAt(tops.size - 1); bottoms.removeAt(bottoms.size - 1); marked.removeAt(marked.size - 1)
        }
        if (marked.count { it } < 2) return null
        // Caselle troppo chiare in una riga che ne ha altre: la colonna è quasi tutta di caselle → ☐
        for (k in 1..cols.size) {
            val body = (1 until cells.size).map { cells[it][k] }
            if (body.count { it == "☐" } >= body.size * 0.6f) for (ri in 1 until cells.size) if (cells[ri][k].isBlank() && marked[ri]) cells[ri][k] = "☐"
        }
        val lastB = bottoms.last()
        val next = (start + 1 until rows.size).firstOrNull { rows[it].t > lastB - 0.1f * rowH } ?: rows.size

        // Larghezze: voci fino alla prima colonna, caselle a metà strada, colonne di testo fino al bordo
        val tableL = minOf(entryL, header.l)
        val spacing = if (cols.size > 1) (cols.last() - cols.first()) / (cols.size - 1) else 3f * rowH
        val edges = ArrayList<Float>()
        edges += tableL
        edges += cols.first() - spacing / 2f
        for (k in 0 until cols.size - 1) edges += (cols[k] + cols[k + 1]) / 2f
        edges += cols.last() + spacing / 2f
        for (tc in textCols.indices) edges += if (tc == textCols.lastIndex) max(edges.last() + 2f * spacing, rows.subList(start, next).maxOf { it.r }) else (textCols[tc].r + textCols[tc + 1].l) / 2f
        val widths = (0 until n).map { (edges[it + 1] - edges[it]).coerceAtLeast(wordH) }
        val sum = widths.sum()
        val tableR = edges.last()
        val shading = (0 until cells.size).map { ri -> page.background(tableL, tops[ri], tableR, bottoms[ri]) }
        return Found(
            item = DocItem.Table(cells.map { it.toList() }, widths.map { it / sum }, true, shading),
            top = header.t,
            box = Box(tableL, header.t, tableR, lastB),
            nextRow = next,
        )
    }

    /**
     * Moduli con caselle (es. "Verifica di | Sì | No | N/A | Note"): il riconoscimento del testo di solito
     * ignora le caselle stampate, quindi le colonne sono esattamente quelle delle etichette dell'intestazione
     * e per ogni riga si guarda nell'immagine se sotto ciascuna etichetta c'è una casella.
     */
    private fun checkboxTable(
        rows: List<Row>, start: Int, wordH: Float, rowH: Float,
        cellMark: (Float, Float, Float, Float) -> Boolean, page: PageImage?,
    ): Found? {
        val header = rows[start]
        if (header.segs.isEmpty()) return null
        val after = rows.subList(start + 1, minOf(rows.size, start + 6))
        if (after.size < 2) return null
        val entryL = after.minOf { it.l } // margine sinistro delle voci
        // Le etichette delle caselle stanno a destra di dove finisce di solito il testo delle voci
        val entryEnds = rows.subList(start + 1, minOf(rows.size, start + 10)).map { it.segs.first().r }.sorted()
        val textEnd = entryEnds[(entryEnds.size * 0.7f).toInt().coerceAtMost(entryEnds.size - 1)]
        val candidates = header.segs.filter { sg ->
            sg.r - sg.l < 8f * wordH && sg.l > entryL + 6f * wordH && sg.l > textEnd - wordH &&
                sg.text.length <= 8 && sg.text.any { it.isLetter() }
        }
        // Un'etichetta "coperta" dal testo lungo di una voce non è una colonna di caselle (parola sbagliata)
        val nextRows = rows.subList(start + 1, minOf(rows.size, start + 10))
        val labels = candidates.filter { lb ->
            val c = (lb.l + lb.r) / 2f
            nextRows.none { r -> r.segs.any { sg -> sg.text.length > 8 && sg.l < c && sg.r > c } }
        }
        if (labels.size < 2) return null
        val firstL = labels.first().l
        val consumed = ArrayList<Row>()
        var leftText = header.segs.filter { it.r < firstL - wordH }.joinToString(" ") { it.text }.trim().trimEnd(':', ';').trim()
        if (leftText.isBlank()) {
            // Etichetta della prima colonna finita su una riga a parte, appena sopra o sotto
            val near = (maxOf(0, start - 2)..minOf(rows.size - 1, start + 2)).map { rows[it] }.filter { r ->
                r !== header && r.segs.size == 1 && r.r < firstL - wordH && abs(r.cy - header.cy) < 1.3f * wordH
            }.minByOrNull { abs(it.cy - header.cy) }
            if (near != null) { leftText = near.segs[0].text; consumed += near }
        }
        val centers = labels.map { (it.l + it.r) / 2f }
        val n = labels.size + 1
        fun half(k: Int): Float { // mezza larghezza della zona in cui cercare la casella sotto l'etichetta k
            val toPrev = if (k > 0) (centers[k] - centers[k - 1]) / 2f else Float.MAX_VALUE
            val toNext = if (k < centers.lastIndex) (centers[k + 1] - centers[k]) / 2f else Float.MAX_VALUE
            return minOf(max((labels[k].r - labels[k].l) / 2f, 0.8f * rowH) + 0.5f * wordH, toPrev * 0.9f, toNext * 0.9f)
        }

        val cells = ArrayList<Array<String>>()
        val tops = ArrayList<Float>(); val bottoms = ArrayList<Float>()
        val marked = ArrayList<Boolean>()
        cells += Array(n) { if (it == 0) leftText else labels[it - 1].text }; tops += header.t; bottoms += header.b; marked += false
        var j = start + 1
        var prevB = header.b
        var quiet = 0
        while (j < rows.size) {
            val row = rows[j]
            if (row in consumed) { j++; continue }
            if (row.t - prevB > 2f * rowH) break
            if (row.l < entryL - 3f * wordH || row.wh > 1.4f * rowH) break // testo fuori tabella o titolo
            val line = Array(n) { "" }
            for (sg in row.segs) {
                val c = (sg.l + sg.r) / 2f
                val k = if (sg.r < firstL - wordH) 0 else 1 + centers.indices.minBy { abs(centers[it] - c) }
                line[k] = if (line[k].isEmpty()) sg.text else line[k] + " " + sg.text
            }
            var any = false
            for (k in labels.indices) {
                val v = line[k + 1].trim()
                if (v in checkboxLike) { line[k + 1] = "☐"; any = true; continue }
                if (v.isNotEmpty()) continue
                val hw = half(k)
                if (cellMark(centers[k] - hw, row.cy - 0.7f * rowH, centers[k] + hw, row.cy + 0.7f * rowH)) { line[k + 1] = "☐"; any = true }
            }
            val prev = cells.last()
            // Riga che continua la voce sopra: la sua casella (centrata sulla cella alta) è la stessa
            val cont = cells.size > 1 && continues(line[0])
            if (cont) {
                prev[0] = TextJoin.joinLines(listOf(prev[0], line[0]))
                for (k in 1 until n) if (prev[k].isEmpty()) prev[k] = line[k]
                bottoms[bottoms.size - 1] = row.b
                if (any) marked[marked.size - 1] = true
            } else {
                if (line[0].isEmpty() && !any) break
                cells += line; tops += row.t; bottoms += row.b; marked += any
            }
            if (any) quiet = 0 else if (!cont) quiet++
            if (quiet >= 2) break
            prevB = row.b
            j++
        }
        // Via le righe finali senza caselle (non fanno parte del modulo)
        while (cells.size > 1 && !marked.last()) {
            cells.removeAt(cells.size - 1); tops.removeAt(tops.size - 1); bottoms.removeAt(bottoms.size - 1); marked.removeAt(marked.size - 1)
            j--
        }
        if (marked.count { it } < 2) return null
        // j deve puntare alla prima riga dopo l'ultima riga tenuta
        val lastB = bottoms.last()
        j = (start + 1 until rows.size).firstOrNull { rows[it].t > lastB - 0.1f * rowH && rows[it] !in consumed } ?: rows.size

        // Colonne quasi tutte di caselle: le poche vuote sono caselle troppo chiare da vedere
        for (k in 1 until n) {
            val body = (1 until cells.size).map { cells[it][k] }
            if (body.count { it == "☐" } >= body.size * 0.6f) for (ri in 1 until cells.size) if (cells[ri][k].isBlank()) cells[ri][k] = "☐"
        }
        // Colonne vuote che non sono l'ultima (es. parole sbagliate nell'intestazione): eliminate
        val keep = (0 until n).filter { k -> k == 0 || k == n - 1 || (1 until cells.size).any { cells[it][k].isNotBlank() } }
        val keptCells = cells.map { row -> keep.map { row[it] } }

        val tableL = minOf(entryL, header.l)
        val spacing = if (centers.size > 1) (centers.last() - centers.first()) / (centers.size - 1) else 4f * wordH
        val tableR = max(rows.subList(start, j).maxOf { it.r }, centers.last() + 1.5f * spacing)
        val edges = ArrayList<Float>()
        edges += tableL
        edges += firstL - 0.5f * wordH
        for (k in 0 until centers.size - 1) edges += (centers[k] + centers[k + 1]) / 2f
        edges += tableR
        val widths = (0 until n).map { (edges[it + 1] - edges[it]).coerceAtLeast(wordH) }
        val keptWidths = keep.map { widths[it] }
        val sum = keptWidths.sum()
        val shading = page?.let { pg -> (0 until cells.size).map { ri -> pg.background(tableL, tops[ri], tableR, bottoms[ri]) } }
        return Found(
            item = DocItem.Table(keptCells, keptWidths.map { it / sum }, true, shading),
            top = minOf(header.t, consumed.minOfOrNull { it.t } ?: header.t),
            box = Box(tableL, header.t, tableR, lastB),
            nextRow = j,
            consumed = consumed,
        )
    }

    /** Divide una riga dove lo spazio tra le parole è molto più largo di un normale spazio. */
    private fun segments(row: Row, wordH: Float): List<Seg> {
        val out = ArrayList<Seg>()
        var cur = StringBuilder(); var l = 0f; var r = 0f
        for (w in row.words) {
            if (cur.isEmpty()) { cur.append(w.text); l = w.l; r = w.r; continue }
            if (w.l - r > 1.4f * wordH) { out += Seg(cur.toString(), l, r); cur = StringBuilder(w.text); l = w.l }
            else cur.append(' ').append(w.text)
            r = max(r, w.r)
        }
        if (cur.isNotEmpty()) out += Seg(cur.toString(), l, r)
        return out
    }

    // ---------- tabelle ----------

    private class Found(val item: DocItem.Table, val top: Float, val box: Box, val nextRow: Int, val consumed: List<Row> = emptyList())

    /** Riga che continua la cella sopra (inizia in minuscolo o con una parentesi/virgoletta). */
    private fun continues(text: String): Boolean {
        // "(geometria, …" continua; "(UNI §5.3d] …" (parentesi letta male al posto di "[") no
        val t = fixCapitalI(text.trimStart('‘', '\'', '"', '“', '«', '(', ' '))
        val c = t.firstOrNull() ?: return false
        return c.isLowerCase()
    }

    private class Col(var sumC: Float, var n: Int, var minL: Float, var fromFirst: Boolean, val label: String? = null) {
        val c get() = sumC / n
    }

    private fun tryTable(rows: List<Row>, start: Int, wordH: Float, rowH: Float, cellMark: ((Float, Float, Float, Float) -> Boolean)?, page: PageImage?): Found? {
        if (rows[start].segs.size < 2) return null
        // 1) Gruppo di righe consecutive: righe a più colonne, più eventuali righe di continuazione
        var j = start
        var multi = 0
        while (j < rows.size) {
            val row = rows[j]
            if (j > start && row.t - rows[j - 1].b > 3f * rowH) break
            if (row.segs.size >= 2) multi++
            else {
                val nextMulti = (rows.getOrNull(j + 1)?.segs?.size ?: 0) >= 2
                if (!(continues(row.segs[0].text) || nextMulti)) break
            }
            j++
        }
        while (j - 1 > start && rows[j - 1].segs.size < 2 && !continues(rows[j - 1].segs[0].text)) j-- // niente righe singole in coda
        val firstGroup = rows.subList(start, j)
        val multiRows = firstGroup.filter { it.segs.size >= 2 }
        if (multi < 2) return null

        // 2) Colonne: raggruppa i segmenti di tutte le righe per posizione orizzontale
        // Le etichette dell'intestazione fanno da riferimento: ogni segmento va alla colonna più vicina
        // solo se è chiaramente più vicino a quella che alle colonne accanto (colonne strette come Sì/No/N/A)
        val seeds = multiRows.first().segs.map { Col((it.l + it.r) / 2f, 1, it.l, true, it.text) }
        val cols = ArrayList<Col>(seeds)
        val fixedC = seeds.map { it.c }
        for (row in multiRows.drop(1)) for (seg in row.segs) {
            val c = (seg.l + seg.r) / 2f
            val byDist = seeds.indices.sortedBy { abs(fixedC[it] - c) }
            val best = byDist.firstOrNull()
            val d1 = best?.let { abs(fixedC[it] - c) } ?: Float.MAX_VALUE
            val d2 = byDist.getOrNull(1)?.let { abs(fixedC[it] - c) } ?: Float.MAX_VALUE
            val leftAligned = best != null && abs(seeds[best].minL - seg.l) < 1.5f * wordH
            val target = if (best != null && (leftAligned || (d1 < 4f * wordH && d1 < 0.5f * d2))) seeds[best]
            else cols.filter { it !in seeds }.minByOrNull { abs(it.c - c) }?.takeIf { abs(it.c - c) < 1.2f * wordH || abs(it.minL - seg.l) < 1.2f * wordH }
            if (target != null) { target.n++; target.minL = min(target.minL, seg.l); if (target !in seeds) target.sumC += c }
            else cols += Col(c, 1, seg.l, false)
        }
        val minSupport = max(2f, 0.3f * multiRows.size)
        val sorted = cols.sortedBy { it.minL }
        // Valgono le colonne ricorrenti e le etichette brevi dell'intestazione (Sì, No, N/A, Note…)
        val kept = sorted.filterIndexed { idx, c ->
            c.n >= minSupport || (c.fromFirst && (idx == sorted.lastIndex || (c.label?.length ?: 99) <= 5))
        }
        val n = kept.size
        if (n < 2) return null
        val bordered = n >= 3
        if (!bordered && (multiRows.size < 2 || multiRows.any { it.segs.size != 2 })) return null
        if (bordered && multiRows.size < 3) return null

        val bounds = (1 until n).map { kept[it].minL - 0.5f * wordH } // inizio di ogni colonna dopo la prima
        fun colOf(seg: Seg): Int = (n - 1 downTo 1).firstOrNull { seg.l + wordH >= bounds[it - 1] } ?: 0
        fun aligned(seg: Seg): Boolean {
            val k = colOf(seg)
            val col = kept[k]
            val leftOk = abs(seg.l - col.minL) < 2f * wordH
            val centerOk = abs((seg.l + seg.r) / 2f - col.c) < 2f * wordH
            val fits = k == n - 1 || seg.r <= bounds[k] + 2f * wordH
            return (leftOk || centerOk || k == 0) && fits
        }
        // Le colonne devono essere allineate davvero: in un testo normale i "buchi" cadono a caso
        val segsAll = multiRows.flatMap { it.segs }
        if (segsAll.count { aligned(it) } < segsAll.size * 0.8f) return null

        // Righe della tabella: dall'intestazione in giù finché ogni pezzo cade in una colonna
        val tableLeft = multiRows.minOf { it.l }
        j = start
        while (j < rows.size) {
            val row = rows[j]
            if (j > start && row.t - rows[j - 1].b > 3f * rowH) break
            val ok = if (row.segs.size >= 2) row.segs.all { aligned(it) }
            else {
                val sg = row.segs[0]
                continues(sg.text) || (abs(sg.l - tableLeft) < 2f * wordH && (n == 1 || sg.r <= bounds[0] + 2f * wordH))
            }
            if (!ok) break
            j++
        }
        val group = rows.subList(start, j)

        // 3) Celle
        val cells = ArrayList<Array<String>>()
        val tops = ArrayList<Float>(); val bottoms = ArrayList<Float>()
        for (row in group) {
            val line = Array(n) { "" }
            for (seg in row.segs) { val k = colOf(seg); line[k] = if (line[k].isEmpty()) seg.text else line[k] + " " + seg.text }
            val onlyFirst = (1 until n).all { line[it].isEmpty() }
            val prev = cells.lastOrNull()
            val prevOnlyFirst = prev != null && (1 until n).all { prev[it].isEmpty() }
            // Riga che continua la cella sopra: se ha le caselle (centrate su una cella di più righe)
            // e la riga sopra non le ha, le caselle vanno alla riga sopra
            val merge = cells.size > 1 && continues(line[0]) && (onlyFirst || prevOnlyFirst)
            if (merge) {
                val last = cells.last()
                last[0] = TextJoin.joinLines(listOf(last[0], line[0]))
                for (k in 1 until n) if (last[k].isEmpty()) last[k] = line[k]
                bottoms[bottoms.size - 1] = row.b
            } else {
                cells += line; tops += row.t; bottoms += row.b
            }
        }

        if (cells.size < (if (bordered) 3 else 2)) return null

        // 4) Colonne di caselle: valori brevi o simboli → ☐; celle vuote con una casella stampata → ☐
        val tableL = group.minOf { it.l }
        var tableR = group.maxOf { it.r }
        if (n >= 3) {
            // Lascia all'ultima colonna (spesso "Note", vuota) almeno lo spazio di una colonna e mezza
            val typical = (1 until n - 1).map { kept[it + 1].minL - kept[it].minL }.average().toFloat()
            tableR = max(tableR, kept[n - 1].minL + 1.5f * typical)
        }
        val colEnd = (0 until n).map { k -> if (k == n - 1) tableR else bounds[k] }
        val colStart = (0 until n).map { k -> if (k == 0) tableL else bounds[k - 1] }
        for (k in 1 until n) {
            val body = (1 until cells.size).map { cells[it][k].trim() }
            val filled = body.filter { it.isNotEmpty() }
            val shortish = filled.count { it in checkboxLike || (it.length <= 3 && it.none { ch -> ch.isDigit() }) }
            val boxColumn = bordered && filled.isNotEmpty() && shortish >= filled.size * 0.6f
            for (ri in 1 until cells.size) {
                val v = cells[ri][k].trim()
                if (v in checkboxLike || (boxColumn && v.isNotEmpty() && v.length <= 3)) cells[ri][k] = "☐"
                else if (v.isEmpty() && bordered && cellMark != null) {
                    val l = max(colStart[k], kept[k].minL - wordH)
                    val r = min(colEnd[k], l + 3f * rowH)
                    if (cellMark(l, tops[ri], r, bottoms[ri])) cells[ri][k] = "☐"
                }
            }
        }

        // Colonne quasi tutte di caselle (moduli): le poche celle vuote sono caselle troppo chiare da vedere
        if (bordered && cells.size > 3) for (k in 1 until n) {
            val body = (1 until cells.size).map { cells[it][k] }
            if (body.count { it == "☐" } >= body.size * 0.75f) for (ri in 1 until cells.size) if (cells[ri][k].isBlank()) cells[ri][k] = "☐"
        }

        // 5) Larghezza delle colonne in proporzione
        val total = (tableR - tableL).coerceAtLeast(1f)
        val fractions = (0 until n).map { k -> ((colEnd[k] - colStart[k]) / total).coerceAtLeast(0.05f) }
        val sum = fractions.sum()
        val shading = if (page == null) null else (0 until cells.size).map { ri -> page.background(tableL, tops[ri], tableR, bottoms[ri]) }
        return Found(
            item = DocItem.Table(cells.map { it.toList() }, fractions.map { it / sum }, bordered, shading),
            top = group.first().t,
            box = Box(tableL, group.first().t, tableR, group.last().b),
            nextRow = j,
        )
    }

    // ---------- titoli e paragrafi ----------

    private fun flushParagraphs(rows: List<Row>, rowH: Float, wordH: Float, page: PageImage?, out: MutableList<Pair<Float, DocItem>>) {
        var para = ArrayList<Row>()
        fun segText(row: Row, k: Int): String {
            val seg = row.segs[k]
            if (page == null) return seg.text
            // Linea da compilare dopo l'etichetta (es. "Sito di: ________")
            val end = row.segs.getOrNull(k + 1)?.l?.minus(wordH) ?: (page.width * 0.97f)
            if (end - seg.r < 4f * wordH) return seg.text
            val start = seg.r + 0.3f * wordH
            val len = page.underline(start, row.t + 0.4f * row.h, end, row.b + 0.6f * row.h)
            if (len < 4f * wordH || len < 0.45f * (end - start)) return seg.text
            return seg.text + " " + "_".repeat((len / (0.55f * wordH)).toInt().coerceIn(6, 45))
        }
        fun emit() {
            if (para.isEmpty()) return
            // Filetto separatore sotto una riga sola (es. "Sezione 1 — Misure Generali" con la linea sotto)
            var rule = false
            if (page != null && para.size == 1 && para[0].segs.size == 1) {
                val row = para[0]; val sg = row.segs[0]
                // Guarda solo dopo la fine del testo: lì c'è la linea, non le lettere
                val from = sg.r + wordH; val to = page.width * 0.97f
                if (to - from > page.width * 0.3f) {
                    val len = page.underline(from, row.t + 0.55f * row.h, to, row.b + 0.9f * row.h)
                    if (len > 0.7f * (to - from)) rule = true
                }
            }
            val ratio = median(para.map { it.wh }) / rowH
            val heading = ratio > 1.3f
            val l = para.minOf { it.l }; val r = para.maxOf { it.r }
            // Riga staccata in pezzi ma centrata nel suo insieme (sottotitolo): un unico paragrafo centrato
            val midRow = page != null && para.size == 1 && para[0].segs.size > 1 &&
                abs((l + r) / 2f - page.width / 2f) < page.width * 0.05f && l > page.width * 0.12f
            // Riga corta con il filetto sotto = titolo di sezione
            val section = rule && para[0].segs[0].text.split(' ').size <= 8
            val text = if (midRow) para[0].segs.joinToString(" ") { it.text }
            else if (para.size == 1 && para[0].segs.size > 1) para[0].segs.indices.joinToString("\t") { segText(para[0], it) }
            else if (para.size == 1) (if (rule) para[0].segs[0].text else segText(para[0], 0))
            else TextJoin.joinLines(para.map { r -> r.segs.joinToString(" ") { it.text } })
            // Centrato: in mezzo alla pagina e non a tutta larghezza
            val centered = midRow || page != null && para.all { it.segs.size == 1 } &&
                abs((l + r) / 2f - page.width / 2f) < page.width * 0.05f && r - l < page.width * 0.75f && l > page.width * 0.12f
            val shade = page?.background(l, para.first().t, r, para.last().b)
            out += para.first().t to DocItem.Text(
                text,
                bold = heading || section,
                sizePt = if (heading) (11f * ratio).coerceIn(12f, 22f) else if (section) 13f else null,
                centered = centered,
                shading = shade,
                ruleBelow = rule,
            )
            para = ArrayList()
        }
        for (row in rows) {
            val prev = para.lastOrNull()
            val join = prev != null &&
                row.cyAt(row.l) - prev.cyAt(row.l) < 1.6f * rowH && // righe vicine (misurate a sinistra, anche se storte)
                prev.r > row.r - 0.3f * (row.r - row.l) && // una riga corta chiude il paragrafo (o è un titolo)
                row.t - prev.b < 0.9f * rowH &&
                abs(row.wh - prev.wh) < 0.25f * max(row.wh, prev.wh) && // stessa dimensione del carattere
                row.segs.size == 1 && prev.segs.size == 1 && // nessuna colonna
                row.wh / rowH <= 1.3f
            if (!join) emit()
            para += row
        }
        emit()
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 1f
        val s = v.sorted()
        return s[s.size / 2].coerceAtLeast(1f)
    }
}
