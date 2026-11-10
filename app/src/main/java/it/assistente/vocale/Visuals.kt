package it.assistente.vocale

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import it.assistente.vocale.AssistantBus.Phase
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/*
 * Effetti grafici dell'assistente: onde che seguono la voce, bordo animato della scheda,
 * contorno luminoso dello schermo. Si animano solo mentre sono visibili.
 */

/** Colore della fase del dialogo (blu ascolta, arancione chiede, verde esegue, rosso errore). */
fun phaseColor(c: Context, phase: Phase): Int = c.getColor(
    when (phase) {
        Phase.LISTENING -> R.color.listening
        Phase.ASKING -> R.color.asking
        Phase.ERROR -> R.color.error
        else -> R.color.working
    }
)

private val rainbow = intArrayOf(
    0xFF4F8CFF.toInt(), 0xFFB16CFF.toInt(), 0xFFFF6CB5.toInt(), 0xFFFFB04F.toInt(),
    0xFF4FE3C1.toInt(), 0xFF4F8CFF.toInt(),
)

// =================================================================================================

/** Onde sinusoidali la cui ampiezza segue il volume della voce ([AssistantBus.level]). */
class WaveView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private var phase = 0f
    private var level = 0f
    var color: Int = c.getColor(R.color.listening)

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            // l'ampiezza insegue il volume: sale in fretta, scende piano
            val target = AssistantBus.level
            level += (target - level) * (if (target > level) 0.45f else 0.12f)
            phase += 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(tick) }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { removeCallbacks(tick); postOnAnimation(tick) }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        val amp = (0.08f + level * 0.92f) * (h / 2 - 3)
        val waves = listOf(Triple(1.0f, 1.0f, 255), Triple(0.7f, 1.6f, 140), Triple(0.45f, 2.3f, 80))
        for ((scale, freq, alpha) in waves) {
            paint.color = color
            paint.alpha = alpha
            paint.strokeWidth = resources.displayMetrics.density * if (alpha == 255) 2.5f else 1.6f
            path.reset()
            var x = 0f
            while (x <= w) {
                // inviluppo: le onde si stringono ai lati
                val env = sin(PI * x / w).toFloat()
                val y = mid + sin(x / w * 2 * PI * freq + phase * freq).toFloat() * amp * scale * env
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 4f
            }
            canvas.drawPath(path, paint)
        }
    }
}

// =================================================================================================

/** Bordo della scheda: un filo di colore che scorre lungo il contorno mentre ascolta. */
class AnimatedBorderDrawable(private val c: Context, private val radius: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.resources.displayMetrics.density * 2f
    }
    private val rect = RectF()
    private val matrix = Matrix()
    private var angle = 0f
    private var color = c.getColor(R.color.listening)
    private var moving = false
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidateSelf() }
    }

    /** In ascolto il colore scorre; negli altri stati è un bordo fermo del colore della fase. */
    fun set(color: Int, moving: Boolean) {
        this.color = color
        if (moving != this.moving) {
            this.moving = moving
            if (moving) animator.start() else animator.cancel()
        }
        invalidateSelf()
    }

    fun stop() = animator.cancel()

    override fun draw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        if (moving) {
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            val shader = SweepGradient(rect.centerX(), rect.centerY(),
                intArrayOf(transparent, color, Color.WHITE, color, transparent, transparent),
                floatArrayOf(0f, 0.12f, 0.2f, 0.28f, 0.42f, 1f))
            matrix.setRotate(angle, rect.centerX(), rect.centerY())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            paint.alpha = 255
        } else {
            paint.shader = null
            paint.color = color
            paint.alpha = 110
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// =================================================================================================

/**
 * Contorno luminoso dello schermo: un bagliore lungo i bordi che segue gli angoli arrotondati del telefono.
 * Respira mentre ascolta e si intensifica con la voce. Non intercetta i tocchi.
 */
class EdgeGlowView @JvmOverloads constructor(c: Context, attrs: AttributeSet? = null) : View(c, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()
    private var cornerRadius = resources.displayMetrics.density * 40
    private var t = 0f
    private var level = 0f
    /** forza uno stile (anteprima nelle impostazioni); null = quello scelto */
    var styleOverride: Prefs.Glow? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            t += 0.016f
            val target = AssistantBus.level
            level += (target - level) * (if (target > level) 0.35f else 0.08f)
            invalidate()
            postOnAnimation(this)
        }
    }

    init { isClickable = false; isFocusable = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(tick) }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { removeCallbacks(tick); postOnAnimation(tick) }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // raggio reale degli angoli dello schermo (Android 12+)
        if (Build.VERSION.SDK_INT >= 31) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.let { if (it > 0) cornerRadius = it.toFloat() }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        val style = styleOverride ?: Prefs.glow(context)
        if (style == Prefs.Glow.OFF) return
        val phase = AssistantBus.state.phase.takeIf { it != Phase.HIDDEN } ?: Phase.LISTENING
        val listening = phase == Phase.LISTENING
        // respiro lento + voce
        val breath = if (listening) 0.55f + 0.25f * sin(t * 3.2f) + 0.35f * level else 0.75f
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()

        val base = when (style) {
            Prefs.Glow.BLUE -> context.getColor(R.color.listening)
            Prefs.Glow.STATUS -> phaseColor(context, phase)
            else -> Color.WHITE
        }
        if (style == Prefs.Glow.RAINBOW) {
            val shader = SweepGradient(w / 2, h / 2, rainbow, null)
            matrix.setRotate(t * 90f, w / 2, h / 2)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        } else {
            paint.shader = null
            paint.color = base
        }
        // bagliore finto ma leggero: più passate, dalla più larga e tenue alla più sottile e intensa
        val layers = listOf(18f to 0.12f, 11f to 0.22f, 6f to 0.45f, 2.5f to 0.95f)
        for ((width, alpha) in layers) {
            val sw = width * d * (0.8f + 0.4f * breath)
            paint.strokeWidth = sw
            paint.alpha = (255 * alpha * breath.coerceIn(0.2f, 1f)).toInt()
            val inset = sw / 2
            rect.set(inset, inset, w - inset, h - inset)
            val r = max(0f, cornerRadius - inset)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/**
 * Sfondo sfocato dietro a una finestra (Android 12+, se il telefono lo supporta e non è in risparmio
 * energetico); altrimenti un leggero oscuramento.
 */
fun WindowManager.LayoutParams.applyBackdrop(c: Context) {
    if (!Prefs.blur(c)) return
    val wm = c.getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        blurBehindRadius = (c.resources.displayMetrics.density * 24).toInt()
    }
    flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
    dimAmount = 0.18f
}

/** Finestra a tutto schermo con il contorno luminoso, sopra le app (telefono sbloccato). */
class EdgeGlow(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: EdgeGlowView? = null
    private val main = Handler(Looper.getMainLooper())

    fun show(styleOverride: Prefs.Glow? = null) {
        if (view != null) return
        val style = styleOverride ?: Prefs.glow(context)
        if (style == Prefs.Glow.OFF || !android.provider.Settings.canDrawOverlays(context)) return
        val v = EdgeGlowView(context).apply { this.styleOverride = styleOverride }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            windowAnimations = android.R.style.Animation_Toast
        }
        try { wm.addView(v, lp); view = v } catch (e: Exception) { }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
    }

    /** Anteprima di qualche secondo (dalle impostazioni). */
    fun preview(style: Prefs.Glow, millis: Long = 2500) {
        hide()
        show(style)
        main.postDelayed({ hide() }, millis)
    }
}
