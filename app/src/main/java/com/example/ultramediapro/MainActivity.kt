package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

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

    // YouTube ফুলস্ক্রিন ভিউ + স্ক্রিন লক ও ব্রাইটনেস/ভলিউম গেসচার
    if (fullscreenView != null) {
        var ytLocked by remember { mutableStateOf(false) }
        var gestureHud by remember { mutableStateOf("") }
        var showFsBar by remember { mutableStateOf(true) }
        val audioManager = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        LaunchedEffect(showFsBar) {
            if (showFsBar) {
                delay(3500)
                showFsBar = false
            }
        }

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

            // লক করা থাকলে বা গেসচার সক্রিয় থাকলে কন্ট্রোল লেয়ার
            if (ytLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { _, _ -> }
                        }
                ) {
                    IconButton(
                        onClick = { ytLocked = false; showFsBar = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .safeDrawingPadding()
                            .padding(14.dp)
                            .background(Color(0x99000000), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Unlock", tint = Color(0xFFFFC107))
                    }
                }
            } else {
                // বাম-ডান edge-এর ওপর ব্রাইটনেস/ভলিউম সোয়াইপ জোন (YouTube-এর নিজস্ব ট্যাপ নষ্ট না করে)
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(0.22f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    if (abs(dragAmount.y) > abs(dragAmount.x)) {
                                        change.consume()
                                        val lp = activity.window.attributes
                                        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        val next = (cur + (-dragAmount.y / size.height)).coerceIn(0.05f, 1.0f)
                                        lp.screenBrightness = next
                                        activity.window.attributes = lp
                                        gestureHud = "Brightness: ${(next * 100).toInt()}%"
                                    }
                                }
                            }
                    )
                    Spacer(modifier = Modifier.weight(0.56f))
                    Box(
                        modifier = Modifier
                            .weight(0.22f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    if (abs(dragAmount.y) > abs(dragAmount.x)) {
                                        change.consume()
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val step = if (dragAmount.y < 0) 1 else -1
                                        val nextVol = (curVol + step).coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                        gestureHud = "Volume: ${(nextVol * 100) / maxVol}%"
                                    }
                                }
                            }
                    )
                }

                // স্ক্রিন লক ও এক্সিট বাটন
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { ytLocked = true },
                        modifier = Modifier.size(38.dp).background(Color(0x88000000), CircleShape)
                    ) {
                        Icon(Icons.Rounded.LockOpen, contentDescription = "Lock Screen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { onHideFullscreen() },
                        modifier = Modifier.size(38.dp).background(Color(0x88000000), CircleShape)
                    ) {
                        Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }

            if (gestureHud.isNotEmpty()) {
                LaunchedEffect(gestureHud) {
                    delay(1000)
                    gestureHud = ""
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(gestureHud, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
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
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf("https://m.youtube.com") }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        if (url != null) currentUrl = url
                        view?.evaluateJavascript(bgScript, null)
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (url != null) currentUrl = url
                        view?.evaluateJavascript(bgScript, null)
                    }
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view != null && callback
