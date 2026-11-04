package it.spagnuolo.docverso.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    private val dict = mapOf(
        "Site of:" to "Sito di:", "Presence of operating procedure" to "Presenza di procedura operativa",
        "Notes" to "Note", "Yes" to "Sì", "General measures" to "Misure generali", "Hello world" to "Ciao mondo",
    )
    private val calls = ArrayList<String>()
    private val tr = DocTranslator { s -> calls += s; dict[s] ?: "[$s]" }

    @Test
    fun testoProtetto() {
        assertEquals("Sito di: ______  ", tr.text("Site of: ______  "))
        assertEquals("☐", tr.text("☐"))
        assertEquals("SPGMSM70S08A509E 90.44.6", tr.text("SPGMSM70S08A509E 90.44.6"))
        // Maiuscolo: tradotto in minuscolo e rimesso maiuscolo
        assertEquals("MISURE GENERALI", tr.text("GENERAL MEASURES"))
        assertEquals("Sì\tNote", tr.text("Yes\tNotes"))
        assertTrue(calls.none { it.contains("_") || it.contains("\t") })
    }

    @Test
    fun paginaEsatta() {
        val run = { t: String -> DocItem.Run(t, "Arial", 9f) }
        val cell = DocItem.GridCell(1, 0, listOf(
            DocItem.Para(listOf(DocItem.Piece(5f, listOf(run("Presence of operating")))), 1f, 10f),
            DocItem.Para(listOf(DocItem.Piece(5f, listOf(run("procedure")))), 0f, 10f),
        ), 0.5f, 0.5f, 0.5f, 0.5f)
        val page = DocItem.ExactPage(595f, 842f, 28f, 20f, 28f, 20f, listOf(
            DocItem.Para(listOf(DocItem.Piece(0f, listOf(run("Hello world")))), 0f, 12f),
            DocItem.Grid(0f, 0f, listOf(400f), listOf(DocItem.GridRow(20f, listOf(cell)))),
        ), emptyList())
        val out = tr.item(page) as DocItem.ExactPage
        assertEquals("Ciao mondo", (out.blocks[0] as DocItem.Para).pieces[0].runs[0].text)
        val g = out.blocks[1] as DocItem.Grid
        assertTrue(g.growRows)
        // Le due righe della cella diventano una frase sola
        assertEquals(listOf("Presenza di procedura operativa"), g.rows[0].cells[0].paras.map { p -> p.pieces[0].runs.joinToString("") { it.text } })
    }

    @Test
    fun dueFasiComeIlBrowser() {
        // Stesso risultato del traduttore sincrono, con le frasi raccolte prima e tradotte tutte insieme
        val page = DocItem.ExactPage(595f, 842f, 28f, 20f, 28f, 20f, listOf(
            DocItem.Para(listOf(DocItem.Piece(0f, listOf(DocItem.Run("Hello world", "Arial", 9f))), DocItem.Piece(200f, listOf(DocItem.Run("Notes ____", "Arial", 9f)))), 0f, 12f),
        ), emptyList())
        val direct = tr.item(page) as DocItem.ExactPage
        val batch = BatchTranslation()
        val asked = batch.collect { it.item(page) }
        assertEquals(listOf("Hello world", "Notes"), asked)
        asked.forEach { batch.put(it, dict[it] ?: "[$it]") }
        val two = batch.apply { it.item(page) } as DocItem.ExactPage
        assertEquals((direct.blocks[0] as DocItem.Para).pieces.map { p -> p.runs.map { it.text } }, (two.blocks[0] as DocItem.Para).pieces.map { p -> p.runs.map { it.text } })
    }
}
