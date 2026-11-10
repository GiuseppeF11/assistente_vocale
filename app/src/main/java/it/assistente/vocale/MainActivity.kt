package it.assistente.vocale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Schermata principale: a colpo d'occhio se l'assistente funziona, gli avvisi (permessi mancanti,
 * aggiornamenti) e l'elenco delle sezioni con un riassunto di una riga. Le impostazioni stanno nelle pagine.
 */
class MainActivity : Activity() {

    private lateinit var dot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var heard: TextView
    private lateinit var toggle: Button
    private lateinit var alerts: LinearLayout
    private lateinit var permAlert: Row
    private lateinit var updateAlert: Row
    private lateinit var version: TextView
    private val rows = HashMap<SectionKey, Row>()

    private var release: Updater.Release? = null
    private var checkedUpdates = false
    private var checkFailed = false
    private val listener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.bg))
            addView(content)
        })
        val f = Form(this, content)

        // --- intestazione ---
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(getColor(R.color.text))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(8), dp(8), 0)
        })
        version = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(dp(8), dp(2), dp(8), dp(16))
        }
        content.addView(version)

        // --- scheda di stato ---
        val status = f.card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        dot = View(this)
        top.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(getColor(R.color.text))
            typeface = Typeface.DEFAULT_BOLD
        }
        statusDetail = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_dim))
        }
        texts.addView(statusTitle); texts.addView(statusDetail)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        status.addView(top)
        heard = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.accent_text))
            setPadding(dp(48), 0, dp(20), dp(8))
        }
        status.addView(heard)
        toggle = f.button(status, "Avvia ascolto", primary = true) { toggleListening() }

        // --- avvisi (compaiono solo se serve) ---
        alerts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(alerts)
        val alertForm = Form(this, alerts)
        alertForm.header("Da sistemare")
        val alertCard = alertForm.card()
        permAlert = alertForm.row(alertCard, "Permessi mancanti", null, "⚠️") { SectionActivity.open(this, SectionKey.PERMISSIONS) }
        updateAlert = alertForm.row(alertCard, "Aggiornamento disponibile", null, "⬇️") { startUpdate() }

        // --- sezioni ---
        fun group(title: String, keys: List<SectionKey>) {
            f.header(title)
            val card = f.card()
            keys.forEach { k -> rows[k] = f.row(card, k.title, null, k.icon) { SectionActivity.open(this, k) } }
        }
        group("Assistente", listOf(SectionKey.ACTIVATION, SectionKey.RECOGNITION, SectionKey.VOICE))
        group("Azioni", listOf(SectionKey.CALLS, SectionKey.SPOTIFY, SectionKey.CALENDAR, SectionKey.PLACES))
        group("Sistema", listOf(SectionKey.CAR, SectionKey.ENERGY, SectionKey.PERMISSIONS))
        group("Aiuto", listOf(SectionKey.COMMANDS))
    }

    override fun onResume() {
        super.onResume()
        AssistantBus.addListener(listener)
        // dopo un riavvio (o se il sistema ha chiuso il servizio) basta riaprire l'app
        if (Prefs.voiceWake(this) && !WakeService.running && granted(Manifest.permission.RECORD_AUDIO)) {
            WakeService.start(this)
        }
        checkUpdates()
        refresh()
    }

    override fun onPause() {
        AssistantBus.removeListener(listener)
        super.onPause()
    }

    private fun toggleListening() {
        when {
            WakeService.running && Prefs.voiceWake(this) -> { Prefs.setEnabled(this, false); WakeService.stop(this) }
            !granted(Manifest.permission.RECORD_AUDIO) -> SectionActivity.open(this, SectionKey.PERMISSIONS)
            else -> { Prefs.setEnabled(this, true); WakeService.start(this) }
        }
        refresh()
    }

    // ---- aggiornamenti ----

    private fun checkUpdates() {
        if (checkedUpdates) return
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

    /** Apre il download nel browser (l'app non installa da sola: farebbe scattare Play Protect). */
    private fun startUpdate() {
        val r = release ?: return
        AssistantBus.update("Scarica il file e aprilo per installare ${r.name}.")
        openSafe(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)))
    }

    // ---- stato e riassunti ----

    private fun refresh() {
        val activation = Prefs.activation(this)
        val voice = activation != Prefs.Activation.GESTURE
        val listening = WakeService.running && Prefs.voiceWake(this)
        val serviceStatus = AssistantBus.serviceStatus

        // stato
        val (colorId, title, detail) = when {
            !voice -> Triple(R.color.working, "Pronto", "Si attiva con il tasto o il gesto dell'assistente")
            listening && serviceStatus.startsWith("In pausa") -> Triple(R.color.asking, "In pausa", serviceStatus.substringAfter(": "))
            listening && serviceStatus.startsWith("Errore") -> Triple(R.color.error, "Errore", serviceStatus.substringAfter(": "))
            listening -> Triple(R.color.working, "Attivo",
                "In attesa di «${Prefs.wakePhrase(this)}»" + if (activation == Prefs.Activation.BOTH) " · anche tasto e gesto" else "")
            else -> Triple(R.color.text_dim, "Spento", "L'ascolto a voce è fermo")
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(colorId)) }
        statusTitle.text = title
        statusDetail.text = detail
        val h = AssistantBus.wakeHeard
        heard.visibility = if (listening) View.VISIBLE else View.GONE
        heard.text = if (h.isEmpty()) "Sento: — prova a dire la frase" else "Sento: «$h»"
        toggle.visibility = if (voice) View.VISIBLE else View.GONE
        toggle.text = if (listening) "Ferma ascolto" else "Avvia ascolto"

        // avvisi
        val missing = PermissionsSection.missing(this)
        permAlert.visible = missing > 0
        permAlert.title.text = if (missing == 1) "Manca 1 permesso" else "Mancano $missing permessi"
        permAlert.set("Tocca per concederli")
        val r = release
        updateAlert.visible = r != null
        if (r != null) {
            updateAlert.title.text = "Aggiorna a ${r.name.removePrefix("Versione ")}"
            updateAlert.set(AssistantBus.updateStatus.ifEmpty { r.notes.lineSequence().firstOrNull()?.let { "Novità: $it" } })
        }
        alerts.visibility = if (missing > 0 || r != null) View.VISIBLE else View.GONE
        version.text = "Versione ${Updater.installedName(this)}" + when {
            r != null -> " · aggiornamento disponibile"
            checkFailed -> ""
            checkedUpdates -> " · aggiornata"
            else -> ""
        }

        // riassunti delle sezioni
        rows[SectionKey.ACTIVATION]?.set(when (activation) {
            Prefs.Activation.VOICE -> "A voce · «${Prefs.wakePhrase(this)}»"
            Prefs.Activation.GESTURE -> "Tasto o gesto"
            Prefs.Activation.BOTH -> "Voce, tasto e gesto · «${Prefs.wakePhrase(this)}»"
        })
        rows[SectionKey.RECOGNITION]?.set(if (Prefs.systemAsr(this)) "Riconoscimento del telefono" else "Solo Vosk")
        rows[SectionKey.VOICE]?.set(
            (if (Prefs.ttsVoice(this) == null) "Voce automatica" else "Voce scelta") + " · " +
                "contorno ${Prefs.glow(this).label.lowercase()} · " +
                (if (Prefs.feedbackSound(this) == Feedback.Sound.NONE) "senza segnale" else "${Prefs.feedbackSound(this).label.removeSuffix("…")}, ${Prefs.feedbackVolume(this)}%") +
                (if (Prefs.feedbackVibrate(this)) " · vibrazione" else "")
        )
        rows[SectionKey.CALLS]?.set(if (Prefs.speaker(this)) "Sempre in vivavoce" else "Vivavoce solo se lo chiedi")
        rows[SectionKey.SPOTIFY]?.set(when {
            !Actions.spotifyInstalled(this) -> "Spotify non installato"
            SpotifyAccount.isLinked(this) -> "Collegato ✓"
            else -> "Non collegato"
        })
        rows[SectionKey.CALENDAR]?.set(
            if (!CalendarStore.hasPermission(this)) "Manca il permesso" else CalendarStore.target(this)?.label ?: "Nessun calendario"
        )
        rows[SectionKey.PLACES]?.set(
            "Casa " + (if (Prefs.home(this).isNotEmpty()) "✓" else "—") + " · Lavoro " + (if (Prefs.work(this).isNotEmpty()) "✓" else "—")
        )
        rows[SectionKey.CAR]?.set(
            Prefs.carMode(this).label.substringBefore(" (") + if (CarMode.androidAutoConnected(this)) " · collegato" else ""
        )
        rows[SectionKey.ENERGY]?.set(
            Prefs.vad(this).label.substringBefore(" (").substringBefore(":") +
                if (Prefs.batteryPause(this)) " · pausa sotto ${Prefs.batteryThreshold(this)}%" else ""
        )
        rows[SectionKey.PERMISSIONS]?.set(if (missing == 0) "Tutti concessi ✓" else "Mancano $missing")
        rows[SectionKey.COMMANDS]?.set("Cosa puoi dire")
    }
}
