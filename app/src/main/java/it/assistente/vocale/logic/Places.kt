package it.assistente.vocale.logic

/**
 * Luoghi salvati dall'utente oltre a casa e lavoro ("Palestra", "Mamma", "Casa al mare").
 * Riconosce frasi naturali: "portami in palestra", "da mamma", "dalla nonna", "a casa di mamma".
 */
object Places {

    data class Place(val name: String, val address: String)

    /** parole da togliere davanti al nome: preposizioni, articoli, possessivi */
    private val lead = setOf(
        "a", "al", "allo", "alla", "ai", "agli", "alle", "all",
        "in", "nel", "nello", "nella", "nei", "negli", "nelle", "nell",
        "da", "dal", "dallo", "dalla", "dai", "dagli", "dalle", "dall",
        "verso", "fino", "per", "presso",
        "il", "lo", "la", "i", "gli", "le", "l",
        "mio", "mia", "miei", "mie", "nostro", "nostra",
    )

    /** "casa di mamma" -> "mamma": casa (o studio, ufficio) seguita da di/del/della */
    private val ownerWords = setOf("casa", "studio", "ufficio", "negozio")
    private val of = setOf("di", "del", "dello", "della", "dei", "degli", "delle", "dell")

    /** Il nome ripulito, senza spazi: la chiave di confronto. */
    fun key(s: String): String {
        var t = Text.tokens(s.replace('\'', ' '))
        while (t.isNotEmpty() && t.first() in lead) t = t.drop(1)
        return t.joinToString("")
    }

    /** Il luogo salvato a cui si riferisce [destination], o null (allora si cerca su Maps). */
    fun match(destination: String, places: List<Place>): Place? {
        val usable = places.filter { it.name.isNotBlank() && it.address.isNotBlank() }
        if (usable.isEmpty()) return null
        val heard = candidates(destination)
        if (heard.isEmpty()) return null
        var best: Place? = null
        var bestScore = 0.0
        for (p in usable) {
            val k = key(p.name)
            if (k.isEmpty()) continue
            for (h in heard) {
                if (h == k) return p
                // nomi corti: tolleranza minore, altrimenti "bar" diventa "bari"
                val threshold = when {
                    k.length < 5 -> 0.92
                    k.length < 8 -> 0.85
                    else -> 0.8
                }
                val score = Text.soundsLike(k, h)
                if (score >= threshold && score > bestScore) { best = p; bestScore = score }
            }
        }
        return best
    }

    /** I modi di leggere la destinazione: com'è detta e senza "casa di" davanti. */
    private fun candidates(destination: String): List<String> {
        var t = Text.tokens(destination.replace('\'', ' '))
        while (t.isNotEmpty() && t.first() in lead) t = t.drop(1)
        if (t.isEmpty()) return emptyList()
        val out = arrayListOf(t.joinToString(""))
        if (t.size >= 3 && t[0] in ownerWords && t[1] in of) {
            var rest = t.drop(2)
            while (rest.isNotEmpty() && rest.first() in lead) rest = rest.drop(1)
            if (rest.isNotEmpty()) out += rest.joinToString("")
        }
        return out
    }
}
