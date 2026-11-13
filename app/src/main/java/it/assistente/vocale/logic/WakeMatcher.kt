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

    /**
     * Riattivazione a voce durante l'ascolto del comando: se in [heard] c'è la frase intera, restituisce quello che
     * viene dopo la sua ultima ripetizione ("chiama ehm ok alfio chiama Luca" -> "chiama Luca", "ok alfio" -> "").
     * null se la frase non c'è. Mantiene maiuscole e accenti.
     */
    fun afterLast(phrase: String, heard: String): String? {
        val p = Text.tokens(phrase)
        val target = p.joinToString("")
        if (p.isEmpty() || target.length < 4) return null
        val words = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norm = words.map { Text.normalize(it).replace(" ", "") }
        val threshold = if (target.length < 6) 0.85 else 0.75
        var end = -1
        for (start in words.indices) {
            for (len in 1..minOf(p.size + 1, words.size - start)) {
                val candidate = norm.subList(start, start + len).joinToString("")
                // la frase intera, non un pezzo: "alfio" da solo è un nome, non una riattivazione
                if (candidate.length * 10 < target.length * 8) continue
                if (Text.soundsLike(target, candidate) >= threshold) { end = maxOf(end, start + len); break }
            }
        }
        if (end < 0) return null
        return words.drop(end).joinToString(" ").trimStart(',', '.', ';', ':', '!', '?', ' ')
    }

    /**
     * Toglie la frase di attivazione dall'inizio del comando: con la frase unica il riconoscitore riceve
     * anche la coda di «Ok Alfio» ("alfio chiama Mario" -> "chiama Mario"). Mantiene maiuscole e accenti.
     */
    fun stripLeading(phrase: String, heard: String): String {
        val p = Text.tokens(phrase)
        if (p.isEmpty()) return heard.trim()
        val words = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // la frase intera o solo la sua parte finale ("alfio")
        val targets = p.indices.map { p.subList(it, p.size).joinToString("") }.filter { it.length >= 4 }
        for (k in minOf(p.size + 1, words.size) downTo 1) {
            val joined = words.subList(0, k).joinToString("") { Text.normalize(it).replace(" ", "") }
            if (joined.length >= 4 && targets.any { t -> Text.soundsLike(t, joined) >= 0.8 }) {
                return words.drop(k).joinToString(" ").trimStart(',', '.', ';', ':', '!', '?', ' ')
            }
        }
        return heard.trim()
    }
}
