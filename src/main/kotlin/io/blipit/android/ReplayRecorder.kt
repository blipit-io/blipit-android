package io.blipit.android

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import io.blipit.nuggets.Config
import io.blipit.nuggets.Json
import io.blipit.nuggets.Outbox
import io.blipit.nuggets.Nuggets
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService

class ReplayFrame(val at: Long, val jpeg: ByteArray, val width: Int, val height: Int)

object ReplayPayload {
    const val SEGMENT_MS = 10_000L
    const val BUFFER_MS = 30_000L
    const val MASK_TAG = "blipit-mask"

    @JvmStatic
    fun videoSize(viewWidth: Int, viewHeight: Int, target: Int): IntArray {
        if (viewWidth <= 0 || viewHeight <= 0) return intArrayOf(0, 0)
        val scale = minOf(1f, target.toFloat() / viewWidth)
        val w = maxOf(16, (viewWidth * scale).toInt() / 16 * 16)
        val h = maxOf(16, (viewHeight * scale).toInt() / 16 * 16)
        return intArrayOf(w, h)
    }

    @JvmStatic
    fun events(n: Int, frames: List<ReplayFrame>, marks: List<Map<String, Any?>>, bytes: Int, screen: String?): List<Map<String, Any?>> {
        val first = frames.first()
        val start = first.at
        val duration = frames.last().at - start + 1000
        val out = ArrayList<Map<String, Any?>>()
        out.add(mapOf("type" to 4, "timestamp" to start, "data" to mapOf("href" to (screen ?: ""), "width" to first.width, "height" to first.height)))
        out.add(
            mapOf(
                "type" to 5,
                "timestamp" to start,
                "data" to mapOf(
                    "tag" to "video",
                    "payload" to mapOf(
                        "segmentId" to n,
                        "size" to bytes,
                        "duration" to duration,
                        "encoding" to "h264",
                        "container" to "mp4",
                        "width" to first.width,
                        "height" to first.height,
                        "frameCount" to frames.size,
                        "frameRate" to 1,
                        "left" to 0,
                        "top" to 0,
                    ),
                ),
            ),
        )
        out.addAll(marks.filter { (it["timestamp"] as? Long ?: 0L) >= start - 1000 })
        return out
    }

    @JvmStatic
    fun tap(at: Long, x: Float, y: Float): Map<String, Any?> = mapOf("type" to 3, "timestamp" to at, "data" to mapOf("source" to 2, "type" to 7, "id" to 0, "x" to x.toInt(), "y" to y.toInt()))

    @JvmStatic
    fun screen(at: Long, name: String): Map<String, Any?> = mapOf(
        "type" to 5,
        "timestamp" to at,
        "data" to mapOf("tag" to "breadcrumb", "payload" to mapOf("timestamp" to at / 1000.0, "type" to "default", "category" to "navigation", "data" to mapOf("to" to name))),
    )

    @JvmStatic
    @TargetApi(26)
    fun body(n: Int, startedAt: Long, frames: List<ReplayFrame>, events: List<Map<String, Any?>>, video: ByteArray, device: String, os: String, screen: String?): Map<String, Any?> = linkedMapOf(
        "segment_id" to n,
        "replay_start_timestamp" to startedAt,
        "timestamp" to frames.last().at + 1000,
        "platform" to "android",
        "device" to device,
        "os" to os,
        "screen" to screen,
        "events" to events,
        "video" to java.util.Base64.getEncoder().encodeToString(video),
    )

    @JvmStatic
    fun write(file: File, replayId: String, n: Int, startedAt: Long, screen: String?, frames: List<ReplayFrame>, events: List<Map<String, Any?>>) {
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeUTF(replayId)
            out.writeInt(n)
            out.writeLong(startedAt)
            out.writeUTF(screen ?: "")
            out.writeInt(frames.size)
            for (f in frames) {
                out.writeLong(f.at)
                out.writeInt(f.width)
                out.writeInt(f.height)
                out.writeInt(f.jpeg.size)
                out.write(f.jpeg)
            }
            val json = Json.encode(events).toByteArray(Charsets.UTF_8)
            out.writeInt(json.size)
            out.write(json)
        }
    }

    class Pending(val replayId: String, val n: Int, val startedAt: Long, val screen: String?, val frames: List<ReplayFrame>, val events: List<Map<String, Any?>>)

    @JvmStatic
    fun read(file: File): Pending = DataInputStream(FileInputStream(file)).use { input ->
        val id = input.readUTF()
        val n = input.readInt()
        val started = input.readLong()
        val screen = input.readUTF().takeIf { it.isNotEmpty() }
        val count = input.readInt()
        require(count in 1..200)
        val frames = ArrayList<ReplayFrame>()
        repeat(count) {
            val at = input.readLong()
            val w = input.readInt()
            val h = input.readInt()
            val size = input.readInt()
            require(size in 1..2_000_000)
            frames.add(ReplayFrame(at, ByteArray(size).also { input.readFully(it) }, w, h))
        }
        val size = input.readInt()
        require(size in 0..2_000_000)
        val bytes = ByteArray(size).also { input.readFully(it) }
        @Suppress("UNCHECKED_CAST")
        val events = (Json.parse(String(bytes, Charsets.UTF_8)) as? List<Map<String, Any?>>) ?: emptyList()
        Pending(id, n, started, screen, frames, events)
    }

    @JvmStatic
    fun accepted(status: Int, body: String): Boolean? {
        if (status == 429 || PlatformMismatch.check(status, body)) return false
        if (status !in 200..299) return null
        val json = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: return true
        val reason = json["reason"] as? String
        return !(json["accepted"] == false && (reason == "replay_quota" || reason == "replay_paused" || reason == "replay_full"))
    }
}

@TargetApi(26)
internal class ReplayRecorder(
    private val config: Config,
    private val io: ExecutorService,
    val session: Boolean,
    val onErrorRate: Double,
    @Volatile var labels: Boolean,
    private val target: Int,
    private val bitRate: Int,
    private val pendingDir: File?,
    private val onPaused: () -> Unit,
) {
    val replayId: String = Nuggets.id()
    private val startedAt = System.currentTimeMillis()
    private val frames = ArrayDeque<ReplayFrame>()
    private val marks = ArrayDeque<Map<String, Any?>>()
    private var segment = 0
    private var screen: String? = null
    private var activity: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("blipit-replay-frames").also { it.start() }
    private val frameHandler = Handler(thread.looper)
    private val mask = Paint().apply { color = Color.rgb(158, 158, 158) }

    @Volatile
    private var running = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { capture() }
            main.postDelayed(this, 1000)
        }
    }

    init {
        main.post(tick)
    }

    fun attach(a: Activity) {
        activity = WeakReference(a)
    }

    fun detach(a: Activity) {
        if (activity?.get() === a) activity = null
    }

    @Synchronized
    fun screen(name: String) {
        if (name == screen) return
        screen = name
        mark(ReplayPayload.screen(System.currentTimeMillis(), name))
    }

    fun tap(decor: View, x: Float, y: Float) {
        val size = ReplayPayload.videoSize(decor.width, decor.height, target)
        if (size[0] == 0) return
        synchronized(this) { mark(ReplayPayload.tap(System.currentTimeMillis(), x * size[0] / decor.width, y * size[1] / decor.height)) }
    }

    private fun mark(event: Map<String, Any?>) {
        marks.addLast(event)
        while (marks.size > 500) marks.removeFirst()
    }

    private fun capture() {
        if (!Core.foreground) return
        val a = activity?.get() ?: return
        val window = a.window ?: return
        val decor = window.peekDecorView() ?: return
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return
        val size = ReplayPayload.videoSize(decor.width, decor.height, target)
        val sx = size[0].toFloat() / decor.width
        val sy = size[1].toFloat() / decor.height
        val rects = ArrayList<RectF>()
        collect(decor, rects, sx, sy, labels && !composeLoaded)
        val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        val at = System.currentTimeMillis()
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS && running) {
                val canvas = Canvas(bitmap)
                for (r in rects) canvas.drawRect(r, mask)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                add(ReplayFrame(at, out.toByteArray(), size[0], size[1]))
            }
            bitmap.recycle()
        }, frameHandler)
    }

    private fun collect(view: View, out: MutableList<RectF>, sx: Float, sy: Float, readable: Boolean) {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return
        if (shouldMask(view, readable)) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            out.add(RectF(loc[0] * sx, loc[1] * sy, (loc[0] + view.width) * sx, (loc[1] + view.height) * sy))
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), out, sx, sy, readable)
    }

    private fun shouldMask(view: View, readable: Boolean): Boolean {
        if (view.tag == ReplayPayload.MASK_TAG) return true
        if (view.javaClass.name.contains("ComposeView")) return true
        return when (view) {
            is WebView, is VideoView, is SurfaceView, is TextureView, is ImageView, is EditText -> true
            is TextView -> !readable
            else -> false
        }
    }

    @Synchronized
    private fun add(frame: ReplayFrame) {
        frames.addLast(frame)
        if (session) {
            if (frame.at - frames.first().at >= ReplayPayload.SEGMENT_MS) send(takeLocked())
        } else {
            while (frames.isNotEmpty() && frame.at - frames.first().at > ReplayPayload.BUFFER_MS) frames.removeFirst()
            val cutoff = frames.first().at - 1000
            while (marks.isNotEmpty() && ((marks.first()["timestamp"] as? Long) ?: 0L) < cutoff) marks.removeFirst()
        }
    }

    private class Batch(val n: Int, val frames: List<ReplayFrame>, val marks: List<Map<String, Any?>>, val screen: String?)

    private fun takeLocked(): Batch? {
        if (frames.isEmpty()) return null
        val batch = Batch(segment++, frames.toList(), marks.toList(), screen)
        frames.clear()
        marks.clear()
        return batch
    }

    fun flushBuffer() {
        val batch = synchronized(this) { takeLocked() }
        send(batch)
    }

    fun persistForCrash() {
        val dir = pendingDir ?: return
        val batch = synchronized(this) { takeLocked() } ?: return
        runCatching {
            dir.mkdirs()
            ReplayPayload.write(File(dir, "$replayId-${batch.n}.bin"), replayId, batch.n, startedAt, batch.screen, batch.frames, batch.marks)
        }
    }

    private fun send(batch: Batch?) {
        if (batch == null) return
        runCatching { io.execute { upload(config, replayId, batch.n, startedAt, batch.frames, batch.marks, batch.screen, bitRate)?.let { if (!it) pause() } } }
    }

    private fun pause() {
        stop()
        onPaused()
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        thread.quitSafely()
        synchronized(this) {
            frames.clear()
            marks.clear()
        }
    }

    companion object {
        private val composeLoaded: Boolean by lazy { runCatching { Class.forName("androidx.compose.ui.platform.ComposeView") }.isSuccess }

        fun upload(config: Config, replayId: String, n: Int, startedAt: Long, frames: List<ReplayFrame>, marks: List<Map<String, Any?>>, screen: String?, bitRate: Int): Boolean? {
            if (PlatformMismatch.blocked) return false
            if (frames.isEmpty()) return null
            val video = runCatching { Mp4.encode(frames, bitRate) }.getOrNull() ?: return null
            val events = ReplayPayload.events(n, frames, marks, video.size, screen)
            val body = ReplayPayload.body(n, startedAt, frames, events, video, Build.MODEL ?: "", "Android ${Build.VERSION.RELEASE}", screen)
            val reply = runCatching { Outbox.send(config, "POST", "/api/v1/replays/$replayId/video/$n", "application/json", Json.encode(body).toByteArray(Charsets.UTF_8)) }.getOrNull() ?: return null
            return ReplayPayload.accepted(reply.status, reply.body)
        }

        fun uploadPending(config: Config, dir: File, bitRate: Int) {
            val files = dir.listFiles { f -> f.name.endsWith(".bin") } ?: return
            for (file in files.sortedBy { it.name }) {
                val pending = runCatching { ReplayPayload.read(file) }.getOrNull()
                if (pending != null) {
                    val result = upload(config, pending.replayId, pending.n, pending.startedAt, pending.frames, pending.events, pending.screen, bitRate)
                    if (result == null && System.currentTimeMillis() - file.lastModified() < 86_400_000L) continue
                }
                file.delete()
            }
        }
    }
}

@TargetApi(26)
internal object Mp4 {
    private const val MIME = "video/avc"

    fun encode(frames: List<ReplayFrame>, bitRate: Int): ByteArray {
        val w = frames[0].width
        val h = frames[0].height
        val file = File.createTempFile("blipit-replay", ".mp4")
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            val t0 = frames[0].at
            var next = 0
            var inputDone = false
            var track = -1
            var lastPts = 0L
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (next < frames.size) {
                            val frame = frames[next++]
                            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                            val image = codec.getInputImage(index)
                            if (bitmap != null && image != null) fill(image, bitmap)
                            bitmap?.recycle()
                            lastPts = maxOf(lastPts + 1, (frame.at - t0) * 1000)
                            codec.queueInputBuffer(index, 0, w * h * 3 / 2, lastPts, 0)
                        } else {
                            codec.queueInputBuffer(index, 0, 0, lastPts + 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (track >= 0) muxer.stop()
            return file.readBytes()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
            file.delete()
        }
    }

    private fun fill(image: Image, bitmap: Bitmap) {
        val w = minOf(image.width, bitmap.width)
        val h = minOf(image.height, bitmap.height)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val planes = image.planes
        val yb = planes[0].buffer
        val ub = planes[1].buffer
        val vb = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * bitmap.width + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                yb.put(y * yRow + x * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    ub.put((y / 2) * uRow + (x / 2) * uPix, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vb.put((y / 2) * vRow + (x / 2) * vPix, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }
}
