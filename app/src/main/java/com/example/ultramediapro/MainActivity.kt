package com.example.ultramediapro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableIntStateOf(0)
    private var isPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        prefs.edit().putBoolean("dark_mode", isDarkTheme).apply()
                    },
                    isPipMode = isPipMode
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (selectedTab == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isPipMode = isInPictureInPictureMode
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    isPipMode: Boolean
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    Scaffold(
        topBar = {
            if (!isPipMode && selectedTab != 0) {
                TopAppBar(
                    title = {
                        Text(
                            if (selectedTab == 1) "Videos" else "Muzio Player",
                            fontWeight = FontWeight.Bold
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
            if (!isPipMode) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        icon = { Icon(Icons.Rounded.PlayCircle, contentDescription = "YouTube") },
                        label = { Text("YouTube") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = "Videos") },
                        label = { Text("Videos") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { onTabSelected(2) },
                        icon = { Icon(Icons.Rounded.MusicNote, contentDescription = "Music") },
                        label = { Text("Music") }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> YouTubeTabScreen()
                1 -> VideoListTabScreen()
                2 -> MuzioMusicTabScreen()
            }
        }
    }
}

class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE && visibility != View.INVISIBLE) {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeTabScreen() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            BackgroundWebView(ctx).apply {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@apply, true)
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl("https://m.youtube.com")
            }
        }
    )
}

@Composable
fun VideoListTabScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    var videos by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) refreshKey++ }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, true).collect {
            videos = MediaScanner.scanVideos(context)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(videos, key = { it.id }) { video ->
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
                    IconButton(onClick = {
                        MediaScanner.deleteFile(activity, video.uri, deleteLauncher) { refreshKey++ }
                    }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
                    }
                }
            )
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
fun MuzioMusicTabScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    var songs by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var currentSong by remember { mutableStateOf<MediaFile?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(false) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) refreshKey++ }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ mediaController = future.get() }, MoreExecutors.directExecutor())
        onDispose { mediaController?.release() }
    }

    LaunchedEffect(refreshKey) {
        MediaScanner.observeMediaChanges(context, false).collect {
            songs = MediaScanner.scanAudio(context)
        }
    }

    LaunchedEffect(mediaController) {
        while (true) {
            isPlaying = mediaController?.isPlaying == true
            delay(500)
        }
    }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2B1212), Color(0xFF120B0B))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // সার্চ বার ও কাউন্ট (স্ক্রিনশট ৪-এর মতো)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ${songs.size} songs...", color = Color.LightGray) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFFFFC107)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            currentSong = song
                            mediaController?.apply {
                                setMediaItem(MediaItem.fromUri(song.uri))
                                prepare()
                                play()
                            }
                        },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
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
                            Text("${song.artist} • ${formatMillis(song.duration)}", color = Color.Gray, fontSize = 12.sp)
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                MediaScanner.deleteFile(activity, song.uri, deleteLauncher) { refreshKey++ }
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color.Gray)
                            }
                        }
                    )
                }
            }
        }

        // ভাসমান হলুদ শাফেল বাটন (স্ক্রিনশট ৪ ও ৫-এর মতো)
        FloatingActionButton(
            onClick = {
                if (songs.isNotEmpty()) {
                    val randomSong = songs.random()
                    currentSong = randomSong
                    mediaController?.apply {
                        setMediaItem(MediaItem.fromUri(randomSong.uri))
                        prepare()
                        play()
                    }
                }
            },
            containerColor = Color(0xFFFFC107),
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = if (currentSong != null) 84.dp else 20.dp)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle")
        }

        // নিচের মিনি-প্লেয়ার বার (স্ক্রিনশট ৪ ও ৫-এর মতো)
        currentSong?.let { song ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xFF261717))
                    .clickable { showFullPlayer = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Album, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(song.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = {
                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                }) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showEqualizer = true }) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = "Equalizer", tint = Color(0xFFFFC107))
                }
            }
        }

        // ফুলস্ক্রিন গোল্ডেন-ব্রাউন প্লেয়ার ও ইকুলাইজার ডায়ালগ (স্ক্রিনশট ৬-এর মতো)
        if (showFullPlayer && currentSong != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF7A6348)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { showFullPlayer = false }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = Color.White)
                        }
                        Text("Now Playing", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
                        IconButton(onClick = { showEqualizer = true }) {
                            Icon(Icons.Rounded.Equalizer, contentDescription = "EQ", tint = Color.White)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF4A3B2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(100.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isFavorite = !isFavorite }) {
                            Icon(
                                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Fav",
                                tint = Color.White
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(currentSong!!.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentSong!!.artist, color = Color.LightGray, fontSize = 13.sp)
                        }
                        IconButton(onClick = { showEqualizer = true }) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = "EQ", tint = Color.White)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val prevIdx = (songs.indexOf(currentSong) - 1).coerceAtLeast(0)
                            currentSong = songs[prevIdx]
                            mediaController?.apply { setMediaItem(MediaItem.fromUri(songs[prevIdx].uri)); prepare(); play() }
                        }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev", tint = Color.White, modifier = Modifier.size(36.dp))
                        }

                        IconButton(
                            onClick = { if (isPlaying) mediaController?.pause() else mediaController?.play() },
                            modifier = Modifier
                                .size(68.dp)
                                .background(Color(0x44FFFFFF), CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(onClick = {
                            val nextIdx = (songs.indexOf(currentSong) + 1) % songs.size
                            currentSong = songs[nextIdx]
                            mediaController?.apply { setMediaItem(MediaItem.fromUri(songs[nextIdx].uri)); prepare(); play() }
                        }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }
        }

        // ৫-ব্যান্ড হার্ডওয়্যার ইকুলাইজার পপআপ
        if (showEqualizer) {
            AlertDialog(
                onDismissRequest = { showEqualizer = false },
                title = { Text("5-Band Equalizer") },
                text = {
                    val eq = MusicPlaybackService.equalizer
                    if (eq == null) {
                        Text("ইকুলাইজার চালু করতে আগে একটি গান প্লে করুন।")
                    } else {
                        Column {
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
                confirmButton = {
                    TextButton(onClick = { showEqualizer = false }) { Text("Done") }
                }
            )
        }
    }
}
