package it.assistente.vocale.logic

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * "Aggiungi al calendario Cena con Marco per domani alle 17:00"
 *   -> titolo «Cena con Marco», domani, 17:00.
 * Lavora sulle parole originali, così il titolo mantiene maiuscole e accenti.
 */
object CalendarParser {

    data class EventSpec(
        val title: String,
        /** null = non detto (con l'ora: oggi o domani lo decide chi chiama) */
        val date: LocalDate?,
        /** null = tutto il giorno */
        val time: TimeParser.Clock?,
        /** fine, se detta ("dalle 19 alle 20:30") */
        val end: TimeParser.Clock? = null,
    )

    private val months = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )
    private val weekdays = mapOf(
        "lunedi" to DayOfWeek.MONDAY, "martedi" to DayOfWeek.TUESDAY, "mercoledi" to DayOfWeek.WEDNESDAY,
        "giovedi" to DayOfWeek.THURSDAY, "venerdi" to DayOfWeek.FRIDAY, "sabato" to DayOfWeek.SATURDAY,
        "domenica" to DayOfWeek.SUNDAY,
    )

    // parole del comando, da togliere dall'inizio
    private val triggerVerbs = setOf(
        "aggiungi", "aggiungimi", "metti", "mettimi", "segna", "segnami", "inserisci", "crea", "salva",
        "fissa", "fissami", "programma", "ricordami", "ricordamelo", "avvisami", "nuovo", "nuova",
        "per", "favore", "piacere", "ok", "ehi", "allora",
    )
    private val articles = setOf("un", "una", "uno", "l", "il", "lo", "la")
    private val eventNouns = setOf("evento", "appuntamento", "impegno", "promemoria")
    private val calendarNouns = setOf("calendario", "agenda")
    private val calendarPreps = setOf("al", "in", "nel", "sul", "sull", "nell", "all", "nella", "sulla", "a")

    /** parole di collegamento da togliere ai bordi del titolo */
    private val edges = setOf(
        "per", "il", "lo", "la", "l", "i", "gli", "le", "alle", "all", "ore", "di", "del", "della", "a", "e",
        "ad", "verso", "da", "dal", "dalla", "giorno", "che", "un", "una", "uno",
    )

    private const val NONE = -1

    fun parse(utterance: String, today: LocalDate): EventSpec {
        // parole originali e i loro token normalizzati ("all'una" -> all, una; "17:00" -> 17, 00)
        val words = utterance.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tokens = ArrayList<String>()
        val owner = ArrayList<Int>()
        words.forEachIndexed { w, word ->
            Text.tokens(word).forEach { tokens += it; owner += w }
        }
        val used = BooleanArray(tokens.size)
        fun use(from: Int, to: Int) { for (k in from until to) if (k in used.indices) used[k] = true }
        fun tok(k: Int) = tokens.getOrNull(k)
        fun num(k: Int): Int? = tok(k)?.let { if (it in setOf("un", "una", "uno")) 1 else ItalianNumbers.wordToInt(it) }

        // --- 1. comando all'inizio ("aggiungi al calendario", "crea un evento", "ricordami di") ---
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k]
            when {
                t in triggerVerbs -> use(k, k + 1)
                t in articles && tok(k + 1) in eventNouns -> use(k, k + 1)
                t in eventNouns -> use(k, k + 1)
                t in calendarPreps && tok(k + 1) in calendarNouns -> { use(k, k + 2); k++ }
                else -> break
            }
            k++
        }
        // "… al calendario" in mezzo alla frase
        for (i in tokens.indices) {
            if (tokens[i] in calendarNouns) {
                use(i, i + 1)
                if (i > 0 && tokens[i - 1] in calendarPreps) use(i - 1, i)
            }
        }

        // --- 2. data ---
        var date: LocalDate? = null
        var period: String? = null
        for (i in tokens.indices) {
            if (used[i] || date != null) continue
            val t = tokens[i]
            when {
                t == "oggi" -> { date = today; use(i, i + 1) }
                t == "stasera" -> { date = today; period = "sera"; use(i, i + 1) }
                t == "stamattina" || t == "stamani" -> { date = today; period = "mattina"; use(i, i + 1) }
                t == "domani" -> { date = today.plusDays(1); use(i, i + 1) }
                t == "domattina" -> { date = today.plusDays(1); period = "mattina"; use(i, i + 1) }
                t == "dopodomani" -> { date = today.plusDays(2); use(i, i + 1) }
                t in weekdays -> {
                    var d = today.plusDays(1)
                    while (d.dayOfWeek != weekdays[t]) d = d.plusDays(1)
                    if (tok(i + 1) in setOf("prossimo", "prossima")) {
                        // "lunedì prossimo" = il primo lunedì dopo oggi
                        use(i + 1, i + 2)
                    }
                    if (tok(i - 1) in setOf("questo", "questa")) use(i - 1, i)
                    date = d; use(i, i + 1)
                }
                (t == "tra" || t == "fra") && num(i + 1) != null &&
                    tok(i + 2) in setOf("giorno", "giorni", "settimana", "settimane") -> {
                    val n = num(i + 1)!!.toLong()
                    date = if (tok(i + 2)!!.startsWith("settiman")) today.plusWeeks(n) else today.plusDays(n)
                    use(i, i + 3)
                }
                // "il 15 novembre (2027)", "15/11"
                num(i)?.let { it in 1..31 } == true && (tok(i + 1) in months || (num(i + 1)?.let { it in 1..12 } == true && owner[i] == owner.getOrElse(i + 1) { NONE })) -> {
                    val day = num(i)!!
                    val month = tok(i + 1)?.let { months.indexOf(it) + 1 }?.takeIf { it > 0 } ?: num(i + 1)!!
                    var end = i + 2
                    var year: Int? = num(end)?.takeIf { it in 2000..2100 || (it in 0..99 && owner.getOrElse(end) { NONE } == owner[i]) }
                    if (year != null) { if (year < 100) year += 2000; end++ }
                    val candidate = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull()
                    if (candidate != null) {
                        date = if (year == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
                        use(i, end)
                        if (tok(i - 1) in setOf("il", "l", "giorno")) use(i - 1, i)
                    }
                }
                // "il 20" (giorno di questo mese, o del prossimo se è passato)
                t in setOf("il", "giorno") && num(i + 1)?.let { it in 1..31 } == true &&
                    tok(i + 2) !in months && owner.getOrElse(i + 2) { NONE } != owner[i + 1] &&
                    tok(i - 1) !in setOf("alle", "all", "ore") -> {
                    val day = num(i + 1)!!
                    var d = runCatching { today.withDayOfMonth(day) }.getOrNull()
                    if (d == null || d.isBefore(today)) d = runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull()
                    if (d != null) { date = d; use(i, i + 2) }
                }
            }
        }

        // --- 3. ora: "alle 17", "dalle 19 alle 20 e 30", "a mezzogiorno" ---
        var start: TimeParser.Clock? = null
        var end: TimeParser.Clock? = null
        var fromSeen = false
        for (i in tokens.indices) {
            if (used[i]) continue
            val t = tokens[i]
            val hourAt = when {
                t in setOf("alle", "all", "ore", "dalle", "dall") -> i + 1
                t == "le" && num(i + 1) != null -> i + 1
                t == "a" && tok(i + 1) in setOf("mezzogiorno", "mezzanotte") -> i + 1
                t in setOf("mezzogiorno", "mezzanotte") -> i
                else -> NONE
            }
            if (hourAt == NONE) continue
            val (clock, after) = TimeParser.clockAt(tokens, hourAt) ?: continue
            use(i, after)
            when {
                t in setOf("dalle", "dall") -> { start = clock; fromSeen = true }
                fromSeen && end == null && t in setOf("alle", "all") -> end = clock
                start == null -> start = clock
            }
        }

        // --- 4. mattina / pomeriggio / sera ---
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t in setOf("sera", "pomeriggio", "notte", "mattina", "mattino")) {
                period = t
                use(i, i + 1)
                if (tok(i - 1) in setOf("di", "del", "della", "nel", "nella", "in")) use(i - 1, i)
            }
        }
        fun adjust(c: TimeParser.Clock?): TimeParser.Clock? = when {
            c == null -> null
            period in setOf("sera", "pomeriggio") -> TimeParser.evening(c, night = false)
            period == "notte" -> TimeParser.evening(c, night = true)
            period in setOf("mattina", "mattino") -> c
            // in agenda «all'una» o «alle 5» senza altro sono quasi sempre del pomeriggio
            c.hour in 1..7 -> TimeParser.Clock(c.hour + 12, c.minute)
            else -> c
        }
        start = adjust(start)
        end = adjust(end)?.takeIf { e -> start != null && (e.hour * 60 + e.minute) > (start!!.hour * 60 + start!!.minute) }

        // --- 5. titolo: le parole rimaste, senza collegamenti ai bordi ---
        val usedWords = owner.indices.filter { used[it] }.map { owner[it] }.toSet()
        val left = words.indices.filter { it !in usedWords }.map { words[it] }.toMutableList()
        fun edge(w: String) = Text.tokens(w).let { it.isEmpty() || it.all { x -> x in edges } }
        while (left.isNotEmpty() && edge(left.first())) left.removeAt(0)
        while (left.isNotEmpty() && edge(left.last())) left.removeAt(left.lastIndex)
        val title = left.joinToString(" ") { it.trim('"', '«', '»', '“', '”', ',', '.', '!', '?', ':', ';') }
            .replace(Regex("\\s+"), " ").trim()
            .replaceFirstChar { it.uppercase() }

        return EventSpec(title, date, start, end)
    }

    /** "oggi", "domani", "dopodomani", "lunedì 16 novembre" (+ anno se diverso). */
    fun describe(date: LocalDate, today: LocalDate): String {
        val days = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
        return when (date) {
            today -> "oggi"
            today.plusDays(1) -> "domani"
            today.plusDays(2) -> "dopodomani"
            else -> buildString {
                append(days[date.dayOfWeek.value - 1]).append(' ').append(date.dayOfMonth).append(' ')
                append(months[date.monthValue - 1])
                if (date.year != today.year) append(' ').append(date.year)
            }
        }
    }
}
