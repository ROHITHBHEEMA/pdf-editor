package app.pdfeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

enum class FitMode { NONE, WIDTH }

private data class SharpTile(val image: ImageBitmap, val src: Rect)

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 12f
private const val MAX_TILE_PX = 4096
private const val SETTLE_MS = 150L

/**
 * The rendered page with pinch zoom and pan. [outline] (bitmap pixels) is drawn over the page; a tap reports the
 * bitmap pixel under the finger.
 */
@Composable
fun PdfCanvas(
    bitmap: ImageBitmap,
    pageIndex: Int,
    scaleState: MutableState<Float>,
    offsetState: MutableState<Offset>,
    outline: Rect?,
    highlightColor: Color,
    backdropColor: Color,
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onTap: (Offset) -> Unit,
    renderTile: (suspend (pageIndex: Int, src: Rect, outW: Int, outH: Int) -> ImageBitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        var scale by scaleState
        var offset by offsetState
        var sharpTile by remember { mutableStateOf<SharpTile?>(null) }

        fun fitWidth() {
            if (viewportW > 0f && bitmap.width > 0) {
                val s = viewportW / bitmap.width
                scale = s
                offset = Offset(0f, ((viewportH - bitmap.height * s) / 2f).coerceAtLeast(0f))
            }
        }

        // Global zoom: kept across pages; only re-fit while a fit mode is active.
        LaunchedEffect(bitmap, viewportW, viewportH, fitMode) {
            if (fitMode == FitMode.WIDTH) fitWidth()
        }

        // Once a zoom/pan settles, re-render the visible window crisply. The base
        // bitmap (interpolated, soft) shows during the gesture; the tile replaces
        // it on the same page rect the instant it lands.
        if (renderTile != null) {
            LaunchedEffect(bitmap, pageIndex, viewportW, viewportH) {
                sharpTile = null
                snapshotFlow { scale to offset }.collectLatest { (sc, off) ->
                    if (sc <= 1f) {
                        sharpTile = null
                        return@collectLatest
                    }
                    delay(SETTLE_MS)
                    val w = bitmap.width.toFloat()
                    val h = bitmap.height.toFloat()
                    val left = ((0f - off.x) / sc).coerceIn(0f, w)
                    val top = ((0f - off.y) / sc).coerceIn(0f, h)
                    val right = ((viewportW - off.x) / sc).coerceIn(0f, w)
                    val bottom = ((viewportH - off.y) / sc).coerceIn(0f, h)
                    if (right - left < 1f || bottom - top < 1f) return@collectLatest
                    val outW = ((right - left) * sc).roundToInt().coerceIn(1, MAX_TILE_PX)
                    val outH = ((bottom - top) * sc).roundToInt().coerceIn(1, MAX_TILE_PX)
                    val src = Rect(left, top, right, bottom)
                    renderTile(pageIndex, src, outW, outH)?.let { sharpTile = SharpTile(it, src) }
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f || pan != Offset.Zero) onUserTransform()
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures { tap ->
                        onTap(Offset((tap.x - offset.x) / scale, (tap.y - offset.y) / scale))
                    }
                },
        ) {
            drawRect(color = backdropColor, size = size)
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawImage(image = bitmap, topLeft = Offset.Zero)
                sharpTile?.let { tile ->
                    val img = tile.image
                    withTransform({
                        translate(tile.src.left, tile.src.top)
                        scale(
                            tile.src.width / img.width.toFloat(),
                            tile.src.height / img.height.toFloat(),
                            pivot = Offset.Zero,
                        )
                    }) {
                        drawImage(
                            image = img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(img.width, img.height),
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
                outline?.let { r ->
                    val pad = 4f
                    val box = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
                    drawRect(color = highlightColor.copy(alpha = 0.12f), topLeft = box.topLeft, size = box.size)
                    drawRect(
                        color = highlightColor,
                        topLeft = box.topLeft,
                        size = box.size,
                        style = Stroke(width = 2f / scale),
                    )
                }
            }
        }
    }
}
