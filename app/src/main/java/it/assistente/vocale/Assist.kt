package it.assistente.vocale

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import it.assistente.vocale.AssistantBus.Phase

/*
 * Attivazione con tasto o gesto: l'app si registra come "Assistente digitale" del telefono
 * (Impostazioni → App predefinite). Tenendo premuto il tasto di accensione, o con il gesto
 * dell'assistente, Android apre la nostra sessione: il microfono si accende solo in quel momento.
 */

/** Il servizio che Android richiede per essere scelti come assistente. Non fa nulla da solo. */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * La finestra dell'assistente di sistema: compare sopra tutto, anche sopra il blocco schermo,
 * e mostra lo stesso popup della modalità a voce. Il dialogo vero lo conduce WakeService.
 */
class AssistSession(private val service: AssistSessionService) : VoiceInteractionSession(service) {

    private var card: AssistantCard? = null
    /** lo stato è ancora "nascosto" finché il servizio non parte: non chiudere subito */
    private var started = false
    /** si è passati alla schermata per lo sblocco: chiudere la sessione senza annullare */
    private var handedOff = false
    private val listener = Runnable { render() }

    override fun onCreateContentView(): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_Assistente)
        val inflater = LayoutInflater.from(themed)
        val v = inflater.inflate(R.layout.activity_assistant, null)
        card = AssistantCard(v.findViewById(R.id.card), themed, inflater)
        v.findViewById<View>(R.id.backdrop).setOnClickListener { cancelDialog() }
        return v
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        started = false
        handedOff = false
        AssistantBus.addListener(listener)
        try {
            WakeService.startSession(context)
        } catch (e: Exception) {
            Log.e(TAG, "Avvio assistente dalla sessione", e)
            hide()
        }
    }

    override fun onHide() {
        AssistantBus.removeListener(listener)
        // chiusa dall'utente (indietro, home): si chiude anche il dialogo
        if (!handedOff && AssistantBus.state.phase != Phase.HIDDEN) cancelDialog()
        super.onHide()
    }

    override fun onBackPressed() = cancelDialog()

    private fun cancelDialog() {
        val controller = AssistantBus.controller
        if (controller != null) controller.cancel() else AssistantBus.hide()
    }

    private fun render() {
        val s = AssistantBus.state
        if (s.phase == Phase.HIDDEN) {
            if (started) hide()
            return
        }
        started = true
        card?.render(s)
        // a telefono bloccato Maps/Spotify richiedono lo sblocco: se ne occupa la schermata
        if (s.launch != null && !handedOff) {
            handedOff = true
            try {
                context.startActivity(
                    Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Schermata di sblocco", e)
            }
            hide()
        }
    }
}

/**
 * Essere l'assistente comporta essere anche il "riconoscimento vocale predefinito" del telefono.
 * Per non rompere le altre app che lo usano, questo servizio inoltra tutto al riconoscitore Google.
 */
class RecognizerProxyService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        delegate = null
        val google = Recognizers.google(this)
        if (google == null) {
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this, google)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { listener.bufferReceived(buffer ?: ByteArray(0)) }
            override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
            override fun onError(error: Int) = safe { listener.error(error) }
            override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        delegate = sr
        sr.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }

    override fun onCancel(listener: Callback) { delegate?.cancel() }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "Inoltro riconoscimento", e) }
    }
}
