package com.capstone.stethoscope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A zoomable, axis-labelled plot — the thing that makes testing mode more than a pretty
 * waveform.
 *
 * The clinical [Waveform] is deliberately axis-free: it shows a scrolling level and
 * nothing else, because a number on it would invite reading a measurement off a display
 * that auto-ranges. This is the opposite tool. Every axis is labelled in real units, the
 * x-axis can be pinched down to individual samples or individual hertz, and a tap places
 * a cursor that reports the exact value under it — which is the only way to answer
 * questions like "is the corner actually at 600 Hz" by looking rather than by trusting.
 *
 * ## Rendering at zoom
 *
 * A 60-second capture at 16 kHz is nearly a million samples across maybe 900 pixels, so
 * the series is drawn column by column: [drawWaveform] reduces each pixel's worth of
 * samples to its min and max and draws that as one vertical segment. Drawing a polyline
 * through every sample instead would be both slow and *wrong* — consecutive points would
 * land on the same pixel and the trace would show whichever happened to be last, hiding
 * exactly the transients a scope exists to reveal.
 */
class ScopeTransform(
    val plot: Rect,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
) {
    private val xSpan = (xMax - xMin).takeIf { it != 0.0 } ?: 1.0
    private val ySpan = (yMax - yMin).takeIf { it != 0.0 } ?: 1.0

    /** Data x to pixel x. */
    fun px(x: Double): Float = plot.left + ((x - xMin) / xSpan * plot.width).toFloat()

    /** Data y to pixel y, with y increasing upward as a plot should. */
    fun py(y: Double): Float = plot.bottom - ((y - yMin) / ySpan * plot.height).toFloat()

    /** Pixel x back to data x, for hit-testing a cursor tap. */
    fun dataX(pixelX: Float): Double = xMin + (pixelX - plot.left) / plot.width * xSpan
}

/**
 * Zoom and pan state for one plot's x-axis, held as a scale plus a centre so that
 * clamping stays trivial: the visible window is always exactly `1/scale` of the full
 * domain and can never wander off the end of it.
 */
class ScopeZoomState(private val maxScale: Float = 4_000f) {

    var scale by mutableFloatStateOf(1f)
        private set

    /** Fraction of the full domain at the centre of the viewport. */
    private var center by mutableFloatStateOf(0.5f)

    val isZoomed: Boolean get() = scale > 1.001f

    /** Visible fraction of the full domain, as `lo` to `hi` in 0..1. */
    fun window(): Pair<Float, Float> {
        val half = 0.5f / scale
        val c = center.coerceIn(half, 1f - half)
        return (c - half) to (c + half)
    }

    /**
     * Zooms by [factor] about [focusFraction] (0..1 across the plot), keeping whatever is
     * under the fingers under the fingers. Zooming about the centre instead feels like the
     * plot is fighting you the moment you are looking at anything off-centre.
     */
    fun zoomAbout(factor: Float, focusFraction: Float) {
        val (lo, hi) = window()
        val anchor = lo + focusFraction.coerceIn(0f, 1f) * (hi - lo)
        val next = (scale * factor).coerceIn(1f, maxScale)
        val half = 0.5f / next
        scale = next
        center = (anchor - focusFraction.coerceIn(0f, 1f) * 2f * half + half)
            .coerceIn(half, 1f - half)
    }

    /** Pans by [dxFraction] of the visible window (positive = content moves right). */
    fun pan(dxFraction: Float) {
        val (lo, hi) = window()
        val half = 0.5f / scale
        center = (center - dxFraction * (hi - lo)).coerceIn(half, 1f - half)
    }

    fun reset() {
        scale = 1f
        center = 0.5f
    }
}

@Composable
fun rememberScopeZoom(vararg keys: Any?): ScopeZoomState =
    remember(*keys) { ScopeZoomState() }

/** A labelled vertical reference line, e.g. the 600 Hz corner. */
data class ScopeGuide(val x: Double, val label: String, val color: Color)

/**
 * Draws a plot with labelled axes, pinch-to-zoom and pan on x, and a tap cursor.
 *
 * [xMin]/[xMax] are the *full* domain; the visible slice is whatever [zoom] says. The
 * series itself is drawn by [drawSeries], which receives a [ScopeTransform] already
 * configured for the visible window.
 */
@Composable
fun ScopePlot(
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    xAxisLabel: String,
    yAxisLabel: String,
    formatX: (Double) -> String,
    formatY: (Double) -> String,
    zoom: ScopeZoomState,
    cursorX: Double?,
    onCursorChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    guides: List<ScopeGuide> = emptyList(),
    height: Dp = 200.dp,
    readout: String? = null,
    drawSeries: DrawScope.(ScopeTransform) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val axisStyle = TextStyle(fontSize = 9.sp, color = colors.onSurfaceVariant)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Axis units live here, not on the canvas.
            //
            // Drawing them inside the plot put the y unit on top of the topmost y tick
            // label ("counts" over "30664") and the x unit on top of the last x tick
            // ("s" over "5.04"), because both were anchored to the same corner as a tick
            // that is always present. There is no spare room in a 30 dp gutter to
            // separate them, and the header is dead space until a cursor is placed.
            Text(
                text = readout ?: "$yAxisLabel vs $xAxisLabel · pinch to zoom, tap to measure",
                style = MaterialTheme.typography.labelSmall,
                color = if (readout != null) colors.primary else colors.onSurfaceVariant,
            )
            if (zoom.isZoomed || cursorX != null) {
                TextButton(onClick = { zoom.reset(); onCursorChange(null) }) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                // Tap first in the chain so a deliberate tap places the cursor instead of
                // being eaten as a zero-distance pan by the transform detector.
                .pointerInput(xMin, xMax) {
                    detectTapGestures { offset ->
                        val plot = plotRect(size.width.toFloat(), size.height.toFloat())
                        if (!plot.contains(offset)) {
                            onCursorChange(null)
                            return@detectTapGestures
                        }
                        val (lo, hi) = zoom.window()
                        val t = ScopeTransform(
                            plot,
                            xMin + lo * (xMax - xMin),
                            xMin + hi * (xMax - xMin),
                            yMin,
                            yMax,
                        )
                        onCursorChange(t.dataX(offset.x))
                    }
                }
                .scopeGestures(zoom, xMin, xMax),
        ) {
            val plot = plotRect(size.width, size.height)
            if (plot.width <= 0f || plot.height <= 0f) return@Canvas

            val (lo, hi) = zoom.window()
            val transform = ScopeTransform(
                plot = plot,
                xMin = xMin + lo * (xMax - xMin),
                xMax = xMin + hi * (xMax - xMin),
                yMin = yMin,
                yMax = yMax,
            )

            drawAxes(
                t = transform,
                measurer = measurer,
                axisStyle = axisStyle,
                gridColor = colors.outlineVariant,
                axisColor = colors.outline,
                formatX = formatX,
                formatY = formatY,
            )

            // Guides under the series: a reference line should never hide the data it is
            // there to help you judge.
            //
            // Labels are stacked one row per guide rather than all sharing the top line.
            // On the raw stage's 0–8 kHz axis the 600 Hz and 1 kHz guides are 30 px apart,
            // so their labels rendered straight over each other as unreadable mush.
            guides.forEachIndexed { index, guide ->
                if (guide.x < transform.xMin || guide.x > transform.xMax) return@forEachIndexed
                val x = transform.px(guide.x)
                drawLine(
                    color = guide.color,
                    start = Offset(x, plot.top),
                    end = Offset(x, plot.bottom),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val label = measurer.measure(guide.label, axisStyle.copy(color = guide.color))
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        (x + 3f).coerceAtMost(plot.right - label.size.width),
                        plot.top + 2f + index * label.size.height,
                    ),
                )
            }

            drawSeries(transform)

            if (cursorX != null && cursorX >= transform.xMin && cursorX <= transform.xMax) {
                val x = transform.px(cursorX)
                drawLine(
                    color = colors.primary,
                    start = Offset(x, plot.top),
                    end = Offset(x, plot.bottom),
                    strokeWidth = 1.5f,
                )
            }
        }
    }
}

/**
 * Zoom and pan gestures that **coexist with the enclosing vertical scroll**.
 *
 * The obvious implementation — `detectTransformGestures` — does not. It claims every
 * drag, including a one-finger vertical swipe, so the `verticalScroll` wrapping the
 * testing screen never saw one and the plots below the first were simply unreachable: the
 * page looked frozen. Nothing about that is visible in a unit test or a preview; it only
 * shows up when you try to scroll past a plot on a real phone.
 *
 * The rule here instead:
 *
 *  - **Two fingers** always mean the plot — pinch to zoom, drag to pan.
 *  - **One finger** scrolls the page, *unless* the plot is already zoomed in and the drag
 *    is clearly horizontal, in which case it pans. When the plot is not zoomed there is
 *    nothing to pan anyway, so the page always wins — which is the common case and the
 *    one that must never feel stuck.
 *
 * Events are only consumed once a gesture has been claimed, so an unclaimed drag reaches
 * the scroll container untouched.
 */
private fun Modifier.scopeGestures(
    zoom: ScopeZoomState,
    xMin: Double,
    xMax: Double,
): Modifier = this.pointerInput(xMin, xMax, zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val plot = plotRect(size.width.toFloat(), size.height.toFloat())
        if (plot.width <= 0f) return@awaitEachGesture

        var claimed = false
        var dragX = 0f
        var dragY = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break

            if (pressed >= 2) {
                claimed = true
            } else if (!claimed && zoom.isZoomed) {
                val pan = event.calculatePan()
                dragX += pan.x
                dragY += pan.y
                // Horizontal-dominant and past the slop: this is a pan, not a scroll.
                if (abs(dragX) > viewConfiguration.touchSlop && abs(dragX) > abs(dragY)) {
                    claimed = true
                }
            }

            if (!claimed) continue

            val zoomChange = event.calculateZoom()
            if (abs(zoomChange - 1f) > 1e-4f) {
                val centroid = event.calculateCentroid(useCurrent = true)
                if (centroid != Offset.Unspecified) {
                    zoom.zoomAbout(zoomChange, (centroid.x - plot.left) / plot.width)
                }
            }
            val panChange = event.calculatePan()
            if (panChange.x != 0f) zoom.pan(panChange.x / plot.width)

            // Claimed: take the events so the scroll container does not also act on them.
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Where the data actually goes, leaving gutters for the tick labels. Computed from the
 * canvas size rather than fixed so the gesture handlers and the draw pass cannot disagree
 * about which pixels are the plot.
 *
 * Gutters are in **dp**, converted through [Density]. Hard-coding pixels here looked fine
 * on a 1x preview and clipped every y-axis label on a real 3x phone, because the labels
 * are sized in sp and scale with density while a raw pixel count does not.
 */
private fun Density.plotRect(width: Float, height: Float): Rect = Rect(
    left = Y_GUTTER.toPx(),
    top = PLOT_INSET.toPx(),
    right = width - PLOT_INSET.toPx(),
    bottom = height - X_GUTTER.toPx(),
)

/** Room for a five-digit y label at 9.sp. */
private val Y_GUTTER = 30.dp

/** Room for one line of x labels below the frame. */
private val X_GUTTER = 16.dp

private val PLOT_INSET = 4.dp

private fun DrawScope.drawAxes(
    t: ScopeTransform,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    gridColor: Color,
    axisColor: Color,
    formatX: (Double) -> String,
    formatY: (Double) -> String,
) {
    drawRect(
        color = axisColor,
        topLeft = Offset(t.plot.left, t.plot.top),
        size = t.plot.size,
        style = Stroke(width = 1f),
    )

    for (i in 0..X_TICKS) {
        val value = t.xMin + (t.xMax - t.xMin) * i / X_TICKS
        val x = t.px(value)
        drawLine(gridColor, Offset(x, t.plot.top), Offset(x, t.plot.bottom), strokeWidth = 0.5f)
        val layout = measurer.measure(formatX(value), axisStyle)
        // Nudge the first and last labels inward so they are not clipped by the canvas.
        val left = (x - layout.size.width / 2f)
            .coerceIn(0f, size.width - layout.size.width)
        drawText(layout, topLeft = Offset(left, t.plot.bottom + 3f))
    }

    for (i in 0..Y_TICKS) {
        val value = t.yMin + (t.yMax - t.yMin) * i / Y_TICKS
        val y = t.py(value)
        drawLine(gridColor, Offset(t.plot.left, y), Offset(t.plot.right, y), strokeWidth = 0.5f)
        val layout = measurer.measure(formatY(value), axisStyle)
        drawText(
            layout,
            topLeft = Offset(
                t.plot.left - layout.size.width - 4f,
                // Kept inside the plot's vertical extent: centred on the bottom gridline,
                // the lowest label hung below the frame and collided with the first x label.
                (y - layout.size.height / 2f).coerceIn(
                    t.plot.top,
                    maxOf(t.plot.top, t.plot.bottom - layout.size.height),
                ),
            ),
        )
    }
}

/**
 * Draws [samples] against a time axis in seconds, reducing each pixel column to the
 * min/max of the samples that fall in it. See the class KDoc for why this rather than a
 * polyline.
 */
fun DrawScope.drawWaveform(
    t: ScopeTransform,
    samples: DoubleArray,
    sampleRate: Int,
    color: Color,
) {
    if (samples.isEmpty() || sampleRate <= 0) return
    val columns = t.plot.width.roundToInt().coerceAtLeast(1)

    for (c in 0 until columns) {
        val x = t.plot.left + c
        val tStart = t.dataX(x)
        val tEnd = t.dataX(x + 1f)
        var i = (tStart * sampleRate).toInt()
        val iEnd = (tEnd * sampleRate).toInt()
        if (iEnd < 0 || i > samples.lastIndex) continue
        if (i < 0) i = 0

        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        // At high zoom a column can span less than one sample; take at least one so the
        // trace stays continuous instead of dotted.
        var j = i
        val last = minOf(maxOf(iEnd, i), samples.lastIndex)
        while (j <= last) {
            val v = samples[j]
            if (v < min) min = v
            if (v > max) max = v
            j++
        }
        if (min > max) continue

        drawLine(
            color = color,
            start = Offset(x, t.py(max)),
            end = Offset(x, t.py(min).coerceAtLeast(t.py(max) + 1f)),
            strokeWidth = 1f,
        )
    }
}

/**
 * Draws a value-vs-Hz curve as a polyline, one point per pixel column.
 *
 * [dashed] marks a curve as a reference rather than a measurement — the response plot
 * draws the specification dashed underneath the solid measured trace, so which is which
 * survives being screenshotted in greyscale.
 */
fun DrawScope.drawSpectrum(
    t: ScopeTransform,
    valueAt: (Double) -> Double,
    color: Color,
    dashed: Boolean = false,
) {
    val columns = t.plot.width.roundToInt().coerceAtLeast(1)
    val path = Path()
    for (c in 0..columns) {
        val x = t.plot.left + c
        val hz = t.dataX(x)
        val y = t.py(valueAt(hz).coerceIn(t.yMin, t.yMax))
        if (c == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color = color,
        style = Stroke(
            width = 1.5f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
        ),
    )
}

private const val X_TICKS = 4
private const val Y_TICKS = 4
