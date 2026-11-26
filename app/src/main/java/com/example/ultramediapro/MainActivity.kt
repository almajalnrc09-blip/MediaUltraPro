package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableIntStateOf(0)
    private var isPipMode by mutableStateOf(false)
    var activeWebView: BackgroundWebView? = null
    var customFullscreenView by mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val prefs = getSharedPreferences("ultra_prefs", MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    background = Color(0xFF140C0C),
                    surface = Color(0xFF1F1414),
                    primary = Color(0xFFFFC107)
                )
            } else {
                lightColorScheme(primary = Color(0xFFD32F2F))
            }

            MaterialTheme(colorScheme = colorScheme) {
                MainAppContainer(
                    activity = this,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        prefs.edit().putBoolean("dark_mode", isDarkTheme).apply()
                    },
                    isPipMode = isPipMode,
                    fullscreenView = customFullscreenView,
                    onShowFullscreen = { view, callback ->
                        customFullscreenView = view
                        customViewCallback = callback
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        setImmersiveMode(true)
                    },
                    onHideFullscreen = { exitYouTubeFullscreen() }
                )
            }
        }
    }

    fun exitYouTubeFullscreen() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customFullscreenView = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setImmersiveMode(false)
    }

    private fun setImmersiveMode(enable: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enable) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, YouTubeBackgroundService::class.java))
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        if (selectedTab == 0) {
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
            try {
                val intent = Intent(this, YouTubeBackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (selectedTab == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeWebView?.evaluateJavascript(
                """
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.dataset.origStyle = v.getAttribute('style') || '';
                        v.style.position = 'fixed';
                        v.style.top = '0';
                        v.style.left = '0';
                        v.style.width = '100vw';
                        v.style.height = '100vh';
                        v.style.zIndex = '9999999';
                        v.style.background = '#000';
                        v.style.objectFit = 'contain';
                        if (v.paused && v.currentTime > 0) v.play();
                    }
                })();
                """.trimIndent(), null
            )
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            activeWebView?.evaluateJavascript(
                """
                (function() {
                    var v = document.querySelector('video');
                    if (v && v.dataset.origStyle !== undefined) {
                        v.setAttribute('style', v.dataset.origStyle);
                    }
                })();
                """.trimIndent(), null
            )
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, YouTubeBackgroundService::class.java))
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(
    activity: MainActivity,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    isPipMode: Boolean,
    fullscreenView: View?,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit
) {
    var isMusicFullPlayerOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    if (fullscreenView != null) {
        BackHandler { onHideFullscreen() }
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                    addView(
                        fullscreenView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        )
        return
    }

    BackHandler(enabled = selectedTab == 0) {
        val wv = activity.activeWebView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            activity.moveTaskToBack(true)
        }
    }

    Scaffold(
        topBar = {
            if (!isPipMode && selectedTab != 0 && !isMusicFullPlayerOpen) {
                TopAppBar(
                    title = {
                        Text(
                            if (selectedTab == 1) "Videos" else "Muzio Player",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    actions = {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = "Theme"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isPipMode && !isMusicFullPlayerOpen) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(46.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactTabIcon(
                            icon = Icons.Rounded.PlayCircle,
                            selected = selectedTab == 0,
                            onClick = { onTabSelected(0) }
                        )
                        CompactTabIcon(
                            icon = Icons.Rounded.VideoLibrary,
                            selected = selectedTab == 1,
                            onClick = { onTabSelected(1) }
                        )
                        CompactTabIcon(
                            icon = Icons.Rounded.MusicNote,
                            selected = selectedTab == 2,
                            onClick = { onTabSelected(2) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = if (selectedTab == 0) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                YouTubeTabScreen(
                    activity = activity,
                    onShowFullscreen = onShowFullscreen,
                    onHideFullscreen = onHideFullscreen
                )
            }

            if (selectedTab == 1) {
                VideoListTabScreen()
            } else if (selectedTab == 2) {
                MuzioMusicTabScreen(
                    onFullPlayerChanged = { isMusicFullPlayerOpen = it }
                )
            }
        }
    }
}

@Composable
fun CompactTabIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}

class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeTabScreen(
    activity: MainActivity,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit
) {
    val bgScript = """
        (function() {
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                var style = document.getElementById('ultra-hide-css');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'ultra-hide-css';
                    style.innerHTML = 'ytm-promoted-sparkles-web-renderer, .open-app-button, ytm-pivot-bar-item-renderer[aria-label="Open App"], button[aria-label="Open App"] { display: none !important; }';
                    document.head.appendChild(style);
                }
            } catch(e) {}
        })();
    """.trimIndent()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val webView = BackgroundWebView(ctx)
            activity.activeWebView = webView

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(bgScript, null)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view?.evaluateJavascript(bgScript, null)
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view != null && callback != null) {
                        onShowFullscreen(view, callback)
                    }
                }

                override fun onHideCustomView() {
                    onHideFullscreen()
                }
            }

            webView.loadUrl("https://m.youtube.com")
            webView
        }
    )
}

@Composable
fun VideoListTabScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    var videos by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var deleteTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var pendingRenameAfterPerm by remember { mutableStateOf<Pair<MediaFile, String>?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) refreshKey++ }

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRenameAfterPerm?.let { (file, name) ->
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    }
                    activity.contentResolver.update(file.uri, values, null, null)
                } catch (_: Exception) { }
            }
            refreshKey++
        }
        pendingRenameAfterPerm = null
    }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, true).collect {
            videos = MediaScanner.scanVideos(context)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(videos, key = { it.id }) { video ->
            var menuExpanded by remember { mutableStateOf(false) }
            ListItem(
                modifier = Modifier.clickable {
                    val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URI", video.uri.toString())
                        putExtra("VIDEO_TITLE", video.title)
                    }
                    context.startActivity(intent)
                },
                leadingContent = {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = { Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${formatMillis(video.duration)} •${video.sizeMb} MB") },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    renameTarget = video
                                    renameInput = video.title
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = Color.Red) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red) },
                                onClick = {
                                    menuExpanded = false
                                    deleteTarget = video
                                }
                            )
                        }
                    }
                }
            )
            HorizontalDivider(thickness = 0.5.dp)
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("ভিডিও ডিলিট করবেন?") },
            text = { Text("আপনি কি নিশ্চিতভাবে \"${target.title}\" ফাইলটি স্থায়ীভাবে মুছে ফেলতে চান?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    MediaScanner.deleteFile(activity, target.uri, deleteLauncher) { refreshKey++ }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Video") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("নতুন নাম") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameInput.trim()
                    renameTarget = null
                    if (newName.isNotEmpty()) {
                        pendingRenameAfterPerm = target to newName
                        MediaScanner.renameFile(activity, target.uri, newName, writeLauncher) {
                            pendingRenameAfterPerm = null
                            refreshKey++
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun MuzioMusicTabScreen(onFullPlayerChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { context.getSharedPreferences("muzio_favs", Context.MODE_PRIVATE) }

    var songs by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var favoriteIds by remember {
        mutableStateOf(prefs.getStringSet("fav_ids", emptySet())?.toSet() ?: emptySet())
    }

    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var currentSong by remember { mutableStateOf<MediaFile?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var totalDur by remember { mutableLongStateOf(1L) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    var showFullPlayer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showQueueDialog by remember { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var pendingRenameAfterPerm by remember { mutableStateOf<Pair<MediaFile, String>?>(null) }

    LaunchedEffect(showFullPlayer) { onFullPlayerChanged(showFullPlayer) }
    DisposableEffect(Unit) { onDispose { onFullPlayerChanged(false) } }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) refreshKey++ }

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRenameAfterPerm?.let { (file, name) ->
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    }
                    activity.contentResolver.update(file.uri, values, null, null)
                } catch (_: Exception) { }
            }
            refreshKey++
        }
        pendingRenameAfterPerm = null
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ mediaController = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose { mediaController?.release() }
    }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, false).collect {
            songs = MediaScanner.scanAudio(context)
        }
    }

    LaunchedEffect(mediaController) {
        while (true) {
            mediaController?.let { ctrl ->
                isPlaying = ctrl.isPlaying
                currentPos = ctrl.currentPosition.coerceAtLeast(0L)
                totalDur = ctrl.duration.coerceAtLeast(1L)
            }
            delay(400)
        }
    }

    fun playSong(song: MediaFile) {
        currentSong = song
        mediaController?.apply {
            setMediaItem(MediaItem.fromUri(song.uri))
            prepare()
            play()
        }
    }

    fun toggleFavorite(songId: Long) {
        val idStr = songId.toString()
        val updated = if (favoriteIds.contains(idStr)) favoriteIds - idStr else favoriteIds + idStr
        favoriteIds = updated
        prefs.edit().putStringSet("fav_ids", updated).apply()
    }

    val displayedSongs = remember(songs, searchQuery, selectedCategory, favoriteIds) {
        val base = when (selectedCategory) {
            1 -> songs.filter { favoriteIds.contains(it.id.toString()) }
            2 -> songs.sortedBy { it.artist.lowercase() }
            3 -> songs.sortedBy { it.album.lowercase() }
            else -> songs
        }
        if (searchQuery.isBlank()) base
        else base.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2B1212), Color(0xFF120B0B))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val tabs = listOf("Songs", "Favorites", "Artists", "Albums")
            TabRow(
                selectedTabIndex = selectedCategory,
                containerColor = Color.Transparent,
                contentColor = Color(0xFFFFC107)
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedCategory == idx,
                        onClick = { selectedCategory = idx },
                        text = {
                            Text(
                                title,
                                fontSize = 13.sp,
                                color = if (selectedCategory == idx) Color(0xFFFFC107) else Color.LightGray
                            )
                        }
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ${displayedSongs.size} songs...", color = Color.LightGray) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFFFFC107)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFC107),
                    unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            if (displayedSongs.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedCategory == 1) "কোনো Favorite গান নেই (হৃদয় আইকনে ট্যাপ করে যোগ করুন)" else "কোনো অডিও পাওয়া যায়নি",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    items(displayedSongs, key = { it.id }) { song ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { playSong(song) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF3A2424)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFFFC107))
                                }
                            },
                            headlineContent = {
                                Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val sub = when (selectedCategory) {
                                    3 -> "${song.album} •${formatMillis(song.duration)}"
                                    else -> "${song.artist} •${formatMillis(song.duration)}"
                                }
                                Text(sub, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = Color.LightGray)
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (favoriteIds.contains(song.id.toString())) "Remove Favorite" else "Add to Favorites") },
                                            leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null) },
                                            onClick = { menuExpanded = false; toggleFavorite(song.id) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, content
