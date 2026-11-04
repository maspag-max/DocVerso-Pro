package it.spagnuolo.docverso.engine

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Generatore minimo di documenti Word (.docx): paragrafi di testo e immagini, una pagina Word
 * per ogni pagina del documento (interruzione di pagina tra l'una e l'altra), formato pagina a scelta.
 * Si apre in Word, Google Documenti, LibreOffice e WPS.
 */
class MiniDocxWriter(private val pageWidthMm: Float = 210f, private val pageHeightMm: Float = 297f) {

    private val body = StringBuilder()
    private val images = ArrayList<Pair<ByteArray, String>>()
    private var pages = 0

    private val margin = 1134 // 2 cm in twip
    private val pageW = (pageWidthMm / 25.4f * 1440f).toInt()
    private val pageH = (pageHeightMm / 25.4f * 1440f).toInt()

    /** Formato e margini di una pagina (twip). Ogni pagina è una sezione: le pagine esatte hanno i loro. */
    private data class Sect(
        val w: Int, val h: Int, val top: Int, val right: Int, val bottom: Int, val left: Int,
        /** Inizio della sezione: "nextPage" (pagina nuova) o "continuous" (fascia sulla stessa pagina). */
        val type: String = "nextPage",
        /** Colonne (larghezze e spazi in twip); vuota = una colonna. */
        val cols: List<Int> = emptyList(), val gaps: List<Int> = emptyList(),
    ) {
        fun xml(): String {
            val orient = if (w > h) " w:orient=\"landscape\"" else ""
            val c = if (cols.size < 2) "" else buildString {
                append("<w:cols w:num=\"${cols.size}\" w:space=\"${gaps.firstOrNull() ?: 0}\" w:equalWidth=\"0\">")
                cols.forEachIndexed { i, cw -> append("<w:col w:w=\"$cw\"").append(if (i < cols.size - 1) " w:space=\"${gaps.getOrElse(i) { 0 }}\"" else "").append("/>") }
                append("</w:cols>")
            }
            return "<w:sectPr><w:type w:val=\"$type\"/><w:pgSz w:w=\"$w\" w:h=\"$h\"$orient/><w:pgMar w:top=\"$top\" w:right=\"$right\" w:bottom=\"$bottom\" w:left=\"$left\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>$c</w:sectPr>"
        }
    }
    private val defaultSect get() = Sect(pageW, pageH, margin, margin, margin, margin)
    private var sect: Sect? = null

    private fun tw(pt: Float) = (pt * 20f).roundToInt()

    /** Aggiunge una pagina di soli paragrafi (le righe al loro interno vanno a capo). */
    fun addPage(paragraphs: List<String>) = addItems(paragraphs.map { DocItem.Text(it) })

    /** Aggiunge una pagina con testo e immagini nell'ordine dato. */
    fun addItems(items: List<DocItem>) {
        // Chiude la pagina precedente (sezione con il suo formato): la nuova parte su una pagina nuova
        sect?.let { body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>").append(it.xml()).append("</w:pPr></w:p>") }
        pages++
        (items.singleOrNull() as? DocItem.ExactPage)?.let { exactPage(it); return }
        sect = defaultSect
        if (items.isEmpty()) {
            body.append("<w:p/>")
            return
        }
        for (item in items) when (item) {
            is DocItem.Text -> paragraph(item.text, item.bold, item.sizePt, item.centered, item.shading, item.ruleBelow)
            is DocItem.Table -> table(item)
            is DocItem.Image -> image(item)
            is DocItem.ExactPage -> {}
        }
    }

    private fun runProps(bold: Boolean, sizePt: Float?): String {
        if (!bold && sizePt == null) return ""
        val sz = sizePt?.let { val hp = (it * 2).toInt(); "<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>" } ?: ""
        return "<w:rPr>" + (if (bold) "<w:b/><w:bCs/>" else "") + sz + "</w:rPr>"
    }

    /** Testo di un paragrafo: '\n' = a capo, '\t' = tabulazione. */
    private fun runs(text: String, bold: Boolean, sizePt: Float?): String = buildString {
        val rPr = runProps(bold, sizePt)
        text.split('\n').forEachIndexed { i, line ->
            if (i > 0) append("<w:r><w:br/></w:r>")
            line.split('\t').forEachIndexed { j, part ->
                if (j > 0) append("<w:r><w:tab/></w:r>")
                if (part.isNotEmpty()) append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">").append(escape(part)).append("</w:t></w:r>")
            }
        }
    }

    private fun hex(c: Int) = (c and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private fun paragraph(p: String, bold: Boolean = false, sizePt: Float? = null, centered: Boolean = false, shading: Int? = null, ruleBelow: Boolean = false) {
        val pPr = StringBuilder()
        if (ruleBelow) pPr.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"1\" w:color=\"1F4E79\"/></w:pBdr>")
        // Le tabulazioni puntano a metà pagina, così le righe a due colonne restano allineate
        if ('\t' in p) pPr.append("<w:tabs><w:tab w:val=\"left\" w:pos=\"${(pageW - 2 * margin) / 2}\"/></w:tabs>")
        if (shading != null) pPr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(shading)}\"/>")
        if (centered) pPr.append("<w:jc w:val=\"center\"/>")
        body.append("<w:p>")
        if (pPr.isNotEmpty()) body.append("<w:pPr>").append(pPr).append("</w:pPr>")
        body.append(runs(p, bold, sizePt)).append("</w:p>")
    }

    private val attachments = ArrayList<Pair<String, ByteArray>>()

    /** File allegato invisibile nel pacchetto Word (es. dati di analisi), sotto "docverso/". */
    fun attach(name: String, bytes: ByteArray) { attachments += "docverso/$name" to bytes }

    private fun table(t: DocItem.Table) {
        if (t.rows.isEmpty()) return
        val textW = pageW - 2 * margin
        val n = t.colFractions.size
        val widths = t.colFractions.map { (it * textW).toInt().coerceAtLeast(300) }
        val border = if (t.bordered) "single" else "nil"
        val b = if (t.bordered) " w:sz=\"4\" w:space=\"0\" w:color=\"808080\"" else ""
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${widths.sum()}\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$side w:val=\"$border\"$b/>")
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        widths.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
        body.append("</w:tblGrid>")
        t.rows.forEachIndexed { ri, row ->
            val header = t.bordered && ri == 0
            body.append("<w:tr>")
            if (header) body.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (k in 0 until n) {
                val text = row.getOrElse(k) { "" }
                val rowColor = t.rowShading?.getOrNull(ri)
                val shade = when {
                    rowColor != null -> "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(rowColor)}\"/>"
                    header -> "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7EBF0\"/>"
                    else -> ""
                }
                val center = if (k > 0 && text.trim() == "☐") "<w:jc w:val=\"center\"/>" else ""
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"${widths[k]}\" w:type=\"dxa\"/>$shade</w:tcPr>")
                body.append("<w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\"/>$center</w:pPr>").append(runs(text, header, null)).append("</w:p></w:tc>")
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>")
    }

    private fun image(img: DocItem.Image) {
        if (img.width <= 0 || img.height <= 0 || img.jpeg.isEmpty()) return
        images += img.jpeg to "jpg"
        val n = images.size
        val rId = "rIdImg$n"
        // Dimensioni in EMU (1 twip = 635 EMU), in proporzione alla pagina originale
        val textW = (pageW - 2 * margin) * 635L
        val textH = (pageH - 2 * margin) * 635L
        var cx = (textW * img.widthFraction.coerceIn(0.15f, 1f)).toLong()
        var cy = cx * img.height / img.width
        if (cy > textH * 9 / 10) { cy = textH * 9 / 10; cx = cy * img.width / img.height }
        body.append(
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
                "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:docPr id=\"$n\" name=\"Immagine $n\"/>" + graphic(n, "jpg", cx, cy) + "</wp:inline></w:drawing></w:r></w:p>"
        )
    }

    private fun graphic(n: Int, ext: String, cx: Long, cy: Long) =
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.$ext\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                "</a:graphicData></a:graphic>"

    // ------------------------------------------------------------ pagine esatte (PDF digitali)

    private fun exactPage(p: DocItem.ExactPage) {
        val s = Sect(tw(p.widthPt), tw(p.heightPt), tw(p.marginT), tw(p.marginR), tw(p.marginB), tw(p.marginL))
        sect = s
        val anchors = StringBuilder()
        for (a in p.anchors) anchors.append(anchor(a))
        // Fasce della pagina (una o due colonne): ogni fascia è una sezione; la prima comincia la pagina,
        // le altre continuano sulla stessa pagina
        var first = true
        val lastMark = p.blocks.indexOfLast { it is DocItem.SectionMark }
        p.blocks.forEachIndexed { i, b ->
            when (b) {
                is DocItem.Para -> exactPara(b, if (i == 0) anchors.toString() else "")
                is DocItem.Grid -> grid(b)
                is DocItem.InlinePic -> inlinePic(b, if (i == 0) anchors.toString() else "")
                is DocItem.ColumnBreak -> body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr><w:r><w:rPr><w:sz w:val=\"2\"/></w:rPr><w:br w:type=\"column\"/></w:r></w:p>")
                is DocItem.SectionMark -> {
                    // La sezione si descrive alla sua fine: colonne della fascia appena finita
                    val z = s.copy(type = if (first) "nextPage" else "continuous", cols = b.cols.map { tw(it) }, gaps = b.gaps.map { tw(it) })
                    first = false
                    // L'ultima fascia si chiude con la pagina (la sua sezione la scrive la pagina successiva o la fine)
                    if (i == lastMark) sect = z
                    else body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>").append(z.xml()).append("<w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr></w:p>")
                }
            }
        }
    }

    private fun rPr(r: DocItem.Run): String = buildString {
        append("<w:rPr>")
        r.font?.let { val f = escape(it); append("<w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:cs=\"$f\" w:eastAsia=\"$f\"/>") }
        if (r.bold) append("<w:b/><w:bCs/>")
        if (r.italic) append("<w:i/><w:iCs/>")
        if (r.underline) append("<w:u w:val=\"single\"/>")
        if (r.color and 0xFFFFFF != 0) append("<w:color w:val=\"${hex(r.color)}\"/>")
        val hp = (r.sizePt * 2f).roundToInt().coerceAtLeast(2)
        append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/></w:rPr>")
    }

    private fun exactPara(p: DocItem.Para, extra: String = "") {
        val pPr = StringBuilder()
        p.frame?.let { f ->
            pPr.append("<w:framePr w:w=\"${tw(f[2])}\" w:h=\"${tw(f[3])}\" w:hRule=\"exact\" w:wrap=\"none\" w:hAnchor=\"page\" w:vAnchor=\"page\" w:x=\"${tw(f[0])}\" w:y=\"${tw(f[1])}\"/>")
        }
        val first = p.pieces.firstOrNull()
        val stops = p.pieces.drop(1)
        if (stops.isNotEmpty()) {
            pPr.append("<w:tabs>")
            for (k in stops) {
                val v = when (k.align) { DocItem.Align.RIGHT -> "right"; DocItem.Align.CENTER -> "center"; else -> "left" }
                pPr.append("<w:tab w:val=\"$v\" w:pos=\"${tw(k.x)}\"/>")
            }
            pPr.append("</w:tabs>")
        }
        pPr.append("<w:spacing w:before=\"${tw(p.beforePt)}\" w:after=\"0\" w:line=\"${tw(p.linePt).coerceAtLeast(12)}\" w:lineRule=\"exact\"/>")
        val left = if (first != null && p.align == DocItem.Align.LEFT && p.frame == null) tw(first.x) else 0
        val fl = tw(p.firstLinePt)
        val firstAttr = if (fl >= 0) "w:firstLine=\"$fl\"" else "w:hanging=\"${-fl}\""
        pPr.append("<w:ind w:left=\"$left\" w:right=\"${tw(p.rightPt)}\" $firstAttr/>")
        when {
            p.justify -> pPr.append("<w:jc w:val=\"both\"/>")
            p.align == DocItem.Align.CENTER -> pPr.append("<w:jc w:val=\"center\"/>")
            p.align == DocItem.Align.RIGHT -> pPr.append("<w:jc w:val=\"right\"/>")
            else -> pPr.append("<w:jc w:val=\"left\"/>")
        }
        // Il segno di paragrafo non deve allargare la riga
        val markSize = first?.runs?.firstOrNull()?.sizePt ?: 1f
        pPr.append("<w:rPr><w:sz w:val=\"${(markSize * 2).roundToInt().coerceAtLeast(2)}\"/></w:rPr>")
        body.append("<w:p><w:pPr>").append(pPr).append("</w:pPr>").append(extra)
        p.pieces.forEachIndexed { k, piece ->
            if (k > 0) body.append("<w:r>").append(rPr(piece.runs.firstOrNull() ?: DocItem.Run("", null, markSize))).append("<w:tab/></w:r>")
            for (r in piece.runs) body.append("<w:r>").append(rPr(r)).append("<w:t xml:space=\"preserve\">").append(escape(r.text)).append("</w:t></w:r>")
        }
        body.append("</w:p>")
    }

    private fun border(side: String, pt: Float, color: Int): String =
        if (pt <= 0f) "<w:$side w:val=\"nil\"/>"
        else "<w:$side w:val=\"single\" w:sz=\"${(pt * 8f).roundToInt().coerceIn(2, 96)}\" w:space=\"0\" w:color=\"${hex(color)}\"/>"

    private fun grid(g: DocItem.Grid) {
        val widths = g.cols.map { tw(it).coerceAtLeast(20) }
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${widths.sum()}\" w:type=\"dxa\"/><w:tblInd w:w=\"${tw(g.indentPt)}\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$side w:val=\"nil\"/>")
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar>")
        for (side in listOf("top", "left", "bottom", "right")) body.append("<w:$side w:w=\"0\" w:type=\"dxa\"/>")
        body.append("</w:tblCellMar><w:tblLook w:val=\"0000\"/></w:tblPr><w:tblGrid>")
        widths.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
        body.append("</w:tblGrid>")
        for (row in g.rows) {
            body.append("<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"${tw(row.heightPt)}\" w:hRule=\"${if (g.growRows) "atLeast" else "exact"}\"/></w:trPr>")
            var col = 0
            for (c in row.cells) {
                val w = (col until (col + c.span).coerceAtMost(widths.size)).sumOf { widths[it] }
                col += c.span
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>")
                if (c.span > 1) body.append("<w:gridSpan w:val=\"${c.span}\"/>")
                when (c.vMerge) { 1 -> body.append("<w:vMerge w:val=\"restart\"/>"); 2 -> body.append("<w:vMerge/>") }
                body.append("<w:tcBorders>").append(border("top", c.top, c.borderColor)).append(border("left", c.left, c.borderColor))
                    .append(border("bottom", c.bottom, c.borderColor)).append(border("right", c.right, c.borderColor)).append("</w:tcBorders>")
                c.shading?.let { body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                body.append("<w:vAlign w:val=\"top\"/></w:tcPr>")
                if (c.paras.isEmpty()) body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr></w:p>")
                else c.paras.forEach { exactPara(it) }
                body.append("</w:tc>")
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
    }

    /** Immagine nel testo, in un paragrafo alto esattamente quanto lei. */
    private fun inlinePic(p: DocItem.InlinePic, extra: String) {
        val ext = if (p.png) "png" else "jpg"
        images += p.bytes to ext
        val n = images.size
        val id = ++anchorId
        val emu = { pt: Float -> (pt * 12700.0).roundToLong().coerceAtLeast(1L) }
        body.append("<w:p><w:pPr><w:spacing w:before=\"${tw(p.beforePt)}\" w:after=\"0\" w:line=\"${tw(p.h + 1f)}\" w:lineRule=\"exact\"/>")
            .append("<w:ind w:left=\"${tw(p.x)}\" w:right=\"0\" w:firstLine=\"0\"/><w:jc w:val=\"left\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr>").append(extra)
            .append("<w:r><w:rPr><w:sz w:val=\"2\"/></w:rPr><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"${emu(p.w)}\" cy=\"${emu(p.h)}\"/>")
            .append("<wp:docPr id=\"$id\" name=\"Figura $id\"/>").append(graphic(n, ext, emu(p.w), emu(p.h))).append("</wp:inline></w:drawing></w:r></w:p>")
    }

    private var anchorId = 1000

    private fun anchor(a: DocItem.Anchor): String {
        val ext = if (a.png) "png" else "jpg"
        images += a.bytes to ext
        val n = images.size
        val id = ++anchorId
        val emu = { pt: Float -> (pt * 12700.0).roundToLong().coerceAtLeast(1L) }
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"${251658240 + id}\" " +
            "behindDoc=\"${if (a.behind) 1 else 0}\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>" +
            "<wp:positionH relativeFrom=\"page\"><wp:posOffset>${emu(a.x)}</wp:posOffset></wp:positionH>" +
            "<wp:positionV relativeFrom=\"page\"><wp:posOffset>${emu(a.y)}</wp:posOffset></wp:positionV>" +
            "<wp:extent cx=\"${emu(a.w)}\" cy=\"${emu(a.h)}\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>" +
            "<wp:docPr id=\"$id\" name=\"Elemento $id\"/><wp:cNvGraphicFramePr/>" + graphic(n, ext, emu(a.w), emu(a.h)) +
            "</wp:anchor></w:drawing></w:r>"
    }

    /** File del pacchetto Word (nome, contenuto): vanno messi in un archivio zip, in quest'ordine. */
    fun parts(title: String): List<Pair<String, ByteArray>> {
        if (pages == 0) body.append("<w:p/>")
        val last = (sect ?: defaultSect).xml()
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body$last</w:body></w:document>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="it-IT"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="png" ContentType="image/png"/><Default Extension="json" ContentType="application/json"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

        val extraRels = attachments.indices.joinToString("") { i ->
            "<Relationship Id=\"rIdDocVerso${i + 1}\" Type=\"https://github.com/maspag-max/DocVerso/analysis\" Target=\"${attachments[i].first}\"/>"
        }
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>$extraRels</Relationships>"""

        val imageRels = images.indices.joinToString("") { i ->
            "<Relationship Id=\"rIdImg${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${i + 1}.${images[i].second}\"/>"
        }
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>$imageRels</Relationships>"""

        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${escape(title)}</dc:title><dc:creator>DocVerso - Massimo Spagnuolo</dc:creator></cp:coreProperties>"""

        val out = ArrayList<Pair<String, ByteArray>>()
        fun put(name: String, text: String) { out += name to text.encodeToByteArray() }
        put("[Content_Types].xml", contentTypes)
        put("_rels/.rels", rels)
        put("docProps/core.xml", core)
        put("word/document.xml", document)
        put("word/styles.xml", styles)
        put("word/_rels/document.xml.rels", docRels)
        images.forEachIndexed { i, (bytes, ext) -> out += "word/media/image${i + 1}.$ext" to bytes }
        attachments.forEach { out += it }
        return out
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c.code < 0x20 && c != '\t' -> {} // caratteri di controllo non ammessi in XML
            else -> append(c)
        }
    }
}
