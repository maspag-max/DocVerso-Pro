package it.spagnuolo.docverso.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQualityTest {
    private fun page(text: String, font: String = "Helvetica") = ExactLayout.Page(
        595f, 842f, listOf(ExactLayout.Font(font, false, false)),
        text.mapIndexed { i, c -> ExactLayout.Glyph(c.toString(), 20f + (i % 90) * 6f, 30f + (i / 90) * 12f, 5f, 10f, 0, 0) },
        emptyList(), emptyList(),
    )

    private val normale = "Il presente documento integra le voci dei moduli ufficiali Allegato 1-a e Allegato 1-b, " +
        "che sono quasi identici nei contenuti. Le voci contrassegnate sono presenti nella norma UNI 11958:2024. "

    @Test
    fun testoNormaleNonCambia() {
        assertFalse(TextQuality.looksGarbled(page(normale.repeat(3)), 0))
        // Codici fiscali, codici a barre e numeri non fanno scattare il controllo
        assertFalse(TextQuality.looksGarbled(page(normale + " SPGMSM70S08A509E MNCMRT76S59H501A 281020261059058540009982039637"), 0))
        assertFalse(TextQuality.looksGarbled(page("Ì1200A6ÎÌ4985329305qÎ".repeat(20), "Code-128"), 0))
    }

    @Test
    fun testoIllegibileVaAllOcr() {
        // Caratteri di uso privato (font senza tabella dei caratteri)
        assertTrue(TextQuality.looksGarbled(page(" ".repeat(30)), 0))
        // Lettere disegnate senza carattere associato
        assertTrue(TextQuality.looksGarbled(page("abc".repeat(10)), 40))
        // Lettere mescolate, senza vocali
        assertTrue(TextQuality.looksGarbled(page("Xkr qbtxzm dfghj klmnp qrstv wxz bcdf ghjkl ".repeat(10)), 0))
    }
}
