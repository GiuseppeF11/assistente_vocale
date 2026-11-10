package it.assistente.vocale.logic

/** Orari e durate detti a voce, in cifre ("7:30", "5 minuti") o in lettere ("sette e mezza"). */
object TimeParser {

    data class Clock(val hour: Int, val minute: Int) {
        override fun toString() = "%d:%02d".format(hour, minute)
    }

    private fun num(t: String?): Int? = when (t) {
        null -> null
        "un", "una", "uno" -> 1
        else -> ItalianNumbers.wordToInt(t)
    }

    private val evening = setOf("sera", "stasera", "pomeriggio", "notte", "stanotte")

    /** "alle sette e mezza di sera" -> 19:30; null se non c'è un orario. */
    fun clock(text: String): Clock? {
        val t = Text.tokens(text)
        val i = when {
            "mezzogiorno" in t -> t.indexOf("mezzogiorno")
            "mezzanotte" in t -> t.indexOf("mezzanotte")
            // "metti una sveglia": "una" è l'articolo solo se non segue "all"/"l"
            else -> t.indices.firstOrNull { k ->
                val n = num(t[k])
                n != null && n in 0..24 && (t[k] !in setOf("un", "uno", "una") || t.getOrNull(k - 1) in setOf("all", "l", "alle", "le"))
            } ?: return null
        }
        var (c, _) = clockAt(t, i) ?: return null
        if (t.any { it in evening }) c = evening(c, night = t.any { it == "notte" || it == "stanotte" })
        return c
    }

    /** Pomeriggio/sera: 7 -> 19; di notte solo dalle 8 in poi (le 2 di notte restano 2). */
    fun evening(c: Clock, night: Boolean): Clock =
        if (c.hour in 1..11 && (!night || c.hour >= 8)) Clock(c.hour + 12, c.minute) else c

    /**
     * Orario che inizia al token [i] (l'ora, oppure "mezzogiorno"/"mezzanotte").
     * Restituisce l'orario (senza correzione mattina/sera) e l'indice del primo token dopo l'orario.
     */
    fun clockAt(t: List<String>, i: Int): Pair<Clock, Int>? {
        var h = when (t.getOrNull(i)) {
            "mezzogiorno" -> 12
            "mezzanotte" -> 0
            else -> num(t.getOrNull(i))?.takeIf { it in 0..24 } ?: return null
        }
        var m = 0
        var j = i + 1
        when {
            t.getOrNull(j) == "e" -> {
                when {
                    t.getOrNull(j + 1) in setOf("mezza", "mezzo") -> { m = 30; j += 2 }
                    t.getOrNull(j + 1) == "un" && t.getOrNull(j + 2) == "quarto" -> { m = 15; j += 3 }
                    t.getOrNull(j + 1) == "tre" && t.getOrNull(j + 2) == "quarti" -> { m = 45; j += 3 }
                    num(t.getOrNull(j + 1))?.let { it in 0..59 } == true -> { m = num(t[j + 1])!!; j += 2 }
                }
            }
            t.getOrNull(j) == "meno" -> {
                val back = when {
                    t.getOrNull(j + 1) == "un" && t.getOrNull(j + 2) == "quarto" -> 15.also { j += 3 }
                    num(t.getOrNull(j + 1)) != null -> num(t[j + 1])!!.also { j += 2 }
                    else -> 0
                }
                if (back in 1..59) { h = (h + 23) % 24; m = 60 - back }
            }
            num(t.getOrNull(j))?.let { it in 0..59 } == true && t.getOrNull(j) !in setOf("un", "una", "uno") -> {
                m = num(t[j])!!; j++
            }
        }
        // "quaranta cinque" detto staccato
        val unit = num(t.getOrNull(j))
        if (m >= 20 && m % 10 == 0 && unit != null && unit in 1..9 && t.getOrNull(j) !in setOf("un", "una", "uno")) {
            m += unit; j++
        }
        if (h == 24) h = 0
        if (h !in 0..23 || m !in 0..59) return null
        return Clock(h, m) to j
    }

    /** "un'ora e mezza" -> 5400 s; "5" -> 300 s (minuti); null se non c'è una durata. */
    fun duration(text: String): Int? {
        val t = Text.tokens(text)
        var total = 0
        var pending: Int? = null
        var lastUnit = 0
        var found = false
        fun unitOf(s: String?) = when (s) {
            "ora", "ore" -> 3600
            "minuto", "minuti", "min" -> 60
            "secondo", "secondi", "sec" -> 1
            else -> 0
        }
        var i = 0
        while (i < t.size) {
            val tok = t[i]
            val u = unitOf(tok)
            when {
                // "mezz'ora", "mezzo minuto"
                (tok == "mezz" || tok == "mezza" || tok == "mezzo") && unitOf(t.getOrNull(i + 1)) > 0 -> {
                    val unit = unitOf(t[i + 1])
                    total += unit / 2; lastUnit = unit; found = true; pending = null; i++
                }
                // "un quarto d'ora"
                tok == "quarto" && t.getOrNull(i + 1) == "d" && t.getOrNull(i + 2) == "ora" -> {
                    total += 900; lastUnit = 60; found = true; pending = null; i += 2
                }
                // "un'ora e mezza"
                (tok == "mezza" || tok == "mezzo") && lastUnit > 0 -> {
                    total += lastUnit / 2; found = true
                }
                u > 0 -> {
                    total += (pending ?: 1) * u; pending = null; lastUnit = u; found = true
                }
                num(tok) != null -> {
                    val n = num(tok)!!
                    val p = pending
                    pending = if (p != null && p >= 20 && p % 10 == 0 && n in 1..9) p + n else n
                }
            }
            i++
        }
        pending?.let { p ->
            // numero senza unità: dopo le ore sono minuti, dopo i minuti secondi, da solo minuti
            total += p * when (lastUnit) { 3600 -> 60; 60 -> 1; 1 -> 1; else -> 60 }
            found = true
        }
        return if (found && total > 0) total else null
    }

    /** 5400 -> "1 ora e 30 minuti" */
    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1) "1 ora" else "$h ore")
            if (m > 0) add(if (m == 1) "1 minuto" else "$m minuti")
            if (s > 0) add(if (s == 1) "1 secondo" else "$s secondi")
        }
        return when (parts.size) {
            0 -> "0 secondi"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        }
    }
}
