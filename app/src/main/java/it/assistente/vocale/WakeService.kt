package it.assistente.vocale

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
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
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
import it.assistente.vocale.logic.WakeMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

/**
 * Servizio in primo piano: tiene aperto il microfono, aspetta la frase di attivazione
 * e poi conduce il dialogo (ascolto -> comprensione -> azione oppure domanda).
 *
 * Frase di attivazione: sempre Vosk, con una grammatica ristretta alle sole parole della frase.
 * Comandi: il riconoscitore del telefono (Google, sul dispositivo quando possibile), molto più
 * preciso; se manca o fallisce si ripiega su Vosk in dettatura libera.
 */
class WakeService : Service(), AssistantBus.Controller {

    companion object {
        const val ACTION_STOP = "it.assistente.vocale.STOP"
        const val ACTION_RELOAD = "it.assistente.vocale.RELOAD"

        private const val RATE_HZ = 16000
        /** nessuna parola entro questo tempo: si chiude */
        private const val SILENCE_TIMEOUT_MS = 7000L
        /** durata massima di una singola frase */
        private const val MAX_UTTERANCE_MS = 15000L
        /** margine per annullare prima che parta la chiamata */
        private const val CALL_DELAY_MS = 1800L
        private const val MAX_MISUNDERSTANDINGS = 2
        /** il riconoscitore di sistema non ha risposto: si abbandona */
        private const val SYSTEM_ASR_WATCHDOG_MS = 20000L
        /** audio tenuto in memoria per la verifica della frase di attivazione */
        private const val RING_SECONDS = 3
        /** dopo un parziale con la frase, aspetta che la parola finisca prima di verificare */
        private const val VERIFY_AFTER_PARTIAL_MS = 400L

        @Volatile var running = false
            private set

        fun start(c: Context) = c.startForegroundService(Intent(c, WakeService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, WakeService::class.java)) }

        /** Applica le impostazioni cambiate (frase di attivazione) senza fermare l'ascolto. */
        fun reload(c: Context) {
            if (running) c.startService(Intent(c, WakeService::class.java).setAction(ACTION_RELOAD))
        }
    }

    /** EXTERNAL: il microfono è stato ceduto al riconoscitore di sistema. */
    private enum class Mode { WAKE, COMMAND, PAUSED, EXTERNAL }

    /** In ordine di preferenza; si scende quando un livello non funziona su questo telefono. */
    private enum class Asr(val label: String) {
        ON_DEVICE("riconoscimento del telefono (offline)"),
        SYSTEM("riconoscimento Google"),
        VOSK("Vosk"),
    }

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
    @Volatile private var micReleased = false
    @Volatile private var resetRequested = false
    @Volatile private var muteUntil = 0L
    @Volatile private var wakePhrase = Prefs.DEFAULT_WAKE
    @Volatile private var wakeDirty = false
    @Volatile private var contacts: List<Contact> = emptyList()
    private var audioThread: Thread? = null

    // --- solo thread audio ---
    private var wakeRec: Recognizer? = null
    private val ring = AudioRing(RATE_HZ * RING_SECONDS)

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
    private var speakerRequested = false
    private val hideRunnable = Runnable { hide() }
    private var callRunnable: Runnable? = null

    private var asrLevel = Asr.ON_DEVICE
    private var biasingOk = true
    private var systemRecognizer: SpeechRecognizer? = null
    /** cambia a ogni nuovo ascolto: scarta le risposte tardive di un riconoscitore precedente */
    private var listenSeq = 0
    private val overlay by lazy { AssistantOverlay(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    // =============================================================================================
    // Ciclo di vita
    // =============================================================================================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (alive) {
                    applySettings()
                    return START_NOT_STICKY
                }
            }
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

    private fun applySettings() {
        val phrase = Prefs.wakePhrase(this)
        if (phrase != wakePhrase) {
            wakePhrase = phrase
            wakeDirty = true
        }
        asrLevel = Asr.ON_DEVICE
        Notifications.refreshService(this)
        AssistantBus.status("In ascolto: di' «$wakePhrase»")
    }

    private fun startEngine() {
        alive = true
        running = true
        failed = false
        wakePhrase = Prefs.wakePhrase(this)
        AssistantBus.controller = this

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistente:ascolto")
            .apply { setReferenceCounted(false); acquire() }

        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: RuntimeException) { null }
        initTts()
        refreshContacts()

        audioThread = Thread({ runEngine() }, "assistente-audio").apply { start() }
    }

    override fun onDestroy() {
        val wasListening = alive && !failed
        alive = false
        running = false
        main.removeCallbacksAndMessages(null)
        destroySystemRecognizer()
        overlay.remove()
        audioThread?.join(800)
        tts?.shutdown()
        tone?.release()
        wakeLock?.release()
        if (AssistantBus.controller === this) AssistantBus.controller = null
        Notifications.cancelWake(this)
        AssistantBus.hide()
        AssistantBus.heard("")
        if (wasListening) AssistantBus.status("Ascolto disattivato")
        super.onDestroy()
    }

    private fun refreshContacts() {
        if (!granted(Manifest.permission.READ_CONTACTS)) return
        Thread({
            try { contacts = ContactsRepository.load(this) } catch (e: Exception) { Log.w(TAG, "Rubrica", e) }
        }, "assistente-rubrica").start()
    }

    // =============================================================================================
    // Voce
    // =============================================================================================

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                AssistantBus.tts("Sintesi vocale non disponibile")
                return@TextToSpeech
            }
            val lang = t.setLanguage(Locale.ITALY)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                // meglio muto che una voce inglese che storpia l'italiano
                AssistantBus.tts("Voce italiana non installata: le domande appariranno solo a schermo")
                return@TextToSpeech
            }
            val voice = bestItalianVoice(t)
            if (voice != null) t.voice = voice
            t.setSpeechRate(1.05f)
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
            val name = t.defaultEngine?.substringAfterLast('.') ?: ""
            AssistantBus.tts("Voce italiana pronta" + if (name.isNotEmpty()) " ($name)" else "")
        }
    }

    /** La voce italiana installata di qualità più alta, senza bisogno di internet. */
    private fun bestItalianVoice(t: TextToSpeech): Voice? = try {
        t.voices.orEmpty()
            .filter { it.locale.language == "it" }
            .filter { !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxWithOrNull(compareBy<Voice>({ it.locale.country == "IT" }, { it.quality }, { -it.latency }))
    } catch (e: Exception) { null }

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

    private fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    // =============================================================================================
    // Thread audio (Vosk)
    // =============================================================================================

    private fun runEngine() {
        try {
            AssistantBus.status("Carico il modello vocale…")
            val path = ModelInstaller.ensure(this)
            Model(path).use { model ->
                Recognizer(model, RATE_HZ.toFloat()).use { cmd ->
                    // più ipotesi per frase: si sceglie quella che corrisponde a un contatto o a un comando
                    cmd.setMaxAlternatives(3)
                    wakeRec = newWakeRecognizer(model)
                    AssistantBus.status("In ascolto: di' «$wakePhrase»")
                    try {
                        audioLoop(model, cmd)
                    } finally {
                        wakeRec?.close()
                        wakeRec = null
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

    /**
     * Grammatica: la frase intera, ogni sua parola da sola e "[unk]".
     * Le parole singole servono a non forzare "ciao" detto da solo dentro "ciao assistente".
     */
    private fun newWakeRecognizer(model: Model): Recognizer {
        val phrase = wakePhrase
        val words = phrase.split(' ').filter { it.isNotEmpty() }.distinct()
        val options = (listOf(phrase) + if (words.size > 1) words else emptyList()) + "[unk]"
        return Recognizer(model, RATE_HZ.toFloat(), JSONArray(options).toString())
    }

    @SuppressLint("MissingPermission") // verificato in onStartCommand
    private fun openMic(): Pair<AudioRecord, AutomaticGainControl?> {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        repeat(15) {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE_HZ * 6),
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                // alza la voce quando il telefono è lontano (sul cruscotto, sul tavolo)
                val agc = if (AutomaticGainControl.isAvailable()) {
                    try { AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
                } else null
                return rec to agc
            }
            rec.release()
            // il riconoscitore di sistema potrebbe non aver ancora rilasciato il microfono
            SystemClock.sleep(300)
        }
        throw IllegalStateException("Microfono non disponibile")
    }

    private fun audioLoop(model: Model, cmd: Recognizer) {
        while (alive) {
            if (mode == Mode.EXTERNAL) {
                micReleased = true
                SystemClock.sleep(50)
                continue
            }
            val (rec, agc) = openMic()
            micReleased = false
            try {
                rec.startRecording()
                capture(rec, model, cmd)
            } finally {
                try { rec.stop() } catch (_: IllegalStateException) { }
                agc?.release()
                rec.release()
            }
        }
    }

    /** Legge il microfono finché non lo si deve cedere al riconoscitore di sistema. */
    private fun capture(rec: AudioRecord, model: Model, cmd: Recognizer) {
        val buf = ShortArray(RATE_HZ / 10) // 100 ms
        var listenStart = SystemClock.elapsedRealtime()
        var lastPartial = ""
        var verifyAt = 0L
        resetRequested = true
        while (alive && mode != Mode.EXTERNAL) {
            if (wakeDirty) {
                wakeDirty = false
                wakeRec?.close()
                wakeRec = newWakeRecognizer(model)
            }
            val wake = wakeRec ?: return
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) { SystemClock.sleep(200); continue }
            val now = SystemClock.elapsedRealtime()
            if (resetRequested) {
                resetRequested = false
                wake.reset(); cmd.reset()
                ring.clear()
                verifyAt = 0L
                listenStart = now
                lastPartial = ""
            }
            // non ascoltare il nostro stesso segnale acustico
            if (now < muteUntil) continue

            when (mode) {
                Mode.PAUSED, Mode.EXTERNAL -> Unit

                Mode.WAKE -> {
                    ring.write(buf, n)
                    val final = wake.acceptWaveForm(buf, n)
                    val heard = Text.spoken(if (final) field(wake.result, "text") else field(wake.partialResult, "partial"))
                    if (heard.isNotEmpty() && !final) AssistantBus.heard(heard)

                    // 1° livello: il rilevatore a grammatica crede di aver sentito la frase
                    val candidate = heard.contains(wakePhrase)
                    if (candidate) {
                        if (final) verifyAt = now
                        else if (verifyAt == 0L) verifyAt = now + VERIFY_AFTER_PARTIAL_MS
                    } else if (final) {
                        verifyAt = 0L
                    }

                    // 2° livello: lo stesso audio riascoltato con tutto il vocabolario
                    if (verifyAt != 0L && now >= verifyAt) {
                        verifyAt = 0L
                        wake.reset()
                        val texts = verify(cmd)
                        if (texts.any { WakeMatcher.matches(wakePhrase, it) }) {
                            ring.clear()
                            AssistantBus.heard(wakePhrase)
                            mode = Mode.PAUSED
                            main.post { onWake() }
                        } else {
                            val what = texts.firstOrNull() ?: "nulla"
                            AssistantBus.heard("$what → non è «$wakePhrase», ignoro")
                        }
                    }
                }

                Mode.COMMAND -> {
                    val elapsed = now - listenStart
                    if (cmd.acceptWaveForm(buf, n)) {
                        // Vosk ha sentito la pausa di fine frase
                        val texts = alternatives(cmd.result)
                        if (texts.isNotEmpty()) {
                            mode = Mode.PAUSED
                            main.post { onFinal(texts, Asr.VOSK) }
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
                            val texts = alternatives(cmd.finalResult)
                            mode = Mode.PAUSED
                            main.post { if (texts.isEmpty()) onSilence() else onFinal(texts, Asr.VOSK) }
                        }
                    }
                }
            }
        }
    }

    /** Riascolta l'audio recente in dettatura libera e restituisce le ipotesi. */
    private fun verify(cmd: Recognizer): List<String> {
        cmd.reset()
        val texts = ArrayList<String>()
        ring.forEachChunk(RATE_HZ / 10) { chunk, len ->
            if (cmd.acceptWaveForm(chunk, len)) texts += alternatives(cmd.result)
        }
        texts += alternatives(cmd.finalResult)
        cmd.reset()
        return texts.distinct()
    }

    private fun field(json: String, key: String): String = try {
        clean(JSONObject(json).optString(key))
    } catch (e: Exception) { "" }

    /** Risultato Vosk con più ipotesi: {"alternatives":[{"text":…},…]} oppure {"text":…}. */
    private fun alternatives(json: String): List<String> = try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("alternatives")
        val list = if (arr != null) (0 until arr.length()).map { clean(arr.getJSONObject(it).optString("text")) }
        else listOf(clean(o.optString("text")))
        list.filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) { emptyList() }

    private fun clean(s: String) = s.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()

    // =============================================================================================
    // Ascolto del comando
    // =============================================================================================

    /** Segnale acustico, poi ascolta con il miglior motore disponibile. */
    private fun listen(message: String, options: List<String>) {
        if (!alive) return
        transcript = ""
        listenSeq++
        AssistantBus.post(UiState(Phase.LISTENING, message, "", options))
        listenWith(chooseAsr(), listenSeq)
    }

    private fun chooseAsr(): Asr {
        if (!Prefs.systemAsr(this)) return Asr.VOSK
        if (asrLevel == Asr.ON_DEVICE &&
            !(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
        ) asrLevel = Asr.SYSTEM
        if (asrLevel == Asr.SYSTEM && !SpeechRecognizer.isRecognitionAvailable(this)) asrLevel = Asr.VOSK
        return asrLevel
    }

    private fun listenWith(asr: Asr, seq: Int) {
        if (!alive || seq != listenSeq) return
        if (asr == Asr.VOSK) {
            beep()
            muteUntil = SystemClock.elapsedRealtime() + 300
            resetRequested = true
            mode = Mode.COMMAND
            return
        }
        // cede il microfono e aspetta che il thread audio lo abbia davvero chiuso
        micReleased = false
        mode = Mode.EXTERNAL
        val deadline = SystemClock.elapsedRealtime() + 1500
        val waitMic = object : Runnable {
            override fun run() {
                if (!alive || seq != listenSeq) return
                if (!micReleased && SystemClock.elapsedRealtime() < deadline) {
                    main.postDelayed(this, 40); return
                }
                beep()
                // il segnale non deve finire nella registrazione
                main.postDelayed({ startSystemRecognizer(asr, seq) }, 220)
            }
        }
        main.post(waitMic)
    }

    private fun startSystemRecognizer(asr: Asr, seq: Int) {
        if (!alive || seq != listenSeq) return
        destroySystemRecognizer()
        val sr = try {
            if (asr == Asr.ON_DEVICE && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.w(TAG, "Riconoscitore di sistema non creato", e)
            null
        }
        if (sr == null) { degrade(asr, persist = true, seq); return }
        systemRecognizer = sr
        val usedBiasing = biasingOk && Build.VERSION.SDK_INT >= 33

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (seq != listenSeq) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (seq != listenSeq) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                destroySystemRecognizer()
                mode = Mode.PAUSED
                if (texts.isEmpty()) onSilence() else onFinal(texts, asr)
            }

            override fun onError(error: Int) {
                if (seq != listenSeq) return
                destroySystemRecognizer()
                Log.i(TAG, "Riconoscitore ${asr.name}: errore $error")
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {
                        mode = Mode.PAUSED
                        // a volte arriva "nessuna corrispondenza" dopo un parziale sensato: lo usiamo
                        if (transcript.isNotBlank()) onFinal(listOf(transcript), asr) else onSilence()
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> degrade(asr, persist = false, seq)
                    else -> {
                        if (usedBiasing && asr == Asr.ON_DEVICE) {
                            // forse non accetta i suggerimenti (nomi della rubrica): riprova senza
                            biasingOk = false
                            listenWith(asr, seq)
                        } else {
                            degrade(asr, persist = true, seq)
                        }
                    }
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (usedBiasing) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasingStrings())
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening", e)
            destroySystemRecognizer()
            degrade(asr, persist = true, seq)
            return
        }
        main.postDelayed({
            if (seq == listenSeq && systemRecognizer === sr) {
                destroySystemRecognizer()
                degrade(asr, persist = false, seq)
            }
        }, SYSTEM_ASR_WATCHDOG_MS)
    }

    /** Il motore non va: si passa al successivo, solo per questa volta o per tutta la sessione. */
    private fun degrade(asr: Asr, persist: Boolean, seq: Int) {
        val next = if (asr == Asr.ON_DEVICE) Asr.SYSTEM else Asr.VOSK
        if (persist && next.ordinal > asrLevel.ordinal) asrLevel = next
        val actual = if (next == Asr.SYSTEM && !SpeechRecognizer.isRecognitionAvailable(this)) Asr.VOSK else next
        listenWith(actual, seq)
    }

    private fun destroySystemRecognizer() {
        systemRecognizer?.let { try { it.destroy() } catch (_: Exception) { } }
        systemRecognizer = null
    }

    /** Parole da favorire: i comandi e i nomi in rubrica. */
    private fun biasingStrings(): ArrayList<String> {
        val list = arrayListOf(
            "chiama", "telefona", "portami", "naviga", "vivavoce", "cellulare", "casa", "lavoro", "annulla",
        )
        contacts.asSequence().map { it.name }.take(150).forEach { list.add(it) }
        return list
    }

    // =============================================================================================
    // Dialogo (thread principale)
    // =============================================================================================

    /** Vero solo mentre aspettiamo che l'utente parli: scarta eventi audio arrivati in ritardo. */
    private fun listening() = alive && AssistantBus.state.phase == Phase.LISTENING

    private fun onWake() {
        if (!alive) return
        main.removeCallbacks(hideRunnable)
        cancelPendingCall()
        pending = Pending.Fresh
        misunderstandings = 0
        speakerRequested = false
        refreshContacts()
        AssistantBus.post(UiState(Phase.LISTENING, "Ti ascolto…"))
        showScreen()
        listen("Ti ascolto…", emptyList())
    }

    private fun onPartial(text: String) {
        if (!listening()) return
        transcript = text
        AssistantBus.post(AssistantBus.state.copy(transcript = text))
    }

    private fun onSilence() {
        if (!listening()) return
        finish("Non ho sentito nulla.", error = false, spoken = false)
    }

    private fun onFinal(texts: List<String>, asr: Asr) {
        if (!listening()) return
        AssistantBus.asr("Ultimo comando capito con: ${asr.label}")
        if (texts.any { CommandParser.mentionsSpeaker(it) }) speakerRequested = true
        val text = pickAlternative(texts)
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
                    val t = CommandParser.callTarget(text)
                    resolveCall(t.target, t.kind)
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

    /**
     * Il riconoscitore propone più ipotesi ("chiama mario rossi" / "chiama mario russo"):
     * si sceglie quella che ha più senso in questo punto del dialogo.
     */
    private fun pickAlternative(texts: List<String>): String {
        if (texts.size == 1) return texts[0]
        fun best(score: (String) -> Int): String =
            texts.withIndex().maxWith(compareBy<IndexedValue<String>>({ score(it.value) }, { -it.index })).value
        return when (val p = pending) {
            Pending.Fresh -> best { t ->
                when (val c = CommandParser.parse(t)) {
                    Command.Cancel -> 4
                    is Command.Call -> 1 + contactScore(c.target)
                    is Command.Navigate -> 2
                    Command.CallWho, Command.NavigateWhere -> 1
                    else -> 0
                }
            }
            Pending.WhoToCall -> best { contactScore(CommandParser.callTarget(it).target) }
            is Pending.PickContact -> best { if (ChoiceParser.pick(it, p.candidates.map { c -> c.name }) != null) 1 else 0 }
            is Pending.PickNumber -> best {
                if (ChoiceParser.kind(it) != null || ChoiceParser.pick(it, p.numbers.map { n -> n.label }) != null) 1 else 0
            }
            Pending.WhereToGo -> texts[0]
        }
    }

    private fun contactScore(target: String): Int {
        if (target.isBlank()) return 0
        if (ItalianNumbers.phoneDigits(target) != null) return 2
        return when (ContactMatcher.find(target, contacts)) {
            is Match.One -> 2
            is Match.Many -> 1
            Match.None -> 0
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
        val speaker = Prefs.speaker(this) || speakerRequested
        val label = if (speaker) "$spokenLabel in vivavoce" else spokenLabel
        pending = Pending.Fresh
        mode = Mode.PAUSED
        AssistantBus.post(UiState(Phase.WORKING, "Chiamo $label…", transcript))
        speak("Chiamo $label")
        val r = Runnable {
            callRunnable = null
            if (Actions.call(this, number, speaker)) hide()
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
        speak("Avvio la navigazione verso $shown")
        if (!isLocked()) {
            // sbloccato: Maps si apre subito, il popup sparisce da solo
            AssistantBus.post(UiState(Phase.WORKING, "Navigazione verso $shown", transcript))
            backToWake()
            if (Actions.navigate(this, destination)) {
                main.removeCallbacks(hideRunnable)
                main.postDelayed(hideRunnable, 1200)
            } else {
                finish("Nessuna app di navigazione trovata.", error = true)
            }
            return
        }
        // bloccato: ci pensa la schermata, che chiede lo sblocco prima di aprire Maps
        AssistantBus.post(UiState(Phase.WORKING, "Navigazione verso $shown", transcript, navigateTo = destination))
        backToWake()
    }

    // ---- chiarimenti, chiusura ----

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
        listenSeq++
        destroySystemRecognizer()
        pending = Pending.Fresh
        resetRequested = true
        mode = Mode.WAKE
    }

    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    /**
     * Sbloccato: un popup sopra l'app in uso, senza aprire niente.
     * Bloccato: Android non mostra popup sopra il blocco schermo, quindi lo stesso popup
     * dentro una schermata trasparente che accende il display.
     */
    private fun showScreen() {
        if (Settings.canDrawOverlays(this)) {
            if (!isLocked() && overlay.show()) return
            val intent = Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return } catch (e: Exception) { Log.w(TAG, "Schermata non avviata", e) }
        }
        Notifications.wake(this)
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
        listenSeq++
        destroySystemRecognizer()
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

/** Gli ultimi secondi di audio, per riascoltarli quando sembra di aver sentito la frase. */
private class AudioRing(size: Int) {
    private val data = ShortArray(size)
    private var pos = 0
    private var filled = 0

    fun write(src: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            val len = minOf(n - i, data.size - pos)
            System.arraycopy(src, i, data, pos, len)
            pos = (pos + len) % data.size
            i += len
        }
        filled = minOf(data.size, filled + n)
    }

    fun clear() { filled = 0 }

    fun forEachChunk(chunk: Int, block: (ShortArray, Int) -> Unit) {
        val tmp = ShortArray(chunk)
        var start = (pos - filled + data.size) % data.size
        var left = filled
        while (left > 0) {
            val len = minOf(chunk, left)
            for (k in 0 until len) tmp[k] = data[(start + k) % data.size]
            block(tmp, len)
            start = (start + len) % data.size
            left -= len
        }
    }
}
