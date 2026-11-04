package it.spagnuolo.docverso.engine

/**
 * Traduzione in due passaggi, per i traduttori asincroni del browser (Bergamot):
 * 1. [collect] fa lavorare [DocTranslator] senza tradurre e raccoglie le frasi che gli servono;
 * 2. fuori si traducono tutte insieme (in parallelo, in blocchi);
 * 3. [apply] rifà lo stesso lavoro usando le traduzioni pronte.
 * DocTranslator è deterministico: nei due passaggi chiede esattamente le stesse frasi.
 */
class BatchTranslation {
    private val pending = LinkedHashSet<String>()
    private val done = HashMap<String, String>()

    /** Primo passaggio: [work] riceve un DocTranslator che restituisce il testo com'è e annota le richieste. */
    fun <T> collect(work: (DocTranslator) -> T): List<String> {
        work(DocTranslator { s -> if (s !in done) pending += s; s })
        return pending.toList()
    }

    fun put(source: String, translated: String) {
        done[source] = translated
        pending -= source
    }

    /** Secondo passaggio: [work] riceve un DocTranslator con le traduzioni già pronte. */
    fun <T> apply(work: (DocTranslator) -> T): T = work(DocTranslator { s -> done[s] ?: s })
}
