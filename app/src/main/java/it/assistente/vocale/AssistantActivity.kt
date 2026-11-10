package it.assistente.vocale

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import it.assistente.vocale.AssistantBus.Phase

/**
 * Usata solo a telefono bloccato (o se il popup non è disponibile): schermata trasparente sopra il
 * blocco schermo che accende il display e mostra lo stesso popup in basso.
 */
class AssistantActivity : Activity() {

    private lateinit var card: AssistantCard
    private var handledDestination: String? = null
    private val listener = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_assistant)
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

        val destination = s.navigateTo
        if (destination != null && destination != handledDestination) {
            handledDestination = destination
            startNavigation(destination)
        }
    }

    /** Il navigatore non può comparire sopra il blocco schermo: prima si chiede lo sblocco. */
    private fun startNavigation(destination: String) {
        val launch = {
            Actions.navigate(this, destination)
            AssistantBus.hide()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { launch(); return }

        AssistantBus.post(AssistantBus.state.copy(message = "Sblocca per avviare la navigazione"))
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = launch()
            override fun onDismissCancelled() = AssistantBus.hide()
            override fun onDismissError() = AssistantBus.hide()
        })
    }
}
