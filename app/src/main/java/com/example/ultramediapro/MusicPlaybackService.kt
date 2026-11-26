package com.example.ultramediapro

import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        var equalizer: Equalizer? = null
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters().setAudioOffloadPreferences(
                    DefaultTrackSelector.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(DefaultTrackSelector.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .build()
                )
            )
        }

        val player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                try {
                    equalizer?.release()
                    equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
                } catch (_: Exception) { }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        equalizer?.release()
        equalizer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
