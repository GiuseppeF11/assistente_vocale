package it.assistente.vocale

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import it.assistente.vocale.AssistantBus.Phase

/**
 * Popup sopra qualunque app (permesso "Mostra sopra altre app"), senza aprire l'applicazione.
 * Non prende il focus: l'app sotto resta utilizzabile; i tocchi fuori dal popup passano sotto.
 */
class AssistantOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var card: AssistantCard? = null
    private val listener = Runnable { update() }

    /** false se il sistema rifiuta la finestra: allora si usa la schermata. */
    fun show(): Boolean {
        if (view != null) { update(); return true }
        val themed = ContextThemeWrapper(context, R.style.Theme_Assistente)
        val inflater = LayoutInflater.from(themed)
        val v = inflater.inflate(R.layout.overlay_assistant, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            windowAnimations = android.R.style.Animation_InputMethod
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Popup non mostrato", e)
            return false
        }
        view = v
        card = AssistantCard(v, themed, inflater)
        AssistantBus.addListener(listener)
        update()
        return true
    }

    private fun update() {
        val s = AssistantBus.state
        if (s.phase == Phase.HIDDEN) { remove(); return }
        card?.render(s)
    }

    fun remove() {
        AssistantBus.removeListener(listener)
        card?.release()
        card = null
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
    }
}
