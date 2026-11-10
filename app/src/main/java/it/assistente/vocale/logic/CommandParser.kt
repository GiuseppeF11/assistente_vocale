package it.assistente.vocale.logic

/** Cosa si cerca su Spotify: decide quale risultato viene riprodotto. */
enum class MediaFocus { ANY, SONG, ARTIST, ALBUM, PLAYLIST }

data class PlayRequest(val query: String, val focus: MediaFocus)

sealed interface Command {
    data class Call(val target: String, val kind: NumberKind?, val speaker: Boolean = false) : Command
    /** "chiama" senza dire chi */
    data object CallWho : Command
    data class Navigate(val destination: String) : Command
    /** "portami" senza dire dove */
    data object NavigateWhere : Command
    /** sveglia a un orario */
    data class Alarm(val hour: Int, val minute: Int) : Command
    /** "svegliami tra 20 minuti" */
    data class AlarmIn(val seconds: Int) : Command
    /** "metti una sveglia" senza orario */
    data object AlarmWhen : Command
    data class Timer(val seconds: Int) : Command
    /** "metti un timer" senza durata */
    data object TimerHowLong : Command

    /** "metti … da Spotify" */
    data class Play(val request: PlayRequest) : Command
    /** "metti" senza dire cosa */
    data object PlayWhat : Command
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

    private val alarmRe = Regex(
        "^$FILLERS(?:(?:metti(?:mi)?|imposta(?:mi)?|crea|aggiungi|punta|fissa|programma)(?: una| la| un'| l')? ?sveglia|" +
            "sveglia|svegliami|(?:puoi|devi|mi puoi) svegliarmi)(?:\\s+(.*))?$"
    )
    private val timerRe = Regex(
        "^$FILLERS(?:(?:metti(?:mi)?|imposta|avvia|fai partire|crea|aggiungi|fammi)(?: un| il)? " +
            "(?:timer|conto alla rovescia)|timer|conto alla rovescia|avvisami|ricordami)(?:\\s+(.*))?$"
    )
    private val relative = Regex("^(?:tra|fra)\\s+")

    private val playRe = Regex(
        "^$FILLERS(?:(?:su |da |apri )?spotify\\s+)?(?:metti(?:mi)?|fammi (?:sentire|ascoltare)|riproduci|suona|" +
            "ascolta(?:re)?|(?:voglio|vorrei) (?:sentire|ascoltare)|fai partire|play)" +
            "(?:\\s+(.*))?$"
    )
    private val spotifySuffix = Regex("\\s*(?:\\b(?:da|su|con|in|sopra|dentro)\\s+)?\\bspotify$")
    private val spotifyPrefix = Regex("^(?:su |da )?spotify\\s*")
    private val focusPrefixes = listOf(
        MediaFocus.SONG to Regex("^(?:la |una )?canzone\\s+|^(?:il |un )?brano\\s+|^(?:il |un )?pezzo\\s+"),
        MediaFocus.ALBUM to Regex("^(?:l' ?|un )?album\\s+|^(?:il |un )?disco\\s+"),
        MediaFocus.PLAYLIST to Regex("^(?:la |una )?playlist\\s+"),
        MediaFocus.ARTIST to Regex(
            "^(?:l' ?)?artista\\s+|^(?:qualcosa|musica|canzoni|qualche canzone|un po' di musica|i successi|le canzoni)" +
                " (?:di|dei|degli|delle|del|della|dello)\\s+"
        ),
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
    private val speakerSuffix = Regex(
        "\\s+(?:in |con il |con l'|con |col |sul |usando il |usando l')?(?:vivavoce|viva voce|altoparlante)$"
    )
    private val speakerPrefix = Regex("^(?:in|con il|col|con l') ?(?:vivavoce|viva voce|altoparlante)\\s+")
    private val speakerWords = Regex("\\b(?:vivavoce|viva voce|altoparlante)\\b")
    private val punctuation = Regex("[^\\p{L}\\p{N}'+ ]+")

    private val kindSuffix = Regex(
        "\\s+(?:sul|al|a|in|nel)\\s+(cellulare|telefonino|mobile|casa|fisso|lavoro|ufficio)$"
    )

    fun parse(raw: String): Command {
        val text = clean(raw)
        if (text.isEmpty()) return Command.Unknown(text)
        if (cancelRe.matches(text)) return Command.Cancel

        callRe.find(text)?.let { m ->
            val t = callTarget(m.groupValues[1])
            return if (t.target.isEmpty()) Command.CallWho else Command.Call(t.target, t.kind, t.speaker)
        }
        navRe.find(text)?.let { m ->
            val dest = destination(m.groupValues[1])
            return if (dest.isEmpty()) Command.NavigateWhere else Command.Navigate(dest)
        }
        alarmRe.find(text)?.let { m ->
            val rest = m.groupValues[1]
            if (relative.containsMatchIn(rest)) {
                TimeParser.duration(rest)?.let { return Command.AlarmIn(it) }
            }
            val c = TimeParser.clock(rest) ?: return Command.AlarmWhen
            return Command.Alarm(c.hour, c.minute)
        }
        timerRe.find(text)?.let { m ->
            val d = TimeParser.duration(m.groupValues[1]) ?: return Command.TimerHowLong
            return Command.Timer(d)
        }
        playRe.find(text)?.let { m ->
            val req = playRequest(m.groupValues[1])
            return if (req.query.isEmpty()) Command.PlayWhat else Command.Play(req)
        }
        return Command.Unknown(text)
    }

    data class CallTarget(val target: String, val kind: NumberKind?, val speaker: Boolean)

    /** Ripulisce "a mario rossi sul cellulare in vivavoce" -> ("mario rossi", MOBILE, vivavoce). */
    fun callTarget(raw: String): CallTarget {
        var t = clean(raw)
        var kind: NumberKind? = null
        var speaker = false
        speakerPrefix.find(t)?.let { m -> speaker = true; t = t.removeRange(m.range).trim() }
        // i suffissi possono comparire in qualunque ordine: "sul cellulare in vivavoce"
        var changed = true
        while (changed) {
            changed = false
            speakerSuffix.find(t)?.let { m -> speaker = true; t = t.removeRange(m.range).trim(); changed = true }
            kindSuffix.find(t)?.let { m ->
                kind = kindOf(listOf(m.groupValues[1]))
                t = t.removeRange(m.range).trim()
                changed = true
            }
        }
        t = stripLeading(t, callLead, callLeadApostrophe)
        return CallTarget(t, kind, speaker)
    }

    /** "il secondo in vivavoce", "sì col vivavoce" */
    fun mentionsSpeaker(raw: String): Boolean = speakerWords.containsMatchIn(clean(raw))

    /** Ripulisce "fino a via etnea cento venti" -> "via etnea 120". */
    fun destination(raw: String): String {
        val t = stripLeading(clean(raw), navLead, null)
        return ItalianNumbers.digitsInText(t)
    }

    /** Ripulisce "l'album nevermind da spotify" -> ("nevermind", ALBUM). */
    fun playRequest(raw: String): PlayRequest {
        var t = clean(raw)
        t = spotifyPrefix.replaceFirst(t, "")
        t = spotifySuffix.replace(t, "").trim()
        var focus = MediaFocus.ANY
        for ((f, re) in focusPrefixes) {
            val m = re.find(t) ?: continue
            focus = f
            t = t.removeRange(m.range).trim()
            break
        }
        return PlayRequest(t, focus)
    }

    fun kindOf(tokens: List<String>): NumberKind? = when {
        tokens.any { it == "cellulare" || it == "telefonino" || it == "mobile" } -> NumberKind.MOBILE
        tokens.any { it == "casa" || it == "fisso" } -> NumberKind.HOME
        tokens.any { it == "lavoro" || it == "ufficio" } -> NumberKind.WORK
        else -> null
    }

    private fun clean(raw: String): String {
        var t = raw.lowercase().replace('’', '\'').replace("[unk]", " ")
        t = punctuation.replace(t, " ").replace(Regex("\\s+"), " ").trim()
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
