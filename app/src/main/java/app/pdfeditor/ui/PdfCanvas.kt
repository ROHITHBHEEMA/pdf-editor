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
import androidx.compose.ui.Alignment
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

data class LeafRect(val id: Int, val rect: Rect)

private data class SharpTile(val image: ImageBitmap, val src: Rect)

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 12f
private const val MAX_TILE_PX = 4096
private const val SETTLE_MS = 150L

@Composable
fun PdfCanvas(
    bitmap: ImageBitmap,
    pageIndex: Int,
    scaleState: MutableState<Float>,
    offsetState: MutableState<Offset>,
    leaves: List<LeafRect>,
    selectedRect: Rect?,
    highlightColor: Color,
    backdropColor: Color,
    runBoxes: List<LeafRect>,
    editingRunId: Int?,
    textBoxColor: Color,
    onEditRun: (Int) -> Unit,
    debugRows: List<Pair<String, String>> = emptyList(),
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onSelect: (Int?) -> Unit,
    renderTile: (suspend (pageIndex: Int, src: Rect, outW: Int, outH: Int) -> ImageBitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        // Hoisted by the caller so zoom/pan survive the canvas moving between the
        // docked and transparent layout branches.
        var scale by scaleState
        var offset by offsetState
        var sharpTile by remember { mutableStateOf<SharpTile?>(null) }

        var showDebug by remember { mutableStateOf(false) }
        var mem by remember { mutableStateOf(MemStats(0, 0, 0)) }
        LaunchedEffect(showDebug) {
            while (showDebug) {
                mem = readMemStats()
                delay(1000)
            }
        }

        fun fitWidth() {
            if (viewportW > 0f && bitmap.width > 0) {
                val s = viewportW / bitmap.width
                scale = s
                offset = Offset(0f, ((viewportH - bitmap.height * s) / 2f).coerceAtLeast(0f))
            }
        }

        fun fitHeight() {
            if (viewportH > 0f && bitmap.height > 0) {
                val s = viewportH / bitmap.height
                scale = s
                offset = Offset(((viewportW - bitmap.width * s) / 2f).coerceAtLeast(0f), 0f)
            }
        }

        // Global zoom: kept across pages; only re-fit while a fit mode is active.
        LaunchedEffect(bitmap, viewportW, viewportH, fitMode) {
            when (fitMode) {
                FitMode.WIDTH -> fitWidth()
                FitMode.HEIGHT -> fitHeight()
                FitMode.NONE -> Unit
            }
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
                .fourFingerTap {
                    showDebug = !showDebug
                    android.util.Log.d("DebugHud", "toggle=$showDebug")
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f || pan != Offset.Zero) onUserTransform()
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    }
                }
                .pointerInput(bitmap, leaves, runBoxes) {
                    detectTapGestures { tap ->
                        val point = Offset((tap.x - offset.x) / scale, (tap.y - offset.y) / scale)
                        val run = runBoxes.lastOrNull { it.rect.contains(point) }
                        if (run != null) {
                            onEditRun(run.id)
                        } else {
                            val hit = leaves.lastOrNull { it.rect.contains(point) }
                            onSelect(hit?.id)
                        }
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
                val runStroke = (1f / scale).coerceAtLeast(0.4f)
                for (rb in runBoxes) {
                    if (rb.id == editingRunId) continue
                    drawRect(color = textBoxColor.copy(alpha = 0.06f), topLeft = rb.rect.topLeft, size = rb.rect.size)
                    drawRect(
                        color = textBoxColor.copy(alpha = 0.5f),
                        topLeft = rb.rect.topLeft,
                        size = rb.rect.size,
                        style = Stroke(width = runStroke),
                    )
                }
                selectedRect?.let { r ->
                    drawRect(color = highlightColor.copy(alpha = 0.18f), topLeft = r.topLeft, size = r.size)
                    drawRect(
                        color = highlightColor,
                        topLeft = r.topLeft,
                        size = r.size,
                        style = Stroke(width = 2f / scale),
                    )
                }
            }
        }

        if (showDebug) {
            DebugOverlay(
                bitmap = bitmap,
                scale = scale,
                mem = mem,
                tilePx = sharpTile?.let { IntSize(it.image.width, it.image.height) },
                extraRows = debugRows,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}
