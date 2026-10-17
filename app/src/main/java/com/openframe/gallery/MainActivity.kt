package com.openframe.gallery

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
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
    private var gen = 0
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val del = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) { refresh(); if (external != null) finish() else opened = null }
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

    // Permanent delete: skips the trash. Android asks the user to confirm once.
    private fun delete(u: Uri) {
        try {
            val s = MediaStore.createDeleteRequest(contentResolver, listOf(u)).intentSender
            del.launch(IntentSenderRequest.Builder(s).build())
        } catch (_: Exception) { }
    }

    @Composable
    private fun App() {
        val list = external?.let { listOf(it) } ?: media
        val o = opened
        BackHandler(o != null) { if (external != null) finish() else opened = null }
        if (o != null && list.isNotEmpty()) Viewer(list, o.coerceIn(0, list.size - 1)) else Grid()
    }

    @Composable
    private fun Grid() {
        val es = entries
        Column(Modifier.fillMaxSize().background(Bg)) {
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
                            is Cell -> Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                                    .background(Surf).clickable { opened = e.index }
                            ) {
                                Thumb(e.item.uri, Modifier.fillMaxSize())
                                if (e.item.video) {
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp)
                                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("▶", color = Purple, fontSize = 11.sp) }
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
        val pager = rememberPagerState(start) { list.size }
        var bars by remember { mutableStateOf(true) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val m = list[i]
                if (m.video) VideoPlayer(m.uri)
                else AsyncImage(
                    ImageRequest.Builder(LocalContext.current).data(m.uri).size(Size.ORIGINAL).build(),
                    null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                )
            }
            if (bars) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TopBtn("✕") { if (external != null) finish() else opened = null }
                    TopBtn("Delete") { delete(list[pager.currentPage].uri) }
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

// Uses Android's own cached thumbnails: fast for photos and videos, loaded off the main thread.
@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
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
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Surf))
}

@Composable
private fun TopBtn(label: String, onClick: () -> Unit) {
    Text(
        label, color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Prussian.copy(alpha = 0.9f))
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val p = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(p) { onDispose { p.release() } }
    AndroidView({ PlayerView(it).apply { player = p } }, Modifier.fillMaxSize())
}
