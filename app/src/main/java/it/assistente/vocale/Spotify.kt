package it.assistente.vocale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import it.assistente.vocale.logic.PlayRequest

/**
 * Fa partire un brano su Spotify collegandosi al suo lettore musicale come fanno Android Auto e gli
 * assistenti (MediaBrowser), poi gli chiede la ricerca vocale. Non serve nessun permesso: in particolare
 * NON l'accesso alle notifiche, che fa bloccare l'installazione da Play Protect.
 * Se Spotify rifiuta il collegamento, chi chiama ripiega sull'apertura della ricerca.
 */
object SpotifyPlayer {

    enum class Result {
        /** sta suonando il brano cercato */
        PLAYING,
        /** Spotify non accetta il collegamento o ha ignorato la richiesta */
        NOT_STARTED,
    }

    private const val CONNECT_TIMEOUT_MS = 6000L
    private const val WAIT_PLAYBACK_MS = 7000L
    private const val POLL_MS = 300L

    private val main = Handler(Looper.getMainLooper())

    /** Il servizio "lettore" che Spotify espone ad Android Auto e agli assistenti. */
    private fun browserService(c: Context): ComponentName? = try {
        c.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(Actions.SPOTIFY), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) { null }

    /** @param done chiamato una sola volta, sul thread principale */
    fun play(c: Context, req: PlayRequest, done: (Result) -> Unit) {
        val component = browserService(c) ?: run { done(Result.NOT_STARTED); return }
        var finished = false
        var browser: MediaBrowser? = null
        val finish = { r: Result ->
            if (!finished) {
                finished = true
                // il collegamento non serve più: la musica continua da sola
                main.postDelayed({ try { browser?.disconnect() } catch (_: Exception) { } }, 1000)
                done(r)
            }
        }
        browser = MediaBrowser(c, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                try {
                    send(MediaController(c, b.sessionToken), req, finish)
                } catch (e: Exception) {
                    Log.w(TAG, "Lettore Spotify", e)
                    finish(Result.NOT_STARTED)
                }
            }
            override fun onConnectionFailed() {
                Log.i(TAG, "Spotify ha rifiutato il collegamento")
                finish(Result.NOT_STARTED)
            }
            override fun onConnectionSuspended() = finish(Result.NOT_STARTED)
        }, null)
        try {
            browser.connect()
        } catch (e: Exception) {
            finish(Result.NOT_STARTED)
            return
        }
        main.postDelayed({ finish(Result.NOT_STARTED) }, CONNECT_TIMEOUT_MS + WAIT_PLAYBACK_MS)
    }

    /** Chiede la ricerca e controlla che il brano sia davvero cambiato. */
    private fun send(ctrl: MediaController, req: PlayRequest, finish: (Result) -> Unit) {
        fun title() = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        fun playing() = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        val before = title()
        val wasPlaying = playing()
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
                // partito un brano diverso, oppure partita la musica che prima era ferma
                if (playing() && t != null && (t != before || !wasPlaying)) { finish(Result.PLAYING); return }
                if (SystemClock.elapsedRealtime() - start > WAIT_PLAYBACK_MS) finish(Result.NOT_STARTED)
                else main.postDelayed(this, POLL_MS)
            }
        }
        main.postDelayed(check, POLL_MS)
    }
}
