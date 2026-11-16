package io.blipit.android

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue

internal class ReportStyle(val dark: Boolean, val surface: Int, val fg: Int, val primary: Int, val onPrimary: Int, private val density: Float) {
    val muted = blend(surface, fg, if (dark) 0.62f else 0.64f)
    val soft = blend(surface, fg, if (dark) 0.08f else 0.05f)
    val line = alpha(fg, if (dark) 0.14f else 0.12f)
    val danger = if (dark) Color.rgb(229, 72, 77) else Color.rgb(220, 38, 38)
    val ring = alpha(primary, 0.5f)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun px(v: Float) = (v * density).toInt()

    fun box(fill: Int, radius: Float, stroke: Int? = null, width: Float = 1f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * density
        stroke?.let { setStroke(px(width), it) }
    }

    fun control(fill: Int, stroke: Int?, press: Int, radius: Float = 8f, inset: Float = 0f): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(fill, radius, ring, 2f))
            addState(intArrayOf(), box(fill, radius, stroke))
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(press), states, box(Color.WHITE, radius))
        return if (inset > 0f) InsetDrawable(ripple, 0, px(inset), 0, px(inset)) else ripple
    }

    fun filled(inset: Float = 0f) = control(primary, null, alpha(onPrimary, 0.18f), inset = inset)
    fun outline(inset: Float = 0f) = control(surface, line, alpha(fg, 0.08f), inset = inset)
    fun ghost(inset: Float = 0f) = control(Color.TRANSPARENT, null, alpha(fg, 0.08f), inset = inset)

    fun input(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(surface, 8f, primary, 1.5f))
        addState(intArrayOf(), box(surface, 8f, line))
    }

    companion object {
        val BLUE = Color.rgb(42, 111, 220)
        val MARK = Color.rgb(255, 59, 48)

        fun alpha(color: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        fun blend(from: Int, to: Int, by: Float) = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * by).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * by).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * by).toInt(),
        )

        fun luminance(color: Int): Double {
            fun c(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * c(Color.red(color)) + 0.7152 * c(Color.green(color)) + 0.0722 * c(Color.blue(color))
        }

        fun contrast(a: Int, b: Int): Double {
            val x = luminance(a)
            val y = luminance(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }

        fun readable(color: Int, on: Int): Int {
            var c = color
            val toward = if (luminance(on) < 0.5) Color.WHITE else Color.BLACK
            repeat(16) { if (contrast(c, on) < 3.0) c = blend(c, toward, 0.15f) }
            return c
        }

        fun of(context: Context): ReportStyle {
            fun value(name: String, platform: Int?, theme: android.content.res.Resources.Theme = context.theme): TypedValue? {
                val id = context.resources.getIdentifier(name, "attr", context.packageName).takeIf { it != 0 } ?: platform ?: return null
                val v = TypedValue()
                return if (theme.resolveAttribute(id, v, true)) v else null
            }
            fun color(name: String, platform: Int? = null, theme: android.content.res.Resources.Theme = context.theme): Int? {
                val v = value(name, platform, theme) ?: return null
                if (v.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return v.data
                if (v.resourceId == 0) return null
                return runCatching {
                    if (Build.VERSION.SDK_INT >= 23) context.getColor(v.resourceId) else @Suppress("DEPRECATION") context.resources.getColor(v.resourceId)
                }.getOrNull()
            }
            val stock = listOf(android.R.style.Theme_DeviceDefault, android.R.style.Theme_DeviceDefault_Light, android.R.style.Theme_Material, android.R.style.Theme_Material_Light).mapNotNull { id ->
                runCatching { context.resources.newTheme().apply { applyStyle(id, true) } }.getOrNull()?.let { color("", android.R.attr.colorPrimary, it) }
            }
            val light = value("isLightTheme", if (Build.VERSION.SDK_INT >= 29) android.R.attr.isLightTheme else null)?.takeIf { it.type == TypedValue.TYPE_INT_BOOLEAN }?.let { it.data != 0 }
            val dark = light?.not() ?: ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            val surface = color("colorSurface")?.takeIf { (luminance(it) < 0.4) == dark && Color.alpha(it) == 255 } ?: if (dark) Color.rgb(24, 25, 28) else Color.WHITE
            val fg = color("colorOnSurface")?.takeIf { contrast(it, surface) >= 7.0 } ?: if (dark) Color.rgb(237, 237, 237) else Color.rgb(15, 23, 42)
            val primary = readable(color("colorPrimary", android.R.attr.colorPrimary)?.takeIf { Color.alpha(it) == 255 && it !in stock } ?: BLUE, surface)
            val on = color("colorOnPrimary")?.takeIf { contrast(it, primary) >= 3.0 } ?: listOf(Color.WHITE, Color.rgb(15, 23, 42)).maxBy { contrast(it, primary) }
            return ReportStyle(dark, surface, fg, primary, on, context.resources.displayMetrics.density)
        }
    }
}
