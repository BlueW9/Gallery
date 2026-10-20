package com.openframe.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val EBg = Color(0xFF0B0B10)
private val ESurf = Color(0xFF14141C)
private val EPurple = Color(0xFFB388FF)
private val EPrussian = Color(0xFF003153)

private enum class Mode { CROP, BLUR }
private enum class Grab { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }
private data class Sel(val l: Float, val t: Float, val r: Float, val b: Float)

private val FULL = Sel(0f, 0f, 1f, 1f)
private val CENTER = Sel(0.3f, 0.3f, 0.7f, 0.7f)

/** Crop and blur editor. Nothing is written until the user taps Save. */
@Composable
fun Editor(
    uri: Uri,
    canReplace: Boolean,
    saving: Boolean,
    onClose: () -> Unit,
    onSave: (List<Op>, Boolean) -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    var failed by remember { mutableStateOf(false) }
    val base by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try { ImageOps.decode(resolver, uri, 4_200_000L) } catch (e: Throwable) { null }
        }
        if (value == null) failed = true
    }
    val ops = remember { mutableStateListOf<Op>() }
    val shown = remember(base, ops.size) { base?.let { ImageOps.render(it, ops.toList()) } }

    var mode by remember { mutableStateOf(Mode.CROP) }
    var sel by remember { mutableStateOf(FULL) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var strength by remember { mutableStateOf(70f) }
    var askSave by remember { mutableStateOf(false) }

    BackHandler { if (!saving) onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(EPrussian).statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EBtn("\u2715") { if (!saving) onClose() }
                Text(
                    "Edit", color = EPurple, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                if (ops.isNotEmpty()) {
                    EBtn("Undo") {
                        ops.removeAt(ops.lastIndex)
                        sel = if (mode == Mode.CROP) FULL else CENTER
                        ratio = null
                    }
                    Spacer(Modifier.width(8.dp))
                }
                EBtn("Save", enabled = ops.isNotEmpty() && !saving) { askSave = true }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val bmp = shown
                if (bmp == null) {
                    Text(
                        if (failed) "Could not open this photo" else "Loading...",
                        color = EPurple, modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                        val cw = constraints.maxWidth.toFloat()
                        val ch = constraints.maxHeight.toFloat()
                        val iw = bmp.width
                        val ih = bmp.height
                        val scale = min(cw / iw, ch / ih)
                        val dw = iw * scale
                        val dh = ih * scale
                        val ox = (cw - dw) / 2f
                        val oy = (ch - dh) / 2f
                        var grab by remember { mutableStateOf(Grab.NONE) }

                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(bmp, mode, ratio) {
                                detectDragGestures(
                                    onDragStart = { p -> grab = hitTest(p, sel, ox, oy, dw, dh, ratio != null) },
                                    onDragEnd = { grab = Grab.NONE },
                                    onDragCancel = { grab = Grab.NONE }
                                ) { change, d ->
                                    change.consume()
                                    if (grab != Grab.NONE) {
                                        sel = dragSel(sel, grab, d.x / dw, d.y / dh, ratio, iw, ih)
                                    }
                                }
                            }
                        ) {
                            val x1 = ox + sel.l * dw
                            val y1 = oy + sel.t * dh
                            val x2 = ox + sel.r * dw
                            val y2 = oy + sel.b * dh
                            val shade = Color.Black.copy(alpha = 0.55f)
                            drawRect(shade, Offset(ox, oy), Size(dw, y1 - oy))
                            drawRect(shade, Offset(ox, y2), Size(dw, oy + dh - y2))
                            drawRect(shade, Offset(ox, y1), Size(x1 - ox, y2 - y1))
                            drawRect(shade, Offset(x2, y1), Size(ox + dw - x2, y2 - y1))
                            drawRect(EPurple, Offset(x1, y1), Size(x2 - x1, y2 - y1), style = Stroke(width = 3f))
                            if (mode == Mode.CROP) {
                                val line = EPurple.copy(alpha = 0.35f)
                                for (i in 1..2) {
                                    val fx = x1 + (x2 - x1) * i / 3f
                                    val fy = y1 + (y2 - y1) * i / 3f
                                    drawLine(line, Offset(fx, y1), Offset(fx, y2), strokeWidth = 1.5f)
                                    drawLine(line, Offset(x1, fy), Offset(x2, fy), strokeWidth = 1.5f)
                                }
                            }
                            for (c in listOf(Offset(x1, y1), Offset(x2, y1), Offset(x1, y2), Offset(x2, y2))) {
                                drawCircle(EPurple, 15f, c)
                                drawCircle(Color.Black, 8f, c)
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(EPrussian).navigationBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeTab("Crop", mode == Mode.CROP, Modifier.weight(1f)) {
                        mode = Mode.CROP; sel = FULL; ratio = null
                    }
                    ModeTab("Blur", mode == Mode.BLUR, Modifier.weight(1f)) {
                        mode = Mode.BLUR; sel = CENTER; ratio = null
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (mode == Mode.CROP) {
                    val presets: List<Pair<String, Float?>> = listOf(
                        "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, v) in presets) {
                            Text(
                                label, color = if (ratio == v) Color.Black else EPurple, fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (ratio == v) EPurple else Color.Black.copy(alpha = 0.35f))
                                    .clickable {
                                        ratio = v
                                        val b = shown
                                        if (v != null && b != null) sel = fitSel(v, b.width, b.height)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val full = sel.l <= 0.005f && sel.t <= 0.005f && sel.r >= 0.995f && sel.b >= 0.995f
                    EPrimary("Apply crop", enabled = !full && shown != null && !saving) {
                        ops.add(CropOp(sel.l, sel.t, sel.r, sel.b))
                        sel = FULL
                        ratio = null
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength", color = EPurple, fontSize = 14.sp)
                        Slider(
                            value = strength, onValueChange = { strength = it }, valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = EPurple, activeTrackColor = EPurple,
                                inactiveTrackColor = Color.Black.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                    }
                    EPrimary("Apply blur", enabled = shown != null && !saving) {
                        ops.add(BlurOp(sel.l, sel.t, sel.r, sel.b, strength.toInt()))
                        sel = CENTER
                    }
                }
            }
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = true) { },
                contentAlignment = Alignment.Center
            ) { Text("Saving...", color = EPurple, fontSize = 18.sp) }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            confirmButton = {},
            title = { Text("Save") },
            text = {
                Column {
                    TextButton({ askSave = false; onSave(ops.toList(), false) }, Modifier.fillMaxWidth()) {
                        Text("Save as new copy", color = EPurple)
                    }
                    if (canReplace) {
                        TextButton({ askSave = false; onSave(ops.toList(), true) }, Modifier.fillMaxWidth()) {
                            Text("Replace original", color = EPurple)
                        }
                    }
                    TextButton({ askSave = false }, Modifier.fillMaxWidth()) {
                        Text("Cancel", color = EPurple.copy(alpha = 0.6f))
                    }
                }
            },
            containerColor = ESurf, titleContentColor = EPurple, textContentColor = EPurple
        )
    }
}

@Composable
private fun EBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) EPurple else EPurple.copy(alpha = 0.35f),
        fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun EPrimary(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            .background(if (enabled) EPurple else EPurple.copy(alpha = 0.3f))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ModeTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (active) EPurple else Color.Black.copy(alpha = 0.35f))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (active) Color.Black else EPurple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** Largest centered rectangle with the given width/height ratio. */
private fun fitSel(ratio: Float, iw: Int, ih: Int): Sel {
    val imgRatio = iw.toFloat() / ih.toFloat()
    val wf: Float
    val hf: Float
    if (ratio > imgRatio) { wf = 1f; hf = imgRatio / ratio } else { wf = ratio / imgRatio; hf = 1f }
    return Sel((1f - wf) / 2f, (1f - hf) / 2f, (1f + wf) / 2f, (1f + hf) / 2f)
}

private fun hitTest(p: Offset, s: Sel, ox: Float, oy: Float, dw: Float, dh: Float, locked: Boolean): Grab {
    val x1 = ox + s.l * dw
    val y1 = oy + s.t * dh
    val x2 = ox + s.r * dw
    val y2 = oy + s.b * dh
    val t = 56f
    fun near(a: Float, b: Float) = abs(a - b) <= t
    val inX = p.x >= x1 - t && p.x <= x2 + t
    val inY = p.y >= y1 - t && p.y <= y2 + t
    if (near(p.x, x1) && near(p.y, y1)) return Grab.TL
    if (near(p.x, x2) && near(p.y, y1)) return Grab.TR
    if (near(p.x, x1) && near(p.y, y2)) return Grab.BL
    if (near(p.x, x2) && near(p.y, y2)) return Grab.BR
    if (!locked) {
        if (near(p.x, x1) && inY) return Grab.L
        if (near(p.x, x2) && inY) return Grab.R
        if (near(p.y, y1) && inX) return Grab.T
        if (near(p.y, y2) && inX) return Grab.B
    }
    if (p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2) return Grab.MOVE
    return Grab.NONE
}

private fun dragSel(s: Sel, g: Grab, dx: Float, dy: Float, ratio: Float?, iw: Int, ih: Int): Sel {
    val minSize = 0.08f
    if (g == Grab.MOVE) {
        val w = s.r - s.l
        val h = s.b - s.t
        val l = (s.l + dx).coerceIn(0f, 1f - w)
        val t = (s.t + dy).coerceIn(0f, 1f - h)
        return Sel(l, t, l + w, t + h)
    }
    val corner = g == Grab.TL || g == Grab.TR || g == Grab.BL || g == Grab.BR
    if (ratio != null && corner) return resizeLocked(s, g, dx, dy, ratio, iw, ih)

    var l = s.l
    var t = s.t
    var r = s.r
    var b = s.b
    when (g) {
        Grab.L -> l = (l + dx).coerceIn(0f, r - minSize)
        Grab.R -> r = (r + dx).coerceIn(l + minSize, 1f)
        Grab.T -> t = (t + dy).coerceIn(0f, b - minSize)
        Grab.B -> b = (b + dy).coerceIn(t + minSize, 1f)
        Grab.TL -> { l = (l + dx).coerceIn(0f, r - minSize); t = (t + dy).coerceIn(0f, b - minSize) }
        Grab.TR -> { r = (r + dx).coerceIn(l + minSize, 1f); t = (t + dy).coerceIn(0f, b - minSize) }
        Grab.BL -> { l = (l + dx).coerceIn(0f, r - minSize); b = (b + dy).coerceIn(t + minSize, 1f) }
        Grab.BR -> { r = (r + dx).coerceIn(l + minSize, 1f); b = (b + dy).coerceIn(t + minSize, 1f) }
        else -> {}
    }
    return Sel(l, t, r, b)
}

/** Corner drag that keeps the width/height ratio; the opposite corner stays fixed. */
private fun resizeLocked(s: Sel, g: Grab, dx: Float, dy: Float, ratio: Float, iw: Int, ih: Int): Sel {
    val left = g == Grab.TL || g == Grab.BL
    val top = g == Grab.TL || g == Grab.TR
    val ax = if (left) s.r else s.l
    val ay = if (top) s.b else s.t
    val cx = (if (left) s.l else s.r) + dx
    val cy = (if (top) s.t else s.b) + dy
    val sx = if (cx >= ax) 1f else -1f
    val sy = if (cy >= ay) 1f else -1f
    var wPx = max(abs(cx - ax) * iw, abs(cy - ay) * ih * ratio)
    wPx = max(wPx, 0.08f * iw)
    val maxByX = (if (sx > 0f) 1f - ax else ax) * iw
    val maxByY = (if (sy > 0f) 1f - ay else ay) * ih * ratio
    wPx = min(wPx, min(maxByX, maxByY))
    val hPx = wPx / ratio
    val nx = ax + sx * wPx / iw
    val ny = ay + sy * hPx / ih
    return Sel(min(ax, nx), min(ay, ny), max(ax, nx), max(ay, ny))
}
