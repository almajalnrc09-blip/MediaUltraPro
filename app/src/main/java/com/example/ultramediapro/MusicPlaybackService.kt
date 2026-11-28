package com.example.ultramediapro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession as Media3Session
import androidx.media3.session.MediaSessionService

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: Media3Session? = null

    companion object {
        var equalizer: Equalizer? = null
        var bassBoost: BassBoost? = null
        private var activeSessionId: Int = C.AUDIO_SESSION_ID_UNSET

        fun initAudioEffects(sessionId: Int) {
            if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
            if (activeSessionId == sessionId && equalizer != null) return
            try {
                equalizer?.release()
                bassBoost?.release()
                activeSessionId = sessionId
                equalizer = Equalizer(0, sessionId).apply { enabled = true }
                bassBoost = BassBoost(0, sessionId).apply { enabled = true }
            } catch (_: Exception) { }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        initAudioEffects(player.audioSessionId)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initAudioEffects(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) initAudioEffects(player.audioSessionId)
            }
        })

        mediaSession = Media3Session.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: Media3Session.ControllerInfo): Media3Session? = mediaSession

    override fun onDestroy() {
        equalizer?.release()
        bassBoost?.release()
        equalizer = null
        bassBoost = null
        activeSessionId = C.AUDIO_SESSION_ID_UNSET
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

// নেটিভ MediaSession + Custom Close (×) বাটন + WakeLock-সহ YouTube ব্যাকগ্রাউন্ড সার্ভিস
class YouTubeBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "yt_bg_media_v3"
        const val NOTIFICATION_ID = 202
        const val ACTION_START_OR_UPDATE = "com.example.ultramediapro.YT_UPDATE"
        const val ACTION_YT_TOGGLE_PLAY = "com.example.ultramediapro.YT_TOGGLE_PLAY"
        const val ACTION_YT_PLAY = "com.example.ultramediapro.YT_PLAY"
        const val ACTION_YT_PAUSE = "com.example.ultramediapro.YT_PAUSE"
        const val ACTION_YT_STOP_BG = "com.example.ultramediapro.YT_STOP_BG"
        const val EXTRA_IS_PLAYING = "IS_PLAYING"
        const val EXTRA_TITLE = "VIDEO_TITLE"
        const val EXTRA_POSITION_MS = "POS_MS"
        const val EXTRA_DURATION_MS = "DUR_MS"

        @Volatile
        var onCommand: ((String) -> Unit)? = null

        @Volatile
        var onSeekCommand: ((Long) -> Unit)? = null

        @Volatile
        var isRunning: Boolean = false
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle: String = "YouTube"
    private var currentlyPlaying: Boolean = true
    private var currentPositionMs: Long = 0L
    private var currentDurationMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        mediaSession = MediaSession(this, "MediaUltraProYouTube").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    currentlyPlaying = true
                    acquireWakeLock()
                    showMediaNotification()
                    onCommand?.invoke(ACTION_YT_PLAY)
                }

                override fun onPause() {
                    currentlyPlaying = false
                    releaseWakeLock()
                    showMediaNotification()
                    onCommand?.invoke(ACTION_YT_PAUSE)
                }

                override fun onStop() {
                    handleStopAndExit()
                }

                override fun onSeekTo(pos: Long) {
                    currentPositionMs = pos.coerceAtLeast(0L)
                    showMediaNotification()
                    onSeekCommand?.invoke(currentPositionMs)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == ACTION_YT_STOP_BG) {
                        handleStopAndExit()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE

        when (action) {
            ACTION_YT_TOGGLE_PLAY -> {
                currentlyPlaying = !currentlyPlaying
                if (currentlyPlaying) acquireWakeLock() else releaseWakeLock()
                showMediaNotification()
                onCommand?.invoke(if (currentlyPlaying) ACTION_YT_PLAY else ACTION_YT_PAUSE)
                return START_NOT_STICKY
            }
            ACTION_YT_PLAY -> {
                currentlyPlaying = true
                acquireWakeLock()
                showMediaNotification()
                onCommand?.invoke(ACTION_YT_PLAY)
                return START_NOT_STICKY
            }
            ACTION_YT_PAUSE -> {
                currentlyPlaying = false
                releaseWakeLock()
                showMediaNotification()
                onCommand?.invoke(ACTION_YT_PAUSE)
                return START_NOT_STICKY
            }
            ACTION_YT_STOP_BG -> {
                handleStopAndExit()
                return START_NOT_STICKY
            }
            else -> {
                currentlyPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true
                val passedTitle = intent?.getStringExtra(EXTRA_TITLE)?.trim()
                if (!passedTitle.isNullOrEmpty()) {
                    currentTitle = passedTitle
                }
                val pos = intent?.getLongExtra(EXTRA_POSITION_MS, -1L) ?: -1L
                val dur = intent?.getLongExtra(EXTRA_DURATION_MS, -1L) ?: -1L
                if (pos >= 0L) currentPositionMs = pos
                if (dur > 0L) currentDurationMs = dur

                if (currentlyPlaying) acquireWakeLock() else releaseWakeLock()
                showMediaNotification()
                return START_STICKY
            }
        }
    }

    private fun handleStopAndExit() {
        isRunning = false
        currentlyPlaying = false
        onCommand?.invoke(ACTION_YT_STOP_BG)
        releaseWakeLock()
        try {
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            mediaSession?.isActive = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) { }
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaUltraPro::YouTubeAudioLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) { }
    }

    private fun showMediaNotification() {
        if (!isRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Background Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "MediaUltraPro • YouTube")
        if (currentDurationMs > 0L) {
            metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
        }
        mediaSession?.setMetadata(metaBuilder.build())

        // Android 13/14/15 মিডিয়া কার্ডে সরাসরি ক্রস (×) ক্লোজ বাটন দেখানোর জন্য CustomAction
        val closeCustomAction = PlaybackState.CustomAction.Builder(
            ACTION_YT_STOP_BG,
            "Close",
            android.R.drawable.ic_menu_close_clear_cancel
        ).build()

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO
                )
                .addCustomAction(closeCustomAction)
                .setState(
                    if (currentlyPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    currentPositionMs,
                    if (currentlyPlaying) 1f else 0f
                )
                .build()
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, YouTubeBackgroundService::class.java).apply {
            action = ACTION_YT_TOGGLE_PLAY
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, YouTubeBackgroundService::class.java).apply {
            action = ACTION_YT_STOP_BG
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIcon = if (currentlyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = if (currentlyPlaying) "Pause" else "Play"
        val subtitle = if (currentlyPlaying) "ব্যাকগ্রাউন্ডে চলছে • খুলতে ট্যাপ করুন" else "পজ করা আছে • খুলতে ট্যাপ করুন"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, toggleIcon),
            toggleLabel,
            togglePendingIntent
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Close",
            stopPendingIntent
        ).build()

        val notification = builder
            .setContentTitle(currentTitle)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(currentlyPlaying)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
