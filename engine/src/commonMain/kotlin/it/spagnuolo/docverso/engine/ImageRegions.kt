package it.spagnuolo.docverso.engine

/**
 * Trova le zone "immagine" (foto, loghi, timbri, grafici) in una griglia ridotta della pagina
 * dove il testo riconosciuto è già stato tolto. Codice puro, senza Android, così è testabile.
 */
object ImageRegions {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val area get() = width * height
    }

    /**
     * [ink] = celle "piene" (scure o colorate), riga per riga, griglia [gw]×[gh].
     * Restituisce i riquadri delle zone abbastanza grandi e dense da essere immagini, dall'alto in basso.
     */
    fun find(ink: BooleanArray, gw: Int, gh: Int): List<Box> {
        // Dilatazione di 1 cella: unisce le parti della stessa immagine separate da zone chiare
        val grown = BooleanArray(ink.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            if (!ink[y * gw + x]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until gw && ny in 0 until gh) grown[ny * gw + nx] = true
            }
        }
        val seen = BooleanArray(ink.size)
        val boxes = ArrayList<Box>()
        val stack = IntArray(ink.size)
        for (start in grown.indices) {
            if (!grown[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var l = gw; var t = gh; var r = -1; var b = -1
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % gw; val y = i / gw
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val j = ny * gw + nx
                    if (grown[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            boxes += Box(l, t, r, b)
        }
        return boxes.filter { box ->
            val inkCells = (box.top..box.bottom).sumOf { y -> (box.left..box.right).count { x -> ink[y * gw + x] } }
            val density = inkCells.toFloat() / box.area
            box.width >= gw * 0.08f && box.height >= gh * 0.05f && // non troppo piccola
                box.area <= gw * gh * 0.85f && // non tutta la pagina (bordi, sfondo)
                density >= 0.30f // abbastanza piena: esclude righe e linee sparse
        }.sortedBy { it.top }
    }
}
