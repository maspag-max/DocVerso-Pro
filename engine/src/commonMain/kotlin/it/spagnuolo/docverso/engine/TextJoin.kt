package it.spagnuolo.docverso.engine

object TextJoin {
    /** Unisce le righe di un blocco in un paragrafo scorrevole (ricompone le parole divise col trattino). */
    fun joinLines(lines: List<String>): String = buildString {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                isEmpty() -> append(line)
                endsWith("-") && line.first().isLowerCase() -> { deleteAt(length - 1); append(line) }
                else -> append(' ').append(line)
            }
        }
    }
}
