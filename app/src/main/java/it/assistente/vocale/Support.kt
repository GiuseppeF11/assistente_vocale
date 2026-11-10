package it.assistente.vocale

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.speech.RecognitionService
import android.telecom.TelecomManager
import android.util.Log
import it.assistente.vocale.logic.Contact
import it.assistente.vocale.logic.MediaFocus
import it.assistente.vocale.logic.NumberKind
import it.assistente.vocale.logic.PhoneNumber
import it.assistente.vocale.logic.PlayRequest
import it.assistente.vocale.logic.Text
import java.io.File

const val TAG = "Assistente"

fun Context.granted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------------------------------------

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("assistente", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    const val DEFAULT_WAKE = "ciao assistente"

    /** Come si attiva l'assistente. */
    enum class Activation { VOICE, GESTURE, BOTH }

    fun activation(c: Context): Activation =
        runCatching { Activation.valueOf(sp(c).getString("activation", "VOICE")!!) }.getOrDefault(Activation.VOICE)
    fun setActivation(c: Context, v: Activation) = sp(c).edit().putString("activation", v.name).apply()

    /** Frase di attivazione attiva: modalità a voce (o entrambe) e ascolto acceso dall'utente. */
    fun voiceWake(c: Context) = activation(c) != Activation.GESTURE && enabled(c)

    // segnale quando inizia l'ascolto
    fun feedbackSound(c: Context): Feedback.Sound =
        runCatching { Feedback.Sound.valueOf(sp(c).getString("fb_sound", "BEEP")!!) }.getOrDefault(Feedback.Sound.BEEP)
    fun feedbackUri(c: Context): String? = sp(c).getString("fb_uri", null)
    fun feedbackVolume(c: Context): Int = sp(c).getInt("fb_volume", 80)
    fun feedbackVibrate(c: Context): Boolean = sp(c).getBoolean("fb_vibrate", false)
    fun setFeedback(c: Context, sound: Feedback.Sound, uri: String?, volume: Int, vibrate: Boolean) = sp(c).edit()
        .putString("fb_sound", sound.name).putString("fb_uri", uri)
        .putInt("fb_volume", volume).putBoolean("fb_vibrate", vibrate).apply()

    // risparmio energetico
    enum class Vad(val label: String, val sensitivity: it.assistente.vocale.logic.VoiceDetector.Sensitivity?) {
        OFF("Disattivato: riconoscitore sempre acceso", null),
        HIGH("Sensibilità alta (sente anche voci lontane)", it.assistente.vocale.logic.VoiceDetector.Sensitivity.HIGH),
        MEDIUM("Sensibilità media (consigliata)", it.assistente.vocale.logic.VoiceDetector.Sensitivity.MEDIUM),
        LOW("Sensibilità bassa (consuma meno, parla vicino)", it.assistente.vocale.logic.VoiceDetector.Sensitivity.LOW),
    }
    fun vad(c: Context): Vad = runCatching { Vad.valueOf(sp(c).getString("vad", "MEDIUM")!!) }.getOrDefault(Vad.MEDIUM)
    fun setVad(c: Context, v: Vad) = sp(c).edit().putString("vad", v.name).apply()
    fun voiceDetector(c: Context) = vad(c).sensitivity
    fun batteryPause(c: Context) = sp(c).getBoolean("battery_pause", true)
    fun batteryThreshold(c: Context) = sp(c).getInt("battery_threshold", 20)
    fun setBatteryPause(c: Context, on: Boolean, threshold: Int) =
        sp(c).edit().putBoolean("battery_pause", on).putInt("battery_threshold", threshold).apply()

    // calendario in cui scrivere gli eventi (-1 = principale)
    fun calendarId(c: Context): Long = sp(c).getLong("calendar", -1)
    fun setCalendarId(c: Context, id: Long) = sp(c).edit().putLong("calendar", id).apply()

    // modalità auto
    enum class Car(val label: String) { AUTO("Automatica (quando è collegato Android Auto)"), ALWAYS("Sempre attiva"), NEVER("Disattivata") }
    fun carMode(c: Context): Car = runCatching { Car.valueOf(sp(c).getString("car", "AUTO")!!) }.getOrDefault(Car.AUTO)
    fun setCarMode(c: Context, v: Car) = sp(c).edit().putString("car", v.name).apply()
    fun carConfirm(c: Context) = sp(c).getBoolean("car_confirm", true)
    fun setCarConfirm(c: Context, v: Boolean) = sp(c).edit().putBoolean("car_confirm", v).apply()

    // voce dell'assistente: null = automatico
    fun ttsEngine(c: Context): String? = sp(c).getString("tts_engine", null)
    fun ttsVoice(c: Context): String? = sp(c).getString("tts_voice", null)
    fun ttsRate(c: Context): Float = sp(c).getFloat("tts_rate", 1.05f)
    fun setTts(c: Context, engine: String?, voice: String?, rate: Float) = sp(c).edit()
        .putString("tts_engine", engine).putString("tts_voice", voice).putFloat("tts_rate", rate).apply()

    fun wakePhrase(c: Context): String =
        Text.spoken(sp(c).getString("wake", DEFAULT_WAKE)!!).ifEmpty { DEFAULT_WAKE }
    fun setWakePhrase(c: Context, v: String) = sp(c).edit().putString("wake", Text.spoken(v)).apply()

    /** chiamate sempre in vivavoce */
    fun speaker(c: Context) = sp(c).getBoolean("speaker", false)
    fun setSpeaker(c: Context, v: Boolean) = sp(c).edit().putBoolean("speaker", v).apply()

    /** riconoscimento dei comandi con il motore del telefono (Google) invece di Vosk */
    fun systemAsr(c: Context) = sp(c).getBoolean("system_asr", true)
    fun setSystemAsr(c: Context, v: Boolean) = sp(c).edit().putBoolean("system_asr", v).apply()

    fun home(c: Context): String = sp(c).getString("home", "")!!.trim()
    fun work(c: Context): String = sp(c).getString("work", "")!!.trim()
    fun setPlaces(c: Context, home: String, work: String) =
        sp(c).edit().putString("home", home.trim()).putString("work", work.trim()).apply()
}

// ---------------------------------------------------------------------------------------------

/** Copia il modello Vosk dagli assets alla memoria interna (Vosk legge solo da file veri). */
object ModelInstaller {
    private const val ASSET_DIR = "model-it"

    @Synchronized
    fun ensure(c: Context): String {
        val target = File(c.filesDir, ASSET_DIR)
        val marker = File(target, ".installed")
        val stamp = c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == stamp) return target.absolutePath

        if (c.assets.list(ASSET_DIR).isNullOrEmpty()) {
            throw IllegalStateException("Modello vocale mancante: vedi README (cartella assets/$ASSET_DIR)")
        }
        target.deleteRecursively()
        copy(c, ASSET_DIR, target)
        marker.writeText(stamp)
        return target.absolutePath
    }

    /**
     * Parole della frase che il modello non conosce (Vosk le ignorerebbe e la frase non scatterebbe mai).
     * null se il modello non include l'elenco delle parole.
     */
    fun missingWords(c: Context, words: List<String>): List<String>? {
        val vocab = File(ensure(c), "graph/words.txt")
        if (!vocab.exists()) return null
        val wanted = words.toMutableSet()
        vocab.bufferedReader().useLines { lines ->
            for (line in lines) {
                val w = line.substringBefore(' ')
                wanted.remove(w)
                if (wanted.isEmpty()) break
            }
        }
        return words.filter { it in wanted }
    }

    private fun copy(c: Context, assetPath: String, dest: File) {
        val children = c.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            c.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copy(c, "$assetPath/$child", File(dest, child))
        }
    }
}

// ---------------------------------------------------------------------------------------------

object ContactsRepository {

    /** Tutti i contatti con almeno un numero; i doppioni con lo stesso nome vengono fusi. */
    fun load(c: Context): List<Contact> {
        if (!c.granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val byName = LinkedHashMap<String, Pair<String, MutableList<PhoneNumber>>>()
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY)
        c.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0)?.trim().orEmpty()
                val number = cur.getString(1)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                val type = cur.getInt(2)
                val label = Phone.getTypeLabel(c.resources, type, cur.getString(3)).toString()
                val entry = byName.getOrPut(Text.normalize(name)) { name to ArrayList() }
                val key = digits(number)
                if (entry.second.none { digits(it.number) == key }) {
                    entry.second.add(PhoneNumber(number, label, kindOf(type), cur.getInt(4) != 0))
                }
            }
        }
        return byName.values.map { Contact(it.first, it.second) }
    }

    private fun digits(n: String) = n.filter { it.isDigit() }.takeLast(9)

    private fun kindOf(type: Int) = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> NumberKind.MOBILE
        Phone.TYPE_HOME -> NumberKind.HOME
        Phone.TYPE_WORK, Phone.TYPE_COMPANY_MAIN -> NumberKind.WORK
        else -> NumberKind.OTHER
    }
}

// ---------------------------------------------------------------------------------------------

object Actions {

    /** Avvia la chiamata tramite il sistema: funziona anche a schermo bloccato. */
    fun call(c: Context, number: String, speaker: Boolean): Boolean {
        if (!c.granted(Manifest.permission.CALL_PHONE)) return false
        return try {
            val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, speaker) }
            c.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), extras)
            if (speaker) ensureSpeaker(c)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Chiamata non riuscita", e)
            false
        }
    }

    /**
     * Ripiego per i telefoni che ignorano la richiesta di vivavoce: appena la chiamata è attiva
     * accende l'altoparlante (una volta sola, così l'utente può poi spegnerlo).
     */
    @Suppress("DEPRECATION")
    private fun ensureSpeaker(c: Context) {
        val am = c.getSystemService(AudioManager::class.java)
        val main = Handler(Looper.getMainLooper())
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
                if (inCall) {
                    if (!am.isSpeakerphoneOn) am.isSpeakerphoneOn = true
                } else if (++tries < 15) {
                    main.postDelayed(this, 600)
                }
            }
        }
        main.postDelayed(check, 800)
    }

    /** Sveglia nell'app Orologio, senza aprirla. */
    fun setAlarm(c: Context, hour: Int, minute: Int): Boolean = startQuietly(
        c,
        Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Assistente")
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true),
    )

    /** Timer nell'app Orologio, senza aprirla. */
    fun setTimer(c: Context, seconds: Int): Boolean = startQuietly(
        c,
        Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Assistente")
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true),
    )

    private fun startQuietly(c: Context, intent: Intent): Boolean = try {
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Orologio", e)
        false
    }

    const val SPOTIFY = "com.spotify.music"

    fun spotifyInstalled(c: Context) = c.packageManager.getLaunchIntentForPackage(SPOTIFY) != null

    /**
     * Chiede a Spotify di cercare e riprodurre (lo stesso meccanismo usato dagli assistenti vocali).
     * Se la versione installata non lo accetta, apre almeno la ricerca.
     */
    /** Parametri della ricerca vocale (stessi per l'intent e per il lettore di Spotify). */
    @Suppress("DEPRECATION")
    fun searchExtras(req: PlayRequest): Bundle = Bundle().apply {
        putString(SearchManager.QUERY, req.query)
        putString(
            MediaStore.EXTRA_MEDIA_FOCUS,
            when (req.focus) {
                MediaFocus.SONG -> "vnd.android.cursor.item/audio"
                MediaFocus.ARTIST -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
                MediaFocus.ALBUM -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
                MediaFocus.PLAYLIST -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
                MediaFocus.ANY -> "vnd.android.cursor.item/*"
            }
        )
        when (req.focus) {
            MediaFocus.SONG -> putString(MediaStore.EXTRA_MEDIA_TITLE, req.query)
            MediaFocus.ARTIST -> putString(MediaStore.EXTRA_MEDIA_ARTIST, req.query)
            MediaFocus.ALBUM -> putString(MediaStore.EXTRA_MEDIA_ALBUM, req.query)
            MediaFocus.PLAYLIST -> putString(MediaStore.EXTRA_MEDIA_PLAYLIST, req.query)
            MediaFocus.ANY -> Unit
        }
    }

    /** Ripiego: apre Spotify sulla ricerca (non fa partire la musica da solo). */
    fun play(c: Context, req: PlayRequest): Boolean {
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SPOTIFY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtras(searchExtras(req))
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(req.query)))
            .setPackage(SPOTIFY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(search, playIntent)) {
            try { c.startActivity(intent); return true } catch (_: ActivityNotFoundException) { }
        }
        return false
    }

    fun run(c: Context, launch: AssistantBus.Launch): Boolean = when (launch) {
        is AssistantBus.Launch.Navigate -> navigate(c, launch.destination)
        is AssistantBus.Launch.Play -> play(c, launch.request)
    }

    /** Apre la navigazione guidata; va chiamata da una Activity a telefono sbloccato. */
    fun navigate(c: Context, destination: String): Boolean {
        val q = Uri.encode(destination)
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=d"))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(maps, any)) {
            try { c.startActivity(intent); return true } catch (_: ActivityNotFoundException) { }
        }
        return false
    }
}

// ---------------------------------------------------------------------------------------------

object Notifications {
    const val ID_SERVICE = 1
    const val ID_WAKE = 2
    const val ID_BOOT = 3
    /** canale "minimo": niente icona nella barra di stato, notifica compressa in fondo */
    const val CH_SERVICE = "ascolto_silenzioso"
    private const val CH_SERVICE_OLD = "ascolto"
    private const val CH_ALERT = "attivazione"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun channels(c: Context) {
        nm(c).deleteNotificationChannel(CH_SERVICE_OLD)
        nm(c).createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Ascolto in background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Obbligatoria per Android finché l'assistente ascolta. Non mostra icone."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        nm(c).createNotificationChannel(
            NotificationChannel(CH_ALERT, "Attivazione assistente", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) }
        )
    }

    private fun activity(c: Context, cls: Class<*>, code: Int): PendingIntent = PendingIntent.getActivity(
        c, code, Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Notifica fissa del servizio in ascolto, con il pulsante per fermarlo. */
    fun service(c: Context, paused: String? = null): Notification {
        channels(c)
        val stop = PendingIntent.getService(
            c, 10, Intent(c, WakeService::class.java).setAction(WakeService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(c, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(paused ?: "In attesa di «${Prefs.wakePhrase(c)}»")
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(activity(c, MainActivity::class.java, 11))
            .addAction(Notification.Action.Builder(null, "Ferma", stop).build())
            .build()
    }

    /**
     * Ripiego se manca il permesso "Mostra sopra altre app": una notifica a schermo intero
     * apre comunque la schermata dell'assistente quando il telefono è bloccato.
     */
    fun wake(c: Context) {
        channels(c)
        val pi = activity(c, AssistantActivity::class.java, 12)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Ti ascolto…")
            .setContentText("Tocca per vedere l'assistente")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm(c).notify(ID_WAKE, n)
    }

    fun cancelWake(c: Context) = nm(c).cancel(ID_WAKE)

    fun refreshService(c: Context, paused: String? = null) = nm(c).notify(ID_SERVICE, service(c, paused))

    /** L'utente ha disattivato la categoria "Ascolto in background"? (l'ascolto continua, la notifica no) */
    fun serviceHidden(c: Context): Boolean {
        channels(c)
        return nm(c).getNotificationChannel(CH_SERVICE)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Apre direttamente la categoria "Ascolto in background" nelle impostazioni di Android. */
    fun serviceChannelSettings(c: Context): Intent {
        channels(c)
        return Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_SERVICE)
    }

    /** Dopo un riavvio Android non permette di riaccendere il microfono da soli: lo chiediamo all'utente. */
    fun boot(c: Context, title: String = "Assistente vocale spento", text: String = "Tocca per riattivare l'ascolto dopo il riavvio") {
        channels(c)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activity(c, MainActivity::class.java, 13))
            .setAutoCancel(true)
            .build()
        nm(c).notify(ID_BOOT, n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.voiceWake(context)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Notifications.boot(context)
            // dopo un aggiornamento Android chiude l'app: il microfono si riattiva solo aprendola
            Intent.ACTION_MY_PACKAGE_REPLACED -> Notifications.boot(
                context, "Assistente aggiornato alla ${Updater.installedName(context)}", "Tocca per riattivare l'ascolto",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------

/**
 * Il riconoscitore vocale di Google, indicato esplicitamente.
 * Quando questa app è l'assistente del telefono, il riconoscitore "predefinito" diventa il nostro
 * (RecognizerProxyService): senza indicarlo esplicitamente si finirebbe a chiamare noi stessi.
 */
object Recognizers {
    private val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

    fun google(c: Context): ComponentName? {
        val services = c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != c.packageName }
        val best = services.minByOrNull { preferred.indexOf(it.packageName).let { i -> if (i < 0) 99 else i } }
            ?: return null
        return ComponentName(best.packageName, best.name)
    }
}

// ---------------------------------------------------------------------------------------------

/** Segnale "ti ascolto": tono, suono a scelta del telefono, nessun suono; più vibrazione facoltativa. */
class Feedback(private val c: Context) {

    enum class Sound(val label: String, val tone: Int, val durationMs: Int) {
        BEEP("Bip", android.media.ToneGenerator.TONE_PROP_BEEP, 150),
        DOUBLE("Doppio bip", android.media.ToneGenerator.TONE_PROP_BEEP2, 300),
        ACK("Conferma", android.media.ToneGenerator.TONE_PROP_ACK, 250),
        PIP("Pip breve", android.media.ToneGenerator.TONE_CDMA_PIP, 120),
        CUSTOM("Suono del telefono…", -1, 0),
        NONE("Nessun suono", -1, 0),
    }

    private var tone: android.media.ToneGenerator? = null
    private var toneVolume = -1
    private var player: android.media.MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    /** Suona/vibra secondo le impostazioni. Restituisce per quanti ms il suono occupa l'audio. */
    fun play(): Int {
        if (Prefs.feedbackVibrate(c)) vibrate()
        val volume = Prefs.feedbackVolume(c)
        if (volume <= 0) return 0
        return when (val sound = Prefs.feedbackSound(c)) {
            Sound.NONE -> 0
            Sound.CUSTOM -> playUri(Prefs.feedbackUri(c), volume)
            else -> playTone(sound, volume)
        }
    }

    private fun playTone(sound: Sound, volume: Int): Int {
        if (tone == null || toneVolume != volume) {
            tone?.release()
            tone = try { android.media.ToneGenerator(AudioManager.STREAM_MUSIC, volume) } catch (e: RuntimeException) { null }
            toneVolume = volume
        }
        tone?.startTone(sound.tone, sound.durationMs)
        return sound.durationMs
    }

    /** Suoni di notifica del telefono: troncati a 1,2 s per non coprire la voce. */
    private fun playUri(uri: String?, volume: Int): Int {
        if (uri == null) return playTone(Sound.BEEP, volume)
        player?.release()
        val mp = try { android.media.MediaPlayer.create(c, Uri.parse(uri)) } catch (e: Exception) { null }
            ?: return playTone(Sound.BEEP, volume)
        val v = volume / 100f
        mp.setVolume(v, v)
        mp.setOnCompletionListener { it.release(); if (player === it) player = null }
        mp.start()
        player = mp
        val length = mp.duration.takeIf { it > 0 }?.coerceAtMost(1200) ?: 800
        main.postDelayed({ if (player === mp) { try { mp.stop() } catch (_: Exception) { }; mp.release(); player = null } }, length.toLong())
        return length
    }

    private fun vibrate() {
        val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") c.getSystemService(android.os.Vibrator::class.java)
        }
        try {
            vib?.vibrate(android.os.VibrationEffect.createOneShot(70, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { Log.w(TAG, "Vibrazione", e) }
    }

    fun release() {
        tone?.release(); tone = null
        player?.release(); player = null
    }
}

// ---------------------------------------------------------------------------------------------

/** Il telefono è collegato ad Android Auto (o la modalità auto è forzata nelle impostazioni)? */
object CarMode {
    /** fornito dall'app Android Auto: lo stesso che usa la libreria ufficiale androidx.car.app */
    private val CONNECTION = Uri.parse("content://androidx.car.app.connection")

    fun isActive(c: Context): Boolean = when (Prefs.carMode(c)) {
        Prefs.Car.ALWAYS -> true
        Prefs.Car.NEVER -> false
        Prefs.Car.AUTO -> androidAutoConnected(c)
    }

    fun androidAutoConnected(c: Context): Boolean {
        val ui = c.getSystemService(android.app.UiModeManager::class.java)
        if (ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR) return true
        return try {
            c.contentResolver.query(CONNECTION, arrayOf("CarConnectionState"), null, null, null)?.use { cur ->
                cur.moveToFirst() && cur.getInt(0) > 0
            } ?: false
        } catch (e: Exception) { false }
    }
}
