package it.assistente.vocale.logic

import java.text.Normalizer
import java.util.Locale

/** Utilità di testo condivise da parser e ricerca contatti. Nessuna dipendenza Android. */
object Text {
    private val marks = Regex("\\p{Mn}+")
    private val nonAlnum = Regex("[^a-z0-9]+")

    /** minuscolo, senza accenti, solo lettere/cifre separate da uno spazio. */
    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ITALIAN)
        val noMarks = marks.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        return nonAlnum.replace(noMarks, " ").trim()
    }

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** Somiglianza 0..1 basata sulla distanza di Levenshtein. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // parole cortissime: solo corrispondenza esatta, altrimenti "di" ~ "da"
        if (a.length <= 2 || b.length <= 2) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
