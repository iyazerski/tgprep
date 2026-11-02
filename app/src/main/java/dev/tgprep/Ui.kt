package dev.tgprep

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Colors, type, and motion shared by the UI. */
object Style {
    const val BACKGROUND = 0xFF070A12.toInt()
    /** Telegram blue. */
    const val ACCENT = 0xFF2AABEE.toInt()
    const val TEXT = 0xFFF5F7FB.toInt()
    const val TEXT_DIM = 0xA6F5F7FB.toInt()
    /** Fast start, long soft landing: used for state changes. */
    val EASE = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    val SEMIBOLD: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
}

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpi(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Deep background with soft color light that drifts slowly. Glass panels show it blurred through them. */
class Backdrop @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Blob(val color: Int, val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
        var radius = 0f
    }

    private val blobs = listOf(
        Blob(Style.ACCENT, 0.12f, 0.16f, 0.70f, 0.21f, 0f),
        Blob(0xFF7B5CFF.toInt(), 0.95f, 0.38f, 0.65f, 0.16f, 2.1f),
        Blob(0xFFFF5FA2.toInt(), 0.20f, 0.80f, 0.60f, 0.19f, 4.2f),
        Blob(0xFF00C9A7.toInt(), 0.90f, 0.96f, 0.55f, 0.25f, 1.3f),
    )
    private val started = SystemClock.uptimeMillis()

    /** Glass panels that show this backdrop and must redraw with each frame. */
    val panels = mutableSetOf<View>()

    /** Animation time in seconds. */
    val seconds: Float get() = (SystemClock.uptimeMillis() - started) / 1000f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val extent = max(w, h).toFloat()
        for (b in blobs) {
            b.radius = b.size * extent
            val rgb = b.color and 0xFFFFFF
            b.paint.shader = RadialGradient(
                0f, 0f, b.radius,
                intArrayOf(rgb or 0x8C000000.toInt(), rgb or 0x30000000, rgb),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    /** Draw the scene at time [t] (seconds) in this view's coordinates. */
    fun drawScene(canvas: Canvas, t: Float) {
        canvas.drawColor(Style.BACKGROUND)
        for (b in blobs) {
            val x = width * (b.x + 0.16f * sin(t * b.speed + b.phase))
            val y = height * (b.y + 0.09f * cos(t * b.speed * 1.3f + b.phase))
            b.matrix.setTranslate(x, y)
            b.paint.shader?.setLocalMatrix(b.matrix)
            canvas.drawCircle(x, y, b.radius, b.paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawScene(canvas, seconds)
        panels.forEach { it.invalidate() }
        postInvalidateOnAnimation()
    }
}

/**
 * Frosted glass panel: shows the [backdrop] behind it blurred and slightly more saturated, under a
 * translucent tint, a soft top highlight, and a thin light-catching edge.
 */
open class GlassPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var backdrop: Backdrop? = null
        set(value) {
            field?.panels?.remove(this)
            field = value
            value?.panels?.add(this)
        }
    var cornerRadius = context.dp(28f)
        set(value) {
            field = value
            updateShape()
        }
    /** Fill drawn over the blurred backdrop. */
    var tint = 0x1AFFFFFF
        set(value) {
            field = value
            invalidate()
        }

    private val node = RenderNode("glass").apply {
        val saturate = ColorMatrix().apply { setSaturation(1.6f) }
        setRenderEffect(
            RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate)),
                RenderEffect.createBlurEffect(context.dp(36f), context.dp(36f), Shader.TileMode.CLAMP),
            ),
        )
    }
    private val path = Path()
    private val edgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
    }
    private val location = IntArray(2)
    private val backdropLocation = IntArray(2)

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShape()
    }

    private fun updateShape() {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(cornerRadius, h / 2)
        path.reset()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        val half = edge.strokeWidth / 2
        edgeRect.set(half, half, w - half, h - half)
        highlight.shader = LinearGradient(0f, 0f, 0f, max(1f, min(h, context.dp(90f))), 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x14FFFFFF, 0x4DFFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val b = backdrop
        if (b != null && canvas.isHardwareAccelerated && width > 0 && height > 0) {
            getLocationInWindow(location)
            b.getLocationInWindow(backdropLocation)
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            // Undo the scale of press animations so the blurred scene stays aligned with the backdrop.
            recording.scale(1 / scaleX, 1 / scaleY, width / 2f, height / 2f)
            recording.translate((backdropLocation[0] - location[0]).toFloat(), (backdropLocation[1] - location[1]).toFloat())
            b.drawScene(recording, b.seconds)
            node.endRecording()
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRenderNode(node)
            canvas.restore()
        }
        fill.color = tint
        canvas.drawPath(path, fill)
        canvas.drawPath(path, highlight)
        val r = min(cornerRadius, height / 2f) - edge.strokeWidth / 2
        canvas.drawRoundRect(edgeRect, r, r, edge)
        super.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        node.discardDisplayList()
    }
}

/** Glass pill button that sinks in when pressed and springs back on release. */
class GlassButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    private val label = TextView(context).apply {
        setTextColor(Style.TEXT)
        textSize = 17f
        typeface = Style.SEMIBOLD
        gravity = Gravity.CENTER
        isDuplicateParentStateEnabled = true
    }

    var text: CharSequence
        get() = label.text
        set(value) {
            label.text = value
        }

    /** Accent buttons are tinted Telegram blue; others are clear glass. */
    var accent = false
        set(value) {
            field = value
            tint = if (value) 0xD92AABEE.toInt() else 0x1FFFFFFF
        }

    init {
        cornerRadius = context.dp(28f)
        tint = 0x1FFFFFFF
        isClickable = true
        isFocusable = true
        minimumHeight = context.dpi(56)
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (!changed) return
        animate()
            .scaleX(if (pressed) 0.95f else 1f)
            .scaleY(if (pressed) 0.95f else 1f)
            .setInterpolator(if (pressed) Style.EASE else OvershootInterpolator(3f))
            .setDuration(if (pressed) 140 else 420)
            .start()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        animate().alpha(if (enabled) 1f else 0.45f).setDuration(200).start()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/** Glossy progress bar: smooth fill with a moving sheen, or a sweeping segment while indeterminate. */
class GlassProgress @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenMatrix = Matrix()
    private val rect = RectF()
    private val started = SystemClock.uptimeMillis()
    private var shown = 0f
    private var target = 0f
    private var indeterminate = true

    /** Show progress [fraction] (0..1); the bar glides to it. */
    fun setProgress(fraction: Float) {
        target = fraction.coerceIn(0f, 1f)
        indeterminate = false
        invalidate()
    }

    /** Show an indeterminate sweep until the next [setProgress]. */
    fun reset() {
        indeterminate = true
        shown = 0f
        target = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Style.ACCENT, 0xFF8FE3FF.toInt(), Shader.TileMode.CLAMP)
        val band = max(1f, w * 0.35f)
        sheen.shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        if (indeterminate) {
            val seg = w * 0.3f
            val x = (w + seg) * ((t * 0.6f) % 1f) - seg
            rect.set(max(0f, x), 0f, min(w, x + seg), h)
        } else {
            shown += (target - shown) * 0.12f
            rect.set(0f, 0f, max(h, w * shown), h)
        }
        if (rect.width() > 0) {
            canvas.drawRoundRect(rect, r, r, fill)
            sheenMatrix.setTranslate((w * 1.6f) * ((t * 0.45f) % 1f) - w * 0.4f, 0f)
            sheen.shader?.setLocalMatrix(sheenMatrix)
            canvas.drawRoundRect(rect, r, r, sheen)
        }
        if (isShown) postInvalidateOnAnimation()
    }
}
