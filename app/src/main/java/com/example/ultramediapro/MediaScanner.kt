package com.example.ultramediapro

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class MediaFile(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val duration: Long,
    val sizeMb: Double
)

object MediaScanner {

    fun observeMediaChanges(context: Context, isVideo: Boolean): Flow<Unit> = callbackFlow {
        val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    suspend fun scanVideos(context: Context): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    }

    suspend fun scanAudio(context: Context): List<MediaFile> = withContext(Dispatchers.IO) {
        queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false)
    }

    private fun queryMediaStore(context: Context, collection: Uri, isVideo: Boolean): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = if (isVideo) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.SIZE
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.SIZE
            )
        }
        val sortOrder = if (isVideo) "${MediaStore.MediaColumns.DATE_ADDED} DESC" else "${MediaStore.MediaColumns.TITLE} ASC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val artistCol = if (!isVideo) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
            val albumCol = if (!isVideo) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown"
                val rawArtist = if (artistCol != -1) cursor.getString(artistCol) else ""
                val artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Unknown Artist" else rawArtist
                val rawAlbum = if (albumCol != -1) cursor.getString(albumCol) else ""
                val album = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") "Unknown Album" else rawAlbum
                val dur = cursor.getLong(durCol)
                val sizeMb = String.format("%.1f", cursor.getLong(sizeCol) / (1024.0 * 1024.0)).toDouble()
                list.add(MediaFile(id, name, artist, album, ContentUris.withAppendedId(collection, id), dur, sizeMb))
            }
        }
        return list
    }

    fun renameFile(
        activity: Activity,
        uri: Uri,
        newName: String,
        writeLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onRenamed: () -> Unit
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        try {
            val updated = activity.contentResolver.update(uri, values, null, null)
            if (updated > 0) onRenamed()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createWriteRequest(activity.contentResolver, listOf(uri))
                writeLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
        }
    }

    fun deleteFile(
        activity: Activity,
        uri: Uri,
        deleteLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onDeleted: () -> Unit
    ) {
        try {
            if (activity.contentResolver.delete(uri, null, null) > 0) onDeleted()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = MediaStore.createDeleteRequest(activity.contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }
        }
    }
}
