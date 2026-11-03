package com.capstone.stethoscope.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.capstone.stethoscope.R
import com.capstone.stethoscope.data.model.AuscultationSite
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A front-facing chest with the five praecordial auscultation sites, tap to choose.
 *
 * ## The artwork and the coordinates
 *
 * The chest is `res/drawable-nodpi/chest_diagram.png`, or `chest_diagram_female.png` when the
 * patient's sex is Female (see [chestArt]), with the five coloured site dots baked into it.
 * The female figure is the male one with a female chest drawn over it and the canvas
 * extended further down the torso, so the breasts can sit at a natural height. Both images
 * are the same width and identical above the male image's bottom edge, so every dot is at
 * the same pixel in both and one set of coordinates serves both — only the aspect ratio
 * differs. Everything drawn on top (selection ring, labels, tap targets) is placed by
 * [siteCentre] from [AuscultationSite.x]/[AuscultationSite.y], which are measured dot
 * centres. Swapping the artwork means re-measuring those.
 *
 * The labels are drawn here rather than baked into the image so they come from string
 * resources (and so translate), and stay legible at any screen size.
 *
 * ## Orientation
 *
 * Anatomical convention: the figure **faces the viewer**, so the patient's right side
 * appears on the left of the screen. Aortic (patient's right) therefore draws left of
 * centre.
 */
@Composable
fun ChestDiagram(
    selected: AuscultationSite?,
    onSelect: (AuscultationSite) -> Unit,
    modifier: Modifier = Modifier,
    patientSex: String? = null,
    labelFor: @Composable (AuscultationSite) -> String,
) {
    val art = chestArt(patientSex)
    val image = ImageBitmap.imageResource(art.res)
    val marker = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val textPx = with(density) { 10.sp.toPx() }
    // Resolve every label up front. They come from string resources, so reading them is
    // a composable call, and the Canvas draw scope is not composition - a lambda invoked
    // during drawing cannot reach them.
    val labels = AuscultationSite.entries.associateWith { labelFor(it) }
    val selectedLabel = selected?.let { labels[it] }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(art.aspect)
            .semantics {
                contentDescription = selectedLabel?.let { "Auscultation site: $it" }
                    ?: "Chest diagram — no auscultation site chosen"
            }
            .pointerInput(art) {
                detectTapGestures { tap ->
                    nearestSite(tap, size.width.toFloat())
                        ?.let(onSelect)
                }
            }
    ) {
        drawImage(
            image = image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        for (site in AuscultationSite.entries) {
            drawSite(
                site = site,
                selected = site == selected,
                marker = marker,
                labelColor = labelColor,
                labelBackground = labelBackground,
                label = labels.getValue(site),
                measurer = measurer,
                textPx = textPx,
            )
        }
    }
}

/** One chest image and its width : height. */
class ChestArt(@DrawableRes val res: Int, val aspect: Float)

private val MALE_ART = ChestArt(R.drawable.chest_diagram, 896f / 503f)
private val FEMALE_ART = ChestArt(R.drawable.chest_diagram_female, 896f / 560f)

/**
 * The chest artwork for a patient's sex, as stored on the recording ("Male" / "Female" /
 * "Other" / null). Only Female gets the female figure; everything else, including no
 * answer, gets the default one.
 */
fun chestArt(patientSex: String?): ChestArt =
    if (patientSex == "Female") FEMALE_ART else MALE_ART

/**
 * Where a site's dot is in a chest image drawn [width] wide. [AuscultationSite.y] is a
 * fraction of the male image's height; scaling by width rather than height is what lets
 * the taller female image share the coordinates.
 */
fun siteCentre(site: AuscultationSite, width: Float): Offset =
    Offset(site.x * width, site.y * (503f / 896f) * width)

/** Radius of the dots baked into the image, as a fraction of its width. */
const val CHEST_DOT_RADIUS = 0.0246f

/** Tap radius, as a fraction of the diagram's width. Generous: fingers are not styluses. */
private const val TAP_FRACTION = 0.10f

/**
 * Sites labelled to the left of their dot. Aortic because it is the only site on the
 * patient's right; Tricuspid because a right-hand label would run into the Mitral dot
 * just below it.
 */
private val LABEL_LEFT = setOf(AuscultationSite.AORTIC, AuscultationSite.TRICUSPID)

/**
 * Nearest site to a tap, or null when the tap was nowhere near one.
 *
 * Nearest-within-a-radius rather than per-marker hit boxes: the sites sit close together
 * on a small screen, and a tap between two of them should pick one rather than silently
 * do nothing. The radius still has a limit, so tapping the shoulder does not select the
 * apex.
 */
private fun nearestSite(tap: Offset, width: Float): AuscultationSite? {
    val limit = width * TAP_FRACTION
    var best: AuscultationSite? = null
    var bestDistance = Float.MAX_VALUE
    for (site in AuscultationSite.entries) {
        val c = siteCentre(site, width)
        val d = hypot(tap.x - c.x, tap.y - c.y)
        if (d < bestDistance) {
            bestDistance = d
            best = site
        }
    }
    return if (bestDistance <= limit) best else null
}

private fun DrawScope.drawSite(
    site: AuscultationSite,
    selected: Boolean,
    marker: Color,
    labelColor: Color,
    labelBackground: Color,
    label: String,
    measurer: TextMeasurer,
    textPx: Float,
) {
    val centre = siteCentre(site, size.width)
    val r = size.width * CHEST_DOT_RADIUS

    if (selected) {
        // A halo and ring around the baked-in dot, so the choice reads at a glance on a
        // bright clinic screen where the dot's own colour would not.
        drawCircle(Color.White.copy(alpha = 0.45f), radius = r * 2.1f, center = centre)
        drawCircle(Color.White, radius = r * 1.45f, center = centre, style = Stroke(r * 0.45f))
        drawCircle(marker, radius = r * 1.45f, center = centre, style = Stroke(r * 0.25f))
    }

    val style = TextStyle(
        fontSize = (textPx / density).sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
    )
    val measured = measurer.measure(label, style)
    // Clear of the selection ring when there is one, so the label does not touch it.
    val gap = (if (selected) r * 1.9f else r) + size.width * 0.012f
    val x = if (site in LABEL_LEFT) centre.x - gap - measured.size.width else centre.x + gap
    val top = centre.y - measured.size.height / 2f
    // Backing pill so the label stays legible over the ribs.
    val pad = measured.size.height * 0.25f
    drawRoundRect(
        color = labelBackground,
        topLeft = Offset(x - pad, top),
        size = Size(measured.size.width + 2 * pad, measured.size.height.toFloat()),
        cornerRadius = CornerRadius(measured.size.height / 2f),
    )
    drawText(
        textLayoutResult = measured,
        color = if (selected) labelColor else labelColor.copy(alpha = 0.8f),
        topLeft = Offset(x, top),
    )
}
