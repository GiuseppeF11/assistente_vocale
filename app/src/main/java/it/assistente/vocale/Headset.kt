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

    companion object {
        /** esito dell'ultimo uso delle cuffie (diagnostica) */
        @Volatile var lastReport: String? = null
    }

    private val am = c.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var proxy: BluetoothHeadset? = null
    private var device: BluetoothDevice? = null
    private var proxyRequested = false
    /** cambia a ogni collegamento/rilascio: scarta richiami arrivati tardi */
    private var seq = 0

    /** il canale voce Bluetooth (SCO) risulta aperto, secondo il sistema audio */
    @Volatile private var scoUp = false
    private var receiverOn = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var startedAt = 0L
    private val scoReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: android.content.Intent) {
            val st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            scoUp = st == AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }

    /** Vero mentre l'audio passa per le cuffie. */
    @Volatile var active = false
        private set

    /** Collega microfono e audio delle cuffie; [then] parte quando sono pronti (al massimo dopo 2,5 s). */
    fun connect(then: () -> Unit) {
        val target = headsetDevice()
        if (target == null) {
            // nessuna cuffia Bluetooth con microfono: si usa il telefono
            report("Nessuna cuffia con microfono trovata: usato il telefono")
            then(); return
        }
        val token = ++seq
        active = true
        startedAt = SystemClock.elapsedRealtime()
        scoUp = false
        if (!receiverOn) {
            try {
                val f = android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(scoReceiver, f, Context.RECEIVER_EXPORTED)
                else c.registerReceiver(scoReceiver, f)
                receiverOn = true
            } catch (e: Exception) { Log.w(TAG, "Stato canale voce", e) }
        }
        withProxy { p ->
            if (token != seq) return@withProxy
            proxy = p
            device = p?.let { connected(it) }
            // dice alle cuffie che il riconoscimento è partito e apre il canale voce
            startVoiceRecognition(p, device)
            route(target)
            waitReady(token, target, startedAt, then)
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
            @Suppress("DEPRECATION") run { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        } catch (e: Exception) { Log.w(TAG, "Audio cuffie", e) }
        try {
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION)
                am.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
        } catch (e: Exception) { Log.w(TAG, "Modalità audio", e) }
        if (receiverOn) { try { c.unregisterReceiver(scoReceiver) } catch (_: Exception) { }; receiverOn = false }
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

    /**
     * Porta voce e microfono sulle cuffie. Servono tutti e tre i passi: la modalità "comunicazione" (il bip e la
     * voce seguono le cuffie), il dispositivo di comunicazione e il vecchio "SCO acceso", che è l'unico a spostare
     * sulle cuffie anche la registrazione del riconoscitore vocale (altrimenti ascolta col microfono del telefono).
     */
    private var routeRefused = false

    private fun route(target: AudioDeviceInfo) {
        routeRefused = false
        try {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) { Log.w(TAG, "Modalità audio", e) }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.setCommunicationDevice(target)) {
                routeRefused = true
                Log.w(TAG, "Cuffie: canale voce rifiutato")
            }
        } catch (e: Exception) { Log.w(TAG, "Audio cuffie", e) }
        if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            try {
                @Suppress("DEPRECATION") run { am.startBluetoothSco(); am.isBluetoothScoOn = true }
            } catch (e: Exception) { Log.w(TAG, "SCO", e) }
        }
    }

    /** Ultimo esito, mostrato in Attivazione → Cuffie Bluetooth. */
    private fun report(text: String) {
        lastReport = text
        Log.i(TAG, "Cuffie: " + text)
    }

    /** Aspetta che il canale voce sia aperto, altrimenti le prime parole (e il bip) si perdono. */
    @SuppressLint("MissingPermission")
    private fun waitReady(token: Int, target: AudioDeviceInfo, start: Long, then: () -> Unit) {
        if (token != seq) return
        val elapsed = SystemClock.elapsedRealtime() - start
        val routed = Build.VERSION.SDK_INT < 31 || am.communicationDevice?.id == target.id
        val p = proxy
        val d = device
        val headsetSays = if (p != null && d != null && hasBtPermission()) {
            try { p.isAudioConnected(d) } catch (e: Exception) { false }
        } else false
        val linkUp = when {
            target.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> elapsed >= 400 // LE Audio: nessun segnale da aspettare
            else -> scoUp || headsetSays
        }
        if ((routed && linkUp) || elapsed >= 3000) {
            val kind = if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "classiche" else "LE Audio"
            val name = target.productName?.toString()?.takeIf { it.isNotBlank() } ?: "cuffie"
            report(when {
                linkUp && routed -> "$name ($kind): canale voce aperto in $elapsed ms"
                !hasBtPermission() -> "$name: canale voce non confermato · manca il permesso «Dispositivi nelle vicinanze»"
                routeRefused -> "$name ($kind): Android ha rifiutato il canale voce"
                else -> "$name ($kind): canale voce non aperto dopo 3 s (comunicazione=${routed}, sco=${scoUp}, cuffie=${headsetSays})"
            })
            // appena aperto il canale, i primi istanti di audio spesso si perdono: un attimo di margine per il bip
            main.postDelayed({ if (token == seq) then() }, if (linkUp) 350L else 0L)
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
