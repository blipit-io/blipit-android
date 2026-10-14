package io.blipit.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

internal object Reporter {
    private var current: WeakReference<Activity>? = null
    private var buttonWanted = false
    private var button: WeakReference<View>? = null
    private var attached = false
    internal var pending: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(app: Application?) {
        if (app == null || attached) return
        attached = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is BlipitReportActivity) return
                current = WeakReference(activity)
                if (buttonWanted) addButton(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun show(from: Activity?) {
        main.post {
            val activity = from ?: current?.get()
            if (Blipit.reporter == null || activity == null) {
                android.util.Log.w("Blipit", "call Blipit.init and open an activity before Blipit.showReport")
                return@post
            }
            current = WeakReference(activity)
            activity.startActivity(Intent(activity, BlipitReportActivity::class.java))
        }
    }

    fun capture(done: (Bitmap?) -> Unit) {
        val activity = current?.get() ?: return done(null)
        val root = activity.window?.decorView ?: return done(null)
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return done(null)
        val scale = minOf(1f, 1440f / maxOf(width, height))
        val bitmap = try {
            Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(null)
        }
        val hidden = button?.get()?.takeIf { it.visibility == View.VISIBLE }
        hidden?.visibility = View.INVISIBLE
        val finish = { ok: Boolean ->
            hidden?.visibility = View.VISIBLE
            done(if (ok) bitmap else null)
        }
        if (Build.VERSION.SDK_INT >= 26 && scale == 1f) {
            try {
                PixelCopy.request(activity.window, bitmap, { result -> finish(result == PixelCopy.SUCCESS) }, main)
                return
            } catch (e: IllegalArgumentException) {
            }
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            root.draw(canvas)
            finish(true)
        } catch (e: RuntimeException) {
            finish(false)
        }
    }

    fun showButton(from: Activity?) {
        main.post {
            buttonWanted = true
            (from ?: current?.get())?.let { addButton(it) }
        }
    }

    fun hideButton() {
        main.post {
            buttonWanted = false
            button?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
            button = null
        }
    }

    private fun addButton(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        button?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply {
            tag = TAG
            text = ReportLanguage.text("title")
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (48 * density).toInt()
            setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
            background = GradientDrawable().apply {
                setColor(ReportTheme.BRAND)
                cornerRadius = 24 * density
            }
            elevation = 8 * density
            contentDescription = text
            val glyph = Glyph(Glyph.Kind.BUG, Color.WHITE, density)
            setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
            compoundDrawablePadding = (8 * density).toInt()
            setOnClickListener { show(activity) }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        val margin = (16 * density).toInt()
        params.setMargins(margin, margin, margin, margin)
        label.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = margin + insets.systemWindowInsetBottom
            insets
        }
        content.addView(label, params)
        button = WeakReference(label)
    }

    private const val TAG = "blipit-report-button"
}

internal object ReportTheme {
    val NAVY = Color.rgb(11, 26, 58)
    val CARD = Color.rgb(26, 33, 53)
    val BRAND = Color.rgb(56, 130, 246)
    val MUTED = Color.rgb(152, 166, 190)
    val MARK = Color.rgb(255, 59, 48)
    val DANGER = Color.rgb(255, 122, 110)
}
