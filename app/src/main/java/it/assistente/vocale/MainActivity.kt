package it.assistente.vocale

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private var languageInfo = ""
    private var probe: SpeechRecognizer? = null
    private val listener = Runnable { refresh() }

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
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
            open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }

        findViewById<Button>(R.id.grant).setOnClickListener { requestPermissions(permissions, 1) }
        findViewById<Button>(R.id.overlay).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        toggle.setOnClickListener {
            if (WakeService.running) {
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
        if (Prefs.enabled(this) && !WakeService.running && granted(Manifest.permission.RECORD_AUDIO)) {
            WakeService.start(this)
        }
        checkItalianOffline()
        refresh()
    }

    override fun onPause() {
        AssistantBus.removeListener(listener)
        Prefs.setPlaces(this, home.text.toString(), work.text.toString())
        super.onPause()
    }

    override fun onDestroy() {
        probe?.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        refresh()
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
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
            line(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS), "Notifiche"),
            line(Settings.canDrawOverlays(this), "Mostra sopra altre app (serve a schermo bloccato)"),
            line(battery, "Nessun risparmio batteria sull'app"),
        ).joinToString("\n")
        status.text = AssistantBus.serviceStatus
        val h = AssistantBus.wakeHeard
        heard.visibility = if (WakeService.running) View.VISIBLE else View.GONE
        heard.text = if (h.isEmpty()) "Sento: — (prova a dire la frase)" else "Sento: «$h»"
        toggle.text = if (WakeService.running) "Ferma ascolto" else "Avvia ascolto"
        asrInfo.text = asrDescription()
        downloadIt.visibility = if (Prefs.systemAsr(this)) View.VISIBLE else View.GONE
        ttsInfo.text = AssistantBus.ttsStatus.ifEmpty {
            if (WakeService.running) "Controllo la voce…" else "Avvia l'ascolto per controllare la voce."
        }
        findViewById<TextView>(R.id.howto).text = getString(R.string.howto, Prefs.wakePhrase(this))
    }
}
