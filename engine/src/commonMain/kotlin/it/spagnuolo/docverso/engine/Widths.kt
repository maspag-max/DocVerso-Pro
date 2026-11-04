package it.spagnuolo.docverso.engine

/** Larghezze approssimate dei caratteri Arial, in frazioni del corpo (per stimare quanto occupa un testo). */
object Widths {
    fun arial(text: String, bold: Boolean = false): Float {
        var w = 0f
        for (c in text) w += when {
            c == ' ' -> 0.278f
            c in "il.,:;'!|" -> 0.25f
            c in "jft()[]/" -> 0.3f
            c == 'r' -> 0.333f
            c in "mw" -> 0.81f
            c in "MW" -> 0.89f
            c.isUpperCase() -> 0.68f
            c.isDigit() -> 0.556f
            else -> 0.53f
        }
        return w * (if (bold) 1.07f else 1f)
    }
}
