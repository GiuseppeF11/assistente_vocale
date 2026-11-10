package it.assistente.vocale

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import it.assistente.vocale.logic.PlayRequest
import it.assistente.vocale.logic.SpotifyChooser
import it.assistente.vocale.logic.SpotifyItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * Spotify con il kit ufficiale:
 *  1. collegamento una tantum dell'account (login Spotify nel browser, PKCE: nessun segreto nell'app)
 *  2. ricerca del brano/artista/album/playlist con la Web API di Spotify
 *  3. riproduzione con App Remote, che comanda l'app Spotify installata (anche a telefono bloccato)
 * Serve un'app registrata su developer.spotify.com: il suo Client ID si inserisce nelle impostazioni.
 */

object SpotifyAccount {
    const val REDIRECT = "assistente-vocale://callback"
    private const val SCOPE = "app-remote-control"

    private fun sp(c: Context) = c.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    /** app "Assistente Vocale" registrata su developer.spotify.com (il Client ID non è un segreto) */
    private const val DEFAULT_CLIENT_ID = "7ad7c36e18c04b2c9f8748f5123866cd"

    fun clientId(c: Context): String = sp(c).getString("client_id", null)?.trim()?.ifEmpty { null } ?: DEFAULT_CLIENT_ID
    fun setClientId(c: Context, id: String) = sp(c).edit().putString("client_id", id.trim()).apply()

    /** Impronta SHA1 della firma dell'app installata: deve coincidere con quella nella dashboard Spotify. */
    fun signingSha1(c: Context): String = try {
        val info = c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) { "?" }

    fun isLinked(c: Context) = clientId(c).isNotEmpty() && sp(c).getString("refresh", null) != null

    fun unlink(c: Context) = sp(c).edit().remove("refresh").remove("access").remove("expires").apply()

    /** Indirizzo di login di Spotify; salva il codice di verifica PKCE per lo scambio successivo. */
    fun authorizeUrl(c: Context): Uri {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val verifier = (1..64).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        sp(c).edit().putString("verifier", verifier).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId(c))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPE)
            .build()
    }

    /** Scambia il codice ricevuto dopo il login con i token. Fuori dal thread principale. */
    fun exchange(c: Context, code: String): Boolean = token(
        c,
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT,
            "client_id" to clientId(c),
            "code_verifier" to (sp(c).getString("verifier", "") ?: ""),
        ),
    )

    /** Token valido (rinnovato se scaduto); null se l'account non è collegato. Fuori dal thread principale. */
    fun accessToken(c: Context): String? {
        val prefs = sp(c)
        val access = prefs.getString("access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("expires", 0) - 60_000) return access
        val refresh = prefs.getString("refresh", null) ?: return null
        val ok = token(c, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(c)))
        return if (ok) prefs.getString("access", null) else null
    }

    private fun token(c: Context, form: Map<String, String>): Boolean = try {
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val ok = conn.responseCode == 200
        val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (!ok) {
            Log.w(TAG, "Token Spotify: ${conn.responseCode} $text")
            // collegamento revocato o scaduto: va rifatto
            if (form["grant_type"] == "refresh_token" && text.contains("invalid_grant")) unlink(c)
            false
        } else {
            val o = JSONObject(text)
            sp(c).edit()
                .putString("access", o.getString("access_token"))
                .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
                .apply { o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString("refresh", it) } }
                .apply()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Token Spotify", e)
        false
    }

    /** Cerca su Spotify e sceglie cosa riprodurre. Fuori dal thread principale. */
    fun search(token: String, req: PlayRequest): SpotifyItem? {
        val url = "https://api.spotify.com/v1/search?type=track,artist,album,playlist&limit=5&market=from_token&q=" +
            URLEncoder.encode(req.query, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "Ricerca Spotify: ${conn.responseCode}")
                return null
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            fun items(key: String, kind: SpotifyItem.Kind): List<SpotifyItem> {
                val arr = o.optJSONObject(key)?.optJSONArray("items") ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    // Spotify a volte restituisce elementi vuoti (playlist rimosse)
                    val it = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uri = it.optString("uri").takeIf { u -> u.isNotEmpty() } ?: return@mapNotNull null
                    SpotifyItem(
                        kind, it.optString("name"), uri,
                        artist = it.optJSONArray("artists")?.optJSONObject(0)?.optString("name"),
                        albumUri = it.optJSONObject("album")?.optString("uri"),
                    )
                }
            }
            return SpotifyChooser.choose(
                req.query, req.focus,
                items("tracks", SpotifyItem.Kind.TRACK),
                items("artists", SpotifyItem.Kind.ARTIST),
                items("albums", SpotifyItem.Kind.ALBUM),
                items("playlists", SpotifyItem.Kind.PLAYLIST),
            )
        } finally {
            conn.disconnect()
        }
    }
}

/** Comanda l'app Spotify installata tramite App Remote. */
object SpotifyPlayer {

    sealed interface Result {
        data object Playing : Result
        /** l'autorizzazione dentro Spotify manca o è stata revocata: va ricollegato */
        data object NeedsLink : Result
        data class Failed(val reason: String) : Result
    }

    private val main = Handler(Looper.getMainLooper())

    fun params(c: Context, showAuth: Boolean): ConnectionParams =
        ConnectionParams.Builder(SpotifyAccount.clientId(c))
            .setRedirectUri(SpotifyAccount.REDIRECT)
            .showAuthView(showAuth)
            .build()

    /** @param done chiamato una sola volta, sul thread principale */
    fun play(c: Context, item: SpotifyItem, done: (Result) -> Unit) {
        var finished = false
        val finish = { r: Result -> if (!finished) { finished = true; done(r) } }
        main.postDelayed({ finish(Result.Failed("Spotify non risponde")) }, 12_000)

        SpotifyAppRemote.connect(c, params(c, showAuth = false), object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                val start = { uri: String ->
                    remote.playerApi.play(uri)
                        .setResultCallback {
                            finish(Result.Playing)
                            main.postDelayed({ SpotifyAppRemote.disconnect(remote) }, 2000)
                        }
                        .setErrorCallback { e ->
                            Log.w(TAG, "Spotify play", e)
                            finish(Result.Failed(e.message ?: "errore"))
                            SpotifyAppRemote.disconnect(remote)
                        }
                }
                // senza Premium Spotify non avvia un singolo brano: si avvia il suo album
                remote.userApi.capabilities
                    .setResultCallback { caps ->
                        val uri = if (item.kind == SpotifyItem.Kind.TRACK && !caps.canPlayOnDemand) item.albumUri ?: item.uri
                        else item.uri
                        start(uri)
                    }
                    .setErrorCallback { start(item.uri) }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "App Remote", error)
                val name = error.javaClass.simpleName
                finish(
                    if (name.contains("NotAuthorized") || name.contains("Authentication") || name.contains("UserNotAuthorized"))
                        Result.NeedsLink
                    else Result.Failed(error.message ?: name)
                )
            }
        })
    }
}

/**
 * Riceve il ritorno dal login Spotify (assistente-vocale://callback?code=…), completa il collegamento
 * e chiede a Spotify il permesso di comandare la riproduzione (una sola volta).
 */
class SpotifyLinkActivity : Activity() {

    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            setPadding(64, 64, 64, 64)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text))
            text = "Collego Spotify…"
        }
        setContentView(label)
        handle(intent?.data)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent.data)
    }

    private fun handle(data: Uri?) {
        val code = data?.getQueryParameter("code")
        if (code == null) {
            label.text = "Collegamento annullato" + (data?.getQueryParameter("error")?.let { " ($it)" } ?: "")
            return
        }
        Thread {
            val ok = SpotifyAccount.exchange(this, code)
            runOnUiThread {
                if (!ok) {
                    label.text = "Login riuscito ma Spotify ha rifiutato il collegamento. " +
                        "Controlla Client ID e Redirect URI ($REDIRECT_HINT) nella dashboard di Spotify."
                    return@runOnUiThread
                }
                label.text = "Account collegato. Ora Spotify chiede il permesso di comandare la musica…"
                // autorizzazione dentro l'app Spotify (app-remote-control): va fatta da una schermata
                SpotifyAppRemote.connect(this, SpotifyPlayer.params(this, showAuth = true), object : Connector.ConnectionListener {
                    override fun onConnected(remote: SpotifyAppRemote) {
                        SpotifyAppRemote.disconnect(remote)
                        label.text = "✓ Spotify collegato. Prova: «metti Albachiara da Spotify»."
                        label.postDelayed({ finish() }, 2500)
                    }
                    override fun onFailure(error: Throwable) {
                        Log.w(TAG, "App Remote (collegamento)", error)
                        label.text = "Login riuscito, ma Spotify non ha confermato il permesso.\n\n" +
                            "Quasi sempre il pacchetto o l'impronta nella dashboard Spotify non corrispondono a questa app. " +
                            "In developer.spotify.com → la tua app → Settings → Android packages devono esserci esattamente:\n\n" +
                            "Package name: ${packageName}\nSHA1: ${SpotifyAccount.signingSha1(this@SpotifyLinkActivity)}\n\n" +
                            "Salva, poi nell'app premi di nuovo «Collega Spotify».\n\n" +
                            "(Dettaglio: ${error.message ?: error.javaClass.simpleName})"
                        label.setTextIsSelectable(true)
                    }
                })
            }
        }.start()
    }

    private companion object {
        const val REDIRECT_HINT = SpotifyAccount.REDIRECT
    }
}
