package it.assistente.vocale

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import it.assistente.vocale.AssistantBus.Phase
import it.assistente.vocale.AssistantBus.UiState

/**
 * Il popup dell'assistente (layout assistant_card): pallino di stato, testo capito che compare in
 * dissolvenza, onde che seguono la voce, bordo animato e ombra del colore della fase.
 * Usato come finestra sopra le app, a schermo bloccato e nella sessione del tasto/gesto.
 */
class AssistantCard(private val root: View, private val context: Context, private val inflater: LayoutInflater) {

    private val circle: View = root.findViewById(R.id.circle)
    private val message: TextView = root.findViewById(R.id.message)
    private val transcript: TextView = root.findViewById(R.id.transcript)
    private val options: LinearLayout = root.findViewById(R.id.options)
    private val waves: WaveView = root.findViewById(R.id.waves)
    private val border = AnimatedBorderDrawable(context, context.resources.displayMetrics.density * 24)
    private var shownOptions: List<String>? = null
    private var shownText = ""
    private var fade: ValueAnimator? = null

    init {
        root.foreground = border
        root.findViewById<View>(R.id.cancel).setOnClickListener {
            val controller = AssistantBus.controller
            if (controller != null) controller.cancel() else AssistantBus.hide()
        }
    }

    fun render(s: UiState) {
        message.text = s.message
        showTranscript(when {
            s.transcript.isNotBlank() -> s.transcript
            s.phase == Phase.LISTENING -> "…"
            else -> ""
        })

        val color = phaseColor(context, s.phase)
        circle.backgroundTintList = ColorStateList.valueOf(color)
        val listening = s.phase == Phase.LISTENING
        border.set(color, moving = listening)
        // ombra colorata: la scheda "si illumina" del colore della fase (Android 9+)
        root.outlineSpotShadowColor = color
        root.outlineAmbientShadowColor = color
        waves.color = color
        waves.visibility = if (listening) View.VISIBLE else View.GONE

        if (shownOptions != s.options) {
            shownOptions = s.options
            options.removeAllViews()
            options.visibility = if (s.options.isEmpty()) View.GONE else View.VISIBLE
            s.options.forEachIndexed { i, label ->
                val b = inflater.inflate(R.layout.item_option, options, false) as Button
                b.text = label
                b.setOnClickListener { AssistantBus.controller?.choose(i) }
                options.addView(b)
            }
        }
    }

    /**
     * Le parole nuove compaiono in dissolvenza; se il riconoscitore cambia idea su quelle già scritte,
     * l'intera riga si ridisegna con una breve dissolvenza.
     */
    private fun showTranscript(text: String) {
        if (text == shownText) return
        val old = shownText
        shownText = text
        fade?.cancel()
        val textColor = context.getColor(R.color.text)
        if (old.isNotEmpty() && old != "…" && text.startsWith(old)) {
            val start = old.length
            fade = ValueAnimator.ofInt(40, 255).apply {
                duration = 260
                addUpdateListener { va ->
                    val a = va.animatedValue as Int
                    transcript.text = SpannableStringBuilder(text).apply {
                        setSpan(ForegroundColorSpan(Color.argb(a, Color.red(textColor), Color.green(textColor), Color.blue(textColor))),
                            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                start()
            }
        } else {
            transcript.text = text
            transcript.alpha = 0.3f
            transcript.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun release() {
        fade?.cancel()
        border.stop()
    }
}
