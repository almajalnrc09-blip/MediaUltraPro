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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalView
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

class YouTubeJsBridge(private val onPlayingChanged: (Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportPlaying(playing: Boolean) {
        mainHandler.post { onPlayingChanged(playing) }
    }
}

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableIntStateOf(0)
    private var isPipMode by mutableStateOf(false)
    @Volatile var isYouTubePlaying: Boolean = false
    var activeWebView: BackgroundWebView? = null
    var customFullscreenView by mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val prefs = getSharedPreferences("ultra_prefs", MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
            val view = LocalView.current

            SideEffect {
                val controller = WindowInsetsControllerCompat(window, view)
                val useDarkStatusIcons = when {
                    customFullscreenView != null -> false
                    selectedTab == 0 -> false
                    else -> !isDarkTheme
                }
                controller.isAppearanceLightStatusBars = useDarkStatusIcons
                controller.isAppearanceLightNavigationBars = !isDarkTheme
            }

            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    background = Color(0xFF140C0C),
                    surface = Color(0xFF1C1111),
                    primary = Color(0xFFFFC107)
                )
            } else {
                lightColorScheme(
                    background = Color(0xFFFAFAFA),
                    surface = Color(0xFFFFFFFF),
                    primary = Color(0xFFD32F2F)
                )
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
                    onShowFullscreen = { fsView, callback ->
                        customFullscreenView = fsView
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
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
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
        if (selectedTab == 0 && isYouTubePlaying) {
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
        if (selectedTab == 0 && isYouTubePlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
            activeWebView?.evaluateJavascript(
                "var v = document.querySelector('video'); if (v && v.paused) { v.play().catch(function(){}); }",
                null
            )
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode = isInPictureInPictureMode
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
        if (isInPictureInPictureMode) {
            activeWebView?.evaluateJavascript(
                """
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        if (v.paused) v.play().catch(function(){});
                    }
                    var fsBtn = document.querySelector('button.fullscreen-icon, [aria-label*="Full screen"], [aria-label*="full screen"]');
                    if (fsBtn && !document.fullscreenElement) { fsBtn.click(); }
                })();
                """.trimIndent(), null
            )
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
    var currentMusicSong by remember { mutableStateOf<MediaFile?>(null) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

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

    BackHandler(enabled = !isMusicFullPlayerOpen) {
        if (selectedTab == 0) {
            val wv = activity.activeWebView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return@BackHandler
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            activity.moveTaskToBack(true)
        } else {
            lastBackPressTime = now
            Toast.makeText(activity, "বের হতে আবার ব্যাক চাপুন", Toast.LENGTH_SHORT).show()
        }
    }

    val musicTopBg = if (isDarkTheme) Color(0xFF2B1212) else MaterialTheme.colorScheme.background

    Scaffold(
        topBar = {
            if (!isPipMode && selectedTab != 0 && !isMusicFullPlayerOpen) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (selectedTab == 2) musicTopBg else MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Text(
                            text = if (selectedTab == 1) "Videos" else "Music",
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
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(40.dp),
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
        Box(modifier = Modifier.fillMaxSize()) {
            // YouTube ওয়েবভিউ স্বাভাবিক পূর্ণ হাইটে চলবে যাতে ভিডিও স্ক্রিন ছোট না হয়
            Box(
                modifier = if (selectedTab == 0) {
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .then(if (!isPipMode) Modifier.padding(bottom = padding.calculateBottomPadding()) else Modifier)
                } else {
                    Modifier.size(0.dp)
                }
            ) {
                YouTubeTabScreen(
                    activity = activity,
                    isVisible = selectedTab == 0,
                    onShowFullscreen = onShowFullscreen,
                    onHideFullscreen = onHideFullscreen
                )
            }

            if (selectedTab == 1) {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    VideoListTabScreen()
                }
            } else if (selectedTab == 2) {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    MuzioMusicTabScreen(
                        isDarkTheme = isDarkTheme,
                        currentSong = currentMusicSong,
                        onSongChanged = { currentMusicSong = it },
                        onFullPlayerChanged = { isMusicFullPlayerOpen = it }
                    )
                }
            }
        }
    }
}

@Composable
fun CompactTabIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
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
    isVisible: Boolean,
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

                function cleanUiAndTrack() {
                    document.querySelectorAll('a, button, ytm-button-renderer').forEach(function(el) {
                        var txt = (el.innerText || el.getAttribute('aria-label') || '').trim().toLowerCase();
                        if (txt === 'open app') {
                            el.style.display = 'none';
                        }
                    });
                    var v = document.querySelector('video');
                    if (window.UltraBridge) {
                        var playing = !!(v && !v.paused && !v.ended && v.readyState > 1);
                        window.UltraBridge.reportPlaying(playing);
                    }
                }
                if (!window.__ultraTrackerInstalled) {
                    window.__ultraTrackerInstalled = true;
                    setInterval(cleanUiAndTrack, 500);
                }
                cleanUiAndTrack();
            } catch(e) {}
        })();
    """.trimIndent()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        update = { webView ->
            webView.visibility = if (isVisible) View.VISIBLE else View.GONE
        },
        factory = { ctx ->
            val webView = BackgroundWebView(ctx)
            activity.activeWebView = webView
            webView.addJavascriptInterface(
                YouTubeJsBridge { playing -> activity.isYouTubePlaying = playing },
                "UltraBridge"
            )

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
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return true
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
                    if (view != null && callback != null) onShowFullscreen(view, callback)
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
        itemsIndexed(videos, key = { _, item -> item.id }) { index, video ->
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
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
fun MuzioMusicTabScreen(
    isDarkTheme: Boolean,
    currentSong: MediaFile?,
    onSongChanged: (MediaFile?) -> Unit,
    onFullPlayerChanged: (Boolean) -> Unit
) {
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
        onSongChanged(song)
        mediaController?.apply {
            setMediaItem(MediaItem.fromUri(song.uri))
            prepare()
            play()
        }
    }

    fun stopAndDismissMiniPlayer() {
        mediaController?.stop()
        onSongChanged(null)
        showFullPlayer = false
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

    val bgBrush = if (isDarkTheme) {
        Brush.verticalGradient(listOf(Color(0xFF2B1212), Color(0xFF120B0B)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFAFAFA), Color(0xFFF0ECEC)))
    }
    val titleColor = if (isDarkTheme) Color.White else Color(0xFF1A1A1A)
    val subColor = if (isDarkTheme) Color.LightGray else Color(0xFF666666)
    val accentColor = if (isDarkTheme) Color(0xFFFFC107) else Color(0xFFD32F2F)
    val iconBoxColor = if (isDarkTheme) Color(0xFF3A2424) else Color(0xFFEDE2E2)
    val miniPlayerBg = if (isDarkTheme) Color(0xFF261717) else Color(0xFFEAE3E3)

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        Column(modifier = Modifier.fillMaxSize()) {
            val tabs = listOf("Songs", "Favorites", "Artists", "Albums")
            TabRow(
                selectedTabIndex = selectedCategory,
                containerColor = Color.Transparent,
                contentColor = accentColor
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedCategory == idx,
                        onClick = { selectedCategory = idx },
                        text = {
                            Text(
                                title,
                                fontSize = 13.sp,
                                color = if (selectedCategory == idx) accentColor else subColor
                            )
                        }
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ${displayedSongs.size} songs...", color = subColor) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = accentColor) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = titleColor,
                    unfocusedTextColor = titleColor,
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = subColor.copy(alpha = 0.5f)
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
                        if (selectedCategory == 1) "কোনো Favorite গান নেই" else "কোনো অডিও পাওয়া যায়নি",
                        color = subColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    itemsIndexed(displayedSongs, key = { _, item -> item.id }) { index, song ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { playSong(song) },
                            leadingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}.", color = subColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(iconBoxColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accentColor)
                                    }
                                }
                            },
                            headlineContent = {
                                Text(song.title, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val sub = if (selectedCategory == 3) "${song.album} • ${formatMillis(song.duration)}"
                                else "${song.artist} • ${formatMillis(song.duration)}"
                                Text(sub, color = subColor, fontSize = 12.sp, maxLines = 1)
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = subColor)
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

        FloatingActionButton(
            onClick = {
                if (displayedSongs.isNotEmpty()) {
                    val pool = if (displayedSongs.size > 1 && currentSong != null) {
                        displayedSongs.filter { it.id != currentSong.id }
                    } else displayedSongs
                    playSong(pool.random())
                }
            },
            containerColor = accentColor,
            contentColor = if (isDarkTheme) Color.Black else Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = if (currentSong != null) 78.dp else 20.dp)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle")
        }

        currentSong?.let { song ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(miniPlayerBg)
                    .clickable { showFullPlayer = true }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Album, contentDescription = null, tint = accentColor, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(song.artist, color = subColor, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = {
                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play", tint = titleColor)
                }
                IconButton(onClick = { showEqualizer = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = "EQ", tint = accentColor)
                }
                IconButton(onClick = { stopAndDismissMiniPlayer() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Player", tint = subColor)
                }
            }
        }

        if (showFullPlayer && currentSong != null) {
            BackHandler { showFullPlayer = false }
            val song = currentSong
            val isFav = favoriteIds.contains(song.id.toString())

            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF7A6348)) {
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

        if (showQueueDialog) {
            AlertDialog(
                onDismissRequest = { showQueueDialog = false },
                title = { Text("Playing Queue (${displayedSongs.size})") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(displayedSongs, key = { _, item -> item.id }) { idx, s ->
                            Text(
                                text = "${idx + 1}. ${s.title}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (s.id == currentSong?.id) FontWeight.Bold else FontWeight.Normal,
                                color = if (s.id == currentSong?.id) accentColor else MaterialTheme.colorScheme.onSurface,
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
