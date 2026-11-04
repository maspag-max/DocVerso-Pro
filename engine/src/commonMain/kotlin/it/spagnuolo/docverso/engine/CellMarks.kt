package it.spagnuolo.docverso.engine

/** Riconosce una casella stampata (o un segno simile) in una cella vuota di tabella. Codice puro, testabile. */
object CellMarks {
    /**
     * [lum] restituisce la luminosità 0..255 del pixel (x, y) del riquadro [w]×[h].
     * Vero se c'è un gruppo di pixel scuri compatto, di dimensione paragonabile all'altezza della riga.
     */
    fun hasMark(w: Int, h: Int, lum: (Int, Int) -> Float): Boolean {
        if (w < 4 || h < 4) return false
        // Ignora un piccolo bordo (linee della tabella che sfiorano la cella)
        val mx = (w * 0.04f).toInt(); val my = (h * 0.12f).toInt()
        // Soglia adattiva: più scuro dello sfondo della cella (anche su celle colorate)
        val sample = ArrayList<Float>()
        for (y in my until h - my step 2) for (x in mx until w - mx step 2) sample += lum(x, y)
        if (sample.isEmpty()) return false
        sample.sort()
        val bg = sample[(sample.size * 0.8f).toInt().coerceAtMost(sample.size - 1)]
        val thr = bg - 28f
        var dark = 0; var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in my until h - my) for (x in mx until w - mx) {
            if (lum(x, y) < thr) {
                dark++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (dark < 6) return false
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        val inner = (w - 2 * mx) * (h - 2 * my)
        val square = bw.toFloat() / bh in 0.5f..2.0f
        return square && bh >= maxOf(5f, h * 0.12f) && bw <= w * 0.9f && dark < inner * 0.5f
    }

    /**
     * Trova le caselle in una fascia orizzontale [w]×[h]: gruppi di colonne con pixel scuri, larghi
     * più o meno quanto alti, che sono quadratini (verificati con [hasMark]). Restituisce i centri x.
     */
    fun findBoxes(w: Int, h: Int, lum: (Int, Int) -> Float): List<Float> {
        if (w < 4 || h < 4) return emptyList()
        // Soglia adattiva sullo sfondo della fascia
        val sample = ArrayList<Float>()
        for (y in 0 until h step 3) for (x in 0 until w step 3) sample += lum(x, y)
        sample.sort()
        val bg = sample[(sample.size * 0.8f).toInt().coerceAtMost(sample.size - 1)]
        val thr = bg - 28f
        val dark = BooleanArray(w) { x -> (0 until h).any { y -> lum(x, y) < thr } }
        val out = ArrayList<Float>()
        var x = 0
        while (x < w) {
            if (!dark[x]) { x++; continue }
            var e = x; var gap = 0
            while (e + 1 < w && (dark[e + 1] || gap < 2)) { if (dark[e + 1]) gap = 0 else gap++; e++ }
            e -= gap
            val gw = e - x + 1
            if (gw >= h * 0.15f && gw <= h * 1.1f) {
                val x0 = (x - 2).coerceAtLeast(0); val x1 = (e + 3).coerceAtMost(w)
                if (hasMark(x1 - x0, h) { xx, yy -> lum(x0 + xx, yy) }) out += (x + e) / 2f
            }
            x = e + 1
        }
        return out
    }
}
