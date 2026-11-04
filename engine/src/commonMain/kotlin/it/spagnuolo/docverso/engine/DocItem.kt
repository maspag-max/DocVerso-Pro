package it.spagnuolo.docverso.engine

/** Contenuto di una pagina Word: paragrafi, tabelle e immagini, nell'ordine in cui compaiono. */
sealed interface DocItem {
    /** Paragrafo. [sizePt] = dimensione del carattere (null = normale). Un '\t' diventa una tabulazione. */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val sizePt: Float? = null,
        val centered: Boolean = false,
        /** Colore di sfondo 0xRRGGBB (null = nessuno). */
        val shading: Int? = null,
        /** Linea orizzontale sotto il paragrafo (filetto separatore, es. sotto i titoli di sezione). */
        val ruleBelow: Boolean = false,
    ) : DocItem

    /**
     * Tabella. [colFractions] = larghezza di ogni colonna rispetto alla tabella (somma 1).
     * [bordered] = con bordi e prima riga d'intestazione in grassetto; senza bordi per i moduli a due colonne.
     */
    data class Table(
        val rows: List<List<String>>,
        val colFractions: List<Float>,
        val bordered: Boolean,
        /** Colore di sfondo di ogni riga 0xRRGGBB (null = nessuno). */
        val rowShading: List<Int?>? = null,
    ) : DocItem

    /**
     * Immagine JPEG. [widthFraction] = larghezza rispetto alla pagina originale (0..1),
     * usata per riprodurla in proporzione nel Word.
     */
    class Image(val jpeg: ByteArray, val width: Int, val height: Int, val widthFraction: Float) : DocItem

    /**
     * Pagina riprodotta con le posizioni esatte dell'originale (PDF digitali). Misure in punti
     * tipografici dall'angolo in alto a sinistra della pagina.
     */
    class ExactPage(
        val widthPt: Float, val heightPt: Float,
        val marginL: Float, val marginT: Float, val marginR: Float, val marginB: Float,
        val blocks: List<Block>,
        val anchors: List<Anchor>,
    ) : DocItem

    /** Pezzo di testo con il suo stile. [font] = famiglia di caratteri Word (es. "Arial"). */
    data class Run(
        val text: String, val font: String?, val sizePt: Float,
        val bold: Boolean = false, val italic: Boolean = false, val color: Int = 0,
    )

    /** Allineamento di un pezzo di riga: a sinistra, centrato o a destra della posizione [Piece.x]. */
    enum class Align { LEFT, CENTER, RIGHT }

    /** Pezzo di riga che parte da [x] (o finisce, se allineato a destra), relativo al bordo del testo. */
    data class Piece(val x: Float, val runs: List<Run>, val align: Align = Align.LEFT)

    sealed interface Block

    /**
     * Paragrafo: [pieces] sulla stessa riga (separati da tabulazioni esatte). [beforePt] = spazio sopra,
     * [linePt] = interlinea esatta. Con un solo pezzo e [rightPt] il testo va a capo come nell'originale.
     * [frame] = posizione assoluta sulla pagina (x, y, larghezza, altezza) per le righe accanto ad altro.
     */
    data class Para(
        val pieces: List<Piece>,
        val beforePt: Float,
        val linePt: Float,
        val rightPt: Float = 0f,
        val align: Align = Align.LEFT,
        val justify: Boolean = false,
        val frame: FloatArray? = null,
    ) : Block

    /** Tabella dalle linee disegnate: colonne e righe con le misure dell'originale. */
    data class Grid(
        val beforePt: Float,
        val indentPt: Float,
        val cols: List<Float>,
        val rows: List<GridRow>,
        /** true = righe che si allungano se il testo non ci sta (testo tradotto); false = altezza esatta. */
        val growRows: Boolean = false,
    ) : Block

    data class GridRow(val heightPt: Float, val cells: List<GridCell>)

    /** Bordi in punti (0 = assente). [vMerge]: 0 nessuna unione, 1 inizio, 2 continuazione. */
    data class GridCell(
        val span: Int,
        val vMerge: Int,
        val paras: List<Para>,
        val top: Float, val left: Float, val bottom: Float, val right: Float,
        val borderColor: Int = 0,
        /** Colore di sfondo della cella 0xRRGGBB (null = nessuno). */
        val shading: Int? = null,
    )

    /**
     * Elemento in posizione fissa sulla pagina: immagine, codice a barre, linea o sfondo colorato.
     * [png] = immagine PNG invece che JPEG. [behind] = dietro al testo.
     */
    class Anchor(val bytes: ByteArray, val png: Boolean, val x: Float, val y: Float, val w: Float, val h: Float, val behind: Boolean)
}
