package com.openframe.gallery

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.size.Size

data class Item(val uri: Uri, val video: Boolean)

private val Bg = Color(0xFF0B0B10)
private val Purple = Color(0xFFB388FF)     // text
private val Prussian = Color(0xFF003153)   // bars / details
private val PrussianLight = Color(0xFF2B6C99)

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    private var items by mutableStateOf(listOf<Item>())
    private var opened by mutableStateOf<Int?>(null)
    private var external by mutableStateOf<Item?>(null)
    private var secure by mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }
    private val loader by lazy {
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val del = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) { refresh(); if (external != null) finish() else opened = null }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setSecure(prefs.getBoolean("secure", false))
        intent?.data?.let { external = Item(it, intent.type?.startsWith("video") == true); opened = 0 }
        perm.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Bg, primary = Purple, onBackground = Purple, onSurface = Purple)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }

    private fun setSecure(on: Boolean) {
        secure = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs.edit().putBoolean("secure", on).apply()
    }

    private fun refresh() {
        val out = mutableListOf<Item>()
        try {
            val c = contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)", null,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
            c?.use {
                while (it.moveToNext()) {
                    val v = it.getInt(1) == 3
                    val base = if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += Item(ContentUris.withAppendedId(base, it.getLong(0)), v)
                }
            }
        } catch (_: SecurityException) { }
        items = out
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
        val list = external?.let { listOf(it) } ?: items
        val o = opened
        BackHandler(o != null) { if (external != null) finish() else opened = null }
        if (o != null && list.isNotEmpty()) Viewer(list, o.coerceIn(0, list.size - 1)) else Grid(list)
    }

    @Composable
    private fun Grid(list: List<Item>) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Prussian).statusBarsPadding().padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Gallery", color = Purple, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("Block screenshots ", color = Purple)
                Switch(secure, { setSecure(it) }, colors = SwitchDefaults.colors(
                    checkedThumbColor = Purple, checkedTrackColor = PrussianLight, uncheckedTrackColor = Bg))
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, it -> it.uri.toString() }) { i, it ->
                    Box(Modifier.aspectRatio(1f).clickable { opened = i }) {
                        AsyncImage(it.uri, null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (it.video) Text("▶", color = Purple, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Viewer(list: List<Item>, start: Int) {
        val pager = rememberPagerState(start) { list.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val it = list[i]
                if (it.video) VideoPlayer(it.uri)
                else AsyncImage(
                    ImageRequest.Builder(LocalContext.current).data(it.uri).size(Size.ORIGINAL).build(),
                    null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Prussian.copy(alpha = 0.85f))
                .statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ if (external != null) finish() else opened = null }) { Text("Back", color = Purple) }
                TextButton({ delete(list[pager.currentPage].uri) }) { Text("Delete permanently", color = Purple) }
            }
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val p = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(p) { onDispose { p.release() } }
    AndroidView({ PlayerView(it).apply { player = p } }, Modifier.fillMaxSize())
}
