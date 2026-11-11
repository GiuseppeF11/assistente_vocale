package it.assistente.vocale

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import it.assistente.vocale.logic.Text

/** Le sezioni delle impostazioni, con icona e titolo (usati anche nella schermata principale). */
enum class SectionKey(val icon: String, val title: String) {
    ACTIVATION("🎙️", "Attivazione"),
    RECOGNITION("🧠", "Riconoscimento"),
    VOICE("🔊", "Voce, suoni e aspetto"),
    CALLS("📞", "Chiamate"),
    SPOTIFY("🎵", "Spotify"),
    CALENDAR("📅", "Calendario"),
    PLACES("📍", "Luoghi"),
    CAR("🚗", "In auto"),
    ENERGY("🔋", "Risparmio energetico"),
    PERMISSIONS("🔐", "Permessi"),
    COMMANDS("💬", "Comandi"),
}

/** Una pagina di impostazioni: costruisce i suoi elementi e li aggiorna. */
abstract class Section(val a: SectionActivity) {
    abstract fun build(f: Form)
    open fun refresh() {}
    open fun resume() {}
    open fun pause() {}
    open fun destroy() {}
    open fun result(requestCode: Int, resultCode: Int, data: Intent?) {}
}

/** Contenitore delle pagine: barra con «indietro» e titolo, contenuto scorrevole. */
class SectionActivity : Activity() {

    companion object {
        private const val EXTRA = "section"
        fun open(c: Context, key: SectionKey) =
            c.startActivity(Intent(c, SectionActivity::class.java).putExtra(EXTRA, key.name))
    }

    private lateinit var section: Section
    private val listener = Runnable { section.refresh() }

    val permissions: Array<String>
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
        val key = runCatching { SectionKey.valueOf(intent.getStringExtra(EXTRA)!!) }.getOrDefault(SectionKey.ACTIVATION)
        section = when (key) {
            SectionKey.ACTIVATION -> ActivationSection(this)
            SectionKey.RECOGNITION -> RecognitionSection(this)
            SectionKey.VOICE -> VoiceSection(this)
            SectionKey.CALLS -> CallsSection(this)
            SectionKey.SPOTIFY -> SpotifySection(this)
            SectionKey.CALENDAR -> CalendarSection(this)
            SectionKey.PLACES -> PlacesSection(this)
            SectionKey.CAR -> CarSection(this)
            SectionKey.ENERGY -> EnergySection(this)
            SectionKey.PERMISSIONS -> PermissionsSection(this)
            SectionKey.COMMANDS -> CommandsSection(this)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.bg))
        }
        // barra in alto: ‹ Titolo
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(16), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER
            contentDescription = "Indietro"
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = "${key.icon}  ${key.title}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(getColor(R.color.text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        page.addView(bar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        section.build(Form(this, content))
    }

    override fun onResume() {
        super.onResume()
        AssistantBus.addListener(listener)
        section.resume()
        section.refresh()
    }

    override fun onPause() {
        AssistantBus.removeListener(listener)
        section.pause()
        super.onPause()
    }

    override fun onDestroy() {
        section.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // es. permesso Bluetooth appena concesso: il servizio rilegge le auto collegate
        WakeService.reload(this)
        section.resume()
        section.refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        section.result(requestCode, resultCode, data)
    }
}

// =================================================================================================
// Attivazione
// =================================================================================================

class ActivationSection(a: SectionActivity) : Section(a) {
    private lateinit var assistRow: Row
    private lateinit var assistButton: Button
    private lateinit var wakeCard: LinearLayout
    private lateinit var wakeHeader: View
    private lateinit var wakeEdit: EditText
    private lateinit var wakeCheck: TextView
    private lateinit var notifRow: Row
    private lateinit var notifCard: LinearLayout

    override fun build(f: Form) {
        f.header("Come si attiva")
        val modes = Prefs.Activation.values().toList()
        f.radioRows(
            f.card(),
            listOf(
                "A voce" to "Dici la frase di attivazione · microfono sempre in ascolto",
                "Con tasto o gesto" to "Tasto di accensione o gesto assistente · microfono solo quando lo chiami",
                "Entrambi" to "Voce, tasto e gesto",
            ),
            modes.indexOf(Prefs.activation(a)),
        ) { i -> setActivation(modes[i]) }

        val assistCard = f.card()
        assistRow = f.row(assistCard, "Assistente del telefono", null)
        assistButton = f.button(assistCard, "Imposta come assistente del telefono") {
            try { a.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (e: Exception) { a.openSafe(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }

        f.header("Frase di attivazione")
        wakeHeader = f.root.getChildAt(f.root.childCount - 1)
        wakeCard = f.card()
        val (_, et) = f.editRow(wakeCard, "Cosa dire per chiamarlo", Prefs.wakePhrase(a), "es. ok alfio", "Salva") { save(it) }
        wakeEdit = et
        wakeCheck = f.note("Meglio due parole che non dici spesso insieme (es. «ehi computer», «ok telefono»).", wakeCard)

        f.header("Notifica di ascolto")
        notifCard = f.card()
        notifRow = f.row(notifCard, "Notifica fissa", null) { a.openSafe(Notifications.serviceChannelSettings(a)) }
    }

    private fun setActivation(mode: Prefs.Activation) {
        Prefs.setActivation(a, mode)
        if (mode != Prefs.Activation.GESTURE && !Prefs.enabled(a)) Prefs.setEnabled(a, true)
        if (Prefs.voiceWake(a) && !WakeService.running && a.granted(Manifest.permission.RECORD_AUDIO)) WakeService.start(a)
        else WakeService.reload(a)
        refresh()
    }

    private fun isAssistant(): Boolean = try {
        a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (e: Exception) { false }

    private fun save(text: String) {
        val phrase = Text.spoken(text)
        val words = phrase.split(' ').filter { it.isNotEmpty() }
        if (phrase.length < 4) { wakeCheck.text = "Troppo corta."; return }
        wakeCheck.text = "Controllo che il modello conosca le parole…"
        Thread {
            val missing = try { ModelInstaller.missingWords(a, words) } catch (e: Exception) { null }
            a.runOnUiThread {
                if (missing != null && missing.isNotEmpty()) {
                    wakeCheck.text = "Il modello non conosce: ${missing.joinToString(", ")}. Scegli altre parole."
                    return@runOnUiThread
                }
                Prefs.setWakePhrase(a, phrase)
                wakeEdit.setText(phrase)
                WakeService.reload(a)
                wakeCheck.text = buildString {
                    append("✓ Salvata: di' «$phrase».")
                    if (missing == null) append(" (Non ho potuto verificare le parole.)")
                    if (words.size == 1) append(" Con una parola sola scatta più spesso per sbaglio: meglio due.")
                }
            }
        }.start()
    }

    override fun refresh() {
        val mode = Prefs.activation(a)
        val gesture = mode != Prefs.Activation.VOICE
        val voice = mode != Prefs.Activation.GESTURE
        (assistRow.view.parent as View).visibility = if (gesture) View.VISIBLE else View.GONE
        val isAssistant = isAssistant()
        assistRow.set(
            if (isAssistant) "✓ Attivo: tieni premuto il tasto di accensione o fai il gesto dall'angolo in basso. Funziona anche a schermo bloccato."
            else "✗ Non ancora impostato. Tocca il pulsante e scegli «${a.getString(R.string.app_name)}» come «App assistente digitale». " +
                "Finché è scelta questa app, «Ok Google» non risponde."
        )
        assistButton.visibility = if (isAssistant) View.GONE else View.VISIBLE
        wakeCard.visibility = if (voice) View.VISIBLE else View.GONE
        wakeHeader.visibility = wakeCard.visibility
        notifCard.visibility = if (voice) View.VISIBLE else View.GONE
        (notifCard.parent as LinearLayout).getChildAt((notifCard.parent as LinearLayout).indexOfChild(notifCard) - 1).visibility = notifCard.visibility
        notifRow.set(
            if (Notifications.serviceHidden(a)) "✓ Nascosta (l'ascolto continua). Tocca per riattivarla."
            else "Android la obbliga finché l'assistente ascolta. Tocca e disattiva «Ascolto in background» per nasconderla."
        )
    }
}

// =================================================================================================
// Riconoscimento
// =================================================================================================

class RecognitionSection(a: SectionActivity) : Section(a) {
    private lateinit var info: Row
    private lateinit var download: Button
    private var probe: SpeechRecognizer? = null
    private var languageInfo = ""

    override fun build(f: Form) {
        f.header("Motore per i comandi")
        val card = f.card()
        f.switchRow(card, "Riconoscimento del telefono", "Molto più preciso di Vosk; offline se l'italiano è scaricato", Prefs.systemAsr(a)) { on ->
            Prefs.setSystemAsr(a, on); WakeService.reload(a); refresh()
        }
        info = f.row(card, "Stato", null)
        download = f.button(card, "Scarica l'italiano offline") { downloadItalian() }
        f.note("La frase di attivazione la riconosce sempre Vosk, direttamente sul telefono.")
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun onDeviceProbe(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) return null
        return probe ?: try { SpeechRecognizer.createOnDeviceSpeechRecognizer(a).also { probe = it } } catch (e: Exception) { null }
    }

    override fun resume() {
        if (Build.VERSION.SDK_INT < 33) return
        val sr = onDeviceProbe() ?: return
        try {
            sr.checkRecognitionSupport(recognitionIntent(), a.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(r: RecognitionSupport) {
                    val has = { l: List<String> -> l.any { lang -> lang.lowercase().startsWith("it") } }
                    languageInfo = when {
                        has(r.installedOnDeviceLanguages) -> "Italiano offline: installato ✓"
                        has(r.pendingOnDeviceLanguages) -> "Italiano offline: in download…"
                        has(r.supportedOnDeviceLanguages) -> "Italiano offline: da scaricare"
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
        if (sr == null || Build.VERSION.SDK_INT < 33) { a.openSafe(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); return }
        try { sr.triggerModelDownload(recognitionIntent()) } catch (e: Exception) { a.openSafe(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        languageInfo = "Italiano offline: download richiesto"
        refresh()
    }

    override fun refresh() {
        val system = Prefs.systemAsr(a)
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
        val base = when {
            !system -> "Uso solo Vosk (meno preciso)."
            onDevice -> "Riconoscimento sul telefono disponibile."
            SpeechRecognizer.isRecognitionAvailable(a) -> "Riconoscimento Google disponibile (offline solo con l'italiano scaricato)."
            else -> "Nessun riconoscimento di sistema: installa o aggiorna l'app Google. Per ora uso Vosk."
        }
        info.set(listOf(base, if (system) languageInfo else "", AssistantBus.asrStatus).filter { it.isNotEmpty() }.joinToString("\n"))
        download.visibility = if (system && !languageInfo.contains("installato")) View.VISIBLE else View.GONE
    }

    override fun destroy() { probe?.destroy() }
}

// =================================================================================================
// Voce e suoni
// =================================================================================================

class VoiceSection(a: SectionActivity) : Section(a) {
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var rateBar: SeekBar
    private lateinit var voiceInfo: TextView
    private lateinit var soundSpinner: Spinner
    private lateinit var volumeBar: SeekBar
    private lateinit var vibrate: Switch
    private var preview: TextToSpeech? = null
    private var previewGen = 0
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()
    private var voices: List<Voice> = emptyList()
    private val feedback by lazy { Feedback(a) }
    private val glowPreview by lazy { EdgeGlow(a) }
    private val pickSound = 7

    override fun build(f: Form) {
        f.header("Voce dell'assistente")
        val card = f.card()
        engineSpinner = f.spinnerRow(card, "Motore", listOf("Carico…"), 0) {}.second
        voiceSpinner = f.spinnerRow(card, "Voce", listOf("Carico…"), 0) {}.second
        rateBar = f.sliderRow(card, 80, ((Prefs.ttsRate(a) - 0.6f) * 100).toInt().coerceIn(0, 80),
            { "Velocità: ${"%.2f".format(0.6f + it / 100f)}×" }) { saveVoice(Prefs.ttsEngine(a), Prefs.ttsVoice(a)) }.second
        f.button(card, "▶  Prova la voce", primary = true) { testVoice() }
        voiceInfo = f.note("", card)
        f.button(card, "Scarica altre voci italiane") {
            a.openSafe(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(Prefs.ttsEngine(a) ?: preview?.defaultEngine))
        }

        f.header("Segnale di ascolto")
        val s = f.card()
        soundSpinner = f.spinnerRow(s, "Suono", listOf(""), 0) {}.second
        bindSounds()
        volumeBar = f.sliderRow(s, 100, Prefs.feedbackVolume(a), { if (it == 0) "Volume: muto" else "Volume: $it%" }) {
            saveFeedback(); feedback.play()
        }.second
        vibrate = f.switchRow(s, "Vibrazione", "Una breve vibrazione quando inizia ad ascoltare", Prefs.feedbackVibrate(a)) { saveFeedback() }.second
        f.button(s, "▶  Prova il segnale") { feedback.play() }
        f.note("Il volume è relativo a quello multimediale del telefono.")

        f.header("Aspetto del popup")
        val look = f.card()
        val glows = Prefs.Glow.values().toList()
        f.spinnerRow(look, "Contorno luminoso dello schermo", glows.map { it.label }, glows.indexOf(Prefs.glow(a)),
            "Si accende quando chiami l'assistente e segue la tua voce") { i ->
            Prefs.setGlow(a, glows[i])
            if (glows[i] != Prefs.Glow.OFF) glowPreview.preview(glows[i])
        }
        f.button(look, "▶  Anteprima del contorno") { glowPreview.preview(Prefs.glow(a).takeIf { it != Prefs.Glow.OFF } ?: Prefs.Glow.STATUS) }
        f.switchRow(look, "Sfondo sfocato", "Sfoca l'app dietro il popup (Android 12+; altrimenti la scurisce appena)",
            Prefs.blur(a)) { Prefs.setBlur(a, it) }
        f.note("«Colore dello stato»: blu mentre ascolta, arancione quando chiede, verde quando esegue, rosso se c'è un errore. " +
            "Il contorno richiede il permesso «Mostra sopra altre app».")

        startPreview(Prefs.ttsEngine(a))
    }

    // ---- voce ----

    private fun currentRate() = 0.6f + rateBar.progress / 100f

    private fun startPreview(engine: String?) {
        preview?.shutdown()
        val gen = ++previewGen
        preview = TextToSpeech(a, { status -> if (gen == previewGen) onPreviewReady(status, engine) }, engine)
    }

    private fun onPreviewReady(status: Int, engine: String?) {
        val t = preview ?: return
        if (status != TextToSpeech.SUCCESS) { voiceInfo.text = "Motore vocale non disponibile"; return }
        engines = t.engines.orEmpty()
        val selectedEngine = engine ?: t.defaultEngine
        engineSpinner.bind(engines.map { it.label }, engines.indexOfFirst { it.name == selectedEngine }.coerceAtLeast(0)) { i ->
            val pkg = engines.getOrNull(i)?.name
            if (pkg != null && pkg != (Prefs.ttsEngine(a) ?: t.defaultEngine)) { saveVoice(pkg, null); startPreview(pkg) }
        }
        voices = try {
            t.voices.orEmpty()
                .filter { it.locale.language == "it" }
                .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
        } catch (e: Exception) { emptyList() }
        val labels = listOf("Automatica (la migliore offline)") + voices.mapIndexed { i, v ->
            val where = if (v.isNetworkConnectionRequired) "online" else "offline"
            val quality = if (v.quality >= Voice.QUALITY_HIGH) " · alta qualità" else ""
            "Voce ${i + 1} · $where$quality"
        }
        val saved = Prefs.ttsVoice(a)
        val selected = voices.indexOfFirst { it.name == saved }.let { if (it < 0) 0 else it + 1 }
        voiceSpinner.bind(labels, selected) { i ->
            val name = if (i == 0) null else voices.getOrNull(i - 1)?.name
            if (name != Prefs.ttsVoice(a)) { saveVoice(Prefs.ttsEngine(a) ?: t.defaultEngine, name); testVoice() }
        }
        voiceInfo.text = if (voices.isEmpty()) "Questo motore non ha voci italiane installate: scaricale o scegli un altro motore."
        else "${voices.size} voci italiane. Quelle «online» funzionano solo con internet."
    }

    private fun saveVoice(engine: String?, voice: String?) {
        Prefs.setTts(a, engine, voice, currentRate())
        WakeService.reload(a)
    }

    private fun testVoice() {
        val t = preview ?: return
        val name = Prefs.ttsVoice(a)
        val v = voices.firstOrNull { it.name == name } ?: voices.firstOrNull { !it.isNetworkConnectionRequired }
        t.language = java.util.Locale.ITALY
        if (v != null) t.voice = v
        t.setSpeechRate(currentRate())
        t.speak("Ciao, sono il tuo assistente. Chiamo Mario Rossi in vivavoce.", TextToSpeech.QUEUE_FLUSH, null, "prova")
    }

    // ---- segnale ----

    private fun bindSounds() {
        val sounds = Feedback.Sound.values().toList()
        soundSpinner.bind(sounds.map { soundLabel(it) }, sounds.indexOf(Prefs.feedbackSound(a))) { i ->
            val sound = sounds[i]
            if (sound == Feedback.Sound.CUSTOM) pickPhoneSound() else { saveFeedback(sound = sound); feedback.play() }
        }
    }

    private fun soundLabel(s: Feedback.Sound): String {
        if (s != Feedback.Sound.CUSTOM) return s.label
        val uri = Prefs.feedbackUri(a) ?: return s.label
        val title = try { RingtoneManager.getRingtone(a, Uri.parse(uri))?.getTitle(a) } catch (e: Exception) { null }
        return if (title != null) "Suono del telefono: $title" else s.label
    }

    private fun saveFeedback(sound: Feedback.Sound = Prefs.feedbackSound(a), uri: String? = Prefs.feedbackUri(a)) {
        Prefs.setFeedback(a, sound, uri, volumeBar.progress, vibrate.isChecked)
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun pickPhoneSound() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Segnale di ascolto")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        Prefs.feedbackUri(a)?.let { intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        try { a.startActivityForResult(intent, pickSound) } catch (e: Exception) { saveFeedback(sound = Feedback.Sound.BEEP) }
    }

    @Suppress("DEPRECATION")
    override fun result(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != pickSound) return
        val uri = if (resultCode == Activity.RESULT_OK) data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) else null
        if (uri != null) { saveFeedback(sound = Feedback.Sound.CUSTOM, uri = uri.toString()); feedback.play() }
        bindSounds()
    }

    override fun refresh() {
        if (::volumeBar.isInitialized) volumeBar.isEnabled = Prefs.feedbackSound(a) != Feedback.Sound.NONE
    }

    override fun destroy() {
        preview?.shutdown()
        feedback.release()
        glowPreview.hide()
    }
}

// =================================================================================================
// Chiamate, Spotify, Calendario, Luoghi, Auto, Energia
// =================================================================================================

class CallsSection(a: SectionActivity) : Section(a) {
    override fun build(f: Form) {
        f.header("Vivavoce")
        f.switchRow(f.card(), "Chiamate sempre in vivavoce", "Altrimenti solo quando lo dici: «chiama Mario in vivavoce»",
            Prefs.speaker(a)) { Prefs.setSpeaker(a, it) }
        f.note("In auto l'audio passa già dall'impianto della macchina. Se il tuo telefono ignora la richiesta, " +
            "l'app accende l'altoparlante appena la chiamata parte.")
    }
}

class SpotifySection(a: SectionActivity) : Section(a) {
    private lateinit var status: Row
    private lateinit var link: Button
    private lateinit var unlink: Button
    private lateinit var dashboard: LinearLayout

    override fun build(f: Form) {
        f.header("Account")
        val card = f.card()
        status = f.row(card, "Stato", null)
        link = f.button(card, "Collega Spotify", primary = true) {
            a.openSafe(Intent(Intent.ACTION_VIEW, SpotifyAccount.authorizeUrl(a)))
        }
        unlink = f.button(card, "Scollega") { SpotifyAccount.unlink(a); refresh() }

        f.header("Dati per la dashboard Spotify")
        dashboard = f.card()
        f.row(dashboard, "Package name", a.packageName).summary.setTextIsSelectable(true)
        f.row(dashboard, "SHA1", SpotifyAccount.signingSha1(a)).summary.setTextIsSelectable(true)
        f.row(dashboard, "Redirect URI", SpotifyAccount.REDIRECT).summary.setTextIsSelectable(true)
        f.note("Servono solo se il collegamento non riesce: devono coincidere con quelli in developer.spotify.com → la tua app → Settings.")
    }

    override fun refresh() {
        val installed = Actions.spotifyInstalled(a)
        val linked = SpotifyAccount.isLinked(a)
        status.set(when {
            !installed -> "Spotify non è installato."
            linked -> "✓ Collegato: la musica parte da sola, anche a telefono bloccato e in auto."
            else -> "Non collegato: si apre solo la ricerca. Accedi a Spotify nel browser, poi conferma il permesso nell'app Spotify."
        })
        link.text = if (linked) "Ricollega Spotify" else "Collega Spotify"
        link.visibility = if (installed) View.VISIBLE else View.GONE
        unlink.visibility = if (linked) View.VISIBLE else View.GONE
    }
}

class CalendarSection(a: SectionActivity) : Section(a) {
    private lateinit var card: LinearLayout
    private lateinit var info: TextView
    private var spinner: Spinner? = null
    private var shown: List<Long>? = null
    private lateinit var f: Form

    override fun build(f: Form) {
        this.f = f
        f.header("Dove scrivere gli eventi")
        card = f.card()
        info = f.note("Prova: «aggiungi al calendario cena con Marco domani alle 20». " +
            "Gli eventi con orario hanno un promemoria ${CalendarStore.REMINDER_MINUTES} minuti prima.")
    }

    override fun resume() {
        val cals = CalendarStore.writable(a)
        val ids = cals.map { it.id }
        if (ids == shown && spinner != null) return
        shown = ids
        card.removeAllViews()
        spinner = null
        if (cals.isEmpty()) {
            f.row(card, "Nessun calendario disponibile",
                if (CalendarStore.hasPermission(a)) "Non trovo calendari in cui scrivere." else "Concedi il permesso del calendario.")
            if (!CalendarStore.hasPermission(a)) f.button(card, "Concedi") { a.requestPermissions(a.permissions, 1) }
            return
        }
        val selected = CalendarStore.target(a)?.let { t -> cals.indexOfFirst { it.id == t.id } } ?: 0
        spinner = f.spinnerRow(card, "Calendario", cals.map { it.label }, selected.coerceAtLeast(0)) { i ->
            Prefs.setCalendarId(a, cals[i].id)
        }.second
    }
}

class PlacesSection(a: SectionActivity) : Section(a) {
    private lateinit var home: EditText
    private lateinit var work: EditText

    override fun build(f: Form) {
        f.header("Luoghi rapidi")
        val card = f.card()
        home = f.editRow(card, "Casa", Prefs.home(a), "Indirizzo di casa").second
        work = f.editRow(card, "Lavoro", Prefs.work(a), "Indirizzo del lavoro").second
        home.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        work.inputType = home.inputType
        f.note("Così basta dire «portami a casa» o «portami al lavoro». Si salvano da soli.")

        f.header("A telefono bloccato")
        val lock = f.card()
        f.switchRow(lock, "Avvia senza sbloccare", "La navigazione parte subito, senza chiederti il PIN o l'impronta",
            Prefs.navNoUnlock(a)) { Prefs.setNavNoUnlock(a, it) }
        f.note("Google Maps di solito mostra la guida sopra la schermata di blocco; per toccare la mappa serve comunque sbloccare. " +
            "Se Maps resta nascosto dietro il blocco, spegni questa opzione.\n\n" +
            "Per non dover sbloccare in auto: Impostazioni del telefono → Schermata di blocco → Smart Lock → " +
            "Dispositivi attendibili → aggiungi il Bluetooth dell'auto.")
    }

    override fun pause() = Prefs.setPlaces(a, home.text.toString(), work.text.toString())
}

class CarSection(a: SectionActivity) : Section(a) {
    private lateinit var status: Row

    override fun build(f: Form) {
        f.header("Modalità auto")
        val card = f.card()
        status = f.row(card, "Android Auto", null)
        val modes = Prefs.Car.values().toList()
        f.spinnerRow(card, "Quando attivarla", modes.map { it.label }, modes.indexOf(Prefs.carMode(a))) { i ->
            Prefs.setCarMode(a, modes[i]); refresh()
        }
        f.switchRow(card, "Conferma prima di agire", "«Chiamo Mario Rossi, confermi?» prima di chiamare o navigare",
            Prefs.carConfirm(a)) { Prefs.setCarConfirm(a, it) }
        f.note("In auto: niente popup, scelte e conferme a voce, voce dell'assistente dalle casse dell'auto. " +
            "Usa l'attivazione a voce: il tasto vocale del volante apre sempre l'assistente Google.")
    }

    override fun refresh() {
        status.set(if (CarMode.androidAutoConnected(a)) "✓ Collegato" else "Non collegato")
    }
}

class EnergySection(a: SectionActivity) : Section(a) {
    private lateinit var status: Row
    private lateinit var warning: TextView
    private lateinit var vadInfo: Row
    private lateinit var windowSpinner: Spinner
    private lateinit var carSpinner: Spinner
    private lateinit var lockSpinner: Spinner
    private lateinit var lockNote: TextView
    private lateinit var threshold: Spinner
    private val windows = listOf(15 to "per 15 secondi", 30 to "per 30 secondi", 60 to "per 1 minuto", -1 to "finché resta acceso")
    private val lockWindows = listOf(0 to "no", 15 to "per 15 secondi", 30 to "per 30 secondi", 60 to "per 1 minuto", -1 to "sempre (consumo alto)")
    private val carDurations = listOf(-1 to "per tutto il viaggio", 10 to "per i primi 10 minuti", 20 to "per i primi 20 minuti", 30 to "per i primi 30 minuti")
    private val thresholds = listOf(10, 15, 20, 25, 30)

    private fun changed() { WakeService.reload(a); refresh() }

    override fun build(f: Form) {
        status = f.row(f.card(), "Adesso", null)
        warning = f.note("")

        f.header("Quando ascoltare «${Prefs.wakePhrase(a)}»")
        val when_ = f.card()
        f.switchRow(when_, "Allo sblocco dello schermo", "Ogni volta che accendi o sblocchi il telefono", Prefs.listenScreen(a)) {
            Prefs.setListen(a, "listen_screen", it); changed()
        }
        windowSpinner = f.spinnerRow(when_, "Per quanto", windows.map { it.second },
            windows.indexOfFirst { it.first == Prefs.screenWindow(a) }.coerceAtLeast(1)) { i ->
            Prefs.setScreenWindow(a, windows[i].first); changed()
        }.second
        lockSpinner = f.spinnerRow(when_, "Dopo aver bloccato lo schermo", lockWindows.map { it.second },
            lockWindows.indexOfFirst { it.first == Prefs.afterLock(a) }.coerceAtLeast(0)) { i ->
            Prefs.setAfterLock(a, lockWindows[i].first); changed()
        }.second
        f.switchRow(when_, "In auto", "Con Android Auto o il Bluetooth dell'auto collegato", Prefs.listenCar(a)) { on ->
            Prefs.setListen(a, "listen_car", on)
            // per riconoscere l'autoradio Bluetooth serve il permesso «dispositivi nelle vicinanze»
            if (on && Build.VERSION.SDK_INT >= 31 && !a.granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            }
            changed()
        }
        carSpinner = f.spinnerRow(when_, "Per quanto", carDurations.map { it.second },
            carDurations.indexOfFirst { it.first == Prefs.carMinutes(a) }.coerceAtLeast(0)) { i ->
            Prefs.setCarMinutes(a, carDurations[i].first); changed()
        }.second
        f.switchRow(when_, "In carica", "Non in auto: in auto valgono le regole dell'auto", Prefs.listenCharging(a)) {
            Prefs.setListen(a, "listen_charging", it); changed()
        }
        f.switchRow(when_, "Sempre", "Microfono sempre aperto: consumo alto", Prefs.listenAlways(a)) {
            Prefs.setListen(a, "listen_always", it); changed()
        }
        lockNote = f.note("")
        f.note("Negli altri momenti il microfono è spento e non consuma: tieni premuto il tasto di accensione.")

        f.header("Protezioni")
        val guards = f.card()
        f.switchRow(guards, "Sospendi se il telefono è caldo", "Riprende quando si raffredda (utile in auto e in carica)", Prefs.heatGuard(a)) {
            Prefs.setListen(a, "heat_guard", it); changed()
        }
        val (_, sw) = f.switchRow(guards, "Sospendi con batteria scarica", "Riprende quando metti in carica", Prefs.batteryPause(a)) { on ->
            Prefs.setBatteryPause(a, on, Prefs.batteryThreshold(a)); changed()
        }
        threshold = f.spinnerRow(guards, "Soglia", thresholds.map { "Sotto il $it%" },
            thresholds.indexOf(Prefs.batteryThreshold(a)).coerceAtLeast(2)) { i ->
            Prefs.setBatteryPause(a, sw.isChecked, thresholds[i]); changed()
        }.second

        f.header("Filtro voce (in auto e in carica)")
        val vadCard = f.card()
        val modes = Prefs.Vad.values().toList()
        f.spinnerRow(vadCard, "Sensibilità", modes.map { it.label }, modes.indexOf(Prefs.vad(a))) { i ->
            Prefs.setVad(a, modes[i]); changed()
        }
        vadInfo = f.row(vadCard, "Come funziona", null)
    }

    override fun refresh() {
        status.set(AssistantBus.serviceStatus.ifEmpty { "—" })
        val mode = Prefs.activation(a)
        warning.visibility = if (mode == Prefs.Activation.VOICE) View.VISIBLE else View.GONE
        warning.text = "⚠️ Con l'attivazione «A voce», quando il microfono è spento non c'è modo di chiamarlo: " +
            "in Attivazione scegli «Entrambi» per avere anche il tasto di accensione."
        windowSpinner.isEnabled = Prefs.listenScreen(a)
        carSpinner.isEnabled = Prefs.listenCar(a)
        val afterLock = Prefs.afterLock(a)
        lockNote.visibility = if (afterLock != 0) View.VISIBLE else View.GONE
        lockNote.text = if (afterLock < 0)
            "⚠️ «Sempre» dopo il blocco tiene il microfono acceso per tutto il tempo a schermo spento: è la situazione che consuma di più."
        else "Quando blocchi lo schermo (o si spegne da solo) il microfono resta acceso per altri $afterLock secondi, poi si spegne."
        threshold.isEnabled = Prefs.batteryPause(a)
        vadInfo.set(if (Prefs.vad(a) == Prefs.Vad.OFF) "Il riconoscitore analizza tutto l'audio: consuma di più."
        else "Durante i viaggi e in carica il riconoscitore si accende solo quando qualcuno parla. " +
            "Allo sblocco dello schermo invece ascolta sempre a sensibilità piena.")
    }
}

// =================================================================================================
// Permessi
// =================================================================================================

class PermissionsSection(a: SectionActivity) : Section(a) {
    private data class Item(val title: String, val why: String, val ok: () -> Boolean, val fix: () -> Unit)
    private val rows = ArrayList<Pair<Item, Row>>()

    override fun build(f: Form) {
        val ask = { a.requestPermissions(a.permissions, 1) }
        val items = listOf(
            Item("Microfono", "Per sentire la frase e i comandi", { a.granted(Manifest.permission.RECORD_AUDIO) }, ask),
            Item("Rubrica", "Per trovare i contatti da chiamare", { a.granted(Manifest.permission.READ_CONTACTS) }, ask),
            Item("Chiamate", "Per avviare le chiamate", { a.granted(Manifest.permission.CALL_PHONE) }, ask),
            Item("Calendario", "Per aggiungere eventi", { CalendarStore.hasPermission(a) }, ask),
            Item("Dispositivi nelle vicinanze", "Per riconoscere l'autoradio Bluetooth",
                { Build.VERSION.SDK_INT < 31 || a.granted(Manifest.permission.BLUETOOTH_CONNECT) }) {
                if (Build.VERSION.SDK_INT >= 31) a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            },
            Item("Notifiche", "Avvisi dopo riavvio o aggiornamento",
                { Build.VERSION.SDK_INT < 33 || a.granted(Manifest.permission.POST_NOTIFICATIONS) }, ask),
            Item("Mostra sopra altre app", "Per il popup e a schermo bloccato", { Settings.canDrawOverlays(a) }) {
                a.openSafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
            },
            Item("Nessun risparmio batteria", "Per non essere chiusa dal sistema",
                { a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName) }) {
                a.openSafe(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
            },
        )
        f.header("Permessi")
        val card = f.card()
        items.forEach { item -> rows += item to f.row(card, item.title, item.why) { item.fix() } }
        f.note("Tocca una voce mancante per concederla.")
    }

    override fun refresh() {
        rows.forEach { (item, row) ->
            val ok = item.ok()
            row.title.text = (if (ok) "✓  " else "✗  ") + item.title
            row.title.setTextColor(a.getColor(if (ok) R.color.text else R.color.error))
        }
    }

    companion object {
        /** Quanti permessi mancano (per l'avviso nella schermata principale). */
        fun missing(c: Context): Int = listOf(
            c.granted(Manifest.permission.RECORD_AUDIO),
            c.granted(Manifest.permission.READ_CONTACTS),
            c.granted(Manifest.permission.CALL_PHONE),
            CalendarStore.hasPermission(c),
            Build.VERSION.SDK_INT < 33 || c.granted(Manifest.permission.POST_NOTIFICATIONS),
            Settings.canDrawOverlays(c),
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
        ).count { !it }
    }
}

// =================================================================================================
// Comandi
// =================================================================================================

class CommandsSection(a: SectionActivity) : Section(a) {
    override fun build(f: Form) {
        f.note("Di' «${Prefs.wakePhrase(a)}» (o usa il tasto/gesto), aspetta il segnale e parla.")
        val groups = listOf(
            "📞 Chiamate" to listOf("chiama Mario Rossi", "chiama mamma sul cellulare", "chiama Luca in vivavoce", "chiama il numero tre tre tre…"),
            "🗺️ Navigazione" to listOf("portami a Catania", "naviga verso via Etnea 120", "portami a casa / al lavoro"),
            "🎵 Musica" to listOf("metti Albachiara da Spotify", "metti l'album Nevermind", "metti la playlist Relax", "fammi sentire qualcosa dei Coldplay"),
            "⏰ Sveglie e timer" to listOf("sveglia alle sette e mezza", "svegliami tra 20 minuti", "timer di 5 minuti"),
            "📅 Calendario" to listOf("aggiungi al calendario cena con Marco domani alle 20", "crea un evento riunione lunedì dalle 10 alle 11",
                "ricordami di chiamare l'idraulico tra 3 giorni"),
            "✋ Sempre" to listOf("annulla", "lascia stare"),
        )
        groups.forEach { (title, examples) ->
            f.header(title)
            val card = f.card()
            examples.forEach { f.row(card, "«$it»") }
        }
    }
}
