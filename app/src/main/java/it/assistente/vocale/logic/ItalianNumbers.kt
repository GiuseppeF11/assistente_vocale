package it.assistente.vocale.logic

/**
 * Il riconoscitore offline restituisce i numeri in lettere ("tre tre tre", "centoventi").
 * Qui li riportiamo in cifre, per i numeri di telefono dettati e per i civici.
 */
object ItalianNumbers {

    private val units = mapOf(
        "zero" to 0, "uno" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11,
        "dodici" to 12, "tredici" to 13, "quattordici" to 14, "quindici" to 15, "sedici" to 16,
        "diciassette" to 17, "diciotto" to 18, "diciannove" to 19,
    )

    private val tens = listOf(
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50,
        "sessanta" to 60, "settanta" to 70, "ottanta" to 80, "novanta" to 90,
    )

    private val hundreds: List<Pair<String, Int>> =
        listOf("cento" to 100) + units.filterValues { it in 2..9 }.map { (w, v) -> (w + "cento") to v * 100 }

    /** "ventitré" -> 23, "centoventi" -> 120, "duemila" -> 2000, "12" -> 12; null se non è un numero. */
    fun wordToInt(raw: String): Int? {
        val w = Text.normalize(raw)
        if (w.isEmpty() || w.contains(' ')) return null
        if (w.all { it.isDigit() }) return w.toIntOrNull()
        return thousands(w)
    }

    private fun below100(s: String): Int? {
        units[s]?.let { return it }
        for ((t, v) in tens) {
            if (s == t) return v
            if (s.startsWith(t)) {
                val u = units[s.substring(t.length)]
                if (u != null && u in 1..9) return v + u
            }
            val stem = t.dropLast(1) // "ventuno", "ventotto"
            if (s.startsWith(stem)) {
                val u = units[s.substring(stem.length)]
                if (u == 1 || u == 8) return v + u
            }
        }
        return null
    }

    private fun below1000(s: String): Int? {
        below100(s)?.let { return it }
        for ((p, v) in hundreds) {
            if (s == p) return v
            if (s.startsWith(p)) below100(s.substring(p.length))?.let { return v + it }
            val stem = p.dropLast(1) // "centottanta", "centotto"
            if (s.startsWith(stem + "ott")) below100(s.substring(stem.length))?.let { return v + it }
        }
        return null
    }

    private fun thousands(s: String): Int? {
        below1000(s)?.let { return it }
        if (s.startsWith("mille")) {
            val rest = s.substring(5)
            return if (rest.isEmpty()) 1000 else below1000(rest)?.let { 1000 + it }
        }
        val i = s.indexOf("mila")
        if (i > 0) {
            val left = below1000(s.substring(0, i)) ?: return null
            if (left < 2) return null
            val rest = s.substring(i + 4)
            val right = if (rest.isEmpty()) 0 else below1000(rest) ?: return null
            return left * 1000 + right
        }
        return null
    }

    /**
     * Numero di telefono dettato: "tre tre tre dodici trentaquattro" -> "3331234".
     * Restituisce null se nel testo c'è anche solo una parola che non è un numero.
     */
    fun phoneDigits(text: String): String? {
        val tokens = Text.tokens(text)
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        // "+39 333…" scritto in cifre dal riconoscitore
        if (text.trimStart().startsWith("+")) sb.append('+')
        for ((i, t) in tokens.withIndex()) {
            if (i == 0 && t == "piu") { if (sb.isEmpty()) sb.append('+'); continue }
            if (t.all { it.isDigit() }) { sb.append(t); continue }
            val n = wordToInt(t) ?: return null
            sb.append(n)
        }
        return if (sb.count { it.isDigit() } >= 3) sb.toString() else null
    }

    /**
     * Sostituisce i numeri in lettere dentro una frase: "via etnea cento venti" -> "via etnea 120".
     * Lascia stare "mille" da solo dopo "dei" (Via dei Mille).
     */
    fun digitsInText(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var acc: Int? = null
        fun flush() { acc?.let { out.add(it.toString()) }; acc = null }
        for ((i, tok) in tokens.withIndex()) {
            val norm = Text.normalize(tok)
            val a = acc
            if (norm == "mila" && a != null && a in 2..999) { acc = a * 1000; continue }
            val keepWord = norm == "mille" && i > 0 && Text.normalize(tokens[i - 1]) == "dei"
            val n = if (keepWord) null else wordToInt(tok)
            if (n == null) { flush(); out.add(tok); continue }
            acc = when {
                a == null -> n
                a >= 1000 && a % 1000 == 0 && n < 1000 -> a + n
                a >= 100 && a % 100 == 0 && n < 100 -> a + n
                else -> { flush(); n }
            }
        }
        flush()
        return out.joinToString(" ")
    }
}
