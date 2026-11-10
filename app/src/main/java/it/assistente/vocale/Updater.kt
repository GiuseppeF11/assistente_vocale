package it.assistente.vocale

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamenti dall'app: legge l'ultima release pubblicata su GitHub, scarica l'APK e lo installa
 * con l'installatore di sistema (lo stesso meccanismo degli store). Internet serve solo qui.
 */
object Updater {

    private const val REPO = "GiuseppeF11/assistente_vocale"
    const val DIRECT_LINK = "https://github.com/$REPO/releases/latest/download/AssistenteVocale.apk"

    data class Release(val versionCode: Long, val name: String, val notes: String, val apkUrl: String)

    fun installedVersion(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    fun installedName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    /** Ultima release su GitHub; null se non raggiungibile. Da chiamare fuori dal thread principale. */
    fun latest(): Release? = try {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AssistenteVocale")
        }
        try {
            if (conn.responseCode != 200) null
            else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                // tag "v1.42" -> versione 42
                val code = Regex("(\\d+)$").find(o.optString("tag_name"))?.value?.toLongOrNull()
                val assets = o.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                if (code == null || apk.isNullOrEmpty()) null
                else Release(code, o.optString("name"), o.optString("body").trim(), apk)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Controllo aggiornamenti", e)
        null
    }

    /** Scarica l'APK (segue il rimando di GitHub al file vero). */
    fun download(c: Context, r: Release, progress: (Int) -> Unit): File {
        val out = File(c.cacheDir, "aggiornamento.apk")
        var url = URL(r.apkUrl)
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("User-Agent", "AssistenteVocale")
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> { url = URL(url, conn.getHeaderField("Location")); return@repeat }
                    200 -> {
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastPct = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                                    }
                                }
                            }
                        }
                        return out
                    }
                    else -> throw IllegalStateException("Download non riuscito (${conn.responseCode})")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Troppi rimandi")
    }

    /** Passa l'APK all'installatore di sistema; Android mostra la conferma. */
    fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            // dalla seconda volta in poi Android può aggiornare senza chiedere conferma
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val pi = PendingIntent.getBroadcast(
                c, 0, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Esito dell'installazione: mostra la conferma di Android o l'errore. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    AssistantBus.update("Non riesco ad aprire la conferma di installazione.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AssistantBus.update("Aggiornamento installato.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore $status"
                AssistantBus.update("Installazione non riuscita: $msg")
            }
        }
    }
}
