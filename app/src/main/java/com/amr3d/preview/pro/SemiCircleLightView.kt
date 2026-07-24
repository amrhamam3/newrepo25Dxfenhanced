package com.amr3d.preview.pro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * عجلة إضاءة نصف دائرية — يسحب المستخدم المقبض على القوس لتغيير زاوية الضوء (0°–180°).
 * الزاوية 0° = يسار، 90° = أعلى، 180° = يمين.
 */
class SemiCircleLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onAngleChanged: ((Float) -> Unit)? = null

    // الزاوية بالدرجات 0..180 (يُحوَّل داخلياً لـ 180..0 عشان القوس RTL)
    var angleDeg: Float = 90f
        set(value) {
            field = value.coerceIn(0f, 180f)
            invalidate()
        }

    // ألوان
    private val trackColor    = Color.parseColor("#2A2D35")
    private val activeColor   = Color.parseColor("#FF8A1E")
    private val handleColor   = Color.parseColor("#FF8A1E")
    private val glowColor     = Color.parseColor("#55FF8A1E")
    private val sunIconColor  = Color.parseColor("#FF8A1E")
    private val labelColor    = Color.parseColor("#888888")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF8A1E")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = sunIconColor
    }

    private val arcRect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var trackWidth = 0f
    private var handleRadius = 0f
    private var isDragging = false

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val pad = w * 0.08f
        cx = w / 2f
        cy = h.toFloat() + pad * 0.5f    // المركز تحت الشاشة قليلاً عشان نرى نصف الدائرة فقط
        radius = w / 2f - pad
        trackWidth = w * 0.045f
        handleRadius = trackWidth * 1.6f

        trackPaint.strokeWidth = trackWidth
        activePaint.strokeWidth = trackWidth
        glowPaint.strokeWidth = trackWidth * 2.5f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        textPaint.textSize = w * 0.07f
        sunPaint.strokeWidth = w * 0.02f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // ══ المسار الخلفي (القوس الرمادي) ══
        trackPaint.color = trackColor
        canvas.drawArc(arcRect, 180f, 180f, false, trackPaint)

        // ══ الجزء النشط (من يسار القوس حتى المقبض) ══
        // angleDeg=0 → sweep=0، angleDeg=180 → sweep=180
        val sweep = angleDeg
        glowPaint.color = glowColor
        canvas.drawArc(arcRect, 180f, sweep, false, glowPaint)
        activePaint.color = activeColor
        canvas.drawArc(arcRect, 180f, sweep, false, activePaint)

        // ══ موضع المقبض ══
        // القوس يبدأ من 180° (يسار) وينتهي عند 0° (يمين) بالرسم
        val handleAngleRad = Math.toRadians((180.0 + angleDeg))
        val hx = cx + radius * cos(handleAngleRad).toFloat()
        val hy = cy + radius * sin(handleAngleRad).toFloat()

        // توهج المقبض
        val glowR = handleRadius * 2.2f
        val radialShader = RadialGradient(hx, hy, glowR,
            intArrayOf(Color.parseColor("#88FF8A1E"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        val glowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = radialShader
        }
        canvas.drawCircle(hx, hy, glowR, glowFillPaint)

        // المقبض
        handlePaint.color = handleColor
        canvas.drawCircle(hx, hy, handleRadius, handlePaint)
        canvas.drawCircle(hx, hy, handleRadius, handleRingPaint)

        // أيقونة الشمس داخل المقبض
        drawSunIcon(canvas, hx, hy, handleRadius * 0.45f)

        // ══ علامات الزوايا ══
        drawTickMark(canvas, 0f,   "0°")
        drawTickMark(canvas, 90f,  "90°")
        drawTickMark(canvas, 180f, "180°")

        // ══ قيمة الزاوية في المنتصف ══
        val labelY = cy - radius * 0.35f
        if (labelY > 0) {
            canvas.drawText("${angleDeg.toInt()}°", cx, labelY, textPaint)
        }
    }

    private fun drawSunIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // دائرة وسطى صغيرة
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(cx, cy, r * 0.5f, circlePaint)
        // أشعة
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = cx + (r * 0.7f) * cos(a).toFloat()
            val y1 = cy + (r * 0.7f) * sin(a).toFloat()
            val x2 = cx + r * cos(a).toFloat()
            val y2 = cy + r * sin(a).toFloat()
            canvas.drawLine(x1, y1, x2, y2, sunPaint)
        }
    }

    private fun drawTickMark(canvas: Canvas, angle: Float, label: String) {
        val rad = Math.toRadians((180.0 + angle))
        val tx = cx + radius * cos(rad).toFloat()
        val ty = cy + radius * sin(rad).toFloat()
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(tx, ty, trackWidth * 0.4f, tickPaint)

        // النص خارج القوس
        val labelR = radius + trackWidth * 1.8f
        val lx = cx + labelR * cos(rad).toFloat()
        val ly = cy + labelR * sin(rad).toFloat()
        if (ly < height) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#666666")
                textSize = this@SemiCircleLightView.width * 0.055f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(label, lx, ly + tp.textSize / 3f, tp)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cx
                val dy = event.y - cy
                // تحويل موضع اللمس لزاوية
                var touchAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                // touchAngle من -180..180، نريد 0..180 بالنسبة للقوس
                // القوس يبدأ من 180° (يسار) في إحداثيات Canvas
                val normalized = (touchAngle - 180f + 360f) % 360f
                // فقط نقبل اللمس في النصف العلوي من الدائرة (180°..360°)
                if (normalized in 0f..180f) {
                    angleDeg = normalized
                    onAngleChanged?.invoke(angleDeg)
                    isDragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // قبول حتى لو في منطقة قريبة من الحواف
                if (normalized > 170f || normalized < 10f) {
                    angleDeg = if (normalized > 170f) 180f else 0f
                    onAngleChanged?.invoke(angleDeg)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // الارتفاع = نصف العرض + مسافة للنصوص
        val h = (w / 2f * 1.15f).toInt()
        setMeasuredDimension(w, h)
    }
}
