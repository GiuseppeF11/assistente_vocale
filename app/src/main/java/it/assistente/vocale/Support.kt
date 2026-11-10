package it.assistente.vocale

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.util.Log
import it.assistente.vocale.logic.Contact
import it.assistente.vocale.logic.NumberKind
import it.assistente.vocale.logic.PhoneNumber
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

    fun home(c: Context): String = sp(c).getString("home", "")!!.trim()
    fun work(c: Context): String = sp(c).getString("work", "")!!.trim()
    fun setPlaces(c: Context, home: String, work: String) =
        sp(c).edit().putString("home", home.trim()).putString("work", work.trim()).apply()
}

// ---------------------------------------------------------------------------------------------

/** Copia il modello Vosk dagli assets alla memoria interna (Vosk legge solo da file veri). */
object ModelInstaller {
    private const val ASSET_DIR = "model-it"

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
    fun call(c: Context, number: String): Boolean {
        if (!c.granted(Manifest.permission.CALL_PHONE)) return false
        return try {
            c.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Chiamata non riuscita", e)
            false
        }
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
    private const val CH_SERVICE = "ascolto"
    private const val CH_ALERT = "attivazione"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun channels(c: Context) {
        nm(c).createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Ascolto in background", NotificationManager.IMPORTANCE_LOW)
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
    fun service(c: Context): Notification {
        channels(c)
        val stop = PendingIntent.getService(
            c, 10, Intent(c, WakeService::class.java).setAction(WakeService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(c, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Assistente in ascolto")
            .setContentText("Di' «${WakeService.WAKE_PHRASE}»")
            .setOngoing(true)
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

    /** Dopo un riavvio Android non permette di riaccendere il microfono da soli: lo chiediamo all'utente. */
    fun boot(c: Context) {
        channels(c)
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Assistente vocale spento")
            .setContentText("Tocca per riattivare l'ascolto dopo il riavvio")
            .setContentIntent(activity(c, MainActivity::class.java, 13))
            .setAutoCancel(true)
            .build()
        nm(c).notify(ID_BOOT, n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.enabled(context)) Notifications.boot(context)
    }
}
