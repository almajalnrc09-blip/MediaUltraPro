package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.media3.common.MediaMetadata as Media3Metadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class YouTubeJsBridge(
    private val onStateChanged: (playing: Boolean, wantPlay: Boolean, title: String, posMs: Long, durMs: Long) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportState(playing: Boolean, wantPlay: Boolean, title: String, posMs: Double, durMs: Double) {
        mainHandler.post {
            onStateChanged(playing, wantPlay, title, posMs.toLong().coerceAtLeast(0L), durMs.toLong().coerceAtLeast(0L))
        }
    }
}

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableIntStateOf(0)
    private var isMusicFullPlayerOpen by mutableStateOf(false)
    private var lastIsDarkTheme: Boolean = false
    private var isAppInBackground: Boolean = false
    @Volatile var isYouTubePlaying: Boolean = false
    @Volatile var userWantsYouTubePlay: Boolean = false
    @Volatile var currentYouTubeTitle: String = "YouTube"
    @Volatile var currentYouTubePosMs: Long = 0L
    @Volatile var currentYouTubeDurMs: Long = 0L
    var activeWebView: BackgroundWebView? = null
    var customFullscreenView by mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val bgHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        YouTubeBackgroundService.onCommand = { command ->
            runOnUiThread {
                when (command) {
                    YouTubeBackgroundService.ACTION_YT_PLAY -> {
                        activeWebView?.onResume()
                        activeWebView?.resumeTimers()
                        isYouTubePlaying = true
                        userWantsYouTubePlay = true
                        activeWebView?.evaluateJavascript(
                            """
                            (function() {
                                window.__ultraWantPlay = true;
                                var v = document.querySelector('video');
                                if (v && v.paused) { v.play().catch(function(){}); }
                                if (window.__ultraReport) window.__ultraReport();
                            })();
                            """.trimIndent(), null
                        )
                    }
                    YouTubeBackgroundService.ACTION_YT_PAUSE -> {
                        isYouTubePlaying = false
                        userWantsYouTubePlay = false
                        activeWebView?.evaluateJavascript(
                            """
                            (function() {
                                window.__ultraWantPlay = false;
                                var v = document.querySelector('video');
                                if (v && !v.paused) { v.pause(); }
                                if (window.__ultraReport) window.__ultraReport();
                            })();
                            """.trimIndent(), null
                        )
                    }
                    YouTubeBackgroundService.ACTION_YT_STOP_BG -> {
                        isYouTubePlaying = false
                        userWantsYouTubePlay = false
                        activeWebView?.evaluateJavascript(
                            """
                            (function() {
                                window.__ultraWantPlay = false;
                                var v = document.querySelector('video');
                                if (v) { v.pause(); }
                                if (window.__ultraReport) window.__ultraReport();
                            })();
                            """.trimIndent(), null
                        )
                    }
                }
            }
        }

        YouTubeBackgroundService.onSeekCommand = { posMs ->
            runOnUiThread {
                val secs = posMs / 1000.0
                activeWebView?.evaluateJavascript(
                    "var v = document.querySelector('video'); if (v && isFinite(v.duration)) { v.currentTime = $secs; }",
                    null
                )
            }
        }

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            lastIsDarkTheme = isDarkTheme

            LaunchedEffect(selectedTab, isDarkTheme, customFullscreenView, isMusicFullPlayerOpen) {
                applySystemBarAppearance(isDarkTheme)
            }

            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    background = Color(0xFF140C0C),
                    surface = Color(0xFF1F1414),
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
                    isMusicFullPlayerOpen = isMusicFullPlayerOpen,
                    onMusicFullPlayerChanged = { isMusicFullPlayerOpen = it },
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

    fun applySystemBarAppearance(isDarkTheme: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val useDarkStatusIcons = when {
            customFullscreenView != null -> false
            selectedTab == 0 -> false
            selectedTab == 2 && isMusicFullPlayerOpen -> false
            else -> !isDarkTheme
        }
        val useDarkNavIcons = !isDarkTheme

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = useDarkStatusIcons
        controller.isAppearanceLightNavigationBars = useDarkNavIcons

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            var appearance = 0
            if (useDarkStatusIcons) appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            if (useDarkNavIcons) appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(appearance, mask)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            var vis = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            vis = if (useDarkStatusIcons) {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = vis
        }
    }

    fun exitYouTubeFullscreen() {
        val cb = customViewCallback
        customViewCallback = null
        customFullscreenView = null
        cb?.onCustomViewHidden()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setImmersiveMode(false)
        applySystemBarAppearance(lastIsDarkTheme)
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

    fun onYouTubeStateUpdated(playing: Boolean, wantPlay: Boolean, title: String, posMs: Long, durMs: Long) {
        val cleanedTitle = title.replace(" - YouTube", "").trim().ifEmpty { "YouTube" }
        val stateChanged = isYouTubePlaying != playing || currentYouTubeTitle != cleanedTitle
        isYouTubePlaying = playing
        userWantsYouTubePlay = wantPlay
        currentYouTubeTitle = cleanedTitle
        currentYouTubePosMs = posMs
        currentYouTubeDurMs = durMs
        if (isAppInBackground && (stateChanged || (playing && !YouTubeBackgroundService.isRunning))) {
            if (playing || YouTubeBackgroundService.isRunning) {
                startOrUpdateYouTubeNotification(playing, cleanedTitle, posMs, durMs)
            }
        }
    }

    fun startOrUpdateYouTubeNotification(
        playing: Boolean,
        title: String = currentYouTubeTitle,
        posMs: Long = currentYouTubePosMs,
        durMs: Long = currentYouTubeDurMs
    ) {
        try {
            val intent = Intent(this, YouTubeBackgroundService::class.java).apply {
                action = YouTubeBackgroundService.ACTION_START_OR_UPDATE
                putExtra(YouTubeBackgroundService.EXTRA_IS_PLAYING, playing)
                putExtra(YouTubeBackgroundService.EXTRA_TITLE, title)
                putExtra(YouTubeBackgroundService.EXTRA_POSITION_MS, posMs)
                putExtra(YouTubeBackgroundService.EXTRA_DURATION_MS, durMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) { }
    }

    private fun keepYouTubeAliveInBackground() {
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
        activeWebView?.evaluateJavascript(
            "window.__ultraInBg = true; if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();",
            null
        )
        startOrUpdateYouTubeNotification(true, currentYouTubeTitle, currentYouTubePosMs, currentYouTubeDurMs)

        bgHandler.postDelayed({
            if (isAppInBackground && userWantsYouTubePlay) {
                activeWebView?.onResume()
                activeWebView?.resumeTimers()
                activeWebView?.evaluateJavascript(
                    "var v = document.querySelector('video'); if (v && window.__ultraWantPlay && v.paused && !v.ended) { v.play().catch(function(){}); }",
                    null
                )
            }
        }, 180)
        bgHandler.postDelayed({
            if (isAppInBackground && userWantsYouTubePlay) {
                activeWebView?.onResume()
                activeWebView?.resumeTimers()
                activeWebView?.evaluateJavascript(
                    "var v = document.querySelector('video'); if (v && window.__ultraWantPlay && v.paused && !v.ended) { v.play().catch(function(){}); }",
                    null
                )
            }
        }, 500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBarAppearance(lastIsDarkTheme)
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        bgHandler.removeCallbacksAndMessages(null)
        stopService(Intent(this, YouTubeBackgroundService::class.java))
        activeWebView?.evaluateJavascript("window.__ultraInBg = false;", null)
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
        applySystemBarAppearance(lastIsDarkTheme)
    }

    override fun onPause() {
        super.onPause()
        isAppInBackground = true
        if (selectedTab == 0) {
            if (isYouTubePlaying || userWantsYouTubePlay || customFullscreenView != null) {
                keepYouTubeAliveInBackground()
            } else {
                activeWebView?.evaluateJavascript(
                    "(function(){ var v=document.querySelector('video'); return !!(v && !v.paused && !v.ended); })();"
                ) { res ->
                    if (res == "true") {
                        isYouTubePlaying = true
                        userWantsYouTubePlay = true
                        keepYouTubeAliveInBackground()
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        isAppInBackground = true
        if (selectedTab == 0 && (isYouTubePlaying || userWantsYouTubePlay || customFullscreenView != null)) {
            keepYouTubeAliveInBackground()
        }
    }

    override fun onDestroy() {
        bgHandler.removeCallbacksAndMessages(null)
        YouTubeBackgroundService.onCommand = null
        YouTubeBackgroundService.onSeekCommand = null
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
    isMusicFullPlayerOpen: Boolean,
    onMusicFullPlayerChanged: (Boolean) -> Unit,
    fullscreenView: View?,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit
) {
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

    BackHandler(enabled = !isMusicFullPlayerOpen && fullscreenView == null) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selectedTab != 0 && !isMusicFullPlayerOpen && fullscreenView == null) {
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
                        }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (selectedTab == 0) {
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .then(
                                if (fullscreenView == null) {
                                    Modifier
                                        .statusBarsPadding()
                                        .navigationBarsPadding()
                                        .padding(bottom = 41.dp)
                                } else {
                                    Modifier
                                }
                            )
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
                    Box(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                        VideoListTabScreen()
                    }
                } else if (selectedTab == 2) {
                    Box(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                        MuzioMusicTabScreen(
                            isDarkTheme = isDarkTheme,
                            currentSong = currentMusicSong,
                            onSongChanged = { currentMusicSong = it },
                            onFullPlayerChanged = onMusicFullPlayerChanged
                        )
                    }
                }
            }
        }

        if (!isMusicFullPlayerOpen && fullscreenView == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(0.76f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                color = if (isDarkTheme) Color(0xE6221616) else Color(0xF5FFFFFF)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 8.dp),
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

        if (fullscreenView != null) {
            var ytLocked by remember { mutableStateOf(false) }

            BackHandler {
                if (!ytLocked) onHideFullscreen()
                else Toast.makeText(activity, "স্ক্রিন আনলক করুন", Toast.LENGTH_SHORT).show()
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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

                if (ytLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { _, _ -> }
                            }
                    )
                }

                IconButton(
                    onClick = { ytLocked = !ytLocked },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(12.dp)
                        .size(36.dp)
                        .background(Color(0x77000000), CircleShape)
                ) {
                    Icon(
                        imageVector = if (ytLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = "Screen Lock",
                        tint = if (ytLocked) Color(0xFFFFC107) else Color.White,
                        modifier = Modifier.size(18.dp)
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
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp)
        )
    }
}

class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

// শুধুমাত্র অ্যাড ট্র্যাকিং ও ব্যানার ডোমেইন ব্লক (googlevideo.com ব্লক না করায় ব্ল্যাক স্ক্রিন ওয়েটিং হবে না)
private val AD_HOST_PATTERNS = listOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "ads.youtube.com",
    "s0.2mdn.net",
    "google-analytics.com",
    "googletagmanager.com",
    "pagead2.googlesyndication.com",
    "tpc.googlesyndication.com"
)

private fun isAdNetworkUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (AD_HOST_PATTERNS.any { lower.contains(it) }) return true
    if (lower.contains("youtube.com")) {
        if (lower.contains("/api/stats/ads") ||
            lower.contains("/pagead/") ||
            lower.contains("/ptracking") ||
            lower.contains("/get_midroll_info")
        ) return true
    }
    return false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeTabScreen(
    activity: MainActivity,
    isVisible: Boolean,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit
) {
    // ডিপ JSON অ্যাড-ফিল্টার (JSON.parse + Response.prototype.json + fetch + ytInitialPlayerResponse)
    val adPreScript = """
        (function() {
            if (window.__ultraFetchHooked) return;
            window.__ultraFetchHooked = true;

            function stripAds(obj, depth) {
                if (!obj || typeof obj !== 'object' || depth > 5) return obj;
                try {
                    if (Array.isArray(obj)) {
                        for (var i = 0; i < obj.length; i++) {
                            stripAds(obj[i], depth + 1);
                        }
                        return obj;
                    }
                    if ('adPlacements' in obj) obj.adPlacements = [];
                    if ('playerAds' in obj) obj.playerAds = [];
                    if ('adSlots' in obj) obj.adSlots = [];
                    if ('adBreakHeartbeatParams' in obj) delete obj.adBreakHeartbeatParams;
                    if ('adBreakParams' in obj) delete obj.adBreakParams;
                    if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                        stripAds(obj.playerResponse, depth + 1);
                    }
                    if (obj.response && typeof obj.response === 'object') {
                        stripAds(obj.response, depth + 1);
                    }
                    if (obj.page && typeof obj.page === 'object') {
                        stripAds(obj.page, depth + 1);
                    }
                } catch(e) {}
                return obj;
            }

            try {
                if (window.ytInitialPlayerResponse) stripAds(window.ytInitialPlayerResponse, 0);
                var _ytIPR = window.ytInitialPlayerResponse;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                    configurable: true,
                    get: function() { return _ytIPR; },
                    set: function(val) { _ytIPR = stripAds(val, 0); }
                });
            } catch(e) {}

            try {
                var origParse = JSON.parse;
                JSON.parse = function() {
                    var res = origParse.apply(this, arguments);
                    return stripAds(res, 0);
                };
            } catch(e) {}

            try {
                var origJson = Response.prototype.json;
                Response.prototype.json = function() {
                    return origJson.apply(this, arguments).then(function(data) {
                        return stripAds(data, 0);
                    });
                };
            } catch(e) {}

            try {
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    var p = origFetch.apply(this, arguments);
                    if (url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('/youtubei/v1/next') !== -1 || url.indexOf('pbj=1') !== -1)) {
                        return p.then(function(resp) {
                            try {
                                var cloned = resp.clone();
                                return cloned.text().then(function(txt) {
                                    try {
                                        var data = JSON.parse(txt);
                                        stripAds(data, 0);
                                        var headers = new Headers(resp.headers);
                                        headers.delete('content-length');
                                        headers.delete('content-encoding');
                                        return new Response(JSON.stringify(data), {
                                            status: resp.status,
                                            statusText: resp.statusText,
                                            headers: headers
                                        });
                                    } catch(e) { return resp; }
                                }).catch(function() { return resp; });
                            } catch(e) { return resp; }
                        });
                    }
                    return p;
                };
            } catch(e) {}
        })();
    """.trimIndent()

    val bgScript = """
        (function() {
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                document.hasFocus = function() { return true; };

                var stopEvt = function(e) { e.stopImmediatePropagation(); };
                ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'blur'].forEach(function(evt) {
                    window.addEventListener(evt, stopEvt, true);
                    document.addEventListener(evt, stopEvt, true);
                });
            } catch(e) {}

            try {
                var ultraCss = document.getElementById('ultra-main-css');
                if (!ultraCss) {
                    ultraCss = document.createElement('style');
                    ultraCss.id = 'ultra-main-css';
                    ultraCss.textContent =
                        'ytm-open-app-button, .open-app-button, a[aria-label="Open App"], button[aria-label="Open App"], ' +
                        'ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-companion-ad-renderer, ' +
                        'ytm-ad-slot-renderer, ad-slot-renderer, ytm-in-feed-ad-layout-renderer, ytm-statement-banner-renderer, ' +
                        'ytm-mealbar-promo-renderer, .ytp-ad-overlay-container, .ytp-ad-message-container, #player-ads { display: none !important; } ' +
                        'body.ultra-watch-portrait .player-size, ' +
                        'body.ultra-watch-portrait #player-container-id, ' +
                        'body.ultra-watch-portrait .player-container, ' +
                        'body.ultra-watch-portrait #player, ' +
                        'body.ultra-watch-portrait #movie_player, ' +
                        'body.ultra-watch-portrait .html5-video-player, ' +
                        'body.ultra-watch-portrait .html5-video-container { ' +
                        'width: 100vw !important; max-width: 100vw !important; ' +
                        'height: 56.25vw !important; min-height: 56.25vw !important; max-height: 56.25vw !important; ' +
                        'padding-bottom: 0 !important; margin-left: 0 !important; margin-right: 0 !important; } ' +
                        'body.ultra-watch-portrait #movie_player video, ' +
                        'body.ultra-watch-portrait video.html5-main-video { ' +
                        'width: 100vw !important; height: 56.25vw !important; ' +
                        'max-width: 100vw !important; max-height: 56.25vw !important; ' +
                        'left: 0 !important; top: 0 !important; object-fit: contain !important; }';
                    (document.head || document.documentElement).appendChild(ultraCss);
                }
            } catch(e) {}

            try {
                if (!window.__ultraTrackerInstalled) {
                    window.__ultraTrackerInstalled = true;
                    window.__ultraWantPlay = false;
                    window.__ultraInBg = false;
                    window.__ultraLastTouch = 0;
                    window.__ultraAdMuted = false;
                    window.__ultraWasWatchPortrait = false;

                    document.addEventListener('touchstart', function() { window.__ultraLastTouch = Date.now(); }, true);
                    document.addEventListener('click', function() { window.__ultraLastTouch = Date.now(); }, true);

                    window.__ultraReport = function() {
                        try {
                            var v = document.querySelector('video');
                            var playing = !!(v && !v.paused && !v.ended && v.readyState > 1);
                            var posMs = (v && isFinite(v.currentTime)) ? Math.max(0, Math.floor(v.currentTime * 1000)) : 0;
                            var durMs = (v && isFinite(v.duration)) ? Math.max(0, Math.floor(v.duration * 1000)) : 0;
                            var titleEl = document.querySelector('h2.slim-video-information-title, .slim-video-information-title, ytm-slim-video-metadata-section-renderer h2');
                            var title = (titleEl && titleEl.textContent) ? titleEl.textContent.trim() : (document.title || 'YouTube');
                            if (window.UltraBridge) {
                                window.UltraBridge.reportState(playing, !!window.__ultraWantPlay, title, posMs, durMs);
                            }
                        } catch(e) {}
                    };

                    window.__ultraEnsurePlay = function() {
                        [40, 180, 450, 900].forEach(function(ms) {
                            setTimeout(function() {
                                var v = document.querySelector('video');
                                if (v && window.__ultraWantPlay && v.paused && !v.ended) {
                                    v.play().catch(function(){});
                                }
                                window.__ultraReport();
                            }, ms);
                        });
                    };

                    function hookVideo(v) {
                        if (!v || v.__ultraHooked) return;
                        v.__ultraHooked = true;
                        v.addEventListener('play', function() {
                            window.__ultraWantPlay = true;
                            window.__ultraReport();
                        });
                        v.addEventListener('pause', function() {
                            var userTappedRecently = (Date.now() - window.__ultraLastTouch) < 800;
                            if (userTappedRecently && !window.__ultraInBg) {
                                window.__ultraWantPlay = false;
                            } else if (window.__ultraInBg && window.__ultraWantPlay && !v.ended) {
                                setTimeout(function() {
                                    if (window.__ultraInBg && window.__ultraWantPlay && v.paused && !v.ended) {
                                        v.play().catch(function(){});
                                    }
                                }, 120);
                            }
                            window.__ultraReport();
                        });
                        v.addEventListener('ended', function() {
                            window.__ultraReport();
                        });
                    }

                    function skipVideoAdsIfAny() {
                        try {
                            var player = document.querySelector('#movie_player, .html5-video-player');
                            var isAd = !!(
                                (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) ||
                                document.querySelector('#player-container-id .ytp-ad-player-overlay, #player-container-id .ytp-ad-player-overlay-layout, #player-container-id .ytp-ad-skip-button, #player-container-id .ytp-ad-skip-button-modern, #player-container-id .ytp-skip-ad-button, #player-container-id .ytp-ad-preview-container')
                            );
                            var v = document.querySelector('video');
                            if (isAd && v) {
                                if (!window.__ultraAdMuted) {
                                    window.__ultraAdMuted = true;
                                    v.muted = true;
                                }
                                try { v.playbackRate = 16; } catch(e) {}
                                if (v.paused) {
                                    v.play().catch(function(){});
                                }
                                if (isFinite(v.duration) && v.duration > 0.2) {
                                    v.currentTime = v.duration;
                                }
                                try {
                                    if (player && typeof player.skipAd === 'function') player.skipAd();
                                } catch(e) {}
                                document.querySelectorAll('#player-container-id .ytp-ad-skip-button, #player-container-id .ytp-ad-skip-button-modern, #player-container-id .ytp-skip-ad-button, #player-container-id .ytp-ad-skip-button-slot button, #player-container-id [id^="skip-button"], #player-container-id button').forEach(function(btn) {
                                    var t = (btn.innerText || btn.textContent || btn.getAttribute('aria-label') || '').trim().toLowerCase();
                                    if (t === 'skip' || t === 'skip ad' || t === 'skip ads') {
                                        btn.click();
                                    }
                                });
                            } else if (!isAd && window.__ultraAdMuted && v) {
                                window.__ultraAdMuted = false;
                                v.muted = false;
                                try { v.playbackRate = 1; } catch(e) {}
                                if (v.paused && !v.ended) {
                                    v.play().catch(function(){});
                                }
                            }
                        } catch(e) {}
                    }

                    function updateWatchSizeClass() {
                        try {
                            var isWatch = location.pathname.indexOf('/watch') === 0;
                            var isFs = !!(document.fullscreenElement || document.webkitFullscreenElement);
                            var isPortrait = window.innerHeight > window.innerWidth;
                            var shouldApply = isWatch && !isFs && isPortrait;
                            if (document.body) {
                                if (shouldApply) {
                                    document.body.classList.add('ultra-watch-portrait');
                                    var v = document.querySelector('#movie_player video, video.html5-main-video');
                                    if (v) {
                                        var w = window.innerWidth;
                                        var h = Math.round(w * 9 / 16);
                                        v.style.width = w + 'px';
                                        v.style.height = h + 'px';
                                        v.style.left = '0px';
                                        v.style.top = '0px';
                                    }
                                } else {
                                    document.body.classList.remove('ultra-watch-portrait');
                                }
                                if (shouldApply !== window.__ultraWasWatchPortrait) {
                                    window.__ultraWasWatchPortrait = shouldApply;
                                    window.dispatchEvent(new Event('resize'));
                                }
                            }
                        } catch(e) {}
                    }

                    function hideOpenAppButtons() {
                        try {
                            document.querySelectorAll('a, button, ytm-button-renderer, yt-button-shape, c3-material-button').forEach(function(el) {
                                var raw = (el.innerText || el.textContent || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase();
                                var href = (el.getAttribute('href') || '').toLowerCase();
                                if (raw === 'open app' || href.indexOf('redirect_app_store') !== -1 || href.indexOf('intent://') === 0) {
                                    var wrap = el.closest('ytm-button-renderer, c3-material-button, yt-button-shape') || el;
                                    wrap.style.setProperty('display', 'none', 'important');
                                }
                            });
                        } catch(e) {}
                    }

                    setInterval(function() {
                        skipVideoAdsIfAny();
                        updateWatchSizeClass();
                        var v = document.querySelector('video');
                        hookVideo(v);
                    }, 100);

                    setInterval(function() {
                        hideOpenAppButtons();
                        window.__ultraReport();
                    }, 500);
                }
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
                YouTubeJsBridge { playing, wantPlay, title, posMs, durMs ->
                    activity.onYouTubeStateUpdated(playing, wantPlay, title, posMs, durMs)
                },
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
                userAgentString = userAgentString.replace("; wv", "")
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    if (isAdNetworkUrl(url)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(adPreScript, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(adPreScript, null)
                    view?.evaluateJavascript(bgScript, null)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view?.evaluateJavascript(adPreScript, null)
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
fun rememberEmbeddedAlbumArt(context: Context, uri: Uri): Bitmap? {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val bytes = retriever.embeddedPicture
                if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) { }
            }
        }
    }
    return bitmap
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 64.dp)
    ) {
        itemsIndexed(videos, key = { _, item -> item.id }) { index, video ->
            var menuExpanded by remember { mutableStateOf(false) }
            ListItem(
                modifier = Modifier.clickable {
                    val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                        putExtra("VIDEO_URI", video.uri.toString())
                        putExtra("VIDEO_TITLE", video.title)
                        putStringArrayListExtra("VIDEO_URIS", ArrayList(videos.map { it.uri.toString() }))
                        putStringArrayListExtra("VIDEO_TITLES", ArrayList(videos.map { it.title }))
                        putExtra("VIDEO_INDEX", index)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var customQueueOrder by remember { mutableStateOf<List<Long>>(emptyList()) }

    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var totalDur by remember { mutableLongStateOf(1L) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    var showFullPlayer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showQueueDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var musicSleepTimer by remember { mutableStateOf<CountDownTimer?>(null) }

    var deleteTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameTarget by remember { mutableStateOf<MediaFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var pendingRenameAfterPerm by remember { mutableStateOf<Pair<MediaFile, String>?>(null) }

    LaunchedEffect(showFullPlayer) { onFullPlayerChanged(showFullPlayer) }
    DisposableEffect(Unit) {
        onDispose {
            onFullPlayerChanged(false)
            musicSleepTimer?.cancel()
        }
    }

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
        future.addListener({
            try { mediaController = future.get() } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(context))
        onDispose { mediaController?.release() }
    }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, false).collect {
            songs = MediaScanner.scanAudio(context)
        }
    }

    fun connectAndRun(action: (MediaController) -> Unit) {
        val existing = mediaController
        if (existing != null && existing.isConnected) {
            action(existing)
            return
        }
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val ctrl = future.get()
                mediaController = ctrl
                action(ctrl)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(context))
    }

    fun playSong(song: MediaFile) {
        onSongChanged(song)
        val item = MediaItem.Builder()
            .setUri(song.uri)
            .setMediaMetadata(
                Media3Metadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build()
            )
            .build()
        connectAndRun { ctrl ->
            ctrl.setMediaItem(item)
            ctrl.prepare()
            ctrl.play()
        }
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

    val orderedQueue = remember(displayedSongs, customQueueOrder) {
        if (customQueueOrder.isEmpty()) {
            displayedSongs
        } else {
            val map = displayedSongs.associateBy { it.id }
            val arranged = customQueueOrder.mapNotNull { map[it] }
            val remaining = displayedSongs.filter { it.id !in customQueueOrder }
            arranged + remaining
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentIds = orderedQueue.map { it.id }.toMutableList()
        if (fromIndex in currentIds.indices && toIndex in currentIds.indices) {
            val moved = currentIds.removeAt(fromIndex)
            currentIds.add(toIndex, moved)
            customQueueOrder = currentIds
        }
    }

    val latestQueue by rememberUpdatedState(orderedQueue)
    val latestSong by rememberUpdatedState(currentSong)
    val latestShuffle by rememberUpdatedState(shuffleEnabled)
    val latestRepeat by rememberUpdatedState(repeatMode)

    DisposableEffect(mediaController) {
        val ctrl = mediaController
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && latestRepeat != Player.REPEAT_MODE_ONE) {
                    val queue = latestQueue
                    val cur = latestSong
                    if (queue.isNotEmpty() && cur != null) {
                        val next = if (latestShuffle && queue.size > 1) {
                            queue.filter { it.id != cur.id }.random()
                        } else {
                            val idx = queue.indexOfFirst { it.id == cur.id }
                            if (idx in queue.indices && idx + 1 < queue.size) {
                                queue[idx + 1]
                            } else if (latestRepeat == Player.REPEAT_MODE_ALL) {
                                queue.first()
                            } else null
                        }
                        if (next != null) playSong(next)
                    }
                }
            }
        }
        ctrl?.addListener(listener)
        onDispose { ctrl?.removeListener(listener) }
    }

    LaunchedEffect(mediaController) {
        while (true) {
            mediaController?.let { ctrl ->
                if (ctrl.isConnected) {
                    isPlaying = ctrl.isPlaying
                    currentPos = ctrl.currentPosition.coerceAtLeast(0L)
                    totalDur = ctrl.duration.coerceAtLeast(1L)
                    // নোটিফিকেশনের Close (×) বাটন চেপে বন্ধ করলে মিনি-প্লেয়ারও অটোমেটিক সরে যাবে
                    if (ctrl.mediaItemCount == 0 && latestSong != null) {
                        onSongChanged(null)
                        showFullPlayer = false
                    }
                }
            }
            delay(400)
        }
    }

    fun stopAndDismissMiniPlayer() {
        mediaController?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        onSongChanged(null)
        showFullPlayer = false
    }

    fun setMusicSleepTimer(minutes: Int) {
        musicSleepTimer?.cancel()
        if (minutes == 0) {
            Toast.makeText(context, "Music Sleep Timer বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            musicSleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    mediaController?.pause()
                    Toast.makeText(context, "Sleep Timer শেষ—মিউজিক পজ করা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }.start()
            Toast.makeText(context, "$minutes মিনিট পর মিউজিক পজ হবে", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleFavorite(songId: Long) {
        val idStr = songId.toString()
        val updated = if (favoriteIds.contains(idStr)) favoriteIds - idStr else favoriteIds + idStr
        favoriteIds = updated
        prefs.edit().putStringSet("fav_ids", updated).apply()
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
    val miniPlayerBg = if (isDarkTheme) Color(0xFF2A1919) else Color(0xFFEBE4E4)

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
                placeholder = { Text("Search ${orderedQueue.size} songs...", color = subColor) },
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

            if (orderedQueue.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedCategory == 1) "কোনো Favorite গান নেই" else "কোনো অডিও পাওয়া যায়নি",
                        color = subColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 132.dp)
                ) {
                    itemsIndexed(orderedQueue, key = { _, item -> item.id }) { index, song ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        val coverArt = rememberEmbeddedAlbumArt(context, song.uri)
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { playSong(song) },
                            leadingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}.", color = subColor, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(iconBoxColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (coverArt != null) {
                                            Image(
                                                bitmap = coverArt.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accentColor)
                                        }
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
                if (currentSong != null) {
                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                } else if (orderedQueue.isNotEmpty()) {
                    playSong(orderedQueue.first())
                }
            },
            containerColor = accentColor,
            contentColor = if (isDarkTheme) Color.Black else Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = if (currentSong != null) 114.dp else 54.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play / Pause",
                modifier = Modifier.size(28.dp)
            )
        }

        currentSong?.let { song ->
            val miniArt = rememberEmbeddedAlbumArt(context, song.uri)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = 48.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(miniPlayerBg)
                    .clickable { showFullPlayer = true }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconBoxColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (miniArt != null) {
                        Image(
                            bitmap = miniArt.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Rounded.Album, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
                    }
                }
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
            val fullArt = rememberEmbeddedAlbumArt(context, song.uri)

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
                        Row {
                            IconButton(onClick = { showSleepDialog = true }) {
                                Icon(Icons.Rounded.Bedtime, contentDescription = "Sleep Timer", tint = Color.White)
                            }
                            IconButton(onClick = { showEqualizer = true }) {
                                Icon(Icons.Rounded.Equalizer, contentDescription = "Equalizer", tint = Color(0xFFFFC107))
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF4A3B2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (fullArt != null) {
                            Image(
                                bitmap = fullArt.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(96.dp))
                        }
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
                            if (orderedQueue.isNotEmpty()) {
                                val idx = orderedQueue.indexOfFirst { it.id == song.id }
                                val prev = if (idx > 0) orderedQueue[idx - 1] else orderedQueue.last()
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
                            if (orderedQueue.isNotEmpty()) {
                                val next = if (shuffleEnabled && orderedQueue.size > 1) {
                                    orderedQueue.filter { it.id != song.id }.random()
                                } else {
                                    val idx = orderedQueue.indexOfFirst { it.id == song.id }
                                    orderedQueue[(idx + 1) % orderedQueue.size]
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

        if (showSleepDialog) {
            AlertDialog(
                onDismissRequest = { showSleepDialog = false },
                title = { Text("Music Sleep Timer") },
                text = {
                    Column {
                        listOf(0 to "Off", 15 to "15 Minutes", 30 to "30 Minutes", 45 to "45 Minutes", 60 to "60 Minutes").forEach { (mins, text) ->
                            TextButton(
                                onClick = { setMusicSleepTimer(mins); showSleepDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(text) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("Cancel") } }
            )
        }

        if (showQueueDialog) {
            AlertDialog(
                onDismissRequest = { showQueueDialog = false },
                title = { Text("Playing Queue (${orderedQueue.size})") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(orderedQueue, key = { _, item -> item.id }) { idx, s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}. ${s.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                    fontWeight = if (s.id == currentSong?.id) FontWeight.Bold else FontWeight.Normal,
                                    color = if (s.id == currentSong?.id) accentColor else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            playSong(s)
                                            showQueueDialog = false
                                        }
                                        .padding(vertical = 6.dp)
                                )
                                IconButton(
                                    onClick = { moveQueueItem(idx, idx - 1) },
                                    enabled = idx > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        tint = if (idx > 0) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.35f)
                                    )
                                }
                                IconButton(
                                    onClick = { moveQueueItem(idx, idx + 1) },
                                    enabled = idx < orderedQueue.lastIndex,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        tint = if (idx < orderedQueue.lastIndex) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.35f)
                                    )
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
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
                        val bands = eq.numberOfBands.toInt()
                        val range = eq.bandLevelRange
                        val bandLevels = remember(eq) {
                            mutableStateListOf<Float>().apply {
                                for (i in 0 until bands) {
                                    add(eq.getBandLevel(i.toShort()).toFloat())
                                }
                            }
                        }
                        var selectedPreset by remember(eq) {
                            mutableIntStateOf(
                                try { eq.currentPreset.toInt() } catch (_: Exception) { -1 }
                            )
                        }
                        var bassStrength by remember(bass) {
                            mutableFloatStateOf(
                                if (bass != null && bass.strengthSupported) bass.roundedStrength.toFloat() else 0f
                            )
                        }

                        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            val presetCount = eq.numberOfPresets.toInt().coerceAtMost(6)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (p in 0 until presetCount) {
                                    val name = eq.getPresetName(p.toShort())
                                    FilterChip(
                                        selected = selectedPreset == p,
                                        onClick = {
                                            try {
                                                eq.usePreset(p.toShort())
                                                selectedPreset = p
                                                for (i in 0 until bands) {
                                                    bandLevels[i] = eq.getBandLevel(i.toShort()).toFloat()
                                                }
                                            } catch (_: Exception) { }
                                        },
                                        label = { Text(name, fontSize = 11.sp) }
                                    )
                                }
                                FilterChip(
                                    selected = selectedPreset == -1,
                                    onClick = { selectedPreset = -1 },
                                    label = { Text("Custom", fontSize = 11.sp) }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            if (bass != null && bass.strengthSupported) {
                                Text("Bass Boost (${(bassStrength / 10).toInt()}%)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Slider(
                                    value = bassStrength,
                                    valueRange = 0f..1000f,
                                    onValueChange = {
                                        bassStrength = it
                                        selectedPreset = -1
                                        bass.setStrength(it.toInt().toShort())
                                    }
                                )
                            }

                            for (i in 0 until bands) {
                                val freqHz = eq.getCenterFreq(i.toShort()) / 1000
                                val freqLabel = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"
                                Text(freqLabel, fontSize = 12.sp)
                                Slider(
                                    value = bandLevels[i],
                                    valueRange = range[0].toFloat()..range[1].toFloat(),
                                    onValueChange = {
                                        bandLevels[i] = it
                                        selectedPreset = -1
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
