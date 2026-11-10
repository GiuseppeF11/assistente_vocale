package it.assistente.vocale.logic

enum class NumberKind { MOBILE, HOME, WORK, OTHER }

data class PhoneNumber(
    val number: String,
    val label: String,
    val kind: NumberKind,
    /** numero predefinito impostato in rubrica */
    val primary: Boolean = false,
)

data class Contact(val name: String, val numbers: List<PhoneNumber>)

sealed interface Match {
    data object None : Match
    data class One(val contact: Contact) : Match
    data class Many(val contacts: List<Contact>) : Match
}

/** Ricerca "a orecchio" di un nome pronunciato dentro la rubrica. */
object ContactMatcher {
    /** sotto questa soglia il contatto non è considerato */
    private const val MIN_SCORE = 0.70
    /** due candidati più vicini di così sono ambigui: si chiede */
    private const val MARGIN = 0.08
    private const val TOKEN_FLOOR = 0.6

    fun find(query: String, contacts: List<Contact>, max: Int = 4): Match {
        val q = Text.tokens(query)
        if (q.isEmpty()) return Match.None
        val scored = contacts
            .map { it to score(q, Text.tokens(it.name)) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return Match.None
        val top = scored[0].second
        val close = scored.takeWhile { top - it.second < MARGIN }
        return if (close.size == 1) Match.One(close[0].first)
        else Match.Many(close.take(max).map { it.first })
    }

    /** 80%: quanto del nome detto si ritrova nel contatto; 20%: quanto del contatto è stato detto. */
    fun score(query: List<String>, name: List<String>): Double {
        val (qCov, nCov) = coverage(query, name)
        return 0.8 * qCov + 0.2 * nCov
    }

    /** (quota del detto ritrovata nel nome, quota del nome ritrovata nel detto) */
    fun coverage(query: List<String>, name: List<String>): Pair<Double, Double> {
        if (query.isEmpty() || name.isEmpty()) return 0.0 to 0.0
        val qCov = query.sumOf { best(it, name) } / query.size
        val nCov = name.sumOf { best(it, query) } / name.size
        return qCov to nCov
    }

    private fun best(token: String, others: List<String>): Double {
        val s = others.maxOf { Text.soundsLike(token, it) }
        return if (s < TOKEN_FLOOR) 0.0 else s
    }
}

/** Interpreta la risposta a una domanda con più opzioni ("il secondo", "rossi", "cellulare"). */
object ChoiceParser {
    private val ordinals = listOf(
        setOf("primo", "prima", "uno"),
        setOf("secondo", "seconda", "due"),
        setOf("terzo", "terza", "tre"),
        setOf("quarto", "quarta", "quattro"),
    )
    private val last = setOf("ultimo", "ultima")

    fun pick(answer: String, labels: List<String>): Int? {
        val tokens = Text.tokens(answer)
        if (tokens.isEmpty() || labels.isEmpty()) return null
        // prima le etichette: "casa due" deve vincere su "due" inteso come "il secondo"
        val scored = labels.indices
            .map {
                // "quello di casa" contiene tutta l'etichetta "Casa"; "rossi" sta tutto in "Mario Rossi"
                val (q, n) = ContactMatcher.coverage(tokens, Text.tokens(labels[it]))
                it to maxOf(q, n)
            }
            .sortedByDescending { it.second }
        val top = scored[0]
        val second = scored.getOrNull(1)?.second ?: 0.0
        if (top.second >= 0.5 && top.second - second >= 0.2) return top.first

        if (tokens.any { it in last }) return labels.lastIndex
        for ((i, words) in ordinals.withIndex()) {
            if (i < labels.size && tokens.any { it in words }) return i
        }
        return null
    }

    fun kind(answer: String): NumberKind? = CommandParser.kindOf(Text.tokens(answer))
}
