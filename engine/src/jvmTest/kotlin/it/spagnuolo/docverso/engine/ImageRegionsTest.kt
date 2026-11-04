package it.spagnuolo.docverso.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRegionsTest {

    private fun grid(gw: Int, gh: Int, fill: (Int, Int) -> Boolean) = BooleanArray(gw * gh) { i -> fill(i % gw, i / gw) }

    @Test
    fun trovaUnaFotoEIgnoraLeRigheSottili() {
        val gw = 100; val gh = 140
        val ink = grid(gw, gh) { x, y ->
            (x in 20..70 && y in 40..80) || // foto piena
                (y == 110 && x in 5..95) // riga sottile (es. linea separatrice)
        }
        val boxes = ImageRegions.find(ink, gw, gh)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertTrue(b.left in 18..21 && b.right in 69..72 && b.top in 38..41 && b.bottom in 79..82)
    }

    @Test
    fun paginaVuotaNessunaImmagine() {
        assertTrue(ImageRegions.find(BooleanArray(100 * 140), 100, 140).isEmpty())
    }

    @Test
    fun dueImmaginiInOrdineDallAlto() {
        val gw = 100; val gh = 140
        val ink = grid(gw, gh) { x, y -> (x in 10..40 && y in 90..120) || (x in 50..90 && y in 10..30) }
        val boxes = ImageRegions.find(ink, gw, gh)
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].top < boxes[1].top)
    }
}
