package it.assistente.vocale

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import it.assistente.vocale.AssistantBus.Phase

/**
 * Usata solo a telefono bloccato (o se il popup non è disponibile): schermata trasparente sopra il
 * blocco schermo che accende il display e mostra lo stesso popup in basso.
 */
class AssistantActivity : Activity() {

    private lateinit var card: AssistantCard
    private var handledLaunch: AssistantBus.Launch? = null
    private val listener = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { applyBackdrop(this@AssistantActivity) }
        setContentView(R.layout.activity_assistant)
        findViewById<View>(R.id.edge_glow).visibility = if (Prefs.glow(this) == Prefs.Glow.OFF) View.GONE else View.VISIBLE
        card = AssistantCard(findViewById(R.id.card), this, layoutInflater)
        // tocco fuori dal popup = annulla
        findViewById<android.view.View>(R.id.backdrop).setOnClickListener { cancel() }
        AssistantBus.addListener(listener)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    override fun onDestroy() {
        AssistantBus.removeListener(listener)
        card.release()
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
        card.render(s)

        val launch = s.launch
        if (launch != null && launch != handledLaunch) {
            handledLaunch = launch
            unlockAndLaunch(launch)
        }
    }

    /** Maps e Spotify non possono comparire sopra il blocco schermo: prima si chiede lo sblocco. */
    private fun unlockAndLaunch(target: AssistantBus.Launch) {
        val launch = {
            Actions.run(this, target)
            AssistantBus.controller?.done() ?: AssistantBus.hide()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { launch(); return }

        AssistantBus.post(AssistantBus.state.copy(message = "Sblocca il telefono per continuare"))
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = launch()
            override fun onDismissCancelled() { AssistantBus.controller?.cancel() ?: AssistantBus.hide() }
            override fun onDismissError() { AssistantBus.controller?.cancel() ?: AssistantBus.hide() }
        })
    }
}
