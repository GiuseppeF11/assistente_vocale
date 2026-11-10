package it.assistente.vocale.logic

sealed interface Command {
    data class Call(val target: String, val kind: NumberKind?) : Command
    /** "chiama" senza dire chi */
    data object CallWho : Command
    data class Navigate(val destination: String) : Command
    /** "portami" senza dire dove */
    data object NavigateWhere : Command
    data object Cancel : Command
    data class Unknown(val text: String) : Command
}

/**
 * Capisce le due intenzioni supportate a partire dal testo riconosciuto
 * (minuscolo, senza punteggiatura, come lo restituisce Vosk).
 */
object CommandParser {

    private const val FILLERS = "(?:(?:per favore|per piacere|ok|allora|ehi|senti|dai|ciao assistente)\\s+)*"

    private val callRe = Regex(
        "^$FILLERS(?:fai una (?:chiamata|telefonata)|fammi (?:chiamare|telefonare)|" +
            "(?:puoi|voglio|vorrei|devo) (?:chiamare|telefonare)|telefona(?:re)?|chiam(?:are|ami|a|o))" +
            "(?:\\s+(.*))?$"
    )

    private val navRe = Regex(
        "^$FILLERS(?:portami|accompagnami|guidami|naviga(?:re)?|navigazione|navigatore|andiamo|vai|" +
            "(?:voglio|vorrei|devo) andare|avvia (?:la navigazione|il navigatore)|" +
            "indicazioni(?: stradali)?|strada|percorso|" +
            "come (?:arrivo|si arriva|faccio ad arrivare|faccio a arrivare))" +
            "(?:\\s+(.*))?$"
    )

    private val cancelRe = Regex(
        "^(?:annulla|annullare|lascia (?:stare|perdere)|niente|nessuno|basta|stop|esci|chiudi|no|no grazie)$"
    )

    private val trailingPolite = Regex("\\s+(?:per favore|per piacere|grazie)$")
    private val callLead = Regex("^(?:(?:al|il) numero|numero|ad|a|al|allo|alla|il|lo|la)\\s+")
    private val callLeadApostrophe = Regex("^(?:all'|l')\\s*")
    private val navLead = Regex(
        "^(?:verso|fino|per|ad|a|al|allo|alla|ai|agli|alle|in|da|dal|dalla|nel|nella)\\s+"
    )
    private val kindSuffix = Regex(
        "\\s+(?:sul|al|a|in|nel)\\s+(cellulare|telefonino|mobile|casa|fisso|lavoro|ufficio)$"
    )

    fun parse(raw: String): Command {
        val text = clean(raw)
        if (text.isEmpty()) return Command.Unknown(text)
        if (cancelRe.matches(text)) return Command.Cancel

        callRe.find(text)?.let { m ->
            val (target, kind) = callTarget(m.groupValues[1])
            return if (target.isEmpty()) Command.CallWho else Command.Call(target, kind)
        }
        navRe.find(text)?.let { m ->
            val dest = destination(m.groupValues[1])
            return if (dest.isEmpty()) Command.NavigateWhere else Command.Navigate(dest)
        }
        return Command.Unknown(text)
    }

    /** Ripulisce "a mario rossi sul cellulare" -> ("mario rossi", MOBILE). */
    fun callTarget(raw: String): Pair<String, NumberKind?> {
        var t = clean(raw)
        var kind: NumberKind? = null
        kindSuffix.find(t)?.let { m ->
            kind = kindOf(listOf(m.groupValues[1]))
            t = t.removeRange(m.range).trim()
        }
        t = stripLeading(t, callLead, callLeadApostrophe)
        return t to kind
    }

    /** Ripulisce "fino a via etnea cento venti" -> "via etnea 120". */
    fun destination(raw: String): String {
        val t = stripLeading(clean(raw), navLead, null)
        return ItalianNumbers.digitsInText(t)
    }

    fun kindOf(tokens: List<String>): NumberKind? = when {
        tokens.any { it == "cellulare" || it == "telefonino" || it == "mobile" } -> NumberKind.MOBILE
        tokens.any { it == "casa" || it == "fisso" } -> NumberKind.HOME
        tokens.any { it == "lavoro" || it == "ufficio" } -> NumberKind.WORK
        else -> null
    }

    private fun clean(raw: String): String {
        var t = raw.lowercase().replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        while (true) {
            val next = trailingPolite.replace(t, "")
            if (next == t) break
            t = next
        }
        return t
    }

    private fun stripLeading(s: String, words: Regex, apostrophe: Regex?): String {
        var t = s
        while (true) {
            var next = words.replaceFirst(t, "")
            if (apostrophe != null) next = apostrophe.replaceFirst(next, "")
            // non svuotare tutto: "chiama la" resta vuoto, ma "portami a" + "a" non deve mangiarsi un nome
            if (next == t) break
            t = next
        }
        // una sola preposizione rimasta non è un bersaglio
        return if (words.matches("$t ")) "" else t.trim()
    }
}
