package app.pdfeditor.ui

import android.os.Debug
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAP_TIMEOUT_MS = 500L
private const val TAP_SLOP_DP = 40f

data class MemStats(val pssMb: Int, val heapUsedMb: Int, val heapMaxMb: Int)

fun readMemStats(): MemStats {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    val rt = Runtime.getRuntime()
    val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
    val maxMb = (rt.maxMemory() / (1024L * 1024L)).toInt()
    return MemStats(info.totalPss / 1024, usedMb, maxMb)
}

@Composable
fun DebugOverlay(
    bitmap: ImageBitmap,
    scale: Float,
    mem: MemStats,
    tilePx: IntSize? = null,
    extraRows: List<Pair<String, String>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val bmpMb = bitmap.width.toLong() * bitmap.height * 4 / (1024.0 * 1024.0)
    val tile = tilePx?.let {
        val mb = it.width.toLong() * it.height * 4 / (1024.0 * 1024.0)
        "${it.width} x ${it.height}  %.1f MB".format(mb)
    } ?: "off"
    val rows = listOf(
        "RAM" to "${mem.pssMb} MB",
        "Heap" to "${mem.heapUsedMb} / ${mem.heapMaxMb} MB",
        "Res" to "${bitmap.width} x ${bitmap.height} px",
        "Bitmap" to "%.1f MB".format(bmpMb),
        "Tile" to tile,
        "Zoom" to "%.2fx".format(scale),
    )
    Column(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xAA000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        rows.forEach { (label, value) ->
            Text(
                text = label.padEnd(7) + value,
                color = Color(0xFFEEEEEE),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (extraRows.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            extraRows.forEach { (label, value) ->
                Text(
                    text = label.padEnd(7) + value,
                    color = Color(0xFF8AD0FF),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "four-finger tap to dismiss",
            color = Color(0xFFAAAAAA),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// Recognise a quick, near-stationary four-finger tap; consumes it so the
// canvas pinch/tap handlers do not also react.
fun Modifier.fourFingerTap(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    val slop = TAP_SLOP_DP.dp.toPx()
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val startTime = first.uptimeMillis
        var endTime = startTime
        var maxPointers = 1
        var active = false
        var moved = false
        val starts = mutableMapOf<PointerId, Offset>()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size > maxPointers) maxPointers = pressed.size
            if (!active && pressed.size >= 4) active = true
            for (c in pressed) {
                val origin = starts.getOrPut(c.id) { c.position }
                if ((c.position - origin).getDistance() > slop) moved = true
            }
            if (active) event.changes.forEach { it.consume() }
            endTime = event.changes.maxOf { it.uptimeMillis }
            if (pressed.isEmpty()) break
        }
        Log.d("DebugHud", "gesture max=$maxPointers moved=$moved dur=${endTime - startTime}")
        if (maxPointers >= 4 && !moved && endTime - startTime <= TAP_TIMEOUT_MS) onTap()
    }
}
