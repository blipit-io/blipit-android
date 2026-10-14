package io.blipit.android

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

internal class Glyph(private val kind: Kind, color: Int, density: Float, private val dp: Int = 22) : Drawable() {
    enum class Kind { BACK, CLOSE, CAMERA, RECORD, TEXT, PEN, RECT, ARROW, UNDO, FIT, ZOOM_IN, ZOOM_OUT, BUG }

    private val size = (dp * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * size / 24f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun tint(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        val stroke = paint.strokeWidth
        paint.strokeWidth = 1.8f
        val p = Path()
        when (kind) {
            Kind.BACK -> { p.moveTo(15f, 5f); p.lineTo(8f, 12f); p.lineTo(15f, 19f) }
            Kind.CLOSE -> { p.moveTo(6f, 6f); p.lineTo(18f, 18f); p.moveTo(18f, 6f); p.lineTo(6f, 18f) }
            Kind.CAMERA -> {
                p.addRoundRect(RectF(3f, 7f, 21f, 19f), 3f, 3f, Path.Direction.CW)
                p.moveTo(8f, 7f); p.lineTo(9.5f, 4.5f); p.lineTo(14.5f, 4.5f); p.lineTo(16f, 7f)
                p.addCircle(12f, 13f, 3.5f, Path.Direction.CW)
            }
            Kind.RECORD -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                canvas.drawPath(p, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 12f, 4.5f, paint)
                paint.style = Paint.Style.STROKE
                p.reset()
            }
            Kind.TEXT -> {
                p.addRoundRect(RectF(3f, 4f, 21f, 17f), 3f, 3f, Path.Direction.CW)
                p.moveTo(8f, 17f); p.lineTo(7f, 21f); p.lineTo(12f, 17f)
                p.moveTo(7.5f, 9f); p.lineTo(16.5f, 9f); p.moveTo(7.5f, 12.5f); p.lineTo(13.5f, 12.5f)
            }
            Kind.PEN -> {
                p.moveTo(3f, 17f); p.cubicTo(6f, 9f, 9f, 9f, 10f, 13f); p.cubicTo(11f, 17f, 14f, 17f, 16f, 11f); p.cubicTo(17f, 8f, 19f, 7f, 21f, 8f)
            }
            Kind.RECT -> p.addRoundRect(RectF(4f, 6f, 20f, 18f), 1.5f, 1.5f, Path.Direction.CW)
            Kind.ARROW -> { p.moveTo(5f, 19f); p.lineTo(19f, 5f); p.moveTo(10f, 5f); p.lineTo(19f, 5f); p.lineTo(19f, 14f) }
            Kind.UNDO -> {
                p.moveTo(9f, 14f); p.lineTo(4f, 9f); p.lineTo(9f, 4f)
                p.moveTo(4f, 9f); p.lineTo(14.5f, 9f); p.cubicTo(18f, 9f, 20f, 11.5f, 20f, 14.5f); p.cubicTo(20f, 17.5f, 18f, 20f, 14.5f, 20f); p.lineTo(11f, 20f)
            }
            Kind.FIT -> {
                p.moveTo(4f, 9f); p.lineTo(4f, 4f); p.lineTo(9f, 4f)
                p.moveTo(15f, 4f); p.lineTo(20f, 4f); p.lineTo(20f, 9f)
                p.moveTo(20f, 15f); p.lineTo(20f, 20f); p.lineTo(15f, 20f)
                p.moveTo(9f, 20f); p.lineTo(4f, 20f); p.lineTo(4f, 15f)
            }
            Kind.ZOOM_IN, Kind.ZOOM_OUT -> {
                p.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW)
                p.moveTo(15.5f, 15.5f); p.lineTo(20f, 20f)
                p.moveTo(7.5f, 10.5f); p.lineTo(13.5f, 10.5f)
                if (kind == Kind.ZOOM_IN) { p.moveTo(10.5f, 7.5f); p.lineTo(10.5f, 13.5f) }
            }
            Kind.BUG -> {
                p.addRoundRect(RectF(3f, 4f, 21f, 17f), 4f, 4f, Path.Direction.CW)
                p.moveTo(8f, 17f); p.lineTo(7f, 21f); p.lineTo(12f, 17f)
                p.moveTo(12f, 7.5f); p.lineTo(12f, 11.5f)
                canvas.drawPath(p, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 14f, 1.1f, paint)
                paint.style = Paint.Style.STROKE
                p.reset()
            }
        }
        canvas.drawPath(p, paint)
        paint.strokeWidth = stroke
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
