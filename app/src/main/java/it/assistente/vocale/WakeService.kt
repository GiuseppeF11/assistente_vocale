package it.assistente.vocale

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import it.assistente.vocale.AssistantBus.Phase
import it.assistente.vocale.AssistantBus.UiState
import it.assistente.vocale.logic.ChoiceParser
import it.assistente.vocale.logic.Command
import it.assistente.vocale.logic.CommandParser
import it.assistente.vocale.logic.Contact
import it.assistente.vocale.logic.ContactMatcher
import it.assistente.vocale.logic.ItalianNumbers
import it.assistente.vocale.logic.Match
import it.assistente.vocale.logic.NumberKind
import it.assistente.vocale.logic.PhoneNumber
import it.assistente.vocale.logic.Text
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

/**
 * Servizio in primo piano: tiene aperto il microfono, aspetta la parola di attivazione
 * e poi conduce il dialogo (ascolto -> comprensione -> azione oppure domanda).
 *
 * Un solo flusso audio alimenta due riconoscitori Vosk sullo stesso modello:
 *  - "wake": grammatica ristretta alla sola frase di attivazione (leggero, pochi falsi positivi)
 *  - "cmd":  dettatura libera, da cui arrivano i risultati parziali mostrati a schermo
 */
class WakeService : Service(), AssistantBus.Controller {

    companion object {
        /** Deve essere composta da parole presenti nel vocabolario del modello italiano. */
        const val WAKE_PHRASE = "ciao assistente"
        const val ACTION_STOP = "it.assistente.vocale.STOP"

        private const val RATE_HZ = 16000
        /** nessuna parola entro questo tempo: si chiude */
        private const val SILENCE_TIMEOUT_MS = 7000L
        /** durata massima di una singola frase */
        private const val MAX_UTTERANCE_MS = 15000L
        /** margine per annullare prima che parta la chiamata */
        private const val CALL_DELAY_MS = 1800L
        private const val MAX_MISUNDERSTANDINGS = 2

        @Volatile var running = false
            private set

        fun start(c: Context) = c.startForegroundService(Intent(c, WakeService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, WakeService::class.java)) }
    }

    private enum class Mode { WAKE, COMMAND, PAUSED }

    /** Cosa stiamo aspettando che l'utente dica. */
    private sealed interface Pending {
        data object Fresh : Pending
        data object WhoToCall : Pending
        data object WhereToGo : Pending
        data class PickContact(val candidates: List<Contact>, val kind: NumberKind?) : Pending
        data class PickNumber(val contact: Contact, val numbers: List<PhoneNumber>) : Pending
    }

    private val main = Handler(Looper.getMainLooper())

    // --- condivisi con il thread audio ---
    @Volatile private var alive = false
    @Volatile private var failed = false
    @Volatile private var mode = Mode.WAKE
    @Volatile private var resetRequested = false
    @Volatile private var muteUntil = 0L
    private var audioThread: Thread? = null

    // --- solo thread principale ---
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var utteranceSeq = 0
    private var afterSpeech: Pair<String, () -> Unit>? = null
    private var pending: Pending = Pending.Fresh
    private var misunderstandings = 0
    private var transcript = ""
    private var contacts: List<Contact> = emptyList()
    private val hideRunnable = Runnable { hide() }
    private var callRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // =============================================================================================
    // Ciclo di vita
    // =============================================================================================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            AssistantBus.status("Manca il permesso del microfono")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                Notifications.ID_SERVICE, Notifications.service(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android 14+: il microfono in background si può avviare solo con l'app visibile
            Log.e(TAG, "Avvio in primo piano rifiutato", e)
            AssistantBus.status("Apri l'app per attivare l'ascolto")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) startEngine()
        // NOT_STICKY: un riavvio automatico dal sistema non avrebbe comunque accesso al microfono
        return START_NOT_STICKY
    }

    private fun startEngine() {
        alive = true
        running = true
        AssistantBus.controller = this

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistente:ascolto")
            .apply { setReferenceCounted(false); acquire() }

        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: RuntimeException) { null }

        tts = TextToSpeech(this) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.language = Locale.ITALIAN
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { speechFinished(id) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { speechFinished(id) } }
                })
                ttsReady = true
            }
        }

        audioThread = Thread({ runEngine() }, "assistente-audio").apply { start() }
    }

    override fun onDestroy() {
        val wasListening = alive && !failed
        alive = false
        running = false
        main.removeCallbacksAndMessages(null)
        audioThread?.join(800)
        tts?.shutdown()
        tone?.release()
        wakeLock?.release()
        if (AssistantBus.controller === this) AssistantBus.controller = null
        Notifications.cancelWake(this)
        AssistantBus.hide()
        if (wasListening) AssistantBus.status("Ascolto disattivato")
        super.onDestroy()
    }

    // =============================================================================================
    // Thread audio
    // =============================================================================================

    private fun runEngine() {
        try {
            AssistantBus.status("Carico il modello vocale…")
            val path = ModelInstaller.ensure(this)
            val grammar = JSONArray(listOf(WAKE_PHRASE, "[unk]")).toString()
            Model(path).use { model ->
                Recognizer(model, RATE_HZ.toFloat(), grammar).use { wake ->
                    Recognizer(model, RATE_HZ.toFloat()).use { cmd ->
                        AssistantBus.status("In ascolto: di' «$WAKE_PHRASE»")
                        audioLoop(wake, cmd)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Motore vocale fermato", e)
            failed = true
            AssistantBus.status("Errore: ${e.message ?: e.javaClass.simpleName}")
            main.post { stopSelf() }
        }
    }

    @SuppressLint("MissingPermission") // verificato in onStartCommand
    private fun audioLoop(wake: Recognizer, cmd: Recognizer) {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE_HZ * 2),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microfono non disponibile" }
        rec.startRecording()

        val buf = ShortArray(RATE_HZ / 10) // 100 ms
        var listenStart = 0L
        var lastPartial = ""
        try {
            while (alive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { SystemClock.sleep(200); continue }
                val now = SystemClock.elapsedRealtime()
                if (resetRequested) {
                    resetRequested = false
                    wake.reset(); cmd.reset()
                    listenStart = now
                    lastPartial = ""
                }
                // non ascoltare il nostro stesso segnale acustico
                if (now < muteUntil) continue

                when (mode) {
                    Mode.PAUSED -> Unit

                    Mode.WAKE -> {
                        val heard = if (wake.acceptWaveForm(buf, n)) field(wake.result, "text")
                        else field(wake.partialResult, "partial")
                        if (heard.contains(WAKE_PHRASE)) {
                            wake.reset()
                            mode = Mode.PAUSED
                            main.post { onWake() }
                        }
                    }

                    Mode.COMMAND -> {
                        val elapsed = now - listenStart
                        if (cmd.acceptWaveForm(buf, n)) {
                            // Vosk ha sentito la pausa di fine frase
                            val text = field(cmd.result, "text")
                            if (text.isNotBlank()) {
                                mode = Mode.PAUSED
                                main.post { onFinal(text) }
                            } else if (elapsed > SILENCE_TIMEOUT_MS) {
                                mode = Mode.PAUSED
                                main.post { onSilence() }
                            }
                        } else {
                            val partial = field(cmd.partialResult, "partial")
                            if (partial != lastPartial) {
                                lastPartial = partial
                                main.post { onPartial(partial) }
                            }
                            if (partial.isBlank() && elapsed > SILENCE_TIMEOUT_MS) {
                                mode = Mode.PAUSED
                                main.post { onSilence() }
                            } else if (elapsed > MAX_UTTERANCE_MS) {
                                val text = field(cmd.finalResult, "text")
                                mode = Mode.PAUSED
                                main.post { if (text.isBlank()) onSilence() else onFinal(text) }
                            }
                        }
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) { }
            rec.release()
        }
    }

    private fun field(json: String, key: String): String = try {
        JSONObject(json).optString(key).replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
    } catch (e: Exception) { "" }

    // =============================================================================================
    // Dialogo (thread principale)
    // =============================================================================================

    private fun onWake() {
        if (!alive) return
        main.removeCallbacks(hideRunnable)
        cancelPendingCall()
        pending = Pending.Fresh
        misunderstandings = 0
        contacts = emptyList()
        AssistantBus.post(UiState(Phase.LISTENING, "Ti ascolto…"))
        showScreen()
        listen("Ti ascolto…", emptyList())
    }

    /** Vero solo mentre aspettiamo che l'utente parli: scarta eventi audio arrivati in ritardo. */
    private fun listening() = alive && AssistantBus.state.phase == Phase.LISTENING

    private fun onPartial(text: String) {
        if (!listening()) return
        transcript = text
        AssistantBus.post(AssistantBus.state.copy(transcript = text))
    }

    private fun onSilence() {
        if (!listening()) return
        finish("Non ho sentito nulla.", error = false, spoken = false)
    }

    private fun onFinal(text: String) {
        if (!listening()) return
        transcript = text
        AssistantBus.post(AssistantBus.state.copy(transcript = text))

        val cmd = CommandParser.parse(text)
        if (cmd is Command.Cancel) { finish("Annullato.", error = false); return }

        when (val p = pending) {
            Pending.Fresh -> when (cmd) {
                is Command.Call -> resolveCall(cmd.target, cmd.kind)
                Command.CallWho -> ask("Chi vuoi chiamare?", Pending.WhoToCall)
                is Command.Navigate -> navigate(cmd.destination)
                Command.NavigateWhere -> ask("Dove vuoi andare?", Pending.WhereToGo)
                else -> ask(
                    "Non ho capito. Puoi dire «chiama» e un nome, oppure «portami a» e un posto.",
                    Pending.Fresh, misunderstood = true,
                )
            }

            Pending.WhoToCall -> when (cmd) {
                is Command.Call -> resolveCall(cmd.target, cmd.kind)
                is Command.Navigate -> navigate(cmd.destination)
                else -> {
                    val (target, kind) = CommandParser.callTarget(text)
                    resolveCall(target, kind)
                }
            }

            Pending.WhereToGo -> when (cmd) {
                is Command.Navigate -> navigate(cmd.destination)
                is Command.Call -> resolveCall(cmd.target, cmd.kind)
                else -> navigate(CommandParser.destination(text))
            }

            is Pending.PickContact -> {
                val names = p.candidates.map { it.name }
                val i = ChoiceParser.pick(text, names)
                when {
                    i != null -> chooseNumber(p.candidates[i], p.kind)
                    cmd is Command.Call -> resolveCall(cmd.target, cmd.kind)
                    else -> ask("Non ho capito quale. ${orList(names)}?", p, names, misunderstood = true)
                }
            }

            is Pending.PickNumber -> {
                val labels = p.numbers.map { it.label }
                val byKind = ChoiceParser.kind(text)?.let { k -> p.numbers.filter { it.kind == k } }
                val i = ChoiceParser.pick(text, labels)
                when {
                    byKind?.size == 1 -> call(byKind[0].number, "${p.contact.name}, ${byKind[0].label}")
                    i != null -> call(p.numbers[i].number, "${p.contact.name}, ${p.numbers[i].label}")
                    else -> ask(numberQuestion(p.contact, p.numbers), p, numberOptions(p.numbers), misunderstood = true)
                }
            }
        }
    }

    // ---- chiamate ----

    private fun resolveCall(target: String, kind: NumberKind?) {
        if (target.isBlank()) { ask("Chi vuoi chiamare?", Pending.WhoToCall); return }

        // numero dettato a voce
        ItalianNumbers.phoneDigits(target)?.let { call(it, it.toList().joinToString(" ")); return }

        if (!granted(Manifest.permission.READ_CONTACTS)) {
            finish("Manca il permesso per leggere la rubrica.", error = true); return
        }
        if (contacts.isEmpty()) contacts = ContactsRepository.load(this)

        when (val m = ContactMatcher.find(target, contacts)) {
            Match.None -> ask(
                "Non trovo «$target» in rubrica. Chi vuoi chiamare?", Pending.WhoToCall, misunderstood = true,
            )
            is Match.One -> chooseNumber(m.contact, kind)
            is Match.Many -> {
                val names = m.contacts.map { it.name }
                ask("Ho trovato ${orList(names)}. Quale?", Pending.PickContact(m.contacts, kind), names)
            }
        }
    }

    private fun chooseNumber(contact: Contact, kind: NumberKind?) {
        val all = contact.numbers
        val ofKind = if (kind != null) all.filter { it.kind == kind } else emptyList()
        val primary = all.filter { it.primary }
        val chosen = when {
            all.size == 1 -> all[0]
            ofKind.size == 1 -> ofKind[0]
            kind == null && primary.size == 1 -> primary[0] // numero predefinito scelto in rubrica
            else -> null
        }
        if (chosen != null) {
            call(chosen.number, if (all.size == 1) contact.name else "${contact.name}, ${chosen.label}")
            return
        }
        val list = if (ofKind.size > 1) ofKind else all
        ask(numberQuestion(contact, list), Pending.PickNumber(contact, list), numberOptions(list))
    }

    private fun numberQuestion(contact: Contact, numbers: List<PhoneNumber>): String {
        val labels = numbers.map { it.label.lowercase() }
        return if (labels.distinct().size == labels.size) "${contact.name} ha più numeri: ${orList(labels)}?"
        else "${contact.name} ha più numeri. Dimmi il primo, il secondo, oppure toccane uno."
    }

    private fun numberOptions(numbers: List<PhoneNumber>) = numbers.map { "${it.label} · ${it.number}" }

    private fun call(number: String, spokenLabel: String) {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            finish("Manca il permesso per effettuare chiamate.", error = true); return
        }
        pending = Pending.Fresh
        mode = Mode.PAUSED
        AssistantBus.post(UiState(Phase.WORKING, "Chiamo $spokenLabel…", transcript))
        speak("Chiamo $spokenLabel")
        val r = Runnable {
            callRunnable = null
            if (Actions.call(this, number)) hide()
            else finish("Non sono riuscito ad avviare la chiamata.", error = true)
        }
        callRunnable = r
        main.postDelayed(r, CALL_DELAY_MS)
    }

    private fun cancelPendingCall() {
        callRunnable?.let { main.removeCallbacks(it) }
        callRunnable = null
    }

    // ---- navigazione ----

    private fun navigate(rawDestination: String) {
        if (rawDestination.isBlank()) { ask("Dove vuoi andare?", Pending.WhereToGo); return }
        var destination = rawDestination
        var shown = rawDestination
        when (Text.normalize(rawDestination)) {
            "casa", "casa mia" -> {
                destination = Prefs.home(this); shown = "casa"
                if (destination.isEmpty()) {
                    finish("Indirizzo di casa non impostato. Inseriscilo nell'app.", error = true); return
                }
            }
            "lavoro", "ufficio", "al lavoro", "in ufficio" -> {
                destination = Prefs.work(this); shown = "lavoro"
                if (destination.isEmpty()) {
                    finish("Indirizzo del lavoro non impostato. Inseriscilo nell'app.", error = true); return
                }
            }
        }
        // l'apertura del navigatore la fa la schermata, che può chiedere lo sblocco
        AssistantBus.post(UiState(Phase.WORKING, "Navigazione verso $shown", transcript, navigateTo = destination))
        speak("Avvio la navigazione verso $shown")
        backToWake()
    }

    // ---- chiarimenti, ascolto, chiusura ----

    private fun ask(
        message: String, next: Pending, options: List<String> = emptyList(), misunderstood: Boolean = false,
    ) {
        if (misunderstood) {
            if (++misunderstandings > MAX_MISUNDERSTANDINGS) {
                finish("Non sono riuscito a capire. Riprova.", error = true); return
            }
        } else {
            misunderstandings = 0
        }
        pending = next
        mode = Mode.PAUSED
        AssistantBus.post(UiState(Phase.ASKING, message, transcript, options))
        speak(message) { listen(message, options) }
    }

    /** Segnale acustico e via: da qui il thread audio alimenta la dettatura libera. */
    private fun listen(message: String, options: List<String>) {
        if (!alive) return
        transcript = ""
        AssistantBus.post(UiState(Phase.LISTENING, message, "", options))
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        muteUntil = SystemClock.elapsedRealtime() + 300
        resetRequested = true
        mode = Mode.COMMAND
    }

    private fun finish(message: String, error: Boolean, spoken: Boolean = true) {
        AssistantBus.post(UiState(if (error) Phase.ERROR else Phase.DONE, message, transcript))
        if (spoken) speak(message)
        backToWake()
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 2500)
    }

    private fun hide() {
        main.removeCallbacks(hideRunnable)
        Notifications.cancelWake(this)
        AssistantBus.hide()
        backToWake()
    }

    private fun backToWake() {
        pending = Pending.Fresh
        resetRequested = true
        mode = Mode.WAKE
    }

    private fun showScreen() {
        val intent = Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(intent); return } catch (e: Exception) { Log.w(TAG, "Schermata non avviata", e) }
        }
        Notifications.wake(this)
    }

    // ---- voce ----

    private fun speak(text: String, then: (() -> Unit)? = null) {
        val id = "u${++utteranceSeq}"
        afterSpeech = if (then != null) id to then else null
        val t = tts
        if (t == null || !ttsReady || t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            speechFinished(id)
            return
        }
        // rete di sicurezza se il motore vocale non richiama
        main.postDelayed({ speechFinished(id) }, 10_000)
    }

    private fun speechFinished(id: String?) {
        val waiting = afterSpeech ?: return
        if (waiting.first != id) return
        afterSpeech = null
        waiting.second()
    }

    // =============================================================================================
    // Comandi dalla schermata
    // =============================================================================================

    override fun cancel() {
        afterSpeech = null
        tts?.stop()
        cancelPendingCall()
        hide()
    }

    override fun choose(index: Int) {
        val p = pending
        mode = Mode.PAUSED
        afterSpeech = null
        tts?.stop()
        val contact = (p as? Pending.PickContact)?.candidates?.getOrNull(index)
        val number = (p as? Pending.PickNumber)?.numbers?.getOrNull(index)
        when {
            p is Pending.PickContact && contact != null -> chooseNumber(contact, p.kind)
            p is Pending.PickNumber && number != null -> call(number.number, "${p.contact.name}, ${number.label}")
            else -> hide()
        }
    }

    private fun orList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " o " + items.last()
    }
}
