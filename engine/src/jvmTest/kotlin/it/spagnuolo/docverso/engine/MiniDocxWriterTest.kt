package it.spagnuolo.docverso.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class MiniDocxWriterTest {

    @Test
    fun scriveUnDocxConDuePagine() {
        val w = MiniDocxWriter(210f, 297f)
        w.addPage(listOf("Contratto di locazione tra Rossi & Bianchi <prova>", "Articolo 1 – Oggetto: l'immobile è sito in Roma."))
        val jpg = javaClass.classLoader!!.getResourceAsStream("page.jpg")!!.readBytes()
        w.addItems(listOf(
            DocItem.Text("Seconda pagina con foto", bold = true, sizePt = 16f), DocItem.Image(jpg, 600, 848, 0.5f), DocItem.Text("Riga uno\nRiga due"),
            DocItem.Table(listOf(listOf("Verifica di", "Sì", "No"), listOf("Avvenuta bonifica", "☐", "☐")), listOf(0.7f, 0.15f, 0.15f), true),
        ))
        val bytes = zip(w.parts("Contratto è ok"))

        val names = mutableListOf<String>()
        var document = ""
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "word/document.xml") document = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/media/image1.jpg")))
        assertTrue(document.contains("r:embed=\"rIdImg1\""))
        assertTrue(document.contains("<w:tbl>") && document.contains("Avvenuta bonifica") && document.contains("<w:b/>"))
        assertTrue(document.contains("Rossi &amp; Bianchi &lt;prova&gt;"))
        assertTrue(document.contains("<w:type w:val=\"nextPage\"/>")) // ogni pagina è una sezione
        assertTrue(document.contains("w:w=\"11905\"") || document.contains("w:w=\"11906\""))
        File(System.getProperty("java.io.tmpdir"), "docverso-test.docx").writeBytes(bytes)
    }

    @Test
    fun uniscePrigheERicomponeIlTrattino() {
        assertEquals(
            "Il presente contratto viene stipulato tra le parti.",
            TextJoin.joinLines(listOf("Il presente con-", "tratto viene stipulato", "tra le parti.")),
        )
        assertEquals("Roma - Napoli", TextJoin.joinLines(listOf("Roma -", "Napoli")))
    }
}
