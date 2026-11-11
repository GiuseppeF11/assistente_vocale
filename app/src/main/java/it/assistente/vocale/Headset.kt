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

    /** Collega microfono e audio delle cuffie; [then] parte quando sono pronti (o, se non si riesce, col telefono). */
    fun connect(musicWasPlaying: Boolean, then: () -> Unit) {
        this.musicWasPlaying = musicWasPlaying
        if (btAudioDevices().isEmpty()) {
            // nessun dispositivo Bluetooth con microfono: si usa il telefono
            report("Nessuna cuffia con microfono trovata: usato il telefono")
            then(); return
        }
        val token = ++seq
        active = true
        startedAt = SystemClock.elapsedRealtime()
        scoUp = false
        routedTo = null
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
            // le cuffie, non l'orologio o altri apparecchi che sanno fare chiamate
            device = p?.let { pickHeadset(it) }
            // dice alle cuffie che il riconoscimento è partito: Android sposta su di loro il canale voce
            startVoiceRecognition(p, device)
            try {
                previousMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (e: Exception) { Log.w(TAG, "Modalità audio", e) }
            waitReady(token, then)
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

    /** Uscite Bluetooth col microfono: classiche (SCO) o LE Audio. Possono esserci anche orologi o l'auto. */
    private fun btAudioDevices(): List<AudioDeviceInfo> = try {
        val all = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices
        else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        all.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    } catch (e: Exception) { emptyList() }

    /** Indirizzi dei dispositivi su cui sta andando la musica (le cuffie; l'orologio non riproduce musica). */
    private fun musicAddresses(): Set<String> = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            .map { it.address.uppercase() }.filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) { emptySet() }

    private val watchWords = listOf("watch", "band", "orologio")

    private fun looksLikeWatch(name: String?) = name != null && watchWords.any { name.contains(it, ignoreCase = true) }

    /** Tra i dispositivi Bluetooth "vivavoce" collegati, le cuffie: audio e musica sì, orologi no. */
    @SuppressLint("MissingPermission")
    private fun pickHeadset(p: BluetoothHeadset): BluetoothDevice? {
        if (!hasBtPermission()) return null
        val music = musicAddresses()
        val list = try { p.connectedDevices } catch (e: Exception) { return null }
        return list.map { d ->
            val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: Exception) { null }
            val name = try { d.name } catch (e: Exception) { null }
            var score = 0
            if (d.address.uppercase() in music) score += 3
            if (major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) score += 2
            if (major == android.bluetooth.BluetoothClass.Device.Major.WEARABLE || looksLikeWatch(name)) score -= 10
            d to score
        }.filter { it.second > -5 }.maxByOrNull { it.second }?.first
    }

    /** Il canale voce da usare: quello delle cuffie scelte, o di quelle su cui va la musica; mai l'orologio. */
    @SuppressLint("MissingPermission")
    private fun pickTarget(elapsed: Long): AudioDeviceInfo? {
        val list = btAudioDevices()
        val d = device
        if (d != null) {
            val name = try { d.name } catch (e: Exception) { null }
            list.firstOrNull { it.address.equals(d.address, ignoreCase = true) }?.let { return it }
            list.firstOrNull { name != null && it.productName?.toString() == name }?.let { return it }
            // Android sta ancora spostando il canale voce sulle cuffie: si aspetta un attimo
            if (elapsed < 1500) return null
        }
        val music = musicAddresses()
        list.firstOrNull { it.address.uppercase() in music }?.let { return it }
        return list.filterNot { looksLikeWatch(it.productName?.toString()) }
            .sortedByDescending { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }.firstOrNull()
    }

    /** c'era musica: le cuffie devono passare dalla musica alla voce, serve più tempo prima del bip */
    private var musicWasPlaying = false

    /** dove è stato chiesto di portare il canale voce */
    private var routedTo: AudioDeviceInfo? = null

    /**
     * Porta voce e microfono sulle cuffie: dispositivo di comunicazione e il vecchio "SCO acceso", che è l'unico
     * a spostare sulle cuffie anche la registrazione del riconoscitore (altrimenti ascolta col telefono).
     */
    private fun route(target: AudioDeviceInfo) {
        routedTo = target
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.setCommunicationDevice(target)) Log.w(TAG, "Cuffie: canale voce rifiutato")
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

    private fun label(a: AudioDeviceInfo?) = a?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "?"

    /** Aspetta che il canale voce sulle cuffie sia aperto, altrimenti il bip e le prime parole si perdono. */
    @SuppressLint("MissingPermission")
    private fun waitReady(token: Int, then: () -> Unit) {
        if (token != seq) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val target = pickTarget(elapsed)
        // se il canale disponibile cambia (es. passa dall'orologio alle cuffie) si segue
        if (target != null && target.id != routedTo?.id) route(target)
        val routed = target != null && (Build.VERSION.SDK_INT < 31 || am.communicationDevice?.id == target.id)
        val p = proxy
        val d = device
        val headsetSays = if (p != null && d != null && hasBtPermission()) {
            try { p.isAudioConnected(d) } catch (e: Exception) { false }
        } else false
        val linkUp = target != null && when (target.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> scoUp || headsetSays
            else -> routed && elapsed >= 400 // LE Audio: nessun segnale da aspettare
        }
        if (routed && linkUp) {
            val kind = if (target!!.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "classiche" else "LE Audio"
            report("${label(target)} ($kind): canale voce aperto in $elapsed ms")
            // appena aperto il canale, i primi istanti di audio si perdono (di più se le cuffie stavano suonando musica):
            // un margine prima del bip
            main.postDelayed({ if (token == seq) then() }, if (musicWasPlaying) 750L else 350L)
            return
        }
        // il canale voce ce l'ha un altro dispositivo (es. l'orologio) e non passa alle cuffie: inutile aspettare
        val hopeless = target == null && elapsed >= 1500
        if (hopeless || elapsed >= 3500) {
            val names = btAudioDevices().joinToString { label(it) }.ifEmpty { "nessuno" }
            val wanted = d?.let { try { it.name } catch (e: Exception) { null } }
            report(when {
                !hasBtPermission() -> "canale voce non aperto · manca il permesso «Dispositivi nelle vicinanze»"
                target == null && wanted != null ->
                    "$wanted: Android non ha dato il canale voce alle cuffie (disponibile: $names). " +
                        "Prova a disattivare «Chiamate» per l'orologio nelle impostazioni Bluetooth"
                else -> "${label(target)}: canale voce non aperto (comunicazione=$routed, sco=$scoUp, cuffie=$headsetSays; disponibili: $names)"
            } + " · usato il telefono")
            // meglio il telefono con bip e voce normali che un canale voce muto. Le cuffie riprendono a ricevere
            // l'audio "musica" dopo una pausa: senza margine il bip arriverebbe tagliato
            release()
            main.postDelayed(then, 700L)
            return
        }
        main.postDelayed({ waitReady(token, then) }, 100)
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceRecognition(p: BluetoothHeadset?, d: BluetoothDevice?) {
        if (p == null || d == null || !hasBtPermission()) return
        try {
            // con le cuffie già in "riconoscimento" (sono loro ad averlo chiesto) può restituire false: va bene
            p.startVoiceRecognition(d)
        } catch (e: Exception) { Log.w(TAG, "Riconoscimento cuffie", e) }
    }

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
