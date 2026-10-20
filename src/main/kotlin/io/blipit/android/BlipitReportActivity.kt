package io.blipit.android

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class BlipitReportActivity : Activity() {
    internal val lang = ReportLanguage.pick()
    internal var image: Bitmap? = null
    internal val shapes = mutableListOf<ReportShape>()
    private val root by lazy { FrameLayout(this) }
    internal var editor: EditorView? = null
    internal var status: TextView? = null
    internal var busy = false
    internal var stage = "choice"
    private val main = Handler(Looper.getMainLooper())

    internal fun t(key: String) = ReportLanguage.text(key, lang)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 21) {
            @Suppress("DEPRECATION")
            window.statusBarColor = ReportTheme.NAVY
            @Suppress("DEPRECATION")
            window.navigationBarColor = ReportTheme.NAVY
        }
        root.setBackgroundColor(ReportTheme.NAVY)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = bars(insets)
            v.setPadding(l, t, r, b)
            insets
        }
        setContentView(root)
        layoutDirectionFor()
        showChoice()
    }

    private fun layoutDirectionFor() {
        root.layoutDirection = if (lang == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun bars(insets: WindowInsets): List<Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            return listOf(i.left, i.top, i.right, i.bottom)
        }
        @Suppress("DEPRECATION")
        return listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

    private fun set(content: View) {
        root.removeAllViews()
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.requestApplyInsets()
    }

    override fun onBackPressed() {
        when {
            busy -> Unit
            stage == "details" && image != null -> showEditor()
            stage == "details" || stage == "editor" -> showChoice()
            else -> finish()
        }
    }

    internal fun showChoice() {
        stage = "choice"
        val column = vertical()
        column.setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(20f))
        val close = barButton(t("close"), Glyph.Kind.CLOSE) { finish() }
        column.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = vertical()
        body.gravity = Gravity.CENTER_VERTICAL
        body.addView(text(t("mHow"), 24f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(22f) })
        body.addView(card(t("mShot"), null, Glyph.Kind.CAMERA) { takeScreenshot() }, cardParams())
        body.addView(card(t("mSkip"), null, Glyph.Kind.TEXT) { showDetails(null) }, cardParams())
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        set(column)
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(14f) }

    private fun takeScreenshot() {
        root.visibility = View.INVISIBLE
        main.postDelayed({
            Reporter.capture { bitmap ->
                root.visibility = View.VISIBLE
                image = bitmap
                if (bitmap != null) showEditor() else showDetails(t("noShot"))
            }
        }, 250)
    }

    internal fun showEditor() {
        val bitmap = image ?: return showChoice()
        stage = "editor"
        val column = vertical()
        val top = horizontal()
        top.setPadding(dpi(4f), dpi(4f), dpi(10f), dpi(4f))
        val zoom = TextView(this).apply {
            setTextColor(ReportTheme.MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
            text = "100%"
        }
        val view = EditorView(this, bitmap, shapes) { zoom.text = String.format(Locale.ROOT, "%d%%", (it * 100).toInt()) }
        editor = view
        top.addView(barButton(t("mBack"), Glyph.Kind.BACK) { showChoice() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(iconButton(Glyph.Kind.ZOOM_OUT, t("mZoomOut")) { view.zoomBy(1 / 1.5f) })
        top.addView(zoom, LinearLayout.LayoutParams(dpi(52f), -2))
        top.addView(iconButton(Glyph.Kind.ZOOM_IN, t("mZoomIn")) { view.zoomBy(1.5f) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(primary(t("mNext")) { showDetails(null) })
        column.addView(top, LinearLayout.LayoutParams(-1, dpi(56f)))
        column.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val tools = horizontal()
        tools.setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(6f))
        val buttons = mutableMapOf<ReportShape.Tool, TextView>()
        fun select(tool: ReportShape.Tool) {
            view.tool = tool
            buttons.forEach { (k, b) -> styleTool(b, k == tool) }
        }
        listOf(
            Triple(ReportShape.Tool.PEN, t("pen"), Glyph.Kind.PEN),
            Triple(ReportShape.Tool.RECT, t("rect"), Glyph.Kind.RECT),
            Triple(ReportShape.Tool.ARROW, t("arrow"), Glyph.Kind.ARROW),
        ).forEach { (tool, label, glyph) ->
            val b = toolButton(label, glyph) { select(tool) }
            buttons[tool] = b
            tools.addView(b, LinearLayout.LayoutParams(0, dpi(60f), 1f))
        }
        tools.addView(toolButton(t("undo"), Glyph.Kind.UNDO) { view.undo() }, LinearLayout.LayoutParams(0, dpi(60f), 1f))
        tools.addView(toolButton(t("mFit"), Glyph.Kind.FIT) { view.fit() }, LinearLayout.LayoutParams(0, dpi(60f), 1f))
        select(view.tool)
        column.addView(tools, LinearLayout.LayoutParams(-1, -2))
        set(column)
    }

    internal fun showDetails(notice: String?) {
        stage = "details"
        val column = vertical()
        val top = horizontal()
        top.setPadding(dpi(4f), dpi(4f), dpi(10f), dpi(4f))
        val spinner = ProgressBar(this).apply { visibility = View.GONE }
        lateinit var send: TextView
        val title = field(t("fTitle"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val message = field(t("fDesc"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        val email = field(t("email"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val line = text("", 14f, false).apply { setTextColor(ReportTheme.MUTED) }
        status = line
        send = primary(t("send")) {
            submit(title.text.toString(), message.text.toString(), email.text.toString(), message) { on ->
                spinner.visibility = if (on) View.VISIBLE else View.GONE
                send.isEnabled = !on
                send.alpha = if (on) 0.6f else 1f
            }
        }
        top.addView(barButton(t("mBack"), Glyph.Kind.BACK) { if (!busy) { if (image != null) showEditor() else showChoice() } })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(spinner, LinearLayout.LayoutParams(dpi(28f), dpi(28f)).apply { marginEnd = dpi(10f) })
        top.addView(send)
        column.addView(top, LinearLayout.LayoutParams(-1, dpi(56f)))

        val form = vertical()
        form.setPadding(dpi(20f), dpi(8f), dpi(20f), dpi(24f))
        form.addView(text(t("title"), 22f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(12f) })
        flattened()?.let { preview ->
            val thumb = ImageView(this).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(ReportTheme.CARD, 12f)
                clipToOutline = true
            }
            form.addView(thumb, LinearLayout.LayoutParams(-1, dpi(150f)).apply { bottomMargin = dpi(12f) })
        }
        notice?.let { form.addView(text(it, 14f, false).apply { setTextColor(ReportTheme.DANGER) }, gap()) }
        form.addView(title, gap())
        form.addView(message, gap())
        form.addView(email, gap())
        form.addView(line, gap())
        val scroll = ScrollView(this).apply { addView(form) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        set(column)
    }

    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(12f) }

    internal fun flattened(): Bitmap? {
        val bitmap = image ?: return null
        if (shapes.isEmpty()) return bitmap
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        shapes.forEach { EditorView.drawShape(canvas, it, EditorView.strokeFor(bitmap)) }
        return out
    }

    private fun submit(title: String, message: String, email: String, focus: View, setBusy: (Boolean) -> Unit) {
        if (busy) return
        val client = Blipit.reporter ?: return
        if (message.isBlank()) {
            show(t("required"), true)
            focus.requestFocus()
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(root.windowToken, 0)
        busy = true
        setBusy(true)
        show(t("sending"), false)
        val flat = flattened()
        val body = ReportPayload.feedback(title, message.trim(), email, ReportDevice.context(this), shapes, image?.width ?: 0, image?.height ?: 0, if (flat == null) 0 else 1, 0, lang, Replay.forReport())
        Executors.newSingleThreadExecutor().execute {
            val result = runCatching {
                val receipt = client.send(body)
                val upload = flat?.let { bmp ->
                    runCatching {
                        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        client.uploadScreenshot(receipt, bytes, "image/jpeg")
                    }.isSuccess
                } ?: true
                Pair(receipt, upload)
            }
            main.post {
                busy = false
                setBusy(false)
                result.onSuccess { (receipt, uploaded) ->
                    val done = receipt.ref?.let { t("sentRef").replace("{ref}", it) } ?: t("sentTitle")
                    show(if (uploaded) done else "$done · ${t("noShot")}", !uploaded)
                    main.postDelayed({ finish() }, 1600)
                }.onFailure { show(t("error"), true) }
            }
        }
    }

    private fun show(text: String, error: Boolean) {
        status?.apply {
            this.text = text
            setTextColor(if (error) ReportTheme.DANGER else ReportTheme.MUTED)
            announceForAccessibility(text)
        }
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius)
        stroke?.let { setStroke(dpi(1f), it) }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun barButton(label: String, glyph: Glyph.Kind, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dpi(48f)
        setPadding(dpi(8f), 0, dpi(12f), 0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(Glyph(glyph, Color.WHITE, resources.displayMetrics.density, 20), null, null, null)
        compoundDrawablePadding = dpi(4f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun iconButton(glyph: Glyph.Kind, label: String, action: () -> Unit) = ImageView(this).apply {
        setImageDrawable(Glyph(glyph, Color.WHITE, resources.displayMetrics.density))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        minimumWidth = dpi(48f)
        minimumHeight = dpi(48f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dpi(48f), dpi(48f))
    }

    private fun primary(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dpi(44f)
        setPadding(dpi(18f), 0, dpi(18f), 0)
        background = rounded(ReportTheme.BRAND, 12f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun toolButton(label: String, glyph: Glyph.Kind, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        tag = Glyph(glyph, ReportTheme.MUTED, resources.displayMetrics.density)
        setCompoundDrawablesWithIntrinsicBounds(null, tag as Glyph, null, null)
        compoundDrawablePadding = dpi(4f)
        setPadding(0, dpi(6f), 0, dpi(4f))
        contentDescription = label
        isClickable = true
        isFocusable = true
        styleTool(this, false)
        setOnClickListener { action() }
    }

    private fun styleTool(view: TextView, on: Boolean) {
        val color = if (on) Color.WHITE else ReportTheme.MUTED
        view.setTextColor(color)
        (view.tag as? Glyph)?.tint(color)
        view.background = if (on) rounded(ReportTheme.BRAND, 12f) else null
        view.isSelected = on
    }

    private fun card(title: String, sub: String?, glyph: Glyph.Kind, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(ReportTheme.CARD, 16f, Color.argb(26, 255, 255, 255))
        setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f))
        minimumHeight = dpi(64f)
        addView(ImageView(context).apply { setImageDrawable(Glyph(glyph, ReportTheme.BRAND, resources.displayMetrics.density, 26)) }, LinearLayout.LayoutParams(dpi(32f), dpi(32f)).apply { marginEnd = dpi(14f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 17f, true))
        sub?.let { texts.addView(text(it, 13f, false).apply { setTextColor(ReportTheme.MUTED) }) }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        contentDescription = sub?.let { "$title, $it" } ?: title
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 16f
        setTextColor(Color.WHITE)
        setHintTextColor(ReportTheme.MUTED)
        background = rounded(ReportTheme.CARD, 12f)
        setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        minHeight = dpi(48f)
        contentDescription = hint
    }
}

internal object ReportDevice {
    fun context(activity: Activity): Map<String, Any?> {
        val metrics = activity.resources.displayMetrics
        val app = runCatching {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            mapOf("id" to activity.packageName, "version" to info.versionName)
        }.getOrDefault(mapOf("id" to activity.packageName))
        return mapOf(
            "platform" to "android",
            "sdk" to mapOf("name" to Blipit.PACKAGE_NAME, "version" to Blipit.VERSION),
            "app" to app,
            "device" to mapOf(
                "os" to "Android",
                "os_version" to Build.VERSION.RELEASE,
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "screen" to "${(metrics.widthPixels / metrics.density).toInt()}x${(metrics.heightPixels / metrics.density).toInt()}",
                "dpr" to metrics.density,
                "language" to Locale.getDefault().toLanguageTag(),
                "timezone" to java.util.TimeZone.getDefault().id,
                "touch" to true,
            ),
        )
    }
}

internal class EditorView(context: Context, private val bitmap: Bitmap, private val shapes: MutableList<ReportShape>, private val onZoom: (Float) -> Unit) : View(context) {
    var tool = ReportShape.Tool.PEN
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var current: ReportShape? = null
    private var multi = false
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val stroke = strokeFor(bitmap)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) fit() else zoomAround(2.5f, e.x, e.y)
            return true
        }
    })

    private var lastFocus: FloatArray? = null
    private var lastSpan = 0f

    init {
        setBackgroundColor(Color.argb(64, 0, 0, 0))
        contentDescription = ReportLanguage.text("pen")
    }

    private fun base(): Float = if (width == 0 || height == 0) 1f else minOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())

    private fun update() {
        val s = base() * zoom
        val w = bitmap.width * s
        val h = bitmap.height * s
        panX = if (w <= width) 0f else panX.coerceIn((width - w) / 2f, (w - width) / 2f)
        panY = if (h <= height) 0f else panY.coerceIn((height - h) / 2f, (h - height) / 2f)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - w) / 2f + panX, (height - h) / 2f + panY)
        matrix.invert(inverse)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        update()
    }

    fun zoomBy(factor: Float) = zoomAround(factor, width / 2f, height / 2f)

    fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val next = (zoom * factor).coerceIn(1f, 5f)
        val applied = next / zoom
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        panX += (cx - fx) * (applied - 1)
        panY += (cy - fy) * (applied - 1)
        zoom = next
        update()
        onZoom(zoom)
    }

    fun fit() {
        zoom = 1f
        panX = 0f
        panY = 0f
        update()
        onZoom(zoom)
    }

    fun undo() {
        if (shapes.isNotEmpty()) shapes.removeAt(shapes.size - 1)
        invalidate()
    }

    fun toImage(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        p[0] = p[0].coerceIn(0f, bitmap.width.toFloat())
        p[1] = p[1].coerceIn(0f, bitmap.height.toFloat())
        return p
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        taps.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                current = ReportShape(tool, mutableListOf(toImage(event.x, event.y), toImage(event.x, event.y)))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                current = null
                lastFocus = focus(event)
                lastSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && event.pointerCount >= 2) {
                    val f = focus(event)
                    val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    lastFocus?.let {
                        panX += f[0] - it[0]
                        panY += f[1] - it[1]
                        if (lastSpan > 0f && span > 0f) zoomAround(span / lastSpan, f[0], f[1]) else update()
                    }
                    lastFocus = f
                    lastSpan = span
                } else if (!multi) {
                    current?.let {
                        val p = toImage(event.x, event.y)
                        if (it.tool == ReportShape.Tool.PEN) it.points.add(p) else it.points[1] = p
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastFocus = null
                lastSpan = 0f
            }
            MotionEvent.ACTION_UP -> {
                current?.let {
                    val a = it.points.first()
                    val b = it.points.last()
                    val moved = it.points.any { p -> hypot(p[0] - a[0], p[1] - a[1]) > stroke }
                    if (!multi && moved && (it.tool == ReportShape.Tool.PEN || hypot(b[0] - a[0], b[1] - a[1]) > stroke)) shapes.add(it)
                }
                current = null
                multi = false
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                multi = false
            }
        }
        invalidate()
        return true
    }

    private fun focus(event: MotionEvent): FloatArray {
        var x = 0f
        var y = 0f
        for (i in 0 until event.pointerCount) {
            x += event.getX(i)
            y += event.getY(i)
        }
        return floatArrayOf(x / event.pointerCount, y / event.pointerCount)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        shapes.forEach { drawShape(canvas, it, stroke) }
        current?.let { drawShape(canvas, it, stroke) }
        canvas.restore()
    }

    companion object {
        fun strokeFor(bitmap: Bitmap) = maxOf(4f, bitmap.width / 130f)

        fun drawShape(canvas: Canvas, shape: ReportShape, width: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ReportTheme.MARK
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val a = shape.points.firstOrNull() ?: return
            val b = shape.points.last()
            when (shape.tool) {
                ReportShape.Tool.PEN -> {
                    val path = Path()
                    path.moveTo(a[0], a[1])
                    shape.points.drop(1).forEach { path.lineTo(it[0], it[1]) }
                    canvas.drawPath(path, paint)
                }
                ReportShape.Tool.RECT -> canvas.drawRect(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1]), paint)
                ReportShape.Tool.ARROW -> {
                    canvas.drawLine(a[0], a[1], b[0], b[1], paint)
                    val angle = atan2(b[1] - a[1], b[0] - a[0])
                    val head = width * 5
                    for (side in listOf(Math.PI * 0.8, -Math.PI * 0.8)) {
                        canvas.drawLine(b[0], b[1], b[0] + head * cos(angle + side).toFloat(), b[1] + head * sin(angle + side).toFloat(), paint)
                    }
                }
            }
        }
    }
}
