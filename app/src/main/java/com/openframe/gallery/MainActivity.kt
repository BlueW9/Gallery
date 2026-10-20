package com.openframe.gallery

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Item(val uri: Uri, val video: Boolean, val label: String)

sealed class Entry { abstract val key: String }
class Head(val title: String, at: Int) : Entry() { override val key: String = "h$at" }
class Cell(val index: Int, val item: Item) : Entry() { override val key: String = item.uri.toString() }

private val Bg = Color(0xFF0B0B10)
private val Surf = Color(0xFF14141C)
private val Purple = Color(0xFFB388FF)     // text
private val Prussian = Color(0xFF003153)   // bars / details

private object Thumbs {
    val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }
}

private fun dayLabel(sec: Long): String {
    val d = java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    val fmt = { p: String -> d.format(java.time.format.DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH)) }
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> fmt("EEE, MMM d")
        else -> fmt("MMM d, yyyy")
    }
}

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    private var media by mutableStateOf(listOf<Item>())
    private var entries by mutableStateOf(listOf<Entry>())
    private var opened by mutableStateOf<Int?>(null)
    private var external by mutableStateOf<Item?>(null)
    private var access by mutableStateOf(true)
    private var loaded by mutableStateOf(false)
    private var selected by mutableStateOf(setOf<String>())
    private var wallTarget by mutableStateOf<Uri?>(null)
    private var editing by mutableStateOf<Item?>(null)
    private var saving by mutableStateOf(false)
    private var pendingReplace: Pair<Uri, List<Op>>? = null
    private var gen = 0
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val del = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) {
            selected = emptySet()
            refresh()
            if (external != null) finish() else opened = null
        }
    }

    private val writeReq = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val p = pendingReplace
        pendingReplace = null
        if (it.resultCode == RESULT_OK && p != null) runSave(p.first, p.second, true)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Screenshots and screen recording are always blocked.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        intent?.data?.let { external = Item(it, intent.type?.startsWith("video") == true, ""); opened = 0 }
        askPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Bg, primary = Purple, onBackground = Purple, onSurface = Purple)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askPermission() {
        perm.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    private fun hasAccess(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    // Reads the library on a background thread, so the screen never freezes.
    private fun refresh() {
        access = hasAccess()
        if (!access) return
        val my = ++gen
        Thread {
            val out = ArrayList<Item>()
            val labels = HashMap<Long, String>()
            try {
                val c = contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.DATE_ADDED),
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)", null,
                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
                c?.use {
                    while (it.moveToNext()) {
                        val v = it.getInt(1) == 3
                        val sec = it.getLong(2)
                        val day = (sec + java.util.TimeZone.getDefault().getOffset(sec * 1000) / 1000) / 86400
                        val label = labels.getOrPut(day) { dayLabel(sec) }
                        val base = if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        out.add(Item(ContentUris.withAppendedId(base, it.getLong(0)), v, label))
                    }
                }
            } catch (_: Exception) { }
            val ents = ArrayList<Entry>(out.size + 64)
            var last = ""
            for (i in out.indices) {
                val m = out[i]
                if (m.label != last) { last = m.label; ents.add(Head(m.label, i)) }
                ents.add(Cell(i, m))
            }
            runOnUiThread { if (my == gen) { media = out; entries = ents; loaded = true } }
        }.start()
    }

    // Permanent delete (skips the trash). One system confirmation covers all selected items.
    private fun delete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        try {
            val s = MediaStore.createDeleteRequest(contentResolver, uris).intentSender
            del.launch(IntentSenderRequest.Builder(s).build())
        } catch (_: Exception) { }
    }

    private fun toggle(k: String) {
        selected = if (k in selected) selected - k else selected + k
    }

    private fun setWallpaper(uri: Uri, which: Int) {
        val ctx = applicationContext
        Thread {
            val ok = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    WallpaperManager.getInstance(ctx).setStream(it, null, true, which)
                }
                true
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(ctx, if (ok) "Wallpaper updated" else "Could not set wallpaper", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---------- photo editing (crop / blur) ----------

    private fun canReplace(uri: Uri): Boolean {
        val m = contentResolver.getType(uri)
        return uri.authority == MediaStore.AUTHORITY && m in setOf("image/jpeg", "image/png", "image/webp")
    }

    private fun startSave(uri: Uri, ops: List<Op>, replace: Boolean) {
        if (!replace) { runSave(uri, ops, false); return }
        // Replacing a photo needs the system's own permission prompt for that photo.
        try {
            val s = MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender
            pendingReplace = uri to ops
            writeReq.launch(IntentSenderRequest.Builder(s).build())
        } catch (e: Exception) {
            Toast.makeText(this, "This photo can only be saved as a new copy", Toast.LENGTH_LONG).show()
        }
    }

    private fun runSave(uri: Uri, ops: List<Op>, replace: Boolean) {
        saving = true
        Thread {
            var message = ""
            try {
                val srcMime = contentResolver.getType(uri) ?: "image/jpeg"
                val src = ImageOps.decode(contentResolver, uri, 25_000_000L)
                val out = ImageOps.render(src, ops)
                if (replace) {
                    val bytes = ImageOps.encodeBytes(out, srcMime)
                        ?: throw IllegalStateException("could not encode the image")
                    val os = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("could not write the file")
                    os.use { it.write(bytes) }
                    message = "Photo updated"
                } else {
                    saveCopy(out, srcMime)
                    message = "Saved as a new copy"
                }
            } catch (e: Throwable) {
                message = "Could not save: " + (e.message ?: "unknown error")
            }
            val ok = message == "Photo updated" || message == "Saved as a new copy"
            runOnUiThread {
                saving = false
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                if (ok) {
                    editing = null
                    if (replace) {
                        Thumbs.cache.remove(uri.toString())
                        applicationContext.imageLoader.memoryCache?.clear()
                    } else if (external == null) {
                        opened = null
                    }
                    refresh()
                }
            }
        }.start()
    }

    private fun saveCopy(bmp: Bitmap, srcMime: String) {
        val mime = if (srcMime == "image/png" || srcMime == "image/webp") srcMime else "image/jpeg"
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val bytes = ImageOps.encodeBytes(bmp, mime) ?: throw IllegalStateException("could not encode the image")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_$stamp.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val col = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val u = contentResolver.insert(col, cv) ?: throw IllegalStateException("could not create the file")
        try {
            val os = contentResolver.openOutputStream(u) ?: throw IllegalStateException("could not write the file")
            os.use { it.write(bytes) }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(u, cv, null, null)
        } catch (e: Exception) {
            contentResolver.delete(u, null, null)
            throw e
        }
    }

    @Composable
    private fun App() {
        val list = external?.let { listOf(it) } ?: media
        val o = opened
        BackHandler(o != null || selected.isNotEmpty()) {
            if (o != null) { if (external != null) finish() else opened = null }
            else selected = emptySet()
        }
        val ed = editing
        if (ed != null) {
            val replaceOk = remember(ed.uri) { canReplace(ed.uri) }
            Editor(
                uri = ed.uri, canReplace = replaceOk, saving = saving,
                onClose = { editing = null },
                onSave = { ops, replace -> startSave(ed.uri, ops, replace) }
            )
        } else if (o != null && list.isNotEmpty()) Viewer(list, o.coerceIn(0, list.size - 1)) else Grid()
        val w = wallTarget
        if (w != null) {
            AlertDialog(
                onDismissRequest = { wallTarget = null },
                confirmButton = {},
                title = { Text("Set as wallpaper") },
                text = {
                    Column {
                        TextButton({ setWallpaper(w, WallpaperManager.FLAG_SYSTEM); wallTarget = null },
                            Modifier.fillMaxWidth()) { Text("Home screen", color = Purple) }
                        TextButton({ setWallpaper(w, WallpaperManager.FLAG_LOCK); wallTarget = null },
                            Modifier.fillMaxWidth()) { Text("Lock screen", color = Purple) }
                        TextButton({ setWallpaper(w, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK); wallTarget = null },
                            Modifier.fillMaxWidth()) { Text("Both", color = Purple) }
                    }
                },
                containerColor = Surf, titleContentColor = Purple, textContentColor = Purple
            )
        }
    }

    @Composable
    private fun Grid() {
        val es = entries
        val sel = selected
        val selecting = sel.isNotEmpty()
        Column(Modifier.fillMaxSize().background(Bg)) {
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().background(Prussian).statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBtn("✕") { selected = emptySet() }
                    Text(
                        "${sel.size} selected", color = Purple, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    TopBtn("All") { selected = media.map { it.uri.toString() }.toSet() }
                    Spacer(Modifier.width(8.dp))
                    TopBtn("Delete") { delete(media.filter { it.uri.toString() in sel }.map { it.uri }) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Gallery", color = Purple, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("${media.size} items", color = Purple.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                    Text(
                        "Private", color = Purple, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Prussian)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            if (!access) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Allow access to your photos and videos", color = Purple, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton({ askPermission() }) { Text("Allow access", color = Purple) }
                }
            } else if (loaded && es.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { Text("No photos or videos yet", color = Purple, fontSize = 16.sp) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = es.size,
                        key = { es[it].key },
                        span = { idx -> if (es[idx] is Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { if (es[it] is Head) 0 else 1 }
                    ) { idx ->
                        val e = es[idx]
                        when (e) {
                            is Head -> Text(
                                e.title, color = Purple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(8.dp, 14.dp, 8.dp, 6.dp)
                            )
                            is Cell -> {
                                val k = e.item.uri.toString()
                                val isSel = k in sel
                                Box(
                                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Surf)
                                        .combinedClickable(
                                            onClick = { if (selected.isNotEmpty()) toggle(k) else opened = e.index },
                                            onLongClick = { toggle(k) }
                                        )
                                ) {
                                    Thumb(e.item.uri, Modifier.fillMaxSize())
                                    if (isSel) Box(Modifier.fillMaxSize().background(Prussian.copy(alpha = 0.45f)))
                                    if (e.item.video) {
                                        Box(
                                            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp)
                                                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center
                                        ) { Text("▶", color = Purple, fontSize = 11.sp) }
                                    }
                                    if (selecting) {
                                        Box(
                                            Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp)
                                                .clip(CircleShape)
                                                .background(if (isSel) Purple else Color.Black.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSel) Text("✓", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Viewer(list: List<Item>, start: Int) {
        val ctx = LocalContext.current
        val pager = rememberPagerState(start) { list.size }
        var bars by remember { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager.currentPage) { zoomed = false }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }
        ) {
            // While a photo is zoomed in, one finger moves the photo instead of swiping to the next one.
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { i ->
                val m = list[i]
                if (m.video) VideoPlayer(m.uri)
                else ZoomableImage(m.uri, pager.currentPage == i) { zoomed = it }
            }
            if (bars) {
                val cur = list[pager.currentPage.coerceIn(0, list.size - 1)]
                val canEdit = remember(cur.uri) { ctx.contentResolver.getType(cur.uri) != "image/gif" }
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBtn("✕") { if (external != null) finish() else opened = null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!cur.video) {
                            if (canEdit) {
                                TopBtn("Edit", 14.dp) { editing = cur }
                                Spacer(Modifier.width(6.dp))
                            }
                            TopBtn("Wallpaper", 14.dp) { wallTarget = cur.uri }
                            Spacer(Modifier.width(6.dp))
                        }
                        TopBtn("Delete", 14.dp) { delete(listOf(cur.uri)) }
                    }
                }
                Text(
                    "${pager.currentPage + 1} / ${list.size}", color = Purple, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                        .clip(RoundedCornerShape(50)).background(Prussian.copy(alpha = 0.9f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// Pinch to zoom (up to 6x), drag to move while zoomed, double tap to zoom in and out.
@Composable
private fun ZoomableImage(uri: Uri, current: Boolean, onZoomed: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val notify by rememberUpdatedState(onZoomed)
    LaunchedEffect(current) {
        if (!current) { scale = 1f; offset = Offset.Zero }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var lastTap = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val c = Offset(w / 2f, h / 2f)
                var moved = false
                var multi = false
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size > 1) multi = true
                    // One finger at normal size is left alone, so swiping between photos and tapping still work.
                    if (pressed.size > 1 || scale > 1.01f) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            val centroid = event.calculateCentroid()
                            val cen = if (centroid.isSpecified) centroid else c
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            val z = newScale / scale
                            val rel = cen - c
                            val t = rel - (rel - offset) * z + pan
                            val maxX = w * (newScale - 1f) / 2f
                            val maxY = h * (newScale - 1f) / 2f
                            scale = newScale
                            offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                            notify(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    val p = pressed.firstOrNull()?.position ?: down.position
                    if ((p - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                } while (event.changes.any { it.pressed })

                if (!moved && !multi) {
                    val now = down.uptimeMillis
                    if (now - lastTap < 300L) {
                        lastTap = 0L
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            val rel = down.position - c
                            val maxX = w * (s - 1f) / 2f
                            val maxY = h * (s - 1f) / 2f
                            offset = Offset(
                                (rel.x * (1f - s)).coerceIn(-maxX, maxX),
                                (rel.y * (1f - s)).coerceIn(-maxY, maxY)
                            )
                            scale = s
                        }
                        notify(scale > 1.01f)
                    } else {
                        lastTap = now
                    }
                }
            }
        }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        ) {
            // The small thumbnail shows instantly; the sharp version (up to 4K) replaces it when ready.
            Thumb(uri, Modifier.fillMaxSize(), ContentScale.Fit, Color.Transparent)
            AsyncImage(
                ImageRequest.Builder(ctx).data(uri).size(3840, 3840).build(),
                null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Uses Android's own cached thumbnails: fast for photos and videos, loaded off the main thread.
@Composable
private fun Thumb(uri: Uri, modifier: Modifier, scale: ContentScale = ContentScale.Crop, bg: Color = Surf) {
    val resolver = LocalContext.current.contentResolver
    val bmp by produceState<ImageBitmap?>(Thumbs.cache.get(uri.toString()), uri) {
        val k = uri.toString()
        value = Thumbs.cache.get(k)
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    resolver.loadThumbnail(uri, android.util.Size(320, 320), null)
                        .asImageBitmap().also { Thumbs.cache.put(k, it) }
                } catch (e: Exception) { null }
            }
        }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = scale)
    else Box(modifier.background(bg))
}

@Composable
private fun TopBtn(label: String, pad: Dp = 18.dp, onClick: () -> Unit) {
    Text(
        label, color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Prussian.copy(alpha = 0.9f))
            .clickable { onClick() }.padding(horizontal = pad, vertical = 10.dp)
    )
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val p = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(p) { onDispose { p.release() } }
    AndroidView({ PlayerView(it).apply { player = p } }, Modifier.fillMaxSize())
}
