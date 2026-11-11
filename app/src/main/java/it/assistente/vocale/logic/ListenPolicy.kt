package it.assistente.vocale.logic

/**
 * Decide se il microfono deve ascoltare «Ok Alfio» in questo momento.
 * Fuori dalle situazioni scelte il microfono resta spento (consumo zero); il tasto di accensione funziona sempre.
 */
object ListenPolicy {

    data class Settings(
        /** ascolta all'accensione/sblocco dello schermo */
        val screen: Boolean = true,
        /** per quanti secondi dopo l'accensione; -1 = finché lo schermo resta acceso */
        val screenWindowSec: Int = 30,
        val car: Boolean = true,
        /** per quanti minuti dal collegamento all'auto; -1 = per tutto il viaggio */
        val carMinutes: Int = -1,
        /** in carica, ma non in auto (in auto valgono le regole dell'auto) */
        val charging: Boolean = true,
        /** sempre (consumo alto) */
        val always: Boolean = false,
        /** sospendi se il telefono è caldo */
        val heatGuard: Boolean = true,
        /** sospendi con batteria scarica (se non in carica) */
        val batteryGuard: Boolean = true,
        val batteryThreshold: Int = 20,
    )

    data class State(
        val now: Long,
        val screenOn: Boolean,
        /** quando lo schermo si è acceso o il telefono è stato sbloccato l'ultima volta */
        val screenSince: Long,
        val inCar: Boolean,
        val carSince: Long,
        val charging: Boolean,
        val hot: Boolean,
        /** percentuale, -1 se non nota */
        val battery: Int,
    )

    enum class Reason(val label: String) {
        SCREEN("schermo acceso"), CAR("in auto"), CHARGING("in carica"), ALWAYS("sempre")
    }

    enum class Off(val label: String) {
        NONE("nessuna condizione attiva"), HEAT("telefono caldo"), BATTERY("batteria scarica")
    }

    sealed interface Decision {
        /** [until] = quando scade (ms), null = finché dura la condizione */
        data class Listen(val reason: Reason, val until: Long?) : Decision
        data class Silent(val why: Off) : Decision
    }

    fun decide(s: Settings, st: State): Decision {
        if (s.batteryGuard && !st.charging && st.battery in 0..s.batteryThreshold) return Decision.Silent(Off.BATTERY)
        if (s.heatGuard && st.hot) return Decision.Silent(Off.HEAT)
        if (s.always) return Decision.Listen(Reason.ALWAYS, null)

        val candidates = ArrayList<Decision.Listen>()
        if (s.car && st.inCar) {
            if (s.carMinutes < 0) candidates += Decision.Listen(Reason.CAR, null)
            else {
                val end = st.carSince + s.carMinutes * 60_000L
                if (st.now < end) candidates += Decision.Listen(Reason.CAR, end)
            }
        }
        if (s.charging && st.charging && !st.inCar) candidates += Decision.Listen(Reason.CHARGING, null)
        if (s.screen && st.screenOn) {
            if (s.screenWindowSec < 0) candidates += Decision.Listen(Reason.SCREEN, null)
            else {
                val end = st.screenSince + s.screenWindowSec * 1000L
                if (st.now < end) candidates += Decision.Listen(Reason.SCREEN, end)
            }
        }
        // la condizione che dura di più: prima quelle senza scadenza, poi la scadenza più lontana
        return candidates.maxWithOrNull(compareBy<Decision.Listen>({ it.until == null }, { it.until ?: 0 }))
            ?: Decision.Silent(Off.NONE)
    }
}
