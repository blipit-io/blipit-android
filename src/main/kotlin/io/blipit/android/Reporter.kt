package io.blipit.android

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

internal object Reporter {
    private var current: WeakReference<Activity>? = null
    private var buttonWanted = false

    @Volatile
    private var serverButton = false
    private var button: WeakReference<View>? = null
    private var promptView: WeakReference<View>? = null
    private var attached = false
    internal var pending: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "blipit-report").also { it.isDaemon = true } }

    @Volatile
    internal var open = false

    @Volatile
    var handler: ((String, String?) -> Unit)? = null

    @Volatile
    private var userId: String? = null

    @Volatile
    private var userEmail: String? = null

    private var crashToPrompt: String? = null

    @Volatile
    private var named: String? = null
    internal val shots = ScreenShots<Bitmap>()
    private val gate = TriggerGate()
    private val prompt = ReportPrompt()
    private val shake = ShakeMeter()
    private var hold: TwoFingerHold? = null
    private var sensors: SensorManager? = null
    private val captures = WeakHashMap<Activity, Any>()
    private val forget = Runnable { shots.clear() }
    private val fetchButton = Runnable { refreshButton() }
    private val snapshotTask = Runnable { snapshot() }
    private val expireTask = Runnable { if (prompt.expire(SystemClock.uptimeMillis())) removePrompt() }
    private val shaken = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size >= 3 && shake.sample(v[0], v[1], v[2], SystemClock.uptimeMillis())) fire("shake")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun attach(app: Application?) {
        if (app == null || attached) return
        attached = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is BlipitReportActivity) return
                resumed(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is BlipitReportActivity) return
                stopShake()
                main.removeCallbacks(snapshotTask)
            }

            override fun onActivityStarted(activity: Activity) {
                main.removeCallbacks(forget)
                watchCaptures(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                unwatchCaptures(activity)
                if (!Core.foreground) main.postDelayed(forget, FORGET_MS)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun attachActivity(activity: Activity) {
        main.post {
            if (activity is BlipitReportActivity) return@post
            watchCaptures(activity)
            resumed(activity)
        }
    }

    private fun resumed(activity: Activity) {
        if (current?.get() !== activity) named = null
        current = WeakReference(activity)
        Core.observe(activity)
        if (buttonWanted || serverButton) addButton(activity)
        startShake(activity)
        scheduleSnapshot()
        val crash = crashToPrompt ?: if (Core.options.reportAfterCrash) Core.pendingCrash else null
        if (crash != null && !open && !prompt.open) {
            crashToPrompt = null
            Core.pendingCrash = null
            showPrompt(activity, "crash", ReportPrompt.CRASH_MS, crash)
        }
    }

    fun show(from: Activity?, trigger: String? = null, crashId: String? = null) {
        main.post {
            val activity = from ?: current?.get()
            if (Blipit.reporter == null || activity == null) {
                android.util.Log.w("Blipit", "call Blipit.init and open an activity before Blipit.showReport")
                return@post
            }
            if (open) return@post
            current = WeakReference(activity)
            open = true
            val intent = Intent(activity, BlipitReportActivity::class.java)
                .putExtra(BlipitReportActivity.EXTRA_CRASH, crashId)
                .putExtra(BlipitReportActivity.EXTRA_PICKER, trigger == null && Core.options.screenPicker)
            runCatching { activity.startActivity(intent) }.onFailure { open = false }
        }
    }

    fun closed() {
        open = false
    }

    private fun fire(trigger: String) {
        if (!gate.allow(SystemClock.uptimeMillis(), open || prompt.open)) return
        launch(trigger, null)
    }

    private fun launch(trigger: String, crashId: String?) {
        val h = handler
        if (h != null) main.post { h(trigger, crashId) } else show(null, trigger, crashId)
    }

    fun currentScreen(): String? = current?.get()?.let { ScreenKey.of(it.javaClass.name, named) }

    fun screen(name: String) {
        main.post {
            named = name
            scheduleSnapshot()
        }
    }

    private fun scheduleSnapshot() {
        if (!Core.options.screenPicker) return
        main.removeCallbacks(snapshotTask)
        main.postDelayed(snapshotTask, SETTLE_MS)
    }

    fun forgetScreens() {
        shots.clear()
    }

    private fun snapshot() {
        val activity = current?.get() ?: return
        if (open || !Core.foreground || !Core.options.screenPicker) return
        val name = ScreenKey.of(activity.javaClass.name, named)
        capture(SNAPSHOT_SIDE, true) { bitmap -> if (bitmap != null && Core.options.screenPicker) shots.add(name, bitmap) }
    }

    private fun startShake(activity: Activity) {
        if (!Core.options.shakeToReport || sensors != null) return
        val manager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (runCatching { manager.registerListener(shaken, accel, SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)) sensors = manager
    }

    private fun stopShake() {
        sensors?.let { runCatching { it.unregisterListener(shaken) } }
        sensors = null
    }

    fun touch(event: MotionEvent) {
        if (!Core.options.twoFingerReport) return
        val h = hold ?: TwoFingerHold(HOLD_MS, 24 * Resources.getSystem().displayMetrics.density).also { hold = it }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                h.down(points(event), SystemClock.uptimeMillis())
                main.postDelayed({ if (h.due(SystemClock.uptimeMillis())) fire("two_finger") }, HOLD_MS)
            } else {
                h.cancel()
            }
            MotionEvent.ACTION_MOVE -> if (h.holding) {
                if (event.pointerCount == 2) h.move(points(event)) else h.cancel()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> h.cancel()
        }
    }

    private fun points(event: MotionEvent): FloatArray = FloatArray(event.pointerCount * 2) { i -> if (i % 2 == 0) event.getX(i / 2) else event.getY(i / 2) }

    @TargetApi(34)
    private fun watchCaptures(activity: Activity) {
        if (!Core.options.screenshotToReport || Build.VERSION.SDK_INT < 34 || activity is BlipitReportActivity || captures.containsKey(activity)) return
        val callback = Activity.ScreenCaptureCallback { main.post { screenshotTaken(activity) } }
        runCatching {
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
            captures[activity] = callback
        }
    }

    @TargetApi(34)
    private fun unwatchCaptures(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        val callback = captures.remove(activity) as? Activity.ScreenCaptureCallback ?: return
        runCatching { activity.unregisterScreenCaptureCallback(callback) }
    }

    private fun screenshotTaken(activity: Activity) {
        if (open || prompt.open) return
        showPrompt(activity, "screenshot", ReportPrompt.SCREENSHOT_MS, null)
    }

    fun promptCrash(eventId: String) {
        if (!Regex("^[0-9a-f]{32}$").matches(eventId)) return
        main.post {
            val activity = current?.get()
            if (activity != null && !open && !prompt.open) showPrompt(activity, "crash", ReportPrompt.CRASH_MS, eventId) else crashToPrompt = eventId
        }
    }

    private fun showPrompt(activity: Activity, kind: String, timeoutMs: Long, crashId: String?) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        if (!prompt.show(kind, SystemClock.uptimeMillis(), timeoutMs, crashId)) return
        val density = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * density).toInt()
        val lang = ReportLanguage.pick()
        val crash = kind == "crash"
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = PROMPT_TAG
            layoutDirection = if (lang == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            background = GradientDrawable().apply {
                setColor(ReportTheme.CARD)
                cornerRadius = 16 * density
                setStroke(px(1f), Color.argb(26, 255, 255, 255))
            }
            elevation = 10 * density
            setPadding(px(16f), px(14f), px(16f), px(10f))
            isClickable = true
        }
        val title = TextView(activity).apply {
            text = ReportLanguage.text(if (crash) "crashAsk" else "shotAsk", lang)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val later = TextView(activity).apply {
            text = ReportLanguage.text("notNow", lang)
            setTextColor(ReportTheme.MUTED)
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(14f), 0, px(14f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { answer(false) }
        }
        val go = TextView(activity).apply {
            text = ReportLanguage.text(if (crash) "tellUs" else "report", lang)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(18f), 0, px(18f), 0)
            background = GradientDrawable().apply {
                setColor(ReportTheme.BRAND)
                cornerRadius = 12 * density
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { answer(true) }
        }
        row.addView(later)
        row.addView(go)
        card.addView(title)
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        val margin = px(16f)
        params.setMargins(margin, margin, margin, margin)
        card.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = margin + insets.systemWindowInsetBottom
            insets
        }
        content.addView(card, params)
        promptView = WeakReference(card)
        card.announceForAccessibility(title.text)
        main.removeCallbacks(expireTask)
        main.postDelayed(expireTask, timeoutMs)
    }

    private fun answer(report: Boolean) {
        main.removeCallbacks(expireTask)
        val picked = prompt.choose(report)
        removePrompt()
        if (picked != null) {
            gate.allow(SystemClock.uptimeMillis(), false)
            main.postDelayed({ launch(picked.first, picked.second) }, 150)
        }
    }

    private fun removePrompt() {
        promptView?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        promptView = null
    }

    fun user(id: String?, email: String?) {
        userId = id
        userEmail = email
        main.removeCallbacks(fetchButton)
        main.postDelayed(fetchButton, 1000)
    }

    fun refreshButton() {
        val client = Blipit.reporter ?: return
        val headers = ReportButton.headers(userId, userEmail)
        io.execute {
            val on = client.buttonSettings(headers) ?: return@execute
            main.post {
                serverButton = on
                if (on) current?.get()?.let { addButton(it) } else if (!buttonWanted) removeButton()
            }
        }
    }

    fun capture(done: (Bitmap?) -> Unit) = capture(1440f, false, done)

    private fun capture(maxSide: Float, mask: Boolean, done: (Bitmap?) -> Unit) {
        val activity = current?.get() ?: return done(null)
        val root = activity.window?.decorView ?: return done(null)
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return done(null)
        val scale = minOf(1f, maxSide / maxOf(width, height))
        val bitmap = try {
            Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(null)
        }
        val rects = ArrayList<RectF>()
        if (mask) masked(root, scale, rects)
        val hidden = listOfNotNull(button?.get(), promptView?.get()).filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        val finish = { ok: Boolean ->
            hidden.forEach { it.visibility = View.VISIBLE }
            if (ok && rects.isNotEmpty()) {
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { color = Color.rgb(158, 158, 158) }
                rects.forEach { canvas.drawRect(it, paint) }
            }
            done(if (ok) bitmap else null)
        }
        if (Build.VERSION.SDK_INT >= 26 && (scale == 1f || mask)) {
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

    private fun masked(view: View, scale: Float, out: MutableList<RectF>) {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return
        if (view.tag == ReplayPayload.MASK_TAG || (view is EditText && secret(view.inputType))) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            out.add(RectF(loc[0] * scale, loc[1] * scale, (loc[0] + view.width) * scale, (loc[1] + view.height) * scale))
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) masked(view.getChildAt(i), scale, out)
    }

    private fun secret(type: Int): Boolean {
        val kind = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
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
            if (!serverButton) removeButton()
        }
    }

    private fun removeButton() {
        button?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        button = null
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
    private const val PROMPT_TAG = "blipit-report-prompt"
    private const val FORGET_MS = 180_000L
    private const val SETTLE_MS = 700L
    private const val HOLD_MS = 1000L
    private const val SNAPSHOT_SIDE = 480f
}

internal object ReportTheme {
    val NAVY = Color.rgb(11, 26, 58)
    val CARD = Color.rgb(26, 33, 53)
    val BRAND = Color.rgb(56, 130, 246)
    val MUTED = Color.rgb(152, 166, 190)
    val MARK = Color.rgb(255, 59, 48)
    val DANGER = Color.rgb(255, 122, 110)
}
