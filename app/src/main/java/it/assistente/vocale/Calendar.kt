package it.assistente.vocale

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import it.assistente.vocale.logic.TimeParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Scrive gli eventi direttamente nel calendario del telefono (Google, Samsung, …), senza aprire app. */
object CalendarStore {

    data class Cal(val id: Long, val name: String, val account: String, val google: Boolean, val primary: Boolean) {
        val label get() = if (name == account) name else "$name ($account)"
    }

    /** minuti di preavviso del promemoria */
    const val REMINDER_MINUTES = 30

    fun hasPermission(c: Context) =
        c.granted(Manifest.permission.READ_CALENDAR) && c.granted(Manifest.permission.WRITE_CALENDAR)

    /** Calendari visibili in cui si può scrivere; prima il principale di Google. */
    fun writable(c: Context): List<Cal> {
        if (!hasPermission(c)) return emptyList()
        val out = ArrayList<Cal>()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY,
        )
        val where = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        try {
            c.contentResolver.query(Calendars.CONTENT_URI, projection, where, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    out += Cal(
                        id = cur.getLong(0),
                        name = cur.getString(1).orEmpty(),
                        account = cur.getString(2).orEmpty(),
                        google = cur.getString(3) == "com.google",
                        primary = cur.getInt(4) == 1,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendari", e)
        }
        return out.sortedWith(compareBy<Cal>({ !it.primary }, { !it.google }, { it.name }))
    }

    /** Quello scelto nelle impostazioni, altrimenti il principale. */
    fun target(c: Context): Cal? {
        val all = writable(c)
        val chosen = Prefs.calendarId(c)
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
    }

    /** Inserisce l'evento; senza ora è un evento di tutto il giorno. */
    fun insert(
        c: Context, title: String, date: LocalDate, time: TimeParser.Clock?, end: TimeParser.Clock?,
    ): Boolean {
        val cal = target(c) ?: return false
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, cal.id)
            put(Events.TITLE, title)
            if (time == null) {
                // tutto il giorno: per convenzione a mezzanotte UTC
                val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(Events.DTSTART, start)
                put(Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                val zone = ZoneId.systemDefault()
                val start = date.atTime(LocalTime.of(time.hour, time.minute)).atZone(zone)
                val finish = end?.let { date.atTime(LocalTime.of(it.hour, it.minute)).atZone(zone) } ?: start.plusHours(1)
                put(Events.DTSTART, start.toInstant().toEpochMilli())
                put(Events.DTEND, finish.toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.HAS_ALARM, 1)
            }
        }
        return try {
            val uri = c.contentResolver.insert(Events.CONTENT_URI, values) ?: return false
            if (time != null) {
                c.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, ContentUris.parseId(uri))
                    put(Reminders.MINUTES, REMINDER_MINUTES)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                })
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Inserimento evento", e)
            false
        }
    }
}
