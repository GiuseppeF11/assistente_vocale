package it.assistente.vocale

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import it.assistente.vocale.logic.Text

/** Schermata di configurazione: permessi, avvio/arresto, frase di attivazione, vivavoce, motori vocali. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var checks: TextView
    private lateinit var toggle: Button
    private lateinit var wake: EditText
    private lateinit var wakeCheck: TextView
    private lateinit var asrInfo: TextView
    private lateinit var downloadIt: Button
    private lateinit var ttsInfo: TextView
    private lateinit var home: EditText
    private lateinit var work: EditText
    private lateinit var assistInfo: TextView
    private lateinit var assistSet: Button
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var rateBar: SeekBar
    private lateinit var rateLabel: TextView
    private lateinit var fbSound: Spinner
    private lateinit var fbVolume: SeekBar
    private lateinit var fbVolumeLabel: TextView
    private lateinit var fbVibrate: Switch
    private lateinit var spotifyInfo: TextView
    private val feedbackPreview by lazy { Feedback(this) }
    private lateinit var versionInfo: TextView
    private lateinit var updateButton: Button
    private var release: Updater.Release? = null
    private var checkedUpdates = false
    private var checkFailed = false
    private var downloading = false
    private var languageInfo = ""
    private var voiceInfo = ""

    // anteprima della voce: un motore vocale tutto per le impostazioni
    private var preview: TextToSpeech? = null
    private var previewGen = 0
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()
    private var voices: List<Voice> = emptyList()
    private var probe: SpeechRecognizer? = null
    private val listener = Runnable { refresh() }

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        heard = findViewById(R.id.heard)
        checks = findViewById(R.id.checks)
        toggle = findViewById(R.id.toggle)
        wake = findViewById(R.id.wake)
        wakeCheck = findViewById(R.id.wake_check)
        asrInfo = findViewById(R.id.asr_info)
        downloadIt = findViewById(R.id.download_it)
        ttsInfo = findViewById(R.id.tts_info)
        home = findViewById(R.id.home)
        work = findViewById(R.id.work)

        assistInfo = findViewById(R.id.assist_info)
        assistSet = findViewById(R.id.assist_set)
        engineSpinner = findViewById(R.id.tts_engine)
        voiceSpinner = findViewById(R.id.tts_voice)
        rateBar = findViewById(R.id.tts_rate)
        rateLabel = findViewById(R.id.tts_rate_label)
        versionInfo = findViewById(R.id.version_info)
        updateButton = findViewById(R.id.update)
        updateButton.setOnClickListener { startUpdate() }
        findViewById<Button>(R.id.hide_notification).setOnClickListener { open(Notifications.serviceChannelSettings(this)) }
        setupActivation()
        setupVoice()
        setupFeedback()
        spotifyInfo = findViewById(R.id.spotify_info)
        findViewById<Button>(R.id.spotify_link).setOnClickListener {
            // login Spotify nel browser; al ritorno ci pensa SpotifyLinkActivity
            open(Intent(Intent.ACTION_VIEW, SpotifyAccount.authorizeUrl(this)))
        }
        findViewById<Button>(R.id.spotify_unlink).setOnClickListener { SpotifyAccount.unlink(this); refresh() }
        setupCar()


        home.setText(Prefs.home(this))
        work.setText(Prefs.work(this))
        wake.setText(Prefs.wakePhrase(this))
        findViewById<Button>(R.id.wake_save).setOnClickListener { saveWakePhrase() }

        findViewById<Switch>(R.id.speaker).apply {
            isChecked = Prefs.speaker(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setSpeaker(this@MainActivity, on) }
        }
        findViewById<Switch>(R.id.system_asr).apply {
            isChecked = Prefs.systemAsr(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setSystemAsr(this@MainActivity, on)
                WakeService.reload(this@MainActivity)
                refresh()
            }
        }
        downloadIt.setOnClickListener { downloadItalian() }
        findViewById<Button>(R.id.tts_install).setOnClickListener {
            // le voci si scaricano dalle impostazioni del motore (es. Google: Impostazioni → Installa dati vocali)
            open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(Prefs.ttsEngine(this) ?: preview?.defaultEngine))
        }

        findViewById<Button>(R.id.grant).setOnClickListener { requestPermissions(permissions, 1) }
        findViewById<Button>(R.id.overlay).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        toggle.setOnClickListener {
            if (WakeService.running && Prefs.voiceWake(this)) {
                Prefs.setEnabled(this, false)
                WakeService.stop(this)
            } else if (!granted(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(permissions, 1)
            } else {
                Prefs.setEnabled(this, true)
                WakeService.start(this)
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        AssistantBus.addListener(listener)
        // dopo un riavvio (o se il sistema ha chiuso il servizio) basta riaprire l'app
        if (Prefs.voiceWake(this) && !WakeService.running && granted(Manifest.permission.RECORD_AUDIO)) {
            WakeService.start(this)
        }
        checkItalianOffline()
        checkUpdates()
        bindCalendars()
        refresh()
    }

    // ---- aggiornamenti ----

    private fun checkUpdates() {
        if (checkedUpdates || downloading) return
        checkedUpdates = true
        Thread {
            val r = Updater.latest()
            runOnUiThread {
                checkFailed = r == null
                release = r?.takeIf { it.versionCode > Updater.installedVersion(this) }
                refresh()
            }
        }.start()
    }

    /**
     * Apre il download dell'ultima versione nel browser: l'app non ha il permesso di installare
     * altre app (è uno dei segnali che fanno scattare Play Protect).
     */
    private fun startUpdate() {
        val r = release ?: return
        AssistantBus.update("Scarica il file e aprilo per installare ${r.name}.")
        open(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)))
    }

    override fun onPause() {
        AssistantBus.removeListener(listener)
        Prefs.setPlaces(this, home.text.toString(), work.text.toString())
        super.onPause()
    }

    override fun onDestroy() {
        probe?.destroy()
        preview?.shutdown()
        feedbackPreview.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        calendarsShown = null
        bindCalendars()
        refresh()
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    // ---- modalità di attivazione ----

    private fun setupActivation() {
        val group = findViewById<RadioGroup>(R.id.activation)
        group.check(
            when (Prefs.activation(this)) {
                Prefs.Activation.VOICE -> R.id.act_voice
                Prefs.Activation.GESTURE -> R.id.act_gesture
                Prefs.Activation.BOTH -> R.id.act_both
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.act_gesture -> Prefs.Activation.GESTURE
                R.id.act_both -> Prefs.Activation.BOTH
                else -> Prefs.Activation.VOICE
            }
            Prefs.setActivation(this, mode)
            if (mode != Prefs.Activation.GESTURE && !Prefs.enabled(this)) Prefs.setEnabled(this, true)
            when {
                // a voce: serve il servizio in ascolto
                Prefs.voiceWake(this) && !WakeService.running && granted(Manifest.permission.RECORD_AUDIO) ->
                    WakeService.start(this)
                // solo gesto: il servizio si chiude da solo (microfono spento)
                else -> WakeService.reload(this)
            }
            refresh()
        }
        assistSet.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
    }

    private fun isAssistant(): Boolean = try {
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (e: Exception) { false }

    // ---- calendario ----

    private var calendarsShown: List<Long>? = null

    private fun bindCalendars() {
        val cals = CalendarStore.writable(this)
        val info = findViewById<TextView>(R.id.calendar_info)
        val spinner = findViewById<Spinner>(R.id.calendar)
        if (cals.isEmpty()) {
            spinner.visibility = View.GONE
            info.text = if (CalendarStore.hasPermission(this)) "Nessun calendario in cui scrivere."
            else "Concedi il permesso del calendario (pulsante «Concedi» più in alto)."
            calendarsShown = null
            return
        }
        spinner.visibility = View.VISIBLE
        info.text = "Prova: «aggiungi al calendario cena con Marco domani alle 20». " +
            "Gli eventi con orario hanno un promemoria ${CalendarStore.REMINDER_MINUTES} minuti prima."
        val ids = cals.map { it.id }
        if (ids == calendarsShown) return
        calendarsShown = ids
        val selected = CalendarStore.target(this)?.let { t -> cals.indexOfFirst { it.id == t.id } } ?: 0
        bindSpinner(spinner, cals.map { it.label }, selected.coerceAtLeast(0)) { i -> Prefs.setCalendarId(this, cals[i].id) }
    }

    // ---- in auto ----

    private fun setupCar() {
        val modes = Prefs.Car.values().toList()
        bindSpinner(findViewById(R.id.car_mode), modes.map { it.label }, modes.indexOf(Prefs.carMode(this))) { i ->
            Prefs.setCarMode(this, modes[i]); refresh()
        }
        findViewById<Switch>(R.id.car_confirm).apply {
            isChecked = Prefs.carConfirm(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setCarConfirm(this@MainActivity, on) }
        }
    }

    // ---- segnale di ascolto ----

    private val pickSound = 7

    private fun setupFeedback() {
        fbSound = findViewById(R.id.fb_sound)
        fbVolume = findViewById(R.id.fb_volume)
        fbVolumeLabel = findViewById(R.id.fb_volume_label)
        fbVibrate = findViewById(R.id.fb_vibrate)

        fbVolume.progress = Prefs.feedbackVolume(this)
        fbVibrate.isChecked = Prefs.feedbackVibrate(this)
        bindSounds()
        fbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = showFeedback()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saveFeedback(); feedbackPreview.play() }
        })
        fbVibrate.setOnCheckedChangeListener { _, _ -> saveFeedback() }
        findViewById<Button>(R.id.fb_test).setOnClickListener { feedbackPreview.play() }
        showFeedback()
    }

    private fun bindSounds() {
        val sounds = Feedback.Sound.values().toList()
        bindSpinner(fbSound, sounds.map { soundLabel(it) }, sounds.indexOf(Prefs.feedbackSound(this))) { i ->
            val sound = sounds[i]
            if (sound == Feedback.Sound.CUSTOM) pickPhoneSound() else { saveFeedback(sound = sound); feedbackPreview.play() }
        }
    }

    private fun soundLabel(s: Feedback.Sound): String {
        if (s != Feedback.Sound.CUSTOM) return s.label
        val uri = Prefs.feedbackUri(this) ?: return s.label
        val title = try { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) } catch (e: Exception) { null }
        return if (title != null) "Suono del telefono: $title" else s.label
    }

    private fun saveFeedback(
        sound: Feedback.Sound = Prefs.feedbackSound(this),
        uri: String? = Prefs.feedbackUri(this),
    ) {
        Prefs.setFeedback(this, sound, uri, fbVolume.progress, fbVibrate.isChecked)
        showFeedback()
    }

    private fun showFeedback() {
        val none = Prefs.feedbackSound(this) == Feedback.Sound.NONE
        fbVolume.isEnabled = !none
        fbVolumeLabel.text = when {
            none -> "Volume: suono disattivato"
            fbVolume.progress == 0 -> "Volume: 0 (muto)"
            else -> "Volume: ${fbVolume.progress}% del volume multimediale"
        }
    }

    @Suppress("DEPRECATION")
    private fun pickPhoneSound() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Segnale di ascolto")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        Prefs.feedbackUri(this)?.let { intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        try { startActivityForResult(intent, pickSound) } catch (e: Exception) { saveFeedback(sound = Feedback.Sound.BEEP) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickSound) return
        val uri = if (resultCode == RESULT_OK) data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) else null
        if (uri != null) {
            saveFeedback(sound = Feedback.Sound.CUSTOM, uri = uri.toString())
            feedbackPreview.play()
        }
        // mostra il nome del suono scelto, oppure torna alla scelta precedente se annullato
        bindSounds()
    }

    // ---- voce ----

    private fun setupVoice() {
        val rate = Prefs.ttsRate(this)
        rateBar.progress = ((rate - 0.6f) * 100).toInt().coerceIn(0, 80)
        showRate()
        rateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = showRate()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                saveVoice(Prefs.ttsEngine(this@MainActivity), Prefs.ttsVoice(this@MainActivity))
            }
        })
        findViewById<Button>(R.id.tts_test).setOnClickListener { testVoice() }
        startPreview(Prefs.ttsEngine(this))
    }

    private fun currentRate() = 0.6f + rateBar.progress / 100f

    private fun showRate() {
        rateLabel.text = "Velocità: ${"%.2f".format(currentRate())}×"
    }

    private fun startPreview(engine: String?) {
        preview?.shutdown()
        val gen = ++previewGen
        voiceInfo = "Carico le voci…"
        preview = TextToSpeech(this, { status -> if (gen == previewGen) onPreviewReady(status, engine) }, engine)
    }

    private fun onPreviewReady(status: Int, engine: String?) {
        val t = preview ?: return
        if (status != TextToSpeech.SUCCESS) { voiceInfo = "Motore vocale non disponibile"; refresh(); return }

        // motori installati (Google, Samsung, …)
        engines = t.engines.orEmpty()
        val selectedEngine = engine ?: t.defaultEngine
        bindSpinner(engineSpinner, engines.map { it.label }, engines.indexOfFirst { it.name == selectedEngine }.coerceAtLeast(0)) { i ->
            val pkg = engines.getOrNull(i)?.name
            if (pkg != null && pkg != (Prefs.ttsEngine(this) ?: t.defaultEngine)) {
                saveVoice(pkg, null)
                startPreview(pkg)
            }
        }

        // voci italiane del motore scelto: prima quelle offline
        voices = try {
            t.voices.orEmpty()
                .filter { it.locale.language == "it" }
                .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
        } catch (e: Exception) { emptyList() }
        val labels = listOf("Automatica") + voices.mapIndexed { i, v ->
            val where = if (v.isNetworkConnectionRequired) "serve internet" else "offline"
            val quality = if (v.quality >= Voice.QUALITY_HIGH) " · alta qualità" else ""
            "Voce ${i + 1} · $where$quality  (${v.name})"
        }
        val saved = Prefs.ttsVoice(this)
        val selected = voices.indexOfFirst { it.name == saved }.let { if (it < 0) 0 else it + 1 }
        bindSpinner(voiceSpinner, labels, selected) { i ->
            val name = if (i == 0) null else voices.getOrNull(i - 1)?.name
            if (name != Prefs.ttsVoice(this)) saveVoice(Prefs.ttsEngine(this) ?: t.defaultEngine, name)
        }
        voiceInfo = when {
            voices.isEmpty() -> "Questo motore non ha voci italiane installate: scaricale o scegli un altro motore."
            else -> "${voices.size} voci italiane disponibili. Le voci «serve internet» funzionano solo online."
        }
        refresh()
    }

    /** Spinner che chiama [onPick] solo quando l'utente cambia davvero la scelta. */
    private fun bindSpinner(sp: Spinner, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.onItemSelectedListener = null
        sp.adapter = adapter
        sp.setSelection(selected, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveVoice(engine: String?, voice: String?) {
        Prefs.setTts(this, engine, voice, currentRate())
        // il servizio ricarica la voce subito, senza fermare l'ascolto
        WakeService.reload(this)
    }

    private fun testVoice() {
        val t = preview ?: return
        val name = Prefs.ttsVoice(this)
        val v = voices.firstOrNull { it.name == name } ?: voices.firstOrNull { !it.isNetworkConnectionRequired }
        t.language = java.util.Locale.ITALY
        if (v != null) t.voice = v
        t.setSpeechRate(currentRate())
        t.speak("Ciao, sono il tuo assistente. Chiamo Mario Rossi in vivavoce.", TextToSpeech.QUEUE_FLUSH, null, "prova")
    }

    // ---- frase di attivazione ----

    private fun saveWakePhrase() {
        val phrase = Text.spoken(wake.text.toString())
        val words = phrase.split(' ').filter { it.isNotEmpty() }
        if (phrase.length < 4) { wakeCheck.text = "Troppo corta."; return }
        wakeCheck.text = "Controllo che il modello conosca le parole…"
        Thread {
            val missing = try { ModelInstaller.missingWords(this, words) } catch (e: Exception) { null }
            runOnUiThread {
                if (missing != null && missing.isNotEmpty()) {
                    wakeCheck.text = "Il modello non conosce: ${missing.joinToString(", ")}. Scegli altre parole."
                    return@runOnUiThread
                }
                Prefs.setWakePhrase(this, phrase)
                wake.setText(phrase)
                WakeService.reload(this)
                wakeCheck.text = buildString {
                    append("Salvata: di' «$phrase».")
                    if (missing == null) append(" (Non ho potuto verificare le parole.)")
                    if (words.size == 1) append(" Con una parola sola scatta più spesso per sbaglio: meglio due.")
                }
            }
        }.start()
    }

    // ---- riconoscimento di sistema ----

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun onDeviceProbe(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return null
        return probe ?: try { SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { probe = it } } catch (e: Exception) { null }
    }

    /** Android 13+: chiede al riconoscitore del telefono se l'italiano offline è installato. */
    private fun checkItalianOffline() {
        if (Build.VERSION.SDK_INT < 33) return
        val sr = onDeviceProbe() ?: return
        try {
            sr.checkRecognitionSupport(recognitionIntent(), mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(r: RecognitionSupport) {
                    val it = { l: List<String> -> l.any { lang -> lang.lowercase().startsWith("it") } }
                    languageInfo = when {
                        it(r.installedOnDeviceLanguages) -> "Italiano offline: installato ✓"
                        it(r.pendingOnDeviceLanguages) -> "Italiano offline: in download…"
                        it(r.supportedOnDeviceLanguages) -> "Italiano offline: da scaricare (tocca il pulsante)"
                        else -> "Italiano offline: non disponibile su questo telefono"
                    }
                    refresh()
                }
                override fun onError(error: Int) {}
            })
        } catch (e: Exception) { }
    }

    private fun downloadItalian() {
        val sr = if (Build.VERSION.SDK_INT >= 33) onDeviceProbe() else null
        if (sr == null || Build.VERSION.SDK_INT < 33) {
            // versioni precedenti: si scarica dalle impostazioni della tastiera/voce Google
            open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            return
        }
        try { sr.triggerModelDownload(recognitionIntent()) } catch (e: Exception) { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        languageInfo = "Italiano offline: download richiesto"
        refresh()
    }

    private fun asrDescription(): String {
        if (!Prefs.systemAsr(this)) return "Uso solo Vosk (meno preciso)."
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val base = when {
            onDevice -> "Riconoscimento sul telefono disponibile: funziona anche senza internet."
            SpeechRecognizer.isRecognitionAvailable(this) ->
                "Riconoscimento Google disponibile. Offline solo se hai scaricato l'italiano."
            else -> "Nessun riconoscimento di sistema: installa o aggiorna l'app Google. Per ora uso Vosk."
        }
        return listOf(base, languageInfo, AssistantBus.asrStatus).filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ---- stato ----

    private fun refresh() {
        fun line(ok: Boolean, text: String) = (if (ok) "✓  " else "✗  ") + text
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        checks.text = listOf(
            line(granted(Manifest.permission.RECORD_AUDIO), "Microfono"),
            line(granted(Manifest.permission.READ_CONTACTS), "Rubrica"),
            line(granted(Manifest.permission.CALL_PHONE), "Chiamate"),
            line(CalendarStore.hasPermission(this), "Calendario"),
            line(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS), "Notifiche"),
            line(Settings.canDrawOverlays(this), "Mostra sopra altre app (serve a schermo bloccato)"),
            line(battery, "Nessun risparmio batteria sull'app"),
        ).joinToString("\n")
        status.text = AssistantBus.serviceStatus
        val r = release
        updateButton.visibility = if (r != null) View.VISIBLE else View.GONE
        updateButton.text = if (r != null) "⬇  Aggiorna a ${r.name.removePrefix("Versione ")}" else "Aggiorna"
        versionInfo.text = buildString {
            append("Versione ${Updater.installedName(this@MainActivity)}")
            when {
                r != null -> append(" · nuova versione disponibile")
                checkFailed -> append(" · non riesco a controllare gli aggiornamenti")
                checkedUpdates && !downloading -> append(" · aggiornata")
            }
            if (r != null && r.notes.isNotBlank()) append("\nNovità: ").append(r.notes.lineSequence().first())
            if (AssistantBus.updateStatus.isNotEmpty()) append("\n").append(AssistantBus.updateStatus)
        }
        val h = AssistantBus.wakeHeard
        heard.text = if (h.isEmpty()) "Sento: — (prova a dire la frase)" else "Sento: «$h»"
        val activation = Prefs.activation(this)
        toggle.visibility = if (activation == Prefs.Activation.GESTURE) View.GONE else View.VISIBLE
        toggle.text = if (WakeService.running && Prefs.voiceWake(this)) "Ferma ascolto" else "Avvia ascolto"
        heard.visibility = if (WakeService.running && Prefs.voiceWake(this)) View.VISIBLE else View.GONE
        // notifica fissa: solo con l'ascolto a voce; si può nascondere senza fermare l'ascolto
        val voice = activation != Prefs.Activation.GESTURE
        val hidden = Notifications.serviceHidden(this)
        findViewById<Button>(R.id.hide_notification).apply {
            visibility = if (voice) View.VISIBLE else View.GONE
            text = if (hidden) "Notifica di ascolto nascosta ✓ (tocca per riattivarla)" else "Nascondi la notifica di ascolto"
        }
        findViewById<TextView>(R.id.notification_info).apply {
            visibility = if (voice && !hidden) View.VISIBLE else View.GONE
            text = "Android obbliga a tenerla finché l'assistente ascolta, ma puoi nasconderla: " +
                "disattiva l'interruttore «Ascolto in background» (l'ascolto continua). " +
                "Su Android 14 e successivi puoi anche semplicemente scorrerla via."
        }
        val needsAssistant = activation != Prefs.Activation.VOICE
        assistSet.visibility = if (needsAssistant) View.VISIBLE else View.GONE
        assistInfo.visibility = if (needsAssistant) View.VISIBLE else View.GONE
        assistInfo.text = if (isAssistant()) {
            "✓ È l'assistente del telefono: tieni premuto il tasto di accensione (o fai il gesto " +
                "dell'assistente dall'angolo in basso). Funziona anche a schermo bloccato."
        } else {
            "✗ Non è ancora l'assistente del telefono: tocca il pulsante e scegli «${getString(R.string.app_name)}» " +
                "come «App assistente digitale». Nota: finché è scelta questa app, «Ok Google» non risponde."
        }
        asrInfo.text = asrDescription()
        downloadIt.visibility = if (Prefs.systemAsr(this)) View.VISIBLE else View.GONE
        val linked = SpotifyAccount.isLinked(this)
        spotifyInfo.text = when {
            !Actions.spotifyInstalled(this) -> "Spotify non è installato."
            linked -> "✓ Spotify collegato: la musica parte da sola, anche a telefono bloccato e in auto."
            else -> "Collega una volta il tuo account: accedi a Spotify nel browser, poi Spotify chiede il permesso " +
                "di comandare la musica. Senza collegamento si apre solo la ricerca.\n\n" +
                "Nella dashboard Spotify (Settings → Android packages) devono esserci:\n" +
                "Package name: $packageName\nSHA1: ${SpotifyAccount.signingSha1(this)}"
        }
        spotifyInfo.setTextIsSelectable(true)
        findViewById<Button>(R.id.spotify_link).text = if (linked) "Ricollega Spotify" else "Collega Spotify"
        findViewById<Button>(R.id.spotify_unlink).visibility = if (linked) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.car_info).text = buildString {
            append(if (CarMode.androidAutoConnected(this@MainActivity)) "Android Auto: collegato ✓" else "Android Auto: non collegato")
            append("\nIn auto: niente popup, scelte e conferme a voce, voce dell'assistente dalle casse dell'auto. ")
            append("Usa l'attivazione a voce: il tasto vocale del volante apre sempre l'assistente Google.")
        }
        ttsInfo.text = listOf(voiceInfo, AssistantBus.ttsStatus).filter { it.isNotEmpty() }.joinToString("\n")
        findViewById<TextView>(R.id.howto).text = getString(R.string.howto, Prefs.wakePhrase(this))
    }
}
