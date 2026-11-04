package it.spagnuolo.docverso.engine

import it.spagnuolo.docverso.engine.LayoutAnalyzer.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAnalyzerTest {

    /** Riga di parole a partire da x, con altezza h e spazi normali. */
    private fun line(y: Float, vararg parts: Pair<Float, String>, h: Float = 10f): List<Word> =
        parts.flatMap { (x0, text) ->
            var x = x0
            text.split(' ').map { w -> Word(w, x, y, x + w.length * 6f, y + h).also { x += w.length * 6f + 4f } }
        }

    @Test
    fun ricostruisceTabellaConCaselle() {
        val words = line(0f, 40f to "Modulo di autorizzazione", h = 20f) +
            line(40f, 40f to "Il presente documento integra le voci dei moduli ufficiali.") +
            line(80f, 40f to "Verifica di", 300f to "Si", 340f to "No", 380f to "N/A", 430f to "Note") +
            line(100f, 40f to "Presenza di procedura operativa", 300f to "□", 340f to "□", 380f to "□") +
            line(120f, 40f to "Avvenuta bonifica", 300f to "□", 340f to "□", 380f to "□") +
            line(140f, 40f to "Idoneità della strumentazione", 300f to "□", 340f to "□", 380f to "□") +
            line(152f, 40f to "delle attrezzature di lavoro") +
            line(172f, 40f to "Idoneità temperatura/umidità", 300f to "□", 340f to "□", 380f to "□")
        val items = LayoutAnalyzer.analyze(words).items.map { it.second }

        val title = items[0] as DocItem.Text
        assertTrue(title.bold)
        assertEquals("Il presente documento integra le voci dei moduli ufficiali.", (items[1] as DocItem.Text).text)
        val table = items[2] as DocItem.Table
        assertTrue(table.bordered)
        assertEquals(5, table.colFractions.size)
        assertEquals(listOf("Verifica di", "Si", "No", "N/A", "Note"), table.rows[0])
        assertEquals(5, table.rows.size) // la riga che continua è unita alla precedente
        assertEquals("Idoneità della strumentazione delle attrezzature di lavoro", table.rows[3][0])
        assertEquals(listOf("☐", "☐", "☐", ""), table.rows[1].drop(1))
    }

    @Test
    fun testoNormaleNonDiventaTabella() {
        val words = line(0f, 40f to "Primo paragrafo di testo normale che continua") +
            line(14f, 40f to "sulla riga seguente senza colonne.") +
            line(50f, 40f to "Secondo paragrafo.")
        val items = LayoutAnalyzer.analyze(words).items.map { it.second }
        assertEquals(2, items.size)
        assertTrue(items.all { it is DocItem.Text })
    }

    @Test
    fun caselleNonRiconosciuteVengonoTrovateNellImmagine() {
        // Come sul telefono: le caselle non arrivano come testo, solo l'intestazione Sì/No/N/A
        val words = line(80f, 40f to "Verifica di", 300f to "Si", 340f to "No", 380f to "N/A", 430f to "Note") +
            line(100f, 40f to "Presenza di procedura operativa") +
            line(120f, 40f to "Avvenuta bonifica") +
            line(140f, 40f to "Idoneità temperatura/umidità")
        // Nell'immagine c'è una casella sotto Si, No e N/A (x 295..400) in ogni riga dopo l'intestazione
        val mark = { l: Float, t: Float, _: Float, _: Float -> t > 95f && l in 280f..400f }
        val table = LayoutAnalyzer.analyze(words, mark).items.map { it.second }.single() as DocItem.Table
        assertEquals(4, table.rows.size)
        assertEquals(listOf("Presenza di procedura operativa", "☐", "☐", "☐", ""), table.rows[1])
    }

    @Test
    fun righeDelRiconoscimentoNonSiMescolano() {
        // Pagina storta: la fine della prima riga scende al livello dell'inizio della seconda
        val a = (0 until 8).map { k -> Word("uno$k", 40f + k * 50f, 0f + k * 2.5f, 80f + k * 50f, 10f + k * 2.5f, line = 0) }
        val b = (0 until 8).map { k -> Word("due$k", 40f + k * 50f, 14f + k * 2.5f, 80f + k * 50f, 24f + k * 2.5f, line = 1) }
        val text = (LayoutAnalyzer.analyze(a + b).items.single().second as DocItem.Text).text
        assertEquals((0 until 8).joinToString(" ") { "uno$it" } + " " + (0 until 8).joinToString(" ") { "due$it" }, text)
    }
}
