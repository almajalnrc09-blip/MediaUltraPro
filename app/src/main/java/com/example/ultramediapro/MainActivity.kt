package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import android.view.WindowInsetsController
import android.view.WindowManager
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class YouTubeJsBridge(private val onStateChanged: (playing: Boolean, wantPlay: Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportState(playing: Boolean, wantPlay: Boolean) {
        mainHandler.post { onStateChanged(playing, wantPlay) }
    }
}

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableIntStateOf(0)
    private var isPipMode by mutableStateOf(false)
    private var isMusicFullPlayerOpen by mutableStateOf(false)
    private var lastIsDarkTheme: Boolean = false
    private var isAppInBackground: Boolean = false
    @Volatile var isYouTubePlaying: Boolean = false
    @Volatile var userWantsYouTubePlay: Boolean = false
    var activeWebView: BackgroundWebView? = null
    var customFullscreenView by mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }

    private val ytActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                YouTubeBackgroundService.ACTION_YT_TOGGLE_PLAY -> {
                    activeWebView?.onResume()
                    activeWebView?.resumeTimers()
                    activeWebView?.evaluateJavascript(
                        "if (window.__ultraTogglePlay) { window.__ultraTogglePlay(); }",
                        null
                    )
                }
                YouTubeBackgroundService.ACTION_YT_STOP_BG -> {
                    activeWebView?.evaluateJavascript(
                        "if (window.__ultraForcePause) { window.__ultraForcePause(); }",
                        null
                    )
                    isYouTubePlaying = false
                    userWantsYouTubePlay = false
                    abandonYouTubeAudioFocus()
                    stopService(Intent(this@MainActivity, YouTubeBackgroundService::class.java))
                    // Stop চাপলে PiP উইন্ডোটিও সাথে সাথে বন্ধ হয়ে যাবে
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                        moveTaskToBack(true)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val filter = IntentFilter().apply {
            addAction(YouTubeBackgroundService.ACTION_YT_TOGGLE_PLAY)
            addAction(YouTubeBackgroundService.ACTION_YT_STOP_BG)
        }
        ContextCompat.registerReceiver(this, ytActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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
                    isPipMode = isPipMode,
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

    private fun requestYouTubeAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(audioFocusListener)
                        .build()
                }
                audioFocusRequest?.let { am.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) { }
    }

    private fun abandonYouTubeAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Exception) { }
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

    private fun buildYoutubePipParams(): PictureInPictureParams {
        val toggleIntent = Intent(YouTubeBackgroundService.ACTION_YT_TOGGLE_PLAY).setPackage(packageName)
        val togglePendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isYouTubePlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (isYouTubePlaying) "Pause" else "Play"
        val action = RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, iconRes),
            label,
            label,
            togglePendingIntent
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .build()
    }

    fun updatePipParamsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            try {
                setPictureInPictureParams(buildYoutubePipParams())
            } catch (_: Exception) { }
        }
    }

    fun onYouTubeStateUpdated(playing: Boolean, wantPlay: Boolean) {
        val changed = isYouTubePlaying != playing
        isYouTubePlaying = playing
        userWantsYouTubePlay = wantPlay
        if (changed) {
            updatePipParamsIfNeeded()
            if (isAppInBackground || isPipMode) {
                startOrUpdateYouTubeNotification(playing)
            }
        }
    }

    fun startOrUpdateYouTubeNotification(playing: Boolean) {
        try {
            val intent = Intent(this, YouTubeBackgroundService::class.java).apply {
                putExtra("IS_PLAYING", playing)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isPipMode) {
            applySystemBarAppearance(lastIsDarkTheme)
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        if (!isPipMode) {
            stopService(Intent(this, YouTubeBackgroundService::class.java))
            activeWebView?.evaluateJavascript("window.__ultraInBg = false;", null)
        }
        activeWebView?.onResume()
        activeWebView?.resumeTimers()
        applySystemBarAppearance(lastIsDarkTheme)
    }

    override fun onPause() {
        super.onPause()
        isAppInBackground = true
        if (selectedTab == 0 && (isYouTubePlaying || userWantsYouTubePlay || customFullscreenView != null)) {
            requestYouTubeAudioFocus()
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
            activeWebView?.evaluateJavascript(
                "window.__ultraInBg = true; if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();",
                null
            )
            startOrUpdateYouTubeNotification(isYouTubePlaying || userWantsYouTubePlay)
        }
    }

    override fun onStop() {
        super.onStop()
        isAppInBackground = true
        if (selectedTab == 0 && (isYouTubePlaying || userWantsYouTubePlay || customFullscreenView != null)) {
            requestYouTubeAudioFocus()
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
            activeWebView?.evaluateJavascript(
                "window.__ultraInBg = true; if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();",
                null
            )
            startOrUpdateYouTubeNotification(isYouTubePlaying || userWantsYouTubePlay)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (selectedTab == 0 && (isYouTubePlaying || userWantsYouTubePlay || customFullscreenView != null) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestYouTubeAudioFocus()
            activeWebView?.onResume()
            activeWebView?.resumeTimers()
            activeWebView?.evaluateJavascript("window.__ultraInBg = true;", null)
            if (customFullscreenView == null) {
                // কালো লেয়ার না বসিয়ে শুধু হেডার/কমেন্ট হাইড করে পরিষ্কার ভিডিও ফ্রেম PiP-তে আনা
                activeWebView?.evaluateJavascript(
                    """
                    (function() {
                        var s = document.getElementById('ultra-pip-css');
                        if (!s) {
                            s = document.createElement('style');
                            s.id = 'ultra-pip-css';
                            s.innerHTML = 'ytm-mobile-topbar-renderer, #header-bar, .watch-below-the-player, ytm-pivot-bar-renderer, .related-items-container, ytm-item-section-renderer, .player-controls-content, ytm-custom-control, .ytp-chrome-top, .ytp-chrome-bottom, .ytp-pause-overlay { display: none !important; } ' +
                                'html, body { margin: 0 !important; padding: 0 !important; overflow: hidden !important; background: #000 !important; } ' +
                                '#player-container-id { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-height: 100vh !important; margin: 0 !important; padding: 0 !important; } ' +
                                '.html5-video-player, .html5-video-container { width: 100% !important; height: 100% !important; background: transparent !important; } ' +
                                'video.html5-main-video, video { width: 100% !important; height: 100% !important; top: 0 !important; left: 0 !important; object-fit: contain !important; }';
                            document.head.appendChild(s);
                        }
                        window.scrollTo(0, 0);
                        if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();
                    })();
                    """.trimIndent(), null
                )
            } else {
                activeWebView?.evaluateJavascript(
                    "if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();",
                    null
                )
            }
            try {
                enterPictureInPictureMode(buildYoutubePipParams())
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
                "window.__ultraInBg = true; if (window.__ultraEnsurePlay) window.__ultraEnsurePlay();",
                null
            )
            startOrUpdateYouTubeNotification(isYouTubePlaying || userWantsYouTubePlay)
        } else {
            activeWebView?.evaluateJavascript(
                "window.__ultraInBg = false; var s = document.getElementById('ultra-pip-css'); if (s) s.remove();",
                null
            )
            if (customFullscreenView == null) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            applySystemBarAppearance(lastIsDarkTheme)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(ytActionReceiver) } catch (_: Exception) { }
        abandonYouTubeAudioFocus()
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
    isPipMode: Boolean,
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
                if (!isPipMode && selectedTab != 0 && !isMusicFullPlayerOpen && fullscreenView == null) {
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
                // YouTube-এর নিচে ঠিক 41dp জায়গা রাখা হয়েছে যাতে Home/Shorts/You ফ্লোটিং বারের গা ঘেঁষে থাকে
                Box(
                    modifier = if (selectedTab == 0) {
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .then(
                                if (!isPipMode && fullscreenView == null) {
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

        // আল্ট্রা-স্লিম 36dp ফিক্সড ফ্লোটিং পিল নেভিগেশন বার
        if (!isPipMode && !isMusicFullPlayerOpen && fullscreenView == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 4.dp),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                color = if (isDarkTheme) Color(0xE6221616) else Color(0xF5FFFFFF)
            ) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        // YouTube ফুলস্ক্রিন ওভারলে
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

                if (!isPipMode) {
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
}

@Composable
fun CompactTabIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 3.dp),
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
                document.hasFocus = function() { return true; };

                var stopEvt = function(e) { e.stopImmediatePropagation(); };
                ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'blur'].forEach(function(evt) {
                    window.addEventListener(evt, stopEvt, true);
                    document.addEventListener(evt, stopEvt, true);
                });

                var hideStyle = document.getElementById('ultra-open-app-hide');
                if (!hideStyle) {
                    hideStyle = document.createElement('style');
                    hideStyle.id = 'ultra-open-app-hide';
                    hideStyle.innerHTML = 'ytm-open-app-button, .open-app-button, a[aria-label="Open App"], button[aria-label="Open App"], ytm-promoted-sparkles-web-renderer { display: none !important; }';
                    document.head.appendChild(hideStyle);
                }

                if (!window.__ultraTrackerInstalled) {
                    window.__ultraTrackerInstalled = true;
                    window.__ultraWantPlay = false;
                    window.__ultraInBg = false;
                    window.__ultraLastTouch = 0;

                    document.addEventListener('touchstart', function() { window.__ultraLastTouch = Date.now(); }, true);
                    document.addEventListener('click', function() { window.__ultraLastTouch = Date.now(); }, true);

                    window.__ultraReport = function() {
                        var v = document.querySelector('video');
                        var playing = !!(v && !v.paused && !v.ended && v.readyState > 1);
                        if (window.UltraBridge) {
                            window.UltraBridge.reportState(playing, !!window.__ultraWantPlay);
                        }
                    };

                    window.__ultraEnsurePlay = function() {
                        var v = document.querySelector('video');
                        if (v && window.__ultraWantPlay && v.paused && !v.ended) {
                            v.play().catch(function(){});
                        }
                        setTimeout(function() {
                            var v2 = document.querySelector('video');
                            if (v2 && window.__ultraWantPlay && v2.paused && !v2.ended) {
                                v2.play().catch(function(){});
                            }
                            window.__ultraReport();
                        }, 250);
                    };

                    window.__ultraTogglePlay = function() {
                        var v = document.querySelector('video');
                        if (!v) return;
                        if (v.paused) {
                            window.__ultraWantPlay = true;
                            v.play().catch(function(){});
                        } else {
                            window.__ultraWantPlay = false;
                            v.pause();
                        }
                        setTimeout(window.__ultraReport, 120);
                    };

                    window.__ultraForcePause = function() {
                        window.__ultraWantPlay = false;
                        var v = document.querySelector('video');
                        if (v) v.pause();
                        window.__ultraReport();
                    };

                    function hookVideo(v) {
                        if (!v || v.__ultraHooked) return;
                        v.__ultraHooked = true;
                        v.addEventListener('play', function() {
                            window.__ultraWantPlay = true;
                            window.__ultraReport();
                        });
                        v.addEventListener('pause', function() {
                            var userTappedRecently = (Date.now() - window.__ultraLastTouch) < 700;
                            if (userTappedRecently && !window.__ultraInBg) {
                                window.__ultraWantPlay = false;
                            } else if (window.__ultraInBg && window.__ultraWantPlay && !v.ended) {
                                setTimeout(function() {
                                    if (window.__ultraInBg && window.__ultraWantPlay && v.paused && !v.ended) {
                                        v.play().catch(function(){});
                                    }
                                }, 150);
                            }
                            window.__ultraReport();
                        });
                        v.addEventListener('ended', function() {
                            window.__ultraWantPlay = false;
                            window.__ultraReport();
                        });
                    }

                    setInterval(function() {
                        document.querySelectorAll('a, button, ytm-button-renderer, yt-button-shape, c3-material-button').forEach(function(el) {
                            var txt = (el.textContent || el.getAttribute('aria-label') || '').trim().toLowerCase();
                            var href = (el.getAttribute('href') || '').toLowerCase();
                            if (txt === 'open app' || txt.indexOf('open app') !== -1 || href.indexOf('redirect_app_store') !== -1 || href.indexOf('intent://') === 0) {
                                var wrap = el.closest('ytm-button-renderer, c3-material-button, yt-button-shape') || el;
                                wrap.style.setProperty('display', 'none', 'important');
                            }
                        });
                        var v = document.querySelector('video');
                        hookVideo(v);
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
                YouTubeJsBridge { playing, wantPlay ->
                    activity.onYouTubeStateUpdated(playing, wantPlay)
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
                // স্ট্যান্ডার্ড মোবাইল Chrome-এর মতো User-Agent (wv ফ্ল্যাগ বাদ)
                userAgentString = userAgentString.replace("; wv", "")
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
        future.addListener({ mediaController = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose { mediaController?.release() }
    }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, false).collect {
            songs = MediaScanner.scanAudio(context)
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
                isPlaying = ctrl.isPlaying
                currentPos = ctrl.currentPosition.coerceAtLeast(0L)
                totalDur = ctrl.duration.coerceAtLeast(1L)
            }
            delay(400)
        }
    }

    fun stopAndDismissMiniPlayer() {
        mediaController?.stop()
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
