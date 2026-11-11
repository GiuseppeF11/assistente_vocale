package it.assistente.vocale

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import it.assistente.vocale.logic.CalendarParser
import it.assistente.vocale.logic.ChoiceParser
import it.assistente.vocale.logic.Command
import it.assistente.vocale.logic.CommandParser
import it.assistente.vocale.logic.Contact
import it.assistente.vocale.logic.ContactMatcher
import it.assistente.vocale.logic.ItalianNumbers
import it.assistente.vocale.logic.Match
import it.assistente.vocale.logic.NumberKind
import it.assistente.vocale.logic.PhoneNumber
import it.assistente.vocale.logic.PlayRequest
import it.assistente.vocale.logic.SpotifyItem
import it.assistente.vocale.logic.Text
import it.assistente.vocale.logic.TimeParser
import it.assistente.vocale.logic.MediaFocus
import it.assistente.vocale.logic.ListenPolicy
import it.assistente.vocale.logic.VoiceDetector
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
        /** chiamato con il tasto/gesto dell'assistente: si salta la frase di attivazione */
        const val ACTION_SESSION = "it.assistente.vocale.SESSION"

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
        /** audio già sentito passato al riconoscitore quando il rilevatore si accende (l'inizio della frase) */
        private const val PREROLL_SAMPLES = RATE_HZ
        /** a riconoscitore spento si legge il microfono a blocchi più grandi: il processore si sveglia meno */
        private const val IDLE_READ_SAMPLES = RATE_HZ * 4 / 10
        /** dopo un parziale con la frase, aspetta che la parola finisca prima di verificare */
        private const val VERIFY_AFTER_PARTIAL_MS = 400L

        @Volatile var running = false
            private set

        fun start(c: Context) = c.startForegroundService(Intent(c, WakeService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, WakeService::class.java)) }

        fun startSession(c: Context) =
            c.startForegroundService(Intent(c, WakeService::class.java).setAction(ACTION_SESSION))

        /** Applica le impostazioni cambiate (frase di attivazione) senza fermare l'ascolto. */
        fun reload(c: Context) {
            if (running) c.startService(Intent(c, WakeService::class.java).setAction(ACTION_RELOAD))
        }
    }

    /**
     * EXTERNAL: il microfono è stato ceduto al riconoscitore di sistema.
     * IDLE: microfono chiuso (nessuna condizione di ascolto attiva, oppure tasto/gesto).
     * HOLD: frase di attivazione appena sentita: si continua a registrare in attesa del riconoscitore.
     * STREAM: il nostro audio va direttamente al riconoscitore di sistema (frase unica, Android 13+).
     */
    private enum class Mode { WAKE, COMMAND, PAUSED, EXTERNAL, IDLE, HOLD, STREAM }

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
        data object WhatToPlay : Pending
        data object AlarmWhen : Pending
        data object TimerHowLong : Pending
        /** evento senza titolo: si chiede come chiamarlo */
        data class EventTitle(val spec: CalendarParser.EventSpec) : Pending
        /** evento senza data né ora: si chiede quando */
        data class EventWhen(val title: String) : Pending
        /** in auto: conferma a voce prima di chiamare o navigare */
        class Confirm(val action: () -> Unit) : Pending
        data class PickContact(val candidates: List<Contact>, val kind: NumberKind?) : Pending
        data class PickNumber(val contact: Contact, val numbers: List<PhoneNumber>) : Pending
    }

    private val main = Handler(Looper.getMainLooper())

    // --- condivisi con il thread audio ---
    @Volatile private var alive = false
    @Volatile private var failed = false
    @Volatile private var modeField = Mode.WAKE
    private val modeLock = Object()
    /** cambiare modo sveglia il thread audio se stava aspettando a microfono chiuso */
    private var mode: Mode
        get() = modeField
        set(v) { modeField = v; synchronized(modeLock) { modeLock.notifyAll() } }
    @Volatile private var micReleased = false
    @Volatile private var resetRequested = false
    @Volatile private var muteUntil = 0L
    @Volatile private var wakePhrase = Prefs.DEFAULT_WAKE
    @Volatile private var wakeDirty = false
    @Volatile private var contacts: List<Contact> = emptyList()
    /** il modello Vosk si carica solo se serve (frase di attivazione o riserva) */
    @Volatile private var voskNeeded = false
    private var audioThread: Thread? = null

    // --- solo thread audio ---
    private var wakeRec: Recognizer? = null
    private val ring = AudioRing(RATE_HZ * RING_SECONDS)
    /** null = rilevatore disattivato (riconoscitore sempre acceso) */
    private var detector: VoiceDetector? = null
    private var voiceActive = false
    @Volatile private var detectorDirty = true
    /** nelle finestre a schermo acceso niente filtro: sensibilità piena */
    @Volatile private var fullSensitivity = false

    // --- contesto (thread principale): decide quando il microfono ascolta ---
    @Volatile private var listenAllowed = false
    private var decision: ListenPolicy.Decision = ListenPolicy.Decision.Silent(ListenPolicy.Off.NONE)
    private var screenOn = true
    private var screenSince = 0L
    private var charging = false
    private var batteryLevel = -1
    private var batteryHot = false
    private var thermalHot = false
    private var autoConnected = false
    private val btCars = HashSet<String>()
    private var carSince = 0L
    private var wasInCar = false
    private var lastStatus = ""
    private val policyTimer = Runnable { updatePolicy() }
    private val contextReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onContext(i)
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalHot = status >= PowerManager.THERMAL_STATUS_MODERATE
        updatePolicy()
    }

    // --- frase unica e riconoscitore pronto ---
    /** audio da passare al riconoscitore: coda della frase di attivazione + quanto detto subito dopo */
    private val held = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()
    @Volatile private var streamOut: java.io.OutputStream? = null
    /** questo dialogo è partito dalla voce: si può usare la frase unica */
    private var fromVoice = false
    /** la sessione di riconoscimento in corso riceve il nostro audio (va tolto «Ok Alfio» dal testo) */
    private var streaming = false
    private var streamStartedAt = 0L
    private var warm: Pair<Asr, SpeechRecognizer>? = null

    // --- solo thread principale ---
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val feedback by lazy { Feedback(this) }
    /** cambia a ogni chiusura del dialogo: scarta i risultati arrivati dopo (es. Spotify) */
    private var actionSeq = 0
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
    /** il dialogo è mostrato dalla sessione dell'assistente di sistema (tasto/gesto) */
    private var sessionUi = false
    /** modalità auto: niente schermo, tutto a voce */
    private var carMode = false
    private var focusRequest: AudioFocusRequest? = null
    private var navConfirmed = false

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
                if (alive) applySettings()
                // senza ascolto a voce il servizio non deve partire solo per ricaricare
                if (alive || !Prefs.voiceWake(this)) return START_NOT_STICKY
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
        if (intent?.action == ACTION_SESSION) {
            sessionUi = true
            onWake()
        }
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
        biasingOk = true
        detectorDirty = true
        tts?.shutdown()
        ttsReady = false
        initTts()
        val inDialog = AssistantBus.state.phase != Phase.HIDDEN
        if (!inDialog && !Prefs.voiceWake(this)) { stopSelf(); return }
        voskNeeded = true
        lastStatus = ""
        loadConnectedCars()
        updatePolicy()
    }

    private fun idleMode() = if (Prefs.voiceWake(this) && listenAllowed) Mode.WAKE else Mode.IDLE

    // =============================================================================================
    // Quando ascoltare: schermo, auto, carica, protezioni
    // =============================================================================================

    private fun registerContext() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.app.UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(android.app.UiModeManager.ACTION_EXIT_CAR_MODE)
            // inviato dall'app Android Auto quando il telefono si collega/scollega dall'auto
            addAction("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED")
        }
        val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(contextReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(contextReceiver, filter)
        sticky?.let { onContext(it) }
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        try {
            val pm = getSystemService(PowerManager::class.java)
            thermalHot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            pm.addThermalStatusListener(mainExecutor, thermalListener)
        } catch (e: Exception) { Log.w(TAG, "Stato termico", e) }
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        screenSince = SystemClock.elapsedRealtime()
        autoConnected = CarMode.androidAutoConnected(this)
        loadConnectedCars()
    }

    private fun unregisterContext() {
        try { unregisterReceiver(contextReceiver) } catch (_: Exception) { }
        try { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener) } catch (_: Exception) { }
    }

    private fun readBattery(i: Intent) {
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        batteryLevel = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        // temperatura in decimi di grado; isteresi per non accendere e spegnere di continuo
        val temp = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        batteryHot = if (batteryHot) temp > 39.0 else temp >= 41.0
    }

    private fun onContext(i: Intent) {
        val now = SystemClock.elapsedRealtime()
        when (i.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                // ogni accensione o sblocco riapre la finestra di ascolto
                screenOn = true; screenSince = now
                autoConnected = CarMode.androidAutoConnected(this)
            }
            Intent.ACTION_SCREEN_OFF -> screenOn = false
            Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
            android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED, android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                @Suppress("DEPRECATION")
                val device = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (device != null && isCar(device)) {
                    if (i.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED) btCars += device.address
                    else btCars -= device.address
                }
            }
            else -> autoConnected = CarMode.androidAutoConnected(this) ||
                i.action == android.app.UiModeManager.ACTION_ENTER_CAR_MODE
        }
        updatePolicy()
    }

    /** Autoradio o vivavoce dell'auto (non cuffie): classe Bluetooth del dispositivo. */
    @SuppressLint("MissingPermission")
    private fun isCar(d: android.bluetooth.BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) return false
        val cls = try { d.bluetoothClass?.deviceClass } catch (e: SecurityException) { null } ?: return false
        return cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }

    /** All'avvio: l'auto è già collegata via Bluetooth? */
    @SuppressLint("MissingPermission")
    private fun loadConnectedCars() {
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(android.bluetooth.BluetoothProfile.HEADSET, android.bluetooth.BluetoothProfile.A2DP)) {
            try {
                adapter.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                        try { proxy.connectedDevices.filter { isCar(it) }.forEach { btCars += it.address } } catch (_: Exception) { }
                        adapter.closeProfileProxy(p, proxy)
                        main.post { updatePolicy() }
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Exception) { Log.w(TAG, "Bluetooth", e) }
        }
    }

    private fun policySettings() = ListenPolicy.Settings(
        screen = Prefs.listenScreen(this),
        screenWindowSec = Prefs.screenWindow(this),
        car = Prefs.listenCar(this),
        carMinutes = Prefs.carMinutes(this),
        charging = Prefs.listenCharging(this),
        always = Prefs.listenAlways(this),
        heatGuard = Prefs.heatGuard(this),
        batteryGuard = Prefs.batteryPause(this),
        batteryThreshold = Prefs.batteryThreshold(this),
    )

    /** Ricalcola se ascoltare; apre o chiude il microfono e aggiorna stato e notifica. */
    private fun updatePolicy() {
        if (!alive) return
        main.removeCallbacks(policyTimer)
        val now = SystemClock.elapsedRealtime()
        val inCar = autoConnected || btCars.isNotEmpty()
        if (inCar && !wasInCar) carSince = now
        wasInCar = inCar
        val d = ListenPolicy.decide(
            policySettings(),
            ListenPolicy.State(now, screenOn, screenSince, inCar, carSince, charging, thermalHot || batteryHot, batteryLevel),
        )
        decision = d
        val allowed = d is ListenPolicy.Decision.Listen && Prefs.voiceWake(this)
        val full = d is ListenPolicy.Decision.Listen && d.reason == ListenPolicy.Reason.SCREEN
        if (full != fullSensitivity) { fullSensitivity = full; detectorDirty = true }
        listenAllowed = allowed
        if (AssistantBus.state.phase == Phase.HIDDEN) mode = idleMode()
        if (allowed) warmUp() else coolDown()
        // alla scadenza della finestra si ricontrolla
        (d as? ListenPolicy.Decision.Listen)?.until?.let { main.postDelayed(policyTimer, (it - now).coerceAtLeast(0) + 50) }

        val text = statusText()
        if (text != lastStatus) {
            lastStatus = text
            AssistantBus.status(text)
            Notifications.refreshService(this, text)
        }
    }

    private fun statusText(): String = when {
        !Prefs.voiceWake(this) -> "Pronto (tasto o gesto)"
        else -> when (val d = decision) {
            is ListenPolicy.Decision.Listen -> "In ascolto · ${d.reason.label}"
            is ListenPolicy.Decision.Silent -> when (d.why) {
                ListenPolicy.Off.HEAT -> "Sospeso: telefono caldo"
                ListenPolicy.Off.BATTERY -> "Sospeso: batteria al $batteryLevel%"
                ListenPolicy.Off.NONE -> "Microfono spento · in attesa"
            }
        }
    }

    private fun startEngine() {
        alive = true
        running = true
        failed = false
        wakePhrase = Prefs.wakePhrase(this)
        mode = idleMode()
        voskNeeded = Prefs.voiceWake(this)
        AssistantBus.controller = this

        // tiene sveglio il processore solo mentre il microfono è aperto (vedi audioLoop)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistente:ascolto")
            .apply { setReferenceCounted(false) }

        initTts()
        refreshContacts()
        registerContext()
        updatePolicy()

        audioThread = Thread({ runEngine() }, "assistente-audio").apply { start() }
    }

    override fun onDestroy() {
        val wasListening = alive && !failed
        alive = false
        synchronized(modeLock) { modeLock.notifyAll() }
        running = false
        main.removeCallbacksAndMessages(null)
        destroySystemRecognizer()
        coolDown()
        closeStream()
        releaseAudioFocus()
        unregisterContext()
        overlay.remove()
        audioThread?.join(800)
        tts?.shutdown()
        feedback.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (AssistantBus.controller === this) AssistantBus.controller = null
        Notifications.cancelWake(this)
        AssistantBus.hide()
        AssistantBus.heard("")
        if (wasListening) AssistantBus.status(
            if (Prefs.activation(this) == Prefs.Activation.GESTURE) "Si attiva con il tasto o il gesto" else "Ascolto disattivato"
        )
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
        tts = TextToSpeech(this, { status ->
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
            // la voce scelta nelle impostazioni, altrimenti la migliore installata
            val chosen = Prefs.ttsVoice(this)?.let { name -> t.voices.orEmpty().firstOrNull { it.name == name } }
            val voice = chosen ?: bestItalianVoice(t)
            if (voice != null) t.voice = voice
            t.setSpeechRate(Prefs.ttsRate(this))
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
            AssistantBus.tts("Voce pronta: ${voice?.name ?: "predefinita"}")
        }, Prefs.ttsEngine(this))
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
        t?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (carMode) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
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

    /** Segnale "ti ascolto"; restituisce i ms per cui non bisogna registrare. */
    private fun beep(): Int = feedback.play()

    // =============================================================================================
    // Thread audio (Vosk)
    // =============================================================================================

    private fun runEngine() {
        try {
            // modalità tasto/gesto: Vosk serve solo come riserva, non lo si carica finché non occorre
            while (alive && !voskNeeded) SystemClock.sleep(100)
            if (!alive) return
            if (Prefs.voiceWake(this)) AssistantBus.status("Carico il modello vocale…")
            val path = ModelInstaller.ensure(this)
            Model(path).use { model ->
                Recognizer(model, RATE_HZ.toFloat()).use { cmd ->
                    // più ipotesi per frase: si sceglie quella che corrisponde a un contatto o a un comando
                    cmd.setMaxAlternatives(3)
                    wakeRec = newWakeRecognizer(model)
                    AssistantBus.status(statusText())
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
            if (mode == Mode.EXTERNAL || mode == Mode.IDLE) {
                micReleased = true
                held.clear()
                // microfono chiuso: il processore può dormire, il thread aspetta il prossimo cambio di modo
                if (wakeLock?.isHeld == true) wakeLock?.release()
                synchronized(modeLock) { if (mode == Mode.EXTERNAL || mode == Mode.IDLE) modeLock.wait(if (mode == Mode.IDLE) 5000 else 50) }
                continue
            }
            wakeLock?.acquire(60 * 60 * 1000L)
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
        val buf = ShortArray(IDLE_READ_SAMPLES)
        var listenStart = SystemClock.elapsedRealtime()
        var lastPartial = ""
        var verifyAt = 0L
        resetRequested = true

        /** passa audio al rilevatore a grammatica e aggiorna il candidato da verificare */
        fun wakeStep(wake: Recognizer, chunk: ShortArray, len: Int, now: Long) {
            val final = wake.acceptWaveForm(chunk, len)
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
        }
        while (alive && mode != Mode.EXTERNAL && mode != Mode.IDLE) {
            if (wakeDirty) {
                wakeDirty = false
                wakeRec?.close()
                wakeRec = newWakeRecognizer(model)
            }
            if (detectorDirty) {
                detectorDirty = false
                // nelle finestre a schermo acceso nessun filtro: non deve perdere nemmeno un «Ok Alfio» detto piano
                detector = if (fullSensitivity) null else Prefs.voiceDetector(this)?.let { VoiceDetector(it, RATE_HZ) }
                voiceActive = false
            }
            val wake = wakeRec ?: return
            // in attesa e nel silenzio: blocchi da 400 ms; altrimenti da 100 ms per reagire subito
            val idle = mode == Mode.WAKE && detector != null && !voiceActive && verifyAt == 0L
            val n = rec.read(buf, 0, if (idle) IDLE_READ_SAMPLES else RATE_HZ / 10)
            if (n <= 0) { SystemClock.sleep(200); continue }
            val now = SystemClock.elapsedRealtime()
            if (resetRequested) {
                resetRequested = false
                wake.reset(); cmd.reset()
                ring.clear()
                detector?.reset()
                voiceActive = false
                verifyAt = 0L
                listenStart = now
                lastPartial = ""
            }
            // non ascoltare il nostro stesso segnale acustico (in diretta al riconoscitore va silenzio)
            if (now < muteUntil) {
                if (mode == Mode.STREAM || mode == Mode.HOLD) java.util.Arrays.fill(buf, 0, n, 0.toShort()) else continue
            }

            when (mode) {
                Mode.PAUSED, Mode.EXTERNAL, Mode.IDLE -> Unit

                // frase appena riconosciuta: si continua a registrare finché il riconoscitore non è pronto
                Mode.HOLD -> {
                    held.add(buf.copyOf(n))
                    AssistantBus.level = levelOf(buf, n)
                }

                // frase unica: il nostro audio va in diretta al riconoscitore di sistema
                Mode.STREAM -> {
                    AssistantBus.level = levelOf(buf, n)
                    val out = streamOut
                    if (out == null) { held.add(buf.copyOf(n)); continue }
                    try {
                        while (true) { val h = held.poll() ?: break; out.write(toBytes(h, h.size)) }
                        out.write(toBytes(buf, n))
                    } catch (e: java.io.IOException) {
                        // il riconoscitore ha finito e ha chiuso il canale
                        if (mode == Mode.STREAM) mode = Mode.PAUSED
                    }
                }

                Mode.WAKE -> {
                    ring.write(buf, n)
                    val was = voiceActive
                    // rilevatore di voce: il riconoscitore lavora solo quando qualcuno parla
                    voiceActive = detector?.process(buf, n) ?: true
                    when {
                        voiceActive && !was -> {
                            // si accende: prima l'ultimo secondo già sentito, così l'inizio della frase non si perde
                            wake.reset()
                            ring.forEachChunk(RATE_HZ / 10, PREROLL_SAMPLES) { c, l -> wakeStep(wake, c, l, now) }
                        }
                        voiceActive -> wakeStep(wake, buf, n, now)
                        was -> {
                            // fine del parlato: si chiude la frase e si controlla l'ultima ipotesi
                            val heard = Text.spoken(field(wake.finalResult, "text"))
                            if (heard.contains(wakePhrase)) verifyAt = now
                            else if (verifyAt == 0L) wake.reset()
                        }
                    }

                    // 2° livello: lo stesso audio riascoltato con tutto il vocabolario
                    if (verifyAt != 0L && now >= verifyAt) {
                        // riconosciuta mentre stavi ancora parlando: forse il comando è già iniziato
                        val stillTalking = voiceActive || detector == null
                        verifyAt = 0L
                        wake.reset()
                        val texts = verify(cmd)
                        if (texts.any { WakeMatcher.matches(wakePhrase, it) }) {
                            // frase unica: si tiene l'ultimo tratto (coda di «Ok Alfio» + inizio del comando)
                            held.clear()
                            if (stillTalking) ring.forEachChunk(RATE_HZ / 10, RATE_HZ * 12 / 10) { c, l -> held.add(c.copyOf(l)) }
                            ring.clear()
                            AssistantBus.heard(wakePhrase)
                            mode = Mode.HOLD
                            main.post { onWake(voice = true) }
                        } else {
                            val what = texts.firstOrNull() ?: "nulla"
                            AssistantBus.heard("$what → non è «$wakePhrase», ignoro")
                        }
                    }
                }

                Mode.COMMAND -> {
                    AssistantBus.level = levelOf(buf, n)
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

    /** PCM 16 bit little endian, come lo vuole il riconoscitore. */
    private fun toBytes(buf: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) { val v = buf[i].toInt(); out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte() }
        return out
    }

    /** Volume del blocco audio, 0..1 (circa 40 dB = silenzio, 75 dB = voce forte). */
    private fun levelOf(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        val db = 10 * kotlin.math.log10(sum / n.coerceAtLeast(1) + 1.0)
        return ((db - 40) / 35).toFloat().coerceIn(0f, 1f)
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
        if (asrLevel == Asr.SYSTEM && Recognizers.google(this) == null) asrLevel = Asr.VOSK
        return asrLevel
    }

    private fun listenWith(asr: Asr, seq: Int) {
        if (!alive || seq != listenSeq) return
        // frase unica: il microfono è già aperto, il nostro audio va direttamente al riconoscitore
        if (asr != Asr.VOSK && fromVoice && mode == Mode.HOLD && Build.VERSION.SDK_INT >= 33 && !Prefs.streamUnsupported(this)) {
            fromVoice = false
            val busy = beep()
            muteUntil = SystemClock.elapsedRealtime() + busy + 40
            startSystemRecognizer(asr, seq, stream = true)
            return
        }
        fromVoice = false
        held.clear()
        if (asr == Asr.VOSK) {
            voskNeeded = true
            val busy = beep()
            muteUntil = SystemClock.elapsedRealtime() + busy + 150
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
                val busy = beep()
                // il segnale non deve finire nella registrazione
                main.postDelayed({ startSystemRecognizer(asr, seq) }, busy + 70L)
            }
        }
        main.post(waitMic)
    }

    private fun createRecognizer(asr: Asr): SpeechRecognizer? = try {
        if (asr == Asr.ON_DEVICE && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else Recognizers.google(this)?.let { SpeechRecognizer.createSpeechRecognizer(this, it) }
    } catch (e: Exception) {
        Log.w(TAG, "Riconoscitore di sistema non creato", e)
        null
    }

    /** Riconoscitore preparato in anticipo mentre si aspetta «Ok Alfio»: dopo la frase parte subito. */
    private fun warmUp() {
        if (warm != null || !Prefs.systemAsr(this)) return
        val asr = chooseAsr()
        if (asr == Asr.VOSK) return
        createRecognizer(asr)?.let { warm = asr to it }
    }

    private fun coolDown() {
        warm?.second?.let { try { it.destroy() } catch (_: Exception) { } }
        warm = null
    }

    private fun startSystemRecognizer(asr: Asr, seq: Int, stream: Boolean = false) {
        if (!alive || seq != listenSeq) return
        destroySystemRecognizer()
        val ready = warm?.takeIf { it.first == asr }?.second
        if (ready != null) warm = null
        val sr = ready ?: createRecognizer(asr)
        if (sr == null) { endStream(); degrade(asr, persist = true, seq); return }
        systemRecognizer = sr
        val usedBiasing = biasingOk && Build.VERSION.SDK_INT >= 33
        streaming = stream
        streamStartedAt = SystemClock.elapsedRealtime()

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Google riporta circa -2..10 dB: diventa 0..1 per le onde e il contorno
                AssistantBus.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
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
                val wasStreaming = streaming
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    .map { if (wasStreaming) WakeMatcher.stripLeading(wakePhrase, it) else it }
                destroySystemRecognizer()
                endStream()
                mode = Mode.PAUSED
                when {
                    texts.any { it.isNotBlank() } -> onFinal(texts.filter { it.isNotBlank() }, asr)
                    // hai detto solo «Ok Alfio» e ti sei fermato: si riascolta il comando
                    wasStreaming && results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().isNotEmpty() ->
                        { listenSeq++; listenWith(asr, listenSeq) }
                    else -> onSilence()
                }
            }

            override fun onError(error: Int) {
                if (seq != listenSeq) return
                val wasStreaming = streaming
                destroySystemRecognizer()
                endStream()
                Log.i(TAG, "Riconoscitore ${asr.name}: errore $error${if (wasStreaming) " (frase unica)" else ""}")
                // il riconoscitore non accetta il nostro audio: si torna al metodo classico, per sempre
                if (wasStreaming && error !in setOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH) &&
                    SystemClock.elapsedRealtime() - streamStartedAt < 3000 && transcript.isBlank()
                ) {
                    Prefs.setStreamUnsupported(this@WakeService, true)
                    listenWith(asr, seq)
                    return
                }
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
        var readEnd: android.os.ParcelFileDescriptor? = null
        if (stream && Build.VERSION.SDK_INT >= 33) {
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            readEnd = pipe[0]
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE_HZ)
            streamOut = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            mode = Mode.STREAM
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening", e)
            destroySystemRecognizer()
            endStream()
            degrade(asr, persist = true, seq)
            return
        } finally {
            // il riconoscitore ha ricevuto la sua copia del canale
            try { readEnd?.close() } catch (_: Exception) { }
        }
        main.postDelayed({
            if (seq == listenSeq && systemRecognizer === sr) {
                destroySystemRecognizer()
                endStream()
                degrade(asr, persist = false, seq)
            }
        }, SYSTEM_ASR_WATCHDOG_MS)
    }

    /** Il motore non va: si passa al successivo, solo per questa volta o per tutta la sessione. */
    private fun degrade(asr: Asr, persist: Boolean, seq: Int) {
        val next = if (asr == Asr.ON_DEVICE) Asr.SYSTEM else Asr.VOSK
        if (persist && next.ordinal > asrLevel.ordinal) asrLevel = next
        val actual = if (next == Asr.SYSTEM && Recognizers.google(this) == null) Asr.VOSK else next
        listenWith(actual, seq)
    }

    /** Chiude il canale audio verso il riconoscitore (il thread audio smette di scrivere). */
    private fun endStream() {
        streaming = false
        if (mode == Mode.STREAM || mode == Mode.HOLD) mode = Mode.PAUSED
        closeStream()
    }

    private fun closeStream() {
        val out = streamOut
        streamOut = null
        held.clear()
        try { out?.close() } catch (_: Exception) { }
    }

    private fun destroySystemRecognizer() {
        systemRecognizer?.let { try { it.destroy() } catch (_: Exception) { } }
        systemRecognizer = null
    }

    /** Parole da favorire: i comandi e i nomi in rubrica. */
    private fun biasingStrings(): ArrayList<String> {
        val list = arrayListOf(
            "chiama", "telefona", "portami", "naviga", "vivavoce", "cellulare", "casa", "lavoro", "annulla",
            "metti", "spotify", "album", "playlist", "sveglia", "svegliami", "timer", "calendario", "agenda",
        )
        contacts.asSequence().map { it.name }.take(150).forEach { list.add(it) }
        return list
    }

    // =============================================================================================
    // Dialogo (thread principale)
    // =============================================================================================

    /** Vero solo mentre aspettiamo che l'utente parli: scarta eventi audio arrivati in ritardo. */
    private fun listening() = alive && AssistantBus.state.phase == Phase.LISTENING

    private fun onWake(voice: Boolean = false) {
        if (!alive) return
        fromVoice = voice
        main.removeCallbacks(hideRunnable)
        cancelPendingCall()
        pending = Pending.Fresh
        misunderstandings = 0
        speakerRequested = false
        carMode = CarMode.isActive(this)
        refreshContacts()
        grabAudioFocus()
        AssistantBus.post(UiState(Phase.LISTENING, "Ti ascolto…"))
        // con il tasto/gesto il popup lo mostra la sessione dell'assistente; in auto lo schermo non si guarda
        when {
            sessionUi -> overlay.remove()
            carMode -> Unit
            else -> showScreen()
        }
        listen("Ti ascolto…", emptyList())
    }

    private fun onPartial(raw: String) {
        if (!listening()) return
        val text = if (streaming) WakeMatcher.stripLeading(wakePhrase, raw) else raw
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

        // in auto, risposta a "Chiamo Mario Rossi, confermi?"
        (pending as? Pending.Confirm)?.let { c ->
            when (ChoiceParser.yesNo(text)) {
                true -> { pending = Pending.Fresh; c.action(); return }
                false -> { finish("Annullato.", error = false); return }
                // "no, Mario Bianchi" o un comando nuovo: si riparte da capo
                null -> pending = Pending.Fresh
            }
        }

        when (val p = pending) {
            Pending.Fresh -> when (cmd) {
                is Command.Call -> resolveCall(cmd.target, cmd.kind)
                Command.CallWho -> ask("Chi vuoi chiamare?", Pending.WhoToCall)
                is Command.Navigate -> navigate(cmd.destination)
                Command.NavigateWhere -> ask("Dove vuoi andare?", Pending.WhereToGo)
                is Command.Play -> play(cmd.request)
                Command.PlayWhat -> ask("Cosa vuoi ascoltare?", Pending.WhatToPlay)
                is Command.Alarm -> alarm(cmd.hour, cmd.minute)
                is Command.AlarmIn -> alarmIn(cmd.seconds)
                Command.AlarmWhen -> ask("A che ora?", Pending.AlarmWhen)
                is Command.Timer -> timer(cmd.seconds)
                Command.TimerHowLong -> ask("Per quanto tempo?", Pending.TimerHowLong)
                Command.AddEvent -> addEvent(CalendarParser.parse(text, java.time.LocalDate.now()))
                else -> ask(
                    "Non ho capito. Puoi chiamare, farti portare in un posto, mettere musica, una sveglia o un timer.",
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

            is Pending.EventTitle -> {
                val title = text.trim().trim('"', '«', '»', '.', '!', '?').replaceFirstChar { it.uppercase() }
                if (title.isEmpty()) ask("Come si chiama l'evento?", p, misunderstood = true)
                else addEvent(p.spec.copy(title = title))
            }

            is Pending.EventWhen -> {
                val s = CalendarParser.parse(text, java.time.LocalDate.now())
                if (s.date == null && s.time == null) ask("Non ho capito. Quando?", p, misunderstood = true)
                else addEvent(s.copy(title = p.title))
            }

            Pending.AlarmWhen -> when (cmd) {
                is Command.Alarm -> alarm(cmd.hour, cmd.minute)
                is Command.AlarmIn -> alarmIn(cmd.seconds)
                else -> {
                    val c = TimeParser.clock(text)
                    if (c != null) alarm(c.hour, c.minute)
                    else ask("Non ho capito l'orario. A che ora?", Pending.AlarmWhen, misunderstood = true)
                }
            }

            Pending.TimerHowLong -> when (cmd) {
                is Command.Timer -> timer(cmd.seconds)
                else -> {
                    val d = TimeParser.duration(text)
                    if (d != null) timer(d)
                    else ask("Non ho capito. Per quanto tempo?", Pending.TimerHowLong, misunderstood = true)
                }
            }

            is Pending.Confirm -> Unit // gestito sopra

            Pending.WhatToPlay -> when (cmd) {
                is Command.Play -> play(cmd.request)
                is Command.Call -> resolveCall(cmd.target, cmd.kind)
                is Command.Navigate -> navigate(cmd.destination)
                else -> {
                    val req = CommandParser.playRequest(text)
                    if (req.query.isEmpty()) ask("Cosa vuoi ascoltare?", Pending.WhatToPlay, misunderstood = true)
                    else play(req)
                }
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
                    is Command.Navigate, is Command.Play, is Command.Alarm, is Command.AlarmIn, is Command.Timer,
                    Command.AddEvent -> 2
                    Command.CallWho, Command.NavigateWhere, Command.PlayWhat, Command.AlarmWhen, Command.TimerHowLong -> 1
                    else -> 0
                }
            }
            Pending.WhoToCall -> best { contactScore(CommandParser.callTarget(it).target) }
            is Pending.PickContact -> best { if (ChoiceParser.pick(it, p.candidates.map { c -> c.name }) != null) 1 else 0 }
            is Pending.PickNumber -> best {
                if (ChoiceParser.kind(it) != null || ChoiceParser.pick(it, p.numbers.map { n -> n.label }) != null) 1 else 0
            }
            Pending.AlarmWhen -> best { if (TimeParser.clock(it) != null) 1 else 0 }
            Pending.TimerHowLong -> best { if (TimeParser.duration(it) != null) 1 else 0 }
            Pending.WhereToGo, Pending.WhatToPlay, is Pending.EventTitle -> texts[0]
            is Pending.EventWhen -> best {
                val s = CalendarParser.parse(it, java.time.LocalDate.now()); if (s.date != null || s.time != null) 1 else 0
            }
            is Pending.Confirm -> best { if (ChoiceParser.yesNo(it) != null) 1 else 0 }
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
        else if (carMode) "${contact.name} ha più numeri. Dimmi il primo o il secondo."
        else "${contact.name} ha più numeri. Dimmi il primo, il secondo, oppure toccane uno."
    }

    private fun numberOptions(numbers: List<PhoneNumber>) = numbers.map { "${it.label} · ${it.number}" }

    private fun call(number: String, spokenLabel: String, confirmed: Boolean = false) {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            finish("Manca il permesso per effettuare chiamate.", error = true); return
        }
        if (carMode && Prefs.carConfirm(this) && !confirmed) {
            ask("Chiamo $spokenLabel, confermi?", Pending.Confirm { call(number, spokenLabel, confirmed = true) })
            return
        }
        // in auto l'audio va già all'impianto della macchina
        val speaker = !carMode && (Prefs.speaker(this) || speakerRequested)
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
        if (carMode && Prefs.carConfirm(this) && !navConfirmed) {
            ask("Navigo verso $shown, confermi?", Pending.Confirm { navConfirmed = true; navigate(rawDestination) })
            return
        }
        navConfirmed = false
        speak("Avvio la navigazione verso $shown")
        launchApp(AssistantBus.Launch.Navigate(destination), "Navigazione verso $shown", "Nessuna app di navigazione trovata.")
    }

    // ---- calendario ----

    private fun addEvent(spec: CalendarParser.EventSpec) {
        if (!CalendarStore.hasPermission(this)) {
            finish("Manca il permesso del calendario: concedilo nell'app.", error = true); return
        }
        if (spec.title.isBlank()) { ask("Come si chiama l'evento?", Pending.EventTitle(spec)); return }
        if (spec.date == null && spec.time == null) { ask("Quando?", Pending.EventWhen(spec.title)); return }

        val today = java.time.LocalDate.now()
        // solo l'ora: oggi se non è già passata, altrimenti domani
        val date = spec.date ?: spec.time!!.let { t ->
            if (java.time.LocalTime.of(t.hour, t.minute).isAfter(java.time.LocalTime.now())) today else today.plusDays(1)
        }
        val calendar = CalendarStore.target(this)
            ?: run { finish("Non trovo un calendario in cui scrivere.", error = true); return }
        if (!CalendarStore.insert(this, spec.title, date, spec.time, spec.end)) {
            finish("Non sono riuscito ad aggiungere l'evento.", error = true); return
        }
        val whenText = CalendarParser.describe(date, today) + when {
            spec.time == null -> ", tutto il giorno"
            spec.end != null -> " dalle ${spec.time} alle ${spec.end}"
            else -> " alle ${spec.time}"
        }
        Log.i(TAG, "Evento in ${calendar.label}")
        confirm("Ho aggiunto «${spec.title}» $whenText")
    }

    // ---- sveglia e timer ----

    private fun alarm(hour: Int, minute: Int) {
        val time = "%d:%02d".format(hour, minute)
        if (Actions.setAlarm(this, hour, minute)) confirm("Sveglia alle $time")
        else finish("Non trovo un'app Orologio per la sveglia.", error = true)
    }

    private fun alarmIn(seconds: Int) {
        val at = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, seconds) }
        val hour = at.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = at.get(java.util.Calendar.MINUTE)
        val time = "%d:%02d".format(hour, minute)
        if (Actions.setAlarm(this, hour, minute)) confirm("Sveglia alle $time, tra ${TimeParser.describe(seconds)}")
        else finish("Non trovo un'app Orologio per la sveglia.", error = true)
    }

    private fun timer(seconds: Int) {
        val what = TimeParser.describe(seconds)
        if (Actions.setTimer(this, seconds)) confirm("Timer di $what avviato")
        else finish("Non trovo un'app Orologio per il timer.", error = true)
    }

    /** Azione riuscita: conferma a voce e a schermo, poi il popup sparisce. */
    private fun confirm(message: String) {
        AssistantBus.post(UiState(Phase.DONE, message, transcript))
        speak(message)
        backToWake()
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 2000)
    }

    // ---- musica ----

    private fun play(req: PlayRequest) {
        if (!Actions.spotifyInstalled(this)) {
            finish("Spotify non è installato.", error = true); return
        }
        if (!SpotifyAccount.isLinked(this)) {
            spotifySearchFallback(req, "Per far partire la musica da sola collega Spotify nelle impostazioni dell'app.")
            return
        }
        AssistantBus.post(UiState(Phase.WORKING, "Cerco «${req.query}» su Spotify…", transcript))
        mode = idleMode()
        val seq = actionSeq
        Thread({
            val token = SpotifyAccount.accessToken(this)
            val result = token?.let { SpotifyAccount.search(it, req) }
            main.post {
                if (!alive || seq != actionSeq) return@post
                when (result) {
                    null -> spotifySearchFallback(req, "Non riesco a collegarmi a Spotify (internet o account da ricollegare).")
                    SpotifyAccount.Search.Empty -> finish("Non ho trovato «${req.query}» su Spotify.", error = true)
                    is SpotifyAccount.Search.Error -> {
                        // messaggio completo a schermo, a voce solo l'essenziale
                        AssistantBus.post(UiState(Phase.ERROR, result.message, transcript))
                        speak("Spotify ha rifiutato la ricerca. Guarda il messaggio sullo schermo.")
                        backToWake()
                        main.removeCallbacks(hideRunnable)
                        main.postDelayed(hideRunnable, 9000)
                    }
                    is SpotifyAccount.Search.Found -> playItem(result.item, req, seq)
                }
            }
        }, "assistente-spotify").start()
    }

    private fun playItem(item: SpotifyItem, req: PlayRequest, seq: Int) {
        AssistantBus.post(UiState(Phase.WORKING, "Spotify: ${item.spoken()}", transcript))
        speak("Metto ${item.spoken()}") {
            if (!alive || seq != actionSeq) return@speak
            // la musica deve poter partire: si lascia l'audio
            releaseAudioFocus()
            SpotifyPlayer.play(this, item) { result ->
                if (!alive || seq != actionSeq) return@play
                when (result) {
                    SpotifyPlayer.Result.Playing -> hide()
                    SpotifyPlayer.Result.NeedsLink ->
                        spotifySearchFallback(req, "Spotify chiede di nuovo il permesso: ricollegalo nelle impostazioni dell'app.")
                    is SpotifyPlayer.Result.Failed ->
                        spotifySearchFallback(req, "Spotify non è partito (${result.reason}).")
                }
            }
        }
    }

    /** Ripiego: apre la ricerca su Spotify (in auto solo un avviso a voce). */
    private fun spotifySearchFallback(req: PlayRequest, why: String) {
        if (carMode) { finish(why, error = true); return }
        launchApp(AssistantBus.Launch.Play(req), "$why Intanto apro la ricerca.", "Non riesco ad aprire Spotify.")
    }

    /**
     * Sbloccato: l'app si apre subito e il popup sparisce da solo.
     * Bloccato: ci pensa la schermata, che chiede lo sblocco prima di aprirla.
     */
    private fun launchApp(launch: AssistantBus.Launch, message: String, failure: String) {
        // in auto non si sblocca il telefono: Maps/Spotify si aprono comunque (finiscono sullo schermo dell'auto)
        if (!isLocked() || carMode) {
            AssistantBus.post(UiState(Phase.WORKING, message, transcript))
            backToWake()
            if (Actions.run(this, launch)) {
                main.removeCallbacks(hideRunnable)
                // un messaggio lungo (es. spiegazione Spotify) resta visibile più a lungo
                main.postDelayed(hideRunnable, if (message.length > 40) 3500 else 1200)
            } else {
                finish(failure, error = true)
            }
            return
        }
        AssistantBus.post(UiState(Phase.WORKING, message, transcript, launch = launch))
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

    /** Mette in pausa la musica mentre si ascolta il comando (come fa Google). */
    private fun grabAudioFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
        }
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun hide() {
        AssistantBus.level = 0f
        // finito il dialogo si ricontrolla se il microfono deve restare aperto
        main.post { updatePolicy() }
        releaseAudioFocus()
        actionSeq++
        main.removeCallbacks(hideRunnable)
        Notifications.cancelWake(this)
        AssistantBus.hide()
        backToWake()
        sessionUi = false
        // modalità tasto/gesto: finito il dialogo il servizio si chiude (niente microfono, niente notifica)
        if (!Prefs.voiceWake(this)) stopWhenQuiet()
    }

    /** Aspetta che la voce finisca la frase prima di chiudere. */
    private fun stopWhenQuiet() {
        if (AssistantBus.state.phase != Phase.HIDDEN) return
        if (tts?.isSpeaking == true || callRunnable != null) {
            main.postDelayed({ stopWhenQuiet() }, 300)
            return
        }
        stopSelf()
    }

    private fun backToWake() {
        listenSeq++
        destroySystemRecognizer()
        endStream()
        pending = Pending.Fresh
        resetRequested = true
        mode = idleMode()
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

    override fun done() = hide()

    override fun choose(index: Int) {
        val p = pending
        listenSeq++
        destroySystemRecognizer()
        endStream()
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

    /** Gli ultimi [last] campioni (tutti, se non indicato), a blocchi di [chunk]. */
    fun forEachChunk(chunk: Int, last: Int = filled, block: (ShortArray, Int) -> Unit) {
        val tmp = ShortArray(chunk)
        val count = minOf(last, filled)
        var start = (pos - count + data.size) % data.size
        var left = count
        while (left > 0) {
            val len = minOf(chunk, left)
            for (k in 0 until len) tmp[k] = data[(start + k) % data.size]
            block(tmp, len)
            start = (start + len) % data.size
            left -= len
        }
    }
}
