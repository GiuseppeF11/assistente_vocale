package it.assistente.vocale

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Controllo aggiornamenti: legge l'ultima release pubblicata su GitHub.
 * Il download e l'installazione li fanno il browser e Android. Internet serve solo qui.
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

}
