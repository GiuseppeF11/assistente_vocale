package it.assistente.vocale

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Schermata di configurazione: permessi, avvio/arresto dell'ascolto, indirizzi di casa e lavoro. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var toggle: Button
    private lateinit var home: EditText
    private lateinit var work: EditText
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
        checks = findViewById(R.id.checks)
        toggle = findViewById(R.id.toggle)
        home = findViewById(R.id.home)
        work = findViewById(R.id.work)
        home.setText(Prefs.home(this))
        work.setText(Prefs.work(this))
        findViewById<TextView>(R.id.howto).text = getString(R.string.howto, WakeService.WAKE_PHRASE)

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
        refresh()
    }

    override fun onPause() {
        AssistantBus.removeListener(listener)
        Prefs.setPlaces(this, home.text.toString(), work.text.toString())
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        refresh()
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

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
        toggle.text = if (WakeService.running) "Ferma ascolto" else "Avvia ascolto"
    }
}
