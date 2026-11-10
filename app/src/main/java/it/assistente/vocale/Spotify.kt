package it.assistente.vocale

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent
import it.assistente.vocale.logic.PlayRequest

/**
 * Serve solo a ottenere da Android il permesso di vedere i lettori musicali attivi
 * (Impostazioni → Accesso alle notifiche). Non legge né conserva le notifiche.
 */
class MediaAccessService : NotificationListenerService()

/**
 * Fa partire un brano su Spotify parlando direttamente con il suo lettore (la "sessione multimediale",
 * la stessa che si controlla dalla tendina), con la ricerca vocale che Spotify offre ad Android Auto
 * e agli assistenti. Funziona anche a telefono bloccato e senza aprire l'app.
 */
object SpotifyPlayer {

    enum class Result {
        /** sta suonando il brano cercato */
        PLAYING,
        /** manca l'accesso alle notifiche */
        NO_ACCESS,
        /** Spotify non è partito o ha ignorato la richiesta */
        NOT_STARTED,
    }

    private const val WAIT_SESSION_MS = 9000L
    private const val WAIT_PLAYBACK_MS = 6000L
    private const val OPEN_APP_AFTER_MS = 2500L
    private const val POLL_MS = 300L

    private val main = Handler(Looper.getMainLooper())

    private fun listener(c: Context) = ComponentName(c, MediaAccessService::class.java)

    fun hasAccess(c: Context): Boolean = try {
        c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener(c))
    } catch (e: Exception) { false }

    private fun session(c: Context): MediaController? = try {
        c.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(listener(c))
            .firstOrNull { it.packageName == Actions.SPOTIFY }
    } catch (e: SecurityException) { null }

    /**
     * @param canOpenUi a telefono sbloccato si può aprire Spotify se non si sveglia da solo
     * @param done chiamato una sola volta, sul thread principale
     */
    fun play(c: Context, req: PlayRequest, canOpenUi: Boolean, done: (Result) -> Unit) {
        if (!hasAccess(c)) { done(Result.NO_ACCESS); return }
        var finished = false
        val finish = { r: Result -> if (!finished) { finished = true; done(r) } }

        session(c)?.let { send(it, req, finish); return }

        // Spotify non è attivo: lo si sveglia con il tasto "play" (riprende l'ultimo brano per un attimo)
        wake(c)
        val start = SystemClock.elapsedRealtime()
        var openedApp = false
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val s = session(c)
                if (s != null) { send(s, req, finish); return }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (canOpenUi && !openedApp && elapsed > OPEN_APP_AFTER_MS) {
                    openedApp = true
                    c.packageManager.getLaunchIntentForPackage(Actions.SPOTIFY)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { try { c.startActivity(it) } catch (e: Exception) { Log.w(TAG, "Apertura Spotify", e) } }
                }
                if (elapsed > WAIT_SESSION_MS) finish(Result.NOT_STARTED) else main.postDelayed(this, POLL_MS)
            }
        }
        main.postDelayed(poll, POLL_MS)
    }

    private fun wake(c: Context) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setPackage(Actions.SPOTIFY)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            try { c.sendBroadcast(intent) } catch (e: Exception) { Log.w(TAG, "Tasto play a Spotify", e) }
        }
    }

    /** Chiede la ricerca e controlla che il brano sia davvero cambiato. */
    private fun send(ctrl: MediaController, req: PlayRequest, finish: (Result) -> Unit) {
        fun title() = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        fun playing() = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        val before = title()
        try {
            ctrl.transportControls.playFromSearch(req.query, Actions.searchExtras(req))
        } catch (e: Exception) {
            Log.w(TAG, "playFromSearch", e)
            finish(Result.NOT_STARTED)
            return
        }
        val start = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val t = title()
                if (playing() && t != null && t != before) { finish(Result.PLAYING); return }
                if (SystemClock.elapsedRealtime() - start > WAIT_PLAYBACK_MS) {
                    finish(Result.NOT_STARTED)
                } else {
                    main.postDelayed(this, POLL_MS)
                }
            }
        }
        main.postDelayed(check, POLL_MS)
    }
}
