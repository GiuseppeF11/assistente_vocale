package it.assistente.vocale.logic

import kotlin.math.log10

/**
 * Rilevatore di voce leggerissimo (energia del segnale con soglia che si adatta al rumore di fondo).
 * Serve a tenere spento il riconoscitore vocale quando nessuno parla: è lui che consuma batteria.
 *
 * - il rumore di fondo viene stimato di continuo: scende in fretta, sale piano (≈1 dB al secondo),
 *   così una TV o un motore costanti diventano "fondo" e smettono di tenere acceso il riconoscitore;
 * - "voce" = alcuni frammenti consecutivi sopra il fondo di [Sensitivity.marginDb];
 * - dopo l'ultima voce resta attivo ancora un po' (hangover), per non tagliare le pause tra le parole.
 */
class VoiceDetector(
    private val sensitivity: Sensitivity,
    private val sampleRate: Int = 16000,
) {
    enum class Sensitivity(val marginDb: Double) {
        /** si accende anche per voci lontane o basse (consuma di più) */
        HIGH(6.0),
        MEDIUM(9.0),
        /** solo voce vicina e chiara (consuma di meno) */
        LOW(12.0),
    }

    private val frame = sampleRate / 50 // 20 ms
    private val startFrames = 3          // 60 ms di voce per accendersi
    private val hangoverFrames = 50      // resta acceso 1 s dopo l'ultima voce
    private val absoluteMinDb = 25.0     // sotto è silenzio digitale

    private var floorDb = Double.NaN
    private var speechRun = 0
    private var hangover = 0

    var active = false
        private set

    fun reset() {
        speechRun = 0; hangover = 0; active = false
    }

    /** Analizza [n] campioni e dice se in questo momento c'è voce (o si è nella coda di una frase). */
    fun process(samples: ShortArray, n: Int): Boolean {
        var start = 0
        while (start + frame <= n) {
            step(energyDb(samples, start, frame))
            start += frame
        }
        if (n - start >= frame / 2) step(energyDb(samples, start, n - start))
        return active
    }

    private fun energyDb(s: ShortArray, from: Int, len: Int): Double {
        var sum = 0.0
        for (i in from until from + len) { val v = s[i].toDouble(); sum += v * v }
        return 10 * log10(sum / len + 1.0)
    }

    private fun step(db: Double) {
        if (floorDb.isNaN()) floorDb = db
        val speech = db > absoluteMinDb && db > floorDb + sensitivity.marginDb
        // fondo: scende subito verso i momenti di quiete, sale lentamente
        floorDb = if (db < floorDb) floorDb * 0.8 + db * 0.2 else floorDb + 0.02

        speechRun = if (speech) speechRun + 1 else 0
        if (speechRun >= startFrames) { active = true; hangover = hangoverFrames }
        else if (active && !speech && --hangover <= 0) active = false
        else if (active && speech) hangover = hangoverFrames
    }
}
