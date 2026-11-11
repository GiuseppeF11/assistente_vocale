package it.assistente.vocale

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/*
 * Gesto assistente delle cuffie Bluetooth (di solito la pressione lunga, o il tocco scelto nell'app delle cuffie).
 * Le cuffie mandano al telefono «avvia riconoscimento vocale» e Android apre l'app scelta per i comandi vocali:
 * se è questa, parte lo stesso dialogo del tasto di accensione, con microfono e voce sulle cuffie.
 */

/** Riceve il comando vocale delle cuffie (e quello "a mani libere" che Android usa a telefono bloccato). */
class HeadsetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // l'avvio va fatto mentre questa schermata è in primo piano: Android lo consente solo così
        try { WakeService.startHeadset(this) } catch (e: Exception) { Log.e(TAG, "Avvio dalle cuffie", e) }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** Microfono e voce dell'assistente sulle cuffie Bluetooth per la durata del dialogo. */
class HeadsetAudio(private val c: Context) {

    private val am = c.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var proxy: BluetoothHeadset? = null
    private var device: BluetoothDevice? = null
    private var proxyRequested = false
    /** cambia a ogni collegamento/rilascio: scarta richiami arrivati tardi */
    private var seq = 0

    /** Vero mentre l'audio passa per le cuffie. */
    @Volatile var active = false
        private set

    /** Collega microfono e audio delle cuffie; [then] parte quando sono pronti (al massimo dopo 2,5 s). */
    fun connect(then: () -> Unit) {
        val target = headsetDevice()
        if (target == null) { then(); return } // nessuna cuffia Bluetooth con microfono: si usa il telefono
        val token = ++seq
        active = true
        withProxy { p ->
            if (token != seq) return@withProxy
            proxy = p
            device = p?.let { connected(it) }
            // dice alle cuffie che il riconoscimento è partito e apre il canale voce
            startVoiceRecognition(p, device)
            route(target)
            val start = SystemClock.elapsedRealtime()
            waitReady(token, target, start, then)
        }
    }

    /** Riporta l'audio com'era (la musica torna in qualità piena). */
    fun release() {
        seq++
        if (!active) return
        active = false
        val p = proxy
        val d = device
        if (p != null && d != null) {
            try { if (hasBtPermission()) p.stopVoiceRecognition(d) } catch (e: Exception) { Log.w(TAG, "Fine riconoscimento cuffie", e) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
            }
        } catch (e: Exception) { Log.w(TAG, "Audio cuffie", e) }
        device = null
    }

    fun close() {
        release()
        proxy?.let { p ->
            try { c.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, p) } catch (_: Exception) { }
        }
        proxy = null
    }

    // ---------------------------------------------------------------------------------------------

    /** Le cuffie collegate col microfono: classiche (SCO) o LE Audio (es. Galaxy Buds recenti). */
    private fun headsetDevice(): AudioDeviceInfo? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    } catch (e: Exception) { null }

    private fun route(target: AudioDeviceInfo) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (!am.setCommunicationDevice(target)) Log.w(TAG, "Cuffie: canale voce rifiutato")
            } else {
                @Suppress("DEPRECATION") run { am.startBluetoothSco(); am.isBluetoothScoOn = true }
            }
        } catch (e: Exception) { Log.w(TAG, "Audio cuffie", e) }
    }

    /** Aspetta che il canale voce sia aperto, altrimenti le prime parole (e il bip) si perdono. */
    @SuppressLint("MissingPermission")
    private fun waitReady(token: Int, target: AudioDeviceInfo, start: Long, then: () -> Unit) {
        if (token != seq) return
        val elapsed = SystemClock.elapsedRealtime() - start
        val routed = Build.VERSION.SDK_INT < 31 || am.communicationDevice?.id == target.id
        val p = proxy
        val d = device
        val linkUp = when {
            target.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> elapsed >= 300
            p != null && d != null && hasBtPermission() -> try { p.isAudioConnected(d) } catch (e: Exception) { elapsed >= 900 }
            else -> elapsed >= 900 // senza il permesso Bluetooth non si può sapere: un attimo di margine
        }
        if ((routed && linkUp) || elapsed >= 2500) {
            if (elapsed >= 2500) Log.w(TAG, "Cuffie: canale voce non confermato, si prosegue")
            then()
            return
        }
        main.postDelayed({ waitReady(token, target, start, then) }, 100)
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecognition(p: BluetoothHeadset?, d: BluetoothDevice?) {
        if (p == null || d == null || !hasBtPermission()) return
        try {
            // con le cuffie già in "riconoscimento" (sono loro ad averlo chiesto) può restituire false: va bene
            p.startVoiceRecognition(d)
        } catch (e: Exception) { Log.w(TAG, "Riconoscimento cuffie", e) }
    }

    @SuppressLint("MissingPermission")
    private fun connected(p: BluetoothHeadset): BluetoothDevice? =
        if (!hasBtPermission()) null else try { p.connectedDevices.firstOrNull() } catch (e: Exception) { null }

    private fun withProxy(cb: (BluetoothHeadset?) -> Unit) {
        proxy?.let { cb(it); return }
        val adapter = if (hasBtPermission()) c.getSystemService(BluetoothManager::class.java)?.adapter else null
        if (adapter == null || proxyRequested) { cb(null); return }
        var done = false
        val finishOnce = { p: BluetoothHeadset? -> if (!done) { done = true; cb(p) } }
        val ok = try {
            proxyRequested = true
            adapter.getProfileProxy(c, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxyRequested = false
                    proxy = p as? BluetoothHeadset
                    main.post { finishOnce(proxy) }
                }
                override fun onServiceDisconnected(profile: Int) { proxy = null }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) { false }
        if (!ok) { proxyRequested = false; cb(null); return }
        // il collegamento al servizio Bluetooth di solito è istantaneo; se tarda si va avanti senza
        main.postDelayed({ proxyRequested = false; finishOnce(null) }, 700)
    }

    private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 ||
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
