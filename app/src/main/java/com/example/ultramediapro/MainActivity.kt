package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
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
            // ব্যাকগ্রাউন্ড ও স্ক্রিন লকে YouTube চালু রাখতে WebView ও সার্ভিস সক্রিয় রাখা
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
            // PiP মোডে শুধুমাত্র ভিডিওটি পুরো বক্সে ফিট করে প্লে চালু রাখা
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

    // YouTube ফুলস্ক্রিন ভিউ চালু থাকলে পুরো স্ক্রিনে দেখাবে
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

    // YouTube ব্যাক বাটন হ্যান্ডলার (অ্যাপ ক্লোজ না হয়ে আগের পেজে/Shorts থেকে বের হবে)
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
            // আল্ট্রা-কমপ্যাক্ট, স্লিম, আইকন-অনলি বটম বার (জায়গা নষ্ট করবে না)
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
            // YouTube WebView মেমোরিতে ধরে রাখা যাতে ট্যাব বদলালেও রিলোড না হয়
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
        // স্ক্রিন লক বা মিনিমাইজ হলে সর্বদা VISIBLE রিপোর্ট করবে
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
                    // intent:// বা অ্যাপ খোলার লিংক ব্লক করা যাতে ERR_UNKNOWN_URL_SCHEME না আসে
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
                MediaScanner.renameFile(activity, file.uri, name, writeLauncher) { refreshKey++ }
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
                supportingContent = { Text("${formatMillis(video.duration)} • ${video.sizeMb} MB") },
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

    // ডাবল কনফার্মেশন ডিলিট ডায়ালগ
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

    // রিনেম ডায়ালগ
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
    var selectedCategory by remember { mutableIntStateOf(0) } // 0=Songs, 1=Favorites, 2=Artists, 3=Albums
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
                MediaScanner.renameFile(activity, file.uri, name, writeLauncher) { refreshKey++ }
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
            // Muzio ক্যাটাগরি ট্যাব: Songs | Favorites | Artists | Albums
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
                                    3 -> "${song.album} • ${formatMillis(song.duration)}"
                                    else -> "${song.artist} • ${formatMillis(song.duration)}"
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
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                renameTarget = song
                                                renameInput = song.title
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = Color.Red) },
                                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red) },
                                            onClick = {
                                                menuExpanded = false
                                                deleteTarget = song
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // ভাসমান শাফেল বাটন
        FloatingActionButton(
            onClick = { if (displayedSongs.isNotEmpty()) playSong(displayedSongs.random()) },
            containerColor = Color(0xFFFFC107),
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = if (currentSong != null) 78.dp else 20.dp)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle")
        }

        // মিনি-প্লেয়ার বার
        currentSong?.let { song ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xFF261717))
                    .clickable { showFullPlayer = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Album, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(34.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(song.artist, color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = {
                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                }) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
                IconButton(onClick = { showEqualizer = true }) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = "EQ", tint = Color(0xFFFFC107))
                }
            }
        }

        // ফুলস্ক্রিন গোল্ডেন-ব্রাউন প্লেয়ার (Seekbar, Repeat, Shuffle, Queue ও Equalizer-সহ)
        if (showFullPlayer && currentSong != null) {
            BackHandler { showFullPlayer = false }
            val song = currentSong!!
            val isFav = favoriteIds.contains(song.id.toString())

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF7A6348)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showFullPlayer = false }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = Color.White)
                        }
                        Text("Now Playing", color = Color.White, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { showEqualizer = true }) {
                            Icon(Icons.Rounded.Equalizer, contentDescription = "Equalizer", tint = Color(0xFFFFC107))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF4A3B2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(96.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { toggleFavorite(song.id) }) {
                            Icon(
                                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) Color(0xFFFF5252) else Color.White
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = Color.LightGray, fontSize = 13.sp, maxLines = 1)
                        }
                        IconButton(onClick = { showQueueDialog = true }) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = "Playlist Queue", tint = Color.White)
                        }
                    }

                    // লাইভ সিকবার ও সময়
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = (currentPos.toFloat() / totalDur.toFloat()).coerceIn(0f, 1f),
                            onValueChange = {
                                val target = (it * totalDur).toLong()
                                currentPos = target
                                mediaController?.seekTo(target)
                            },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatMillis(currentPos), color = Color.White, fontSize = 12.sp)
                            Text(formatMillis(totalDur), color = Color.White, fontSize = 12.sp)
                        }
                    }

                    // কন্ট্রোল রো (Repeat, Prev, Play/Pause, Next, Shuffle)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                else -> Player.REPEAT_MODE_OFF
                            }
                            mediaController?.repeatMode = repeatMode
                        }) {
                            Icon(
                                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                contentDescription = "Repeat",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFFFFC107) else Color.White
                            )
                        }

                        IconButton(onClick = {
                            if (displayedSongs.isNotEmpty()) {
                                val idx = displayedSongs.indexOfFirst { it.id == song.id }
                                val prev = if (idx > 0) displayedSongs[idx - 1] else displayedSongs.last()
                                playSong(prev)
                            }
                        }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev", tint = Color.White, modifier = Modifier.size(34.dp))
                        }

                        IconButton(
                            onClick = { if (isPlaying) mediaController?.pause() else mediaController?.play() },
                            modifier = Modifier.size(64.dp).background(Color(0x44FFFFFF), CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(onClick = {
                            if (displayedSongs.isNotEmpty()) {
                                val next = if (shuffleEnabled) displayedSongs.random() else {
                                    val idx = displayedSongs.indexOfFirst { it.id == song.id }
                                    displayedSongs[(idx + 1) % displayedSongs.size]
                                }
                                playSong(next)
                            }
                        }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                        }

                        IconButton(onClick = { shuffleEnabled = !shuffleEnabled }) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffleEnabled) Color(0xFFFFC107) else Color.White
                            )
                        }
                    }
                }
            }
        }

        // Queue / Up Next লিস্ট ডায়ালগ
        if (showQueueDialog) {
            AlertDialog(
                onDismissRequest = { showQueueDialog = false },
                title = { Text("Playing Queue (${displayedSongs.size})") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(displayedSongs, key = { it.id }) { s ->
                            Text(
                                text = s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (s.id == currentSong?.id) FontWeight.Bold else FontWeight.Normal,
                                color = if (s.id == currentSong?.id) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playSong(s)
                                        showQueueDialog = false
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showQueueDialog = false }) { Text("Close") } }
            )
        }

        // আসল ৫-ব্যান্ড হার্ডওয়্যার ইকুলাইজার + Bass Boost ডায়ালগ
        if (showEqualizer) {
            AlertDialog(
                onDismissRequest = { showEqualizer = false },
                title = { Text("Equalizer & Bass Boost") },
                text = {
                    val eq = MusicPlaybackService.equalizer
                    val bass = MusicPlaybackService.bassBoost
                    if (eq == null) {
                        Text("ইকুলাইজার সক্রিয় করতে আগে যেকোনো একটি গান চালু করুন।")
                    } else {
                        Column(modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                            // প্রিসেট বাটনসমূহ
                            val presetCount = eq.numberOfPresets.toInt().coerceAtMost(5)
                            if (presetCount > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (p in 0 until presetCount) {
                                        val name = eq.getPresetName(p.toShort())
                                        AssistChip(
                                            onClick = {
                                                eq.usePreset(p.toShort())
                                                Toast.makeText(context, "Preset: $name", Toast.LENGTH_SHORT).show()
                                            },
                                            label = { Text(name, fontSize = 10.sp) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Bass Boost স্লাইডার
                            if (bass != null && bass.strengthSupported) {
                                var bassStrength by remember { mutableFloatStateOf(bass.roundedStrength.toFloat()) }
                                Text("Bass Boost (${(bassStrength / 10).toInt()}%)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Slider(
                                    value = bassStrength,
                                    valueRange = 0f..1000f,
                                    onValueChange = {
                                        bassStrength = it
                                        bass.setStrength(it.toInt().toShort())
                                    }
                                )
                            }

                            // ৫-ব্যান্ড ফ্রিকোয়েন্সি স্লাইডার
                            val bands = eq.numberOfBands.toInt()
                            val range = eq.bandLevelRange
                            for (i in 0 until bands) {
                                var level by remember { mutableFloatStateOf(eq.getBandLevel(i.toShort()).toFloat()) }
                                Text("${eq.getCenterFreq(i.toShort()) / 1000} Hz", fontSize = 12.sp)
                                Slider(
                                    value = level,
                                    valueRange = range[0].toFloat()..range[1].toFloat(),
                                    onValueChange = {
                                        level = it
                                        eq.setBandLevel(i.toShort(), it.toInt().toShort())
                                    }
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showEqualizer = false }) { Text("Done") } }
            )
        }

        // ডাবল কনফার্মেশন ডিলিট ডায়ালগ
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("অডিও ডিলিট করবেন?") },
                text = { Text("আপনি কি নিশ্চিতভাবে \"${target.title}\" ফাইলটি স্থায়ীভাবে মুছে ফেলতে চান?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        MediaScanner.deleteFile(activity, target.uri, deleteLauncher) { refreshKey++ }
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
            )
        }

        // রিনেম ডায়ালগ
        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("Rename Audio") },
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
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
            )
        }
    }
}
