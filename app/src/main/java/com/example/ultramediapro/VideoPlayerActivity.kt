package com.example.ultramediapro

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoPlayerActivity : ComponentActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private var vlcVideoLayout: VLCVideoLayout? = null
    private var currentScaleType: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT
    private var currentPfd: ParcelFileDescriptor? = null
    private var sleepTimer: CountDownTimer? = null
    private var isBackgroundAudioMode = false
    private var isPipMode by mutableStateOf(false)

    private var videoUris: List<String> = emptyList()
    private var videoTitles: List<String> = emptyList()
    private var currentIndex by mutableIntStateOf(0)
    private var currentTitle by mutableStateOf("Video")
    private var currentSavedResumePos by mutableLongStateOf(0L)

    private val resumeKeyPrefix = "resume_pos_"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        hideSystemBars()

        val passedUris = intent.getStringArrayListExtra("VIDEO_URIS")
        val passedTitles = intent.getStringArrayListExtra("VIDEO_TITLES")
        val fallbackUri = intent.getStringExtra("VIDEO_URI")
        val fallbackTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Video"

        if (!passedUris.isNullOrEmpty() && !passedTitles.isNullOrEmpty() && passedUris.size == passedTitles.size) {
            videoUris = passedUris
            videoTitles = passedTitles
            currentIndex = intent.getIntExtra("VIDEO_INDEX", 0).coerceIn(0, videoUris.lastIndex)
        } else if (fallbackUri != null) {
            videoUris = listOf(fallbackUri)
            videoTitles = listOf(fallbackTitle)
            currentIndex = 0
        } else {
            return finish()
        }

        val options = ArrayList<String>().apply {
            add("--aout=opensles")
            add("--audio-time-stretch")
            add("--file-caching=1500")
        }
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)

        prepareMediaAt(currentIndex, autoPlay = false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VlcStylePlayerScreen(
                    mediaPlayer = mediaPlayer,
                    videoTitle = currentTitle,
                    videoIndex = currentIndex,
                    hasMultipleVideos = videoUris.size > 1,
                    savedResumePos = currentSavedResumePos,
                    isPipMode = isPipMode,
                    onLayoutCreated = { layout -> vlcVideoLayout = layout },
                    onScaleChanged = { scale -> currentScaleType = scale },
                    onBack = {
                        saveCurrentResumePosition()
                        finish()
                    },
                    onPrevVideo = {
                        if (videoUris.size > 1) {
                            saveCurrentResumePosition()
                            val prevIdx = if (currentIndex > 0) currentIndex - 1 else videoUris.lastIndex
                            prepareMediaAt(prevIdx, autoPlay = true)
                        } else {
                            Toast.makeText(this, "আর কোনো ভিডিও নেই", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onNextVideo = {
                        if (videoUris.size > 1) {
                            saveCurrentResumePosition()
                            val nextIdx = (currentIndex + 1) % videoUris.size
                            prepareMediaAt(nextIdx, autoPlay = true)
                        } else {
                            Toast.makeText(this, "আর কোনো ভিডিও নেই", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onEnterPip = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try {
                                val entered = enterPictureInPictureMode(
                                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                )
                                if (!entered) {
                                    Toast.makeText(this, "App Info থেকে PiP পারমিশন চালু করুন", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(this, "App Info থেকে PiP পারমিশন চালু করুন", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onPlayAsAudio = {
                        isBackgroundAudioMode = true
                        Toast.makeText(this, "ব্যাকগ্রাউন্ড অডিও মোড চালু হয়েছে", Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true)
                    },
                    onSetSleepTimer = { minutes ->
                        sleepTimer?.cancel()
                        if (minutes == 0) {
                            Toast.makeText(this, "Sleep timer বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
                        } else {
                            sleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
                                override fun onTick(millisUntilFinished: Long) {}
                                override fun onFinish() {
                                    saveCurrentResumePosition()
                                    mediaPlayer.pause()
                                    finish()
                                }
                            }.start()
                            Toast.makeText(this, "$minutes মিনিট পর ভিডিও বন্ধ হবে", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSelectTracks = { showTrackSelectionDialog() }
                )
            }
        }
    }

    private fun prepareMediaAt(index: Int, autoPlay: Boolean) {
        if (index !in videoUris.indices) return
        try {
            mediaPlayer.stop()
            currentPfd?.close()
            currentPfd = null
        } catch (_: Exception) { }

        currentIndex = index
        currentTitle = videoTitles[index]
        val uriString = videoUris[index]
        val prefs = getSharedPreferences("video_resume_prefs", MODE_PRIVATE)
        currentSavedResumePos = prefs.getLong(resumeKeyPrefix + uriString.hashCode(), 0L)

        val uri = Uri.parse(uriString)
        val media = try {
            currentPfd = contentResolver.openFileDescriptor(uri, "r")
            if (currentPfd != null) Media(libVLC, currentPfd!!.fileDescriptor) else Media(libVLC, uri)
        } catch (_: Exception) {
            Media(libVLC, uri)
        }

        media.setHWDecoderEnabled(true, false)
        mediaPlayer.setMedia(media)
        media.release()
        if (autoPlay) {
            mediaPlayer.play()
        }
    }

    private fun saveCurrentResumePosition() {
        val uriString = videoUris.getOrNull(currentIndex) ?: return
        try {
            val pos = mediaPlayer.time
            val len = mediaPlayer.length
            val prefs = getSharedPreferences("video_resume_prefs", MODE_PRIVATE)
            val key = resumeKeyPrefix + uriString.hashCode()
            if (pos > 5000L && (len <= 0L || pos < len - 5000L)) {
                prefs.edit().putLong(key, pos).apply()
            } else {
                prefs.edit().remove(key).apply()
            }
        } catch (_: Exception) { }
    }

    override fun onStart() {
        super.onStart()
        isBackgroundAudioMode = false
        vlcVideoLayout?.let { layout ->
            try {
                if (!mediaPlayer.vlcVout.areViewsAttached()) {
                    mediaPlayer.attachViews(layout, null, false, false)
                    mediaPlayer.setVideoScale(currentScaleType)
                }
            } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode = isInPictureInPictureMode
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isPipMode) hideSystemBars()
    }

    private fun showTrackSelectionDialog() {
        val audioTracks = mediaPlayer.audioTracks ?: emptyArray()
        val subTracks = mediaPlayer.spuTracks ?: emptyArray()
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        audioTracks.forEach { track ->
            val mark = if (track.id == mediaPlayer.audioTrack) " ✓" else ""
            items.add("Audio: ${track.name}$mark")
            actions.add {
                mediaPlayer.setAudioTrack(track.id)
                Toast.makeText(this, "Audio: ${track.name}", Toast.LENGTH_SHORT).show()
            }
        }
        subTracks.forEach { track ->
            val mark = if (track.id == mediaPlayer.spuTrack) " ✓" else ""
            items.add("Subtitle: ${track.name}$mark")
            actions.add {
                mediaPlayer.setSpuTrack(track.id)
                Toast.makeText(this, "Subtitle: ${track.name}", Toast.LENGTH_SHORT).show()
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "কোনো অতিরিক্ত ট্র্যাক পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitle Tracks")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentResumePosition()
        if (!isBackgroundAudioMode && !isInPictureInPictureMode) {
            mediaPlayer.pause()
            try {
                mediaPlayer.detachViews()
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.cancel()
        try {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVLC.release()
            currentPfd?.close()
        } catch (_: Exception) { }
    }
}

@Composable
fun SlimVideoSeekBar(
    currentTime: Long,
    totalDuration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onDragStateChanged: (Boolean) -> Unit
) {
    var barWidthPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val safeTotal = totalDuration.coerceAtLeast(1L)
    val displayFraction = if (isDragging) dragFraction else (currentTime.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    val thumbSizeDp = if (isDragging) 14.dp else 11.dp
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
            .pointerInput(safeTotal) {
                detectTapGestures { offset ->
                    val frac = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                    onSeekCommit((frac * safeTotal).toLong())
                }
            }
            .pointerInput(safeTotal) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        onDragStateChanged(true)
                        val frac = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                        dragFraction = frac
                        onSeekPreview((frac * safeTotal).toLong())
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                        dragFraction = frac
                        onSeekPreview((frac * safeTotal).toLong())
                    },
                    onDragEnd = {
                        val target = (dragFraction * safeTotal).toLong()
                        isDragging = false
                        onDragStateChanged(false)
                        onSeekCommit(target)
                    },
                    onDragCancel = {
                        isDragging = false
                        onDragStateChanged(false)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x55FFFFFF))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(displayFraction)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFFF8800))
        )
        val thumbPx = with(density) { thumbSizeDp.toPx() }
        val offsetX = ((barWidthPx * displayFraction) - thumbPx / 2f)
            .coerceIn(0f, (barWidthPx - thumbPx).coerceAtLeast(0f))
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .size(thumbSizeDp)
                .background(Color(0xFFFF8800), CircleShape)
        )
    }
}

@Composable
fun VlcStylePlayerScreen(
    mediaPlayer: MediaPlayer,
    videoTitle: String,
    videoIndex: Int,
    hasMultipleVideos: Boolean,
    savedResumePos: Long,
    isPipMode: Boolean,
    onLayoutCreated: (VLCVideoLayout) -> Unit,
    onScaleChanged: (MediaPlayer.ScaleType) -> Unit,
    onBack: () -> Unit,
    onPrevVideo: () -> Unit,
    onNextVideo: () -> Unit,
    onEnterPip: () -> Unit,
    onPlayAsAudio: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onSelectTracks: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var orientationBeforeLock by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR) }
    var showUnlockButton by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showSideMenu by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(1L) }
    var isSeeking by remember { mutableStateOf(false) }
    var overlayText by remember { mutableStateOf("") }
    var zoomMode by remember { mutableIntStateOf(0) }
    var currentVolume by remember { mutableIntStateOf(mediaPlayer.volume.coerceIn(0, 100)) }
    var resumeApplied by remember(videoIndex) { mutableStateOf(false) }

    val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    var speedIdx by remember { mutableIntStateOf(2) }
    val currentSpeed = speedSteps[speedIdx]
    val speedLabel = when {
        currentSpeed == 1.0f -> "1.0x"
        currentSpeed > 1.0f -> "+${currentSpeed}x"
        else -> "-${currentSpeed}x"
    }

    // লক অবস্থায় ভুল করে ব্যাক চাপলেও ভিডিও বন্ধ হবে না
    BackHandler(enabled = isLocked && !isPipMode) {
        showUnlockButton = true
    }

    LaunchedEffect(isPipMode) {
        if (isPipMode) {
            showControls = false
            showSideMenu = false
            showUnlockButton = false
        }
    }

    LaunchedEffect(isLocked, showUnlockButton) {
        if (isLocked && showUnlockButton) {
            delay(3000)
            showUnlockButton = false
        }
    }

    LaunchedEffect(showControls, showSideMenu, isSeeking) {
        if (showControls && !showSideMenu && !isSeeking) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(videoIndex, savedResumePos) {
        while (true) {
            isPlaying = mediaPlayer.isPlaying
            val t = mediaPlayer.time
            val len = mediaPlayer.length
            if (!isSeeking && t >= 0) currentTime = t
            if (len > 0) totalDuration = len
            if (!resumeApplied && savedResumePos > 5000L && len > savedResumePos + 3000L) {
                resumeApplied = true
                mediaPlayer.setTime(savedResumePos)
                overlayText = "Resumed ${formatMillis(savedResumePos)}"
            }
            delay(400)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked, isPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isPipMode) return@detectTapGestures
                        if (isLocked) {
                            showUnlockButton = !showUnlockButton
                        } else {
                            if (showSideMenu) showSideMenu = false else showControls = !showControls
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isLocked && !isPipMode) {
                            if (offset.x < size.width / 2) {
                                mediaPlayer.setTime((mediaPlayer.time - 10000).coerceAtLeast(0))
                                overlayText = "-10s"
                            } else {
                                mediaPlayer.setTime((mediaPlayer.time + 10000).coerceAtMost(mediaPlayer.length))
                                overlayText = "+10s"
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked, isPipMode) {
                detectDragGestures { change, dragAmount ->
                    if (isLocked || isPipMode) return@detectDragGestures
                    change.consume()
                    if (abs(dragAmount.y) > abs(dragAmount.x)) {
                        val delta = (-dragAmount.y / size.height) * 100
                        if (change.position.x < size.width / 2) {
                            val activity = context as ComponentActivity
                            val lp = activity.window.attributes
                            val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                            val next = (cur + (-dragAmount.y / size.height)).coerceIn(0.01f, 1.0f)
                            lp.screenBrightness = next
                            activity.window.attributes = lp
                            overlayText = "Brightness: ${(next * 100).toInt()}%"
                        } else {
                            currentVolume = (currentVolume + delta.toInt()).coerceIn(0, 100)
                            mediaPlayer.setVolume(currentVolume)
                            overlayText = "Volume: $currentVolume%"
                        }
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    onLayoutCreated(this)
                    mediaPlayer.attachViews(this, null, false, false)
                    mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                    post { mediaPlayer.play() }
                }
            }
        )

        if (overlayText.isNotEmpty() && !isPipMode) {
            LaunchedEffect(overlayText, isSeeking) {
                if (!isSeeking) {
                    delay(1200)
                    overlayText = ""
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(overlayText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // আনলক বাটন: ট্যাপ করলে টাচ ও ওরিয়েন্টেশন দুটোই আনলক হবে
        AnimatedVisibility(
            visible = isLocked && showUnlockButton && !isPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 24.dp)
        ) {
            Surface(
                onClick = {
                    val act = context as ComponentActivity
                    act.requestedOrientation = orientationBeforeLock
                    isLocked = false
                    showUnlockButton = false
                    showControls = true
                    overlayText = "Unlocked"
                },
                shape = RoundedCornerShape(24.dp),
                color = Color(0xBB141414)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Unlock", tint = Color(0xFFFF8800), modifier = Modifier.size(20.dp))
                    Text("TAP TO UNLOCK", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(visible = showControls && !isLocked && !isPipMode, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        text = videoTitle,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMillis(currentTime), color = Color.White, fontSize = 11.sp)
                        Text(formatMillis(totalDuration), color = Color.White, fontSize = 11.sp)
                    }

                    SlimVideoSeekBar(
                        currentTime = currentTime,
                        totalDuration = totalDuration,
                        onSeekPreview = { preview ->
                            currentTime = preview
                            overlayText = formatMillis(preview)
                        },
                        onSeekCommit = { target ->
                            currentTime = target
                            mediaPlayer.setTime(target)
                            overlayText = ""
                        },
                        onDragStateChanged = { dragging ->
                            isSeeking = dragging
                            if (!dragging) overlayText = ""
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onSelectTracks, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = {
                            zoomMode = (zoomMode + 1) % 4
                            val nextScale = when (zoomMode) {
                                0 -> { overlayText = "Best Fit"; MediaPlayer.ScaleType.SURFACE_BEST_FIT }
                                1 -> { overlayText = "Fit Screen"; MediaPlayer.ScaleType.SURFACE_FIT_SCREEN }
                                2 -> { overlayText = "Full Screen (Fill)"; MediaPlayer.ScaleType.SURFACE_FILL }
                                else -> { overlayText = "16:9"; MediaPlayer.ScaleType.SURFACE_16_9 }
                            }
                            mediaPlayer.setVideoScale(nextScale)
                            onScaleChanged(nextScale)
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Zoom", tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        IconButton(
                            onClick = onPrevVideo,
                            enabled = hasMultipleVideos,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous Video",
                                tint = if (hasMultipleVideos) Color.White else Color.Gray,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (mediaPlayer.isPlaying) {
                                    mediaPlayer.pause()
                                    isPlaying = false
                                } else {
                                    mediaPlayer.play()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(
                            onClick = onNextVideo,
                            enabled = hasMultipleVideos,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Next Video",
                                tint = if (hasMultipleVideos) Color.White else Color.Gray,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(onClick = {
                            val act = context as ComponentActivity
                            val isNowLandscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (isNowLandscape) {
                                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                overlayText = "Portrait"
                            } else {
                                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                overlayText = "Landscape"
                            }
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.ScreenRotation, contentDescription = "Rotate", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { showSideMenu = !showSideMenu }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "More", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSideMenu && !isLocked && !isPipMode,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(245.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(Color(0xEE1E1A17))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Lock চাপলে বর্তমান ওরিয়েন্টেশন + টাচ দুটোই লক হয়ে যাবে
                SideMenuItem(Icons.Rounded.LockOpen, "Lock") {
                    val act = context as ComponentActivity
                    orientationBeforeLock = act.requestedOrientation
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    isLocked = true
                    showUnlockButton = true
                    showSideMenu = false
                    showControls = false
                    overlayText = "Locked"
                }
                SideMenuItem(Icons.Rounded.Bedtime, "Sleep timer") { showSleepDialog = true; showSideMenu = false }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = speedLabel,
                            color = if (currentSpeed == 1.0f) Color.White else Color(0xFFFF8800),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                speedIdx = 2
                                mediaPlayer.setRate(1.0f)
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (speedIdx > 0) {
                                    speedIdx--
                                    mediaPlayer.setRate(speedSteps[speedIdx])
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Slower", tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                if (speedIdx < speedSteps.lastIndex) {
                                    speedIdx++
                                    mediaPlayer.setRate(speedSteps[speedIdx])
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Faster", tint = Color.White)
                        }
                    }
                }

                SideMenuItem(Icons.Rounded.HistoryToggleOff, "Jump to Time") { showJumpDialog = true; showSideMenu = false }
                SideMenuItem(Icons.Rounded.Headphones, "Play as audio") { onPlayAsAudio(); showSideMenu = false }
                SideMenuItem(Icons.Rounded.PictureInPictureAlt, "Pop-Up player") { showSideMenu = false; onEnterPip() }
            }
        }

        if (showSleepDialog && !isPipMode) {
            AlertDialog(
                onDismissRequest = { showSleepDialog = false },
                title = { Text("Sleep Timer") },
                text = {
                    Column {
                        listOf(0 to "Off", 15 to "15 Minutes", 30 to "30 Minutes", 45 to "45 Minutes", 60 to "60 Minutes").forEach { (mins, text) ->
                            TextButton(
                                onClick = { onSetSleepTimer(mins); showSleepDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(text) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSleepDialog = false }) { Text("Cancel") } }
            )
        }

        if (showJumpDialog && !isPipMode) {
            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                title = { Text("Jump to Time") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { mediaPlayer.setTime((mediaPlayer.time - 60000).coerceAtLeast(0)) }) { Text("-1m") }
                            Button(onClick = { mediaPlayer.setTime((mediaPlayer.time + 60000).coerceAtMost(mediaPlayer.length)) }) { Text("+1m") }
                        }
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { mediaPlayer.setTime((mediaPlayer.time - 300000).coerceAtLeast(0)) }) { Text("-5m") }
                            Button(onClick = { mediaPlayer.setTime((mediaPlayer.time + 300000).coerceAtMost(mediaPlayer.length)) }) { Text("+5m") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showJumpDialog = false }) { Text("Done") } }
            )
        }
    }
}

@Composable
fun SideMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

fun formatMillis(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs)
    else String.format("%02d:%02d", mins, secs)
}
