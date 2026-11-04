package it.spagnuolo.docverso.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Traduce il contenuto di un documento mantenendone la struttura: paragrafi, tabelle, stili.
 * [raw] traduce un testo (il motore vero sta fuori: nel browser è Bergamot).
 * Codici, numeri, linee da compilare (____) e caselle (☐) non passano dal traduttore.
 */
class DocTranslator(private val raw: (String) -> String) {

    private val cache = HashMap<String, String>()

    /** Parti da non tradurre: linee da compilare, puntini, tabulazioni, caselle, trattini lunghi. */
    private val keep = Regex("_{3,}|\\.{4,}|\\t|[☐☑☒□■✓✔✗]|-{3,}")
    private val wordy = Regex("\\p{L}{2,}")

    fun text(s: String): String {
        if (s.isBlank()) return s
        if (!keep.containsMatchIn(s)) return chunk(s)
        val out = StringBuilder()
        var last = 0
        for (m in keep.findAll(s)) {
            out.append(chunk(s.substring(last, m.range.first)))
            out.append(m.value)
            last = m.range.last + 1
        }
        out.append(chunk(s.substring(last)))
        return out.toString()
    }

    /** Un pezzo senza parti protette: spazi ai lati conservati, maiuscolo trattato a parte. */
    private fun chunk(s: String): String {
        if (s.isBlank() || !translatable(s)) return s
        val lead = s.takeWhile { it.isWhitespace() }
        val trail = s.takeLastWhile { it.isWhitespace() }
        val core = s.trim()
        val t = cache.getOrPut(core) {
            val letters = core.filter { it.isLetter() }
            // TUTTO MAIUSCOLO: il traduttore lavora meglio sul testo normale, poi si rimette maiuscolo
            val caps = letters.length > 3 && letters.count { it.isUpperCase() } >= letters.length * 0.8f
            val src = if (caps) core.lowercase().replaceFirstChar { it.uppercase() } else core
            val res = runCatching { raw(src) }.getOrNull()?.takeIf { it.isNotBlank() } ?: core
            if (caps) res.uppercase() else res
        }
        return lead + t + trail
    }

    /** C'è almeno una parola vera? (codici come "SPGMSM70S08A509E" o "90.44.6" restano come sono) */
    private fun translatable(s: String): Boolean =
        s.split(Regex("\\s+")).any { w -> val c = w.trim { !it.isLetterOrDigit() }; c.length >= 2 && c.none { it.isDigit() } && wordy.matches(c) }

    // ------------------------------------------------------------------ contenuti delle pagine

    fun items(list: List<DocItem>): List<DocItem> = list.map { item(it) }

    fun item(i: DocItem): DocItem = when (i) {
        is DocItem.Text -> i.copy(text = i.text.split('\n').joinToString("\n") { text(it) })
        is DocItem.Table -> i.copy(rows = i.rows.map { r -> r.map { c -> c.split('\n').joinToString("\n") { text(it) } } })
        is DocItem.Image -> i
        is DocItem.ExactPage -> exact(i)
    }

    /**
     * Pagina esatta tradotta. Il margine inferiore si riduce quasi a zero: le posizioni restano quelle
     * dell'originale (sono misurate dall'alto), ma se il testo tradotto prende una riga in più la pagina
     * ha spazio di riserva e la riga a piè di pagina non scivola sulla pagina dopo.
     */
    private fun exact(p: DocItem.ExactPage): DocItem.ExactPage {
        // Larghezza utile di ogni blocco: la colonna in cui sta (le fasce finiscono con SectionMark)
        val full = p.widthPt - p.marginL - p.marginR
        val widths = FloatArray(p.blocks.size) { full }
        var start = 0
        p.blocks.forEachIndexed { i, b ->
            if (b is DocItem.SectionMark) {
                var col = 0
                for (k in start until i) {
                    if (p.blocks[k] is DocItem.ColumnBreak) col++
                    widths[k] = b.cols.getOrNull(col) ?: full
                }
                start = i + 1
            }
        }
        // Testo che scorre (paragrafi di più righe): niente riduzione paragrafo per paragrafo, ma un'unica
        // riduzione per colonna (carattere e interlinea), così il testo tradotto occupa lo stesso spazio
        val out = p.blocks.mapIndexed { i, b ->
            when (b) {
                is DocItem.Para -> if (flowing(b, widths[i])) para(b) else wrapFit(fit(para(b), b, widths[i]), b, widths[i])
                is DocItem.Grid -> grid(b)
                else -> b
            }
        }.toMutableList()
        var from = 0
        for (i in 0..out.size) {
            val end = i == out.size || out[i] is DocItem.ColumnBreak || out[i] is DocItem.SectionMark
            if (!end) continue
            val idx = (from until i).filter { k -> (p.blocks[k] as? DocItem.Para)?.let { flowing(it, widths[k]) } == true }
            val grown = idx.sumOf { width((out[it] as DocItem.Para).pieces[0].runs).toDouble() }
            val orig = idx.sumOf { width((p.blocks[it] as DocItem.Para).pieces[0].runs).toDouble() }
            if (orig > 0 && grown > orig) {
                val f = (1.0 / kotlin.math.sqrt(grown / orig)).toFloat().coerceIn(0.85f, 1f)
                for (k in idx) {
                    val q = out[k] as DocItem.Para
                    out[k] = q.copy(
                        linePt = q.linePt * f, beforePt = q.beforePt,
                        pieces = q.pieces.map { pc -> pc.copy(runs = pc.runs.map { r -> r.copy(sizePt = ((r.sizePt * f * 2f).roundToInt() / 2f).coerceAtLeast(4f)) }) },
                    )
                }
            }
            from = i + 1
        }
        return DocItem.ExactPage(p.widthPt, p.heightPt, p.marginL, p.marginT, p.marginR, min(p.marginB, 2f), out, p.anchors)
    }

    /** Paragrafo di testo che va a capo (più righe nell'originale), non un'etichetta o un pezzo di riga. */
    private fun flowing(b: DocItem.Para, usable: Float): Boolean =
        b.pieces.size == 1 && b.frame == null && b.align == DocItem.Align.LEFT && width(b.pieces[0].runs) > (usable - b.pieces[0].x - b.rightPt) * 1.15f

    private fun para(p: DocItem.Para): DocItem.Para =
        // Il testo tradotto può essere più lungo: le righe in posizione fissa diventano libere di andare a capo
        p.copy(pieces = p.pieces.map { piece(it) }, frame = p.frame?.let { f -> floatArrayOf(f[0], f[1], max(f[2] * 1.4f, f[2] + 40f), f[3]) })

    /** Larghezza stimata di un testo (Arial e simili: circa mezzo corpo per carattere). */
    private fun width(rs: List<DocItem.Run>) = rs.sumOf { r ->
        r.text.sumOf { c ->
            when {
                c == ' ' -> 0.28
                c.isUpperCase() -> if (c in "IJ") 0.3 else if (c in "MW") 0.85 else 0.68
                c.isDigit() -> 0.56
                c in "iljtf.,:;'!|()" -> 0.28
                c in "mw" -> 0.8
                else -> 0.52
            }
        } * r.sizePt * (if (r.bold) 1.06 else 1.0)
    }.toFloat()

    /**
     * Righe con più pezzi affiancati (es. "INDIRIZZO: …   CAP: …   PROV: …"): se un pezzo tradotto non
     * sta più nel suo spazio, il carattere si riduce un poco (fino al 70%) per non spostare il resto.
     */
    private fun fit(t: DocItem.Para, orig: DocItem.Para, usable: Float): DocItem.Para {
        if (t.pieces.size < 2 || t.frame != null) return t
        val ps = t.pieces
        val out = ps.mapIndexed { k, pc ->
            val next = ps.getOrNull(k + 1)
            val avail = when {
                pc.align == DocItem.Align.RIGHT -> pc.x - (ps.getOrNull(k - 1)?.let { it.x + width(it.runs) } ?: 0f)
                next == null -> usable - pc.x
                next.align == DocItem.Align.RIGHT -> next.x - width(next.runs) - pc.x
                else -> next.x - pc.x
            } - 4f
            val need = width(pc.runs)
            val had = width(orig.pieces[k].runs)
            // Si riduce solo ciò che è cresciuto con la traduzione e non ci sta più
            if (need <= avail || need <= had || avail <= 0f) pc
            else {
                val f = (avail / need).coerceIn(0.7f, 1f)
                pc.copy(runs = pc.runs.map { r -> r.copy(sizePt = ((r.sizePt * f * 2f).roundToInt() / 2f).coerceAtLeast(4f)) })
            }
        }
        return t.copy(pieces = out)
    }

    /**
     * Paragrafo di un solo pezzo (anche su più righe): se il testo tradotto avrebbe bisogno di più righe
     * dell'originale, il carattere si riduce un poco (fino all'80%) perché resti nelle stesse righe e
     * non sposti in basso il resto della pagina. [lines] = righe dell'originale, se note.
     */
    private fun wrapFit(t: DocItem.Para, orig: DocItem.Para, usable: Float, lines: Int? = null): DocItem.Para {
        if (t.pieces.size != 1 || orig.pieces.size != 1 || t.frame != null) return t
        val pc = t.pieces[0]
        val avail = usable - (if (t.align == DocItem.Align.LEFT) pc.x else 0f) - t.rightPt
        if (avail <= 10f) return t
        val w0 = width(orig.pieces[0].runs); val w1 = width(pc.runs)
        if (w1 <= w0) return t
        val n0 = lines ?: kotlin.math.ceil(w0 * 0.98f / avail).toInt().coerceAtLeast(1)
        val n1 = kotlin.math.ceil(w1 / avail).toInt()
        if (n1 <= n0) return t
        val f = (n0 * avail * 0.96f / w1).coerceIn(0.8f, 1f)
        return t.copy(pieces = listOf(pc.copy(runs = pc.runs.map { r -> r.copy(sizePt = ((r.sizePt * f * 2f).roundToInt() / 2f).coerceAtLeast(4f)) })))
    }

    private fun piece(pc: DocItem.Piece): DocItem.Piece = pc.copy(runs = runs(pc.runs))

    /** Pezzi con stili diversi: se uno prevale si traduce la frase intera con quello stile, altrimenti pezzo per pezzo. */
    private fun runs(rs: List<DocItem.Run>): List<DocItem.Run> {
        if (rs.isEmpty()) return rs
        if (rs.size == 1) return listOf(rs[0].copy(text = text(rs[0].text)))
        val total = rs.sumOf { it.text.length }
        val main = rs.maxBy { it.text.length }
        return if (main.text.length >= total * 0.6f) listOf(main.copy(text = text(rs.joinToString("") { it.text })))
        else rs.map { it.copy(text = text(it.text)) }
    }

    private fun grid(g: DocItem.Grid): DocItem.Grid = g.copy(
        growRows = true, // le righe si allungano se il testo tradotto va a capo
        rows = g.rows.map { r ->
            var col = 0
            r.copy(cells = r.cells.map { c ->
                val w = g.cols.subList(col.coerceAtMost(g.cols.size), (col + c.span).coerceAtMost(g.cols.size)).sum()
                col += c.span
                val ps = cellParas(c.paras)
                // Una frase sola nata da più righe: deve restare in quel numero di righe
                val fitted = if (ps.size == 1 && c.paras.size > 1) listOf(wrapFit(ps[0], joined(c.paras), w - 1f, c.paras.size))
                else ps.mapIndexed { k, p -> c.paras.getOrNull(k)?.let { o -> wrapFit(p, o, w - 1f) } ?: p }
                c.copy(paras = fitted)
            })
        },
    )

    /** Le righe originali di una cella come un solo pezzo (per misurarne la larghezza totale). */
    private fun joined(ps: List<DocItem.Para>): DocItem.Para =
        ps[0].copy(pieces = listOf(DocItem.Piece(ps[0].pieces.firstOrNull()?.x ?: 0f, ps.flatMap { p -> p.pieces.flatMap { it.runs } })))

    /** Le righe di una cella formano di solito una frase sola: si uniscono prima di tradurle. */
    private fun cellParas(ps: List<DocItem.Para>): List<DocItem.Para> {
        if (ps.size < 2 || ps.any { it.pieces.size != 1 } || ps.map { it.align }.distinct().size > 1) return ps.map { para(it) }
        val runs = ArrayList<DocItem.Run>()
        ps.forEachIndexed { k, p ->
            val rs = p.pieces[0].runs
            if (k > 0 && runs.isNotEmpty()) {
                val last = runs.removeAt(runs.size - 1)
                runs += if (last.text.endsWith("-") && last.text.length > 1) last.copy(text = last.text.dropLast(1)) else last.copy(text = last.text.trimEnd() + " ")
            }
            runs += rs
        }
        val first = ps[0]
        return listOf(first.copy(pieces = listOf(first.pieces[0].copy(runs = runs(merge(runs))))))
    }

    private fun merge(rs: List<DocItem.Run>): List<DocItem.Run> {
        val out = ArrayList<DocItem.Run>()
        for (r in rs) {
            val l = out.lastOrNull()
            if (l != null && l.copy(text = "") == r.copy(text = "")) out[out.size - 1] = l.copy(text = l.text + r.text) else out += r
        }
        return out
    }
}
