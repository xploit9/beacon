package dev.hermes.android.agentphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A decoded frame ready to draw; [version] grows with every frame, so the canvas redraws even when the bitmap object is reused. */
internal class ShownFrame(val image: ImageBitmap, val width: Int, val height: Int, val rotation: Int, val version: Long)

/**
 * Decodes the view's JPEG frames on one background thread into a small ring of reused bitmaps (one 720x1600 frame is 4.6 MB; a new one
 * per frame would churn the heap at 14 frames a second). The newest decoded frame is published on the main thread, then [FrameSink.submit]'s
 * callback runs, which acks it: the server sends the next frame only after that, so a bitmap is never decoded into while it is the one
 * being drawn. Three buffers leave one frame of slack for a draw the render thread has not finished. Nothing is written anywhere: the
 * pixels live in these bitmaps only, and [clear] drops them.
 */
internal class FrameDecoder : FrameSink {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "beacon-agent-phone-frames").apply { isDaemon = true } }
    private val ring = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private var version = 0L
    @Volatile private var closed = false
    /** Read by the canvas; written on the main thread only. */
    val shown: MutableState<ShownFrame?> = mutableStateOf(null)

    override fun submit(header: FrameHeader, data: ByteArray, done: (Boolean) -> Unit) {
        if (closed) return
        worker.execute {
            val bitmap = if (closed) null else decode(data)
            main.post {
                if (closed) return@post
                if (bitmap != null) shown.value = ShownFrame(bitmap.asImageBitmap(), bitmap.width, bitmap.height, header.rotation, ++version)
                done(bitmap != null)
            }
        }
    }

    private fun decode(data: ByteArray): Bitmap? {
        val offset = ViewProtocol.HEADER_BYTES
        val slot = next
        val reuse = ring[slot]
        fun options(into: Bitmap?) = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = into
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, offset, data.size - offset, options(reuse))
        } catch (_: IllegalArgumentException) {
            // A frame of another size (the phone turned) does not fit the old buffer: decode into a new one.
            runCatching { BitmapFactory.decodeByteArray(data, offset, data.size - offset, options(null)) }.getOrNull()
        } ?: return null
        ring[slot] = bitmap
        next = (slot + 1) % ring.size
        return bitmap
    }

    override fun clear() {
        if (closed) return
        main.post { if (!closed) shown.value = null }
        worker.execute { ring.fill(null) }
    }

    fun close() {
        closed = true
        shown.value = null
        worker.execute { ring.fill(null) }
        worker.shutdown()
    }
}

/** Rounded corners like the phone's own screen, as a share of the picture's width. */
private const val ScreenCornerShare = .045f

/**
 * The agent phone's picture, letterboxed into the space it has and drawn on whole pixels with high-quality filtering, so text stays as
 * sharp as the 720-pixel frame allows. Fingers on the picture become [TouchPoint]s through [onTouch] (fractions of the frame as shown);
 * [onFingerDown] runs before a new finger is sent. While [enabled] is false nothing is sent and fingers already down are lifted.
 */
@Composable
internal fun FrameCanvas(
    frame: ShownFrame?,
    modifier: Modifier = Modifier,
    /** How much the picture is darkened, 0-1: a little while it reconnects, more behind an end card. */
    dim: Float = 0f,
    enabled: Boolean = true,
    description: String = "",
    edge: Color = Color.White.copy(alpha = .08f),
    onFingerDown: () -> Unit = {},
    onTouch: (List<TouchPoint>) -> Unit = {},
) {
    val slots = remember { PointerSlots() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fit = fitFrame(size.width, size.height, frame?.width ?: 0, frame?.height ?: 0)
    val currentFit by rememberUpdatedState(fit)
    val live by rememberUpdatedState(enabled && frame != null)
    val send by rememberUpdatedState(onTouch)
    val beforeDown by rememberUpdatedState(onFingerDown)
    // Fingers already down when the view stops taking input (the debugging prompt, a drop) are lifted on the phone at once.
    LaunchedEffect(enabled) { if (!enabled && slots.active) slots.cancelAll().takeIf { it.isNotEmpty() }?.let { send(it) } }
    Canvas(
        modifier
            .onSizeChanged { size = it }
            .semantics { if (description.isNotBlank()) contentDescription = description }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val points = mutableListOf<TouchPoint>()
                            val area = currentFit
                            for (change in event.changes) {
                                val id = change.id.value
                                val at = area.fraction(change.position.x, change.position.y)
                                when {
                                    !live -> slots.up(id, at)?.let(points::add)
                                    change.pressed && !change.previousPressed -> if (area.contains(change.position.x, change.position.y)) {
                                        if (!slots.active) beforeDown()
                                        slots.down(id, at)?.let(points::add)
                                    }
                                    !change.pressed && change.previousPressed -> slots.up(id, at)?.let(points::add)
                                    change.pressed -> slots.move(id, at)?.let(points::add)
                                }
                                change.consume()
                            }
                            if (event.type == PointerEventType.Exit && slots.active && event.changes.none { it.pressed }) points += slots.cancelAll()
                            if (points.isNotEmpty() && (live || points.all { it.phase == "up" })) send(points)
                        }
                    } finally {
                        slots.cancelAll().takeIf { it.isNotEmpty() }?.let { send(it) }
                    }
                }
            },
    ) {
        val shown = frame ?: return@Canvas
        if (fit.empty) return@Canvas
        val radius = fit.width * ScreenCornerShare
        val outline = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(fit.left.toFloat(), fit.top.toFloat(), (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat(), CornerRadius(radius)))
        }
        clipPath(outline) {
            drawImage(shown.image, srcOffset = IntOffset.Zero, srcSize = IntSize(shown.width, shown.height),
                dstOffset = IntOffset(fit.left, fit.top), dstSize = IntSize(fit.width, fit.height), filterQuality = FilterQuality.High)
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)), Offset(fit.left.toFloat(), fit.top.toFloat()), Size(fit.width.toFloat(), fit.height.toFloat()))
        }
        drawPath(outline, edge, style = Stroke(1.dp.toPx()))
    }
}
