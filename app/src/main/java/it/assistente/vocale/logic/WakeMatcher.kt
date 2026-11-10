package it.assistente.vocale.logic

/**
 * Seconda verifica della frase di attivazione.
 *
 * Il rilevatore a grammatica conosce solo le parole della frase e tende a "forzarci dentro" qualunque
 * suono: dici "ok" e lui sente "ok alfio". Per questo l'audio degli ultimi secondi viene riascoltato
 * con la dettatura libera (che conosce tutto il vocabolario) e la frase deve comparire anche lì.
 */
object WakeMatcher {

    /** Vero se in [heard] c'è la frase, anche con piccoli errori ("o calfio", "ciao assistenti"). */
    fun matches(phrase: String, heard: String): Boolean {
        val p = Text.tokens(phrase)
        val h = Text.tokens(heard)
        if (p.isEmpty() || h.isEmpty()) return false
        val target = p.joinToString("")
        // frasi corte: tolleranza minore, altrimenti somiglia a tutto
        val threshold = if (target.length < 6) 0.85 else 0.75
        for (start in h.indices) {
            // il riconoscitore può spezzare o unire le parole: "al fio", "okalfio"
            for (len in 1..minOf(p.size + 1, h.size - start)) {
                val candidate = h.subList(start, start + len).joinToString("")
                if (Text.soundsLike(target, candidate) >= threshold) return true
            }
        }
        return false
    }
}
