package it.assistente.vocale

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import it.assistente.vocale.AssistantBus.Phase

/**
 * Schermata dell'assistente: compare sopra il blocco schermo, accende il display,
 * mostra il microfono che pulsa, il testo capito man mano e le eventuali scelte.
 */
class AssistantActivity : Activity() {

    private lateinit var circle: View
    private lateinit var message: TextView
    private lateinit var transcript: TextView
    private lateinit var options: LinearLayout
    private var pulse: ObjectAnimator? = null
    private var shownOptions: List<String>? = null
    private var handledDestination: String? = null
    private val listener = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_assistant)

        circle = findViewById(R.id.circle)
        message = findViewById(R.id.message)
        transcript = findViewById(R.id.transcript)
        options = findViewById(R.id.options)
        findViewById<Button>(R.id.cancel).setOnClickListener { cancel() }

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            circle,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.22f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.22f),
        ).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        AssistantBus.addListener(listener)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    override fun onDestroy() {
        AssistantBus.removeListener(listener)
        pulse?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancel()

    private fun cancel() {
        val controller = AssistantBus.controller
        if (controller != null) controller.cancel() else AssistantBus.hide()
    }

    private fun render() {
        val s = AssistantBus.state
        if (s.phase == Phase.HIDDEN) { finish(); return }

        message.text = s.message
        transcript.text = when {
            s.transcript.isNotBlank() -> s.transcript
            s.phase == Phase.LISTENING -> "…"
            else -> ""
        }

        val color = getColor(
            when (s.phase) {
                Phase.LISTENING -> R.color.listening
                Phase.ASKING -> R.color.asking
                Phase.ERROR -> R.color.error
                else -> R.color.working
            }
        )
        circle.backgroundTintList = ColorStateList.valueOf(color)

        val p = pulse
        if (s.phase == Phase.LISTENING) {
            if (p != null && !p.isStarted) p.start()
        } else {
            p?.cancel()
            circle.scaleX = 1f; circle.scaleY = 1f
        }

        if (shownOptions != s.options) {
            shownOptions = s.options
            options.removeAllViews()
            s.options.forEachIndexed { i, label ->
                val b = layoutInflater.inflate(R.layout.item_option, options, false) as Button
                b.text = label
                b.setOnClickListener { AssistantBus.controller?.choose(i) }
                options.addView(b)
            }
        }

        val destination = s.navigateTo
        if (destination != null && destination != handledDestination) {
            handledDestination = destination
            startNavigation(destination)
        }
    }

    /** Il navigatore non può comparire sopra il blocco schermo: prima si chiede lo sblocco. */
    private fun startNavigation(destination: String) {
        val launch = {
            if (!Actions.navigate(this, destination)) {
                message.text = "Nessuna app di navigazione trovata"
            }
            AssistantBus.hide()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { launch(); return }

        message.text = "Sblocca per avviare la navigazione"
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = launch()
            override fun onDismissCancelled() = AssistantBus.hide()
            override fun onDismissError() = AssistantBus.hide()
        })
    }
}
