package it.assistente.vocale

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Stato condiviso tra il servizio (che ascolta e decide) e le schermate (che mostrano).
 * Volutamente minimale: niente librerie, solo un valore e dei listener sul thread principale.
 */
object AssistantBus {

    enum class Phase { HIDDEN, LISTENING, ASKING, WORKING, DONE, ERROR }

    data class UiState(
        val phase: Phase = Phase.HIDDEN,
        /** cosa sta facendo o chiedendo l'assistente */
        val message: String = "",
        /** quello che ha capito finora, aggiornato parola per parola */
        val transcript: String = "",
        /** scelte proposte quando serve un chiarimento */
        val options: List<String> = emptyList(),
        /** destinazione da aprire nel navigatore: la esegue la schermata, che può sbloccare il telefono */
        val navigateTo: String? = null,
    )

    interface Controller {
        fun cancel()
        fun choose(index: Int)
    }

    @Volatile var state: UiState = UiState()
        private set

    /** riga di stato del servizio, mostrata nella schermata principale */
    @Volatile var serviceStatus: String = "Ascolto disattivato"
        private set

    /** ultima cosa sentita dal rilevatore della frase di attivazione (per capire se ci sente) */
    @Volatile var wakeHeard: String = ""
        private set

    /** stato della voce italiana, mostrato nelle impostazioni */
    @Volatile var ttsStatus: String = ""
        private set

    /** motore che ha capito l'ultimo comando */
    @Volatile var asrStatus: String = ""
        private set

    @Volatile var controller: Controller? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()

    fun addListener(l: Runnable) { listeners.add(l) }
    fun removeListener(l: Runnable) { listeners.remove(l) }

    fun post(s: UiState) { state = s; fire() }
    fun hide() = post(UiState())
    fun status(text: String) { serviceStatus = text; fire() }
    fun heard(text: String) { if (text != wakeHeard) { wakeHeard = text; fire() } }
    fun tts(text: String) { ttsStatus = text; fire() }
    fun asr(text: String) { if (text != asrStatus) { asrStatus = text; fire() } }

    private fun fire() { main.post { listeners.forEach { it.run() } } }
}
