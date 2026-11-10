package it.assistente.vocale

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import it.assistente.vocale.AssistantBus.Phase
import it.assistente.vocale.AssistantBus.UiState

/**
 * Il popup dell'assistente (layout assistant_card): microfono che pulsa, cosa sta capendo,
 * eventuali scelte. Usato sia come finestra sopra le app sia dentro la schermata a telefono bloccato.
 */
class AssistantCard(root: View, private val context: Context, private val inflater: LayoutInflater) {

    private val circle: View = root.findViewById(R.id.circle)
    private val message: TextView = root.findViewById(R.id.message)
    private val transcript: TextView = root.findViewById(R.id.transcript)
    private val options: LinearLayout = root.findViewById(R.id.options)
    private var shownOptions: List<String>? = null

    private val pulse = ObjectAnimator.ofPropertyValuesHolder(
        circle,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f),
    ).apply {
        duration = 600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }

    init {
        root.findViewById<View>(R.id.cancel).setOnClickListener {
            val controller = AssistantBus.controller
            if (controller != null) controller.cancel() else AssistantBus.hide()
        }
    }

    fun render(s: UiState) {
        message.text = s.message
        transcript.text = when {
            s.transcript.isNotBlank() -> s.transcript
            s.phase == Phase.LISTENING -> "…"
            else -> ""
        }

        val color = context.getColor(
            when (s.phase) {
                Phase.LISTENING -> R.color.listening
                Phase.ASKING -> R.color.asking
                Phase.ERROR -> R.color.error
                else -> R.color.working
            }
        )
        circle.backgroundTintList = ColorStateList.valueOf(color)

        if (s.phase == Phase.LISTENING) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
            circle.scaleX = 1f; circle.scaleY = 1f
        }

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

    fun release() = pulse.cancel()
}
