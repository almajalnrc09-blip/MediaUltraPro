package com.example.ultramediapro

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

class VideoPlayerActivity : ComponentActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private var sleepTimer: CountDownTimer? = null
    private var isBackgroundAudioMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val videoUriString = intent.getStringExtra("VIDEO_URI") ?: return finish()
        val videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Video"

        val options = ArrayList<String>().apply {
            add("--aout=opensles")
            add("--audio-time-stretch")
        }
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)

        val media = Media(libVLC, Uri.parse(videoUriString))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.media = media
        media.release()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VlcStylePlayerScreen(
                    mediaPlayer = mediaPlayer,
                    videoTitle = videoTitle,
                    onBack = { finish() },
                    onEnterPip = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            enterPictureInPictureMode(
                                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            )
                        }
                    },
                    onPlayAsAudio = {
                        isBackgroundAudioMode = true
                        Toast.makeText(this, "ব্যাকগ্রাউন্ড অডিও মোড চালু হয়েছে", Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true)
                    },
                    onSetSleepTimer = { minutes ->
                        sleepTimer?.cancel()
                        sleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
                            override fun onTick(millisUntilFinished: Long) {}
                            override fun onFinish() {
                                mediaPlayer.pause()
                                finish()
                            }
                        }.start()
                        Toast.makeText(this, "$minutes মিনিট পর ভিডিও বন্ধ হবে", Toast.LENGTH_SHORT).show()
                    },
                    onSelectTracks = { showTrackSelectionDialog() },
                    onShowEqualizer = {
                        Toast.makeText(this, "VLC Hardware Audio Boost (200%) সক্রিয় আছে", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun showTrackSelectionDialog() {
        val audioTracks = mediaPlayer.audioTracks ?: emptyArray()
        val subTracks = mediaPlayer.spuTracks ?: emptyArray()
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        audioTracks.forEach { track ->
            items.add("Audio: ${track.name}")
            actions.add { mediaPlayer.audioTrack = track.id }
        }
        subTracks.forEach { track ->
            items.add("Subtitle: ${track.name}")
            actions.add { mediaPlayer.spuTrack = track.id }
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
        if (!isBackgroundAudioMode && !isInPictureInPictureMode) {
            mediaPlayer.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.cancel()
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVLC.release()
    }
}

@Composable
fun VlcStylePlayerScreen(
    mediaPlayer: MediaPlayer,
    videoTitle: String,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onPlayAsAudio: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onSelectTracks: () -> Unit,
    onShowEqualizer: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showSideMenu by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(1L) }
    var overlayText by remember { mutableStateOf("") }
    var zoomMode by remember { mutableIntStateOf(0) } // 0=Normal, 1=Fill, 2=16:9
    var currentVolume by remember { mutableIntStateOf(100) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // কন্ট্রোল ও সাইড মেনু অটোমেটিক হাইড হওয়া
    LaunchedEffect(showControls, showSideMenu) {
        if (showControls || showSideMenu) {
            delay(4000)
            showControls = false
            showSideMenu = false
        }
    }

    // প্লেব্যাক টাইম আপডেট
    LaunchedEffect(Unit) {
        while (true) {
            if (mediaPlayer.isPlaying) {
                currentTime = mediaPlayer.time
                totalDuration = mediaPlayer.length.coerceAtLeast(1L)
                isPlaying = true
            } else {
                isPlaying = false
            }
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = {
                        if (!isLocked) {
                            showControls = !showControls
                            if (!showControls) showSideMenu = false
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            if (offset.x < size.width / 2) {
                                mediaPlayer.time = (mediaPlayer.time - 10000).coerceAtLeast(0)
                                overlayText = "-10s"
                            } else {
                                mediaPlayer.time = (mediaPlayer.time + 10000).coerceAtMost(mediaPlayer.length)
                                overlayText = "+10s"
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked) {
                detectDragGestures { change, dragAmount ->
                    if (isLocked) return@detectDragGestures
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
                            currentVolume = (currentVolume + delta.toInt()).coerceIn(0, 200)
                            mediaPlayer.volume = currentVolume
                            overlayText = "Volume: $currentVolume%"
                        }
                    }
                }
            }
    ) {
        // VLC ভিডিও সারফেস
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    mediaPlayer.attachViews(this, null, false, false)
                    mediaPlayer.play()
                }
            }
        )

        // ওভারলে টেক্সট (ভলিউম/ব্রাইটনেস/স্কিপ)
        if (overlayText.isNotEmpty()) {
            LaunchedEffect(overlayText) {
                delay(1200)
                overlayText = ""
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(overlayText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // লক অবস্থায় আনলক বাটন
        if (isLocked) {
            IconButton(
                onClick = { isLocked = false; showControls = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .background(Color(0x88000000), CircleShape)
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = "Unlock", tint = Color(0xFFFF8800))
            }
        }

        // মেইন কন্ট্রোল (উপরে ও নিচে)
        AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // উপরের বার (টাইটেল)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = videoTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // নিচের বার (VLC স্ক্রিনশটের মতো কমলা স্লাইডার ও বাটনসমূহ)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMillis(currentTime), color = Color.White, fontSize = 12.sp)
                        Text(formatMillis(totalDuration), color = Color.White, fontSize = 12.sp)
                    }

                    Slider(
                        value = (currentTime.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { mediaPlayer.time = (it * totalDuration).toLong() },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF8800),
                            activeTrackColor = Color(0xFFFF8800)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // অডিও/সাবটাইটেল ট্র্যাক ও জুম মোড
                        Row {
                            IconButton(onClick = onSelectTracks) {
                                Icon(Icons.Rounded.Subtitles, contentDescription = "Tracks", tint = Color.White)
                            }
                            IconButton(onClick = {
                                zoomMode = (zoomMode + 1) % 3
                                when (zoomMode) {
                                    0 -> { mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT; overlayText = "Normal" }
                                    1 -> { mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_FILL; overlayText = "Fill" }
                                    2 -> { mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_16_9; overlayText = "16:9" }
                                }
                            }) {
                                Icon(Icons.Rounded.AspectRatio, contentDescription = "Zoom", tint = Color.White)
                            }
                        }

                        // মাঝে প্লে/পজ বাটন
                        IconButton(
                            onClick = {
                                if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        // ডানে স্ক্রিন রোটেট ও থ্রি-ডট মেনু
                        Row {
                            IconButton(onClick = {
                                val act = context as ComponentActivity
                                act.requestedOrientation =
                                    if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }) {
                                Icon(Icons.Rounded.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                            }
                            IconButton(onClick = { showSideMenu = !showSideMenu }) {
                                Icon(Icons.Rounded.MoreHoriz, contentDescription = "More", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ডানপাশের VLC সাইড মেনু (স্ক্রিনশট ৩-এর হুবহু অপশনসমূহ)
        AnimatedVisibility(
            visible = showSideMenu && !isLocked,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Color(0xDD1E1A17))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                SideMenuItem(Icons.Rounded.LockOpen, "Lock") { isLocked = true; showSideMenu = false }
                SideMenuItem(Icons.Rounded.Bedtime, "Sleep timer") { onSetSleepTimer(30); showSideMenu = false }
                SideMenuItem(Icons.Rounded.Speed, "Playback speed (${playbackSpeed}x)") {
                    playbackSpeed = if (playbackSpeed >= 2.0f) 0.5f else playbackSpeed + 0.25f
                    mediaPlayer.rate = playbackSpeed
                }
                SideMenuItem(Icons.Rounded.HistoryToggleOff, "Jump to Time (+1m)") {
                    mediaPlayer.time = (mediaPlayer.time + 60000).coerceAtMost(mediaPlayer.length)
                }
                SideMenuItem(Icons.Rounded.Tune, "Equalizer") { onShowEqualizer(); showSideMenu = false }
                SideMenuItem(Icons.Rounded.Headphones, "Play as audio") { onPlayAsAudio(); showSideMenu = false }
                SideMenuItem(Icons.Rounded.PictureInPictureAlt, "Pop-Up player") { onEnterPip(); showSideMenu = false }
            }
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
    val totalSecs = ms / 1000
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs)
    else String.format("%02d:%02d", mins, secs)
}
