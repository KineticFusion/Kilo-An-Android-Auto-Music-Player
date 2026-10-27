package com.example.kilo

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.os.SystemClock
import com.example.kilo.androidauto.AndroidAutoCache
import com.example.kilo.androidauto.AndroidAutoMetrics
import com.example.kilo.androidauto.AndroidAutoRecoveryManager

@Entity(
    tableName = "auto_browse_items",
    primaryKeys = ["parentId", "position"],
    indices = [Index("parentId")]
)
data class AutoBrowseItemEntity(
    val parentId: String,
    val position: Int,
    val mediaId: String,
    val title: String,
    val subtitle: String?,
    val artist: String?,
    val album: String?,
    val artworkUri: String?,
    val itemUri: String?,
    val isPlayable: Boolean,
    val isBrowsable: Boolean,
    val mediaType: Int
)

@Dao
abstract class AutoBrowseDao {
    @Query("SELECT * FROM auto_browse_items WHERE parentId = :parentId ORDER BY position")
    abstract fun children(parentId: String): List<AutoBrowseItemEntity>

    @Query("SELECT * FROM auto_browse_items WHERE mediaId = :mediaId LIMIT 1")
    abstract fun item(mediaId: String): AutoBrowseItemEntity?

    @Query("DELETE FROM auto_browse_items WHERE parentId = :parentId")
    protected abstract fun deleteChildren(parentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertChildren(items: List<AutoBrowseItemEntity>)

    @Transaction
    open fun replaceChildren(parentId: String, items: List<AutoBrowseItemEntity>) {
        deleteChildren(parentId)
        if (items.isNotEmpty()) insertChildren(items)
    }
}

@Database(entities = [AutoBrowseItemEntity::class], version = 1, exportSchema = false)
abstract class AutoBrowseDatabase : RoomDatabase() {
    abstract fun autoBrowseDao(): AutoBrowseDao

    companion object {
        @Volatile private var instance: AutoBrowseDatabase? = null

        fun get(context: Context): AutoBrowseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AutoBrowseDatabase::class.java,
                "kilo_android_auto_browse.db"
            ).build().also { instance = it }
        }
    }
}

object BrowseTreeCache {
    fun get(parentId: String): List<MediaItem> = AndroidAutoCache.get(parentId)
    fun put(parentId: String, items: List<MediaItem>) {
        AndroidAutoCache.put(parentId, items)
    }
    fun invalidate(parentId: String) { AndroidAutoCache.invalidate(parentId) }
}

/** Room exposes only Android Auto summary rows through this narrow data boundary. */
private object AndroidAutoDatabaseView {
    fun children(context: Context, parentId: String): List<AutoBrowseItemEntity> =
        AutoBrowseDatabase.get(context).autoBrowseDao().children(parentId)

    fun item(context: Context, mediaId: String): AutoBrowseItemEntity? =
        AutoBrowseDatabase.get(context).autoBrowseDao().item(mediaId)

    fun replaceChildren(context: Context, parentId: String, items: List<AutoBrowseItemEntity>) {
        AutoBrowseDatabase.get(context).autoBrowseDao().replaceChildren(parentId, items)
    }
}

object AutoBrowseCache {
    fun read(context: Context, parentId: String): List<MediaItem> {
        val startedAt = SystemClock.elapsedRealtime()
        var succeeded = false
        var count = 0
        return try {
            AndroidAutoRecoveryManager.fallback("browse database parent=$parentId", emptyList()) {
                AndroidAutoDatabaseView.children(context, parentId).map { it.toMediaItem() }
            }.also { count = it.size; succeeded = true }
        } finally {
            AndroidAutoMetrics.recordDatabaseRead(SystemClock.elapsedRealtime() - startedAt, count, succeeded)
        }
    }

    fun find(context: Context, mediaId: String): MediaItem? {
        val startedAt = SystemClock.elapsedRealtime()
        var succeeded = false
        return try {
            AndroidAutoRecoveryManager.fallback("browse item mediaId=$mediaId", null) {
                AndroidAutoDatabaseView.item(context, mediaId)?.toMediaItem()
            }.also { succeeded = true }
        } finally {
            AndroidAutoMetrics.recordDatabaseRead(SystemClock.elapsedRealtime() - startedAt, 1, succeeded)
        }
    }

    fun write(context: Context, parentId: String, items: List<MediaItem>) {
        val rows = items.mapIndexedNotNull { index, item ->
            val id = item.mediaId.trim()
            val title = item.mediaMetadata.title?.toString()?.trim().orEmpty()
            if (id.isBlank() || title.isBlank()) return@mapIndexedNotNull null
            AutoBrowseItemEntity(
                parentId = parentId,
                position = index,
                mediaId = id,
                title = title,
                subtitle = item.mediaMetadata.subtitle?.toString(),
                artist = item.mediaMetadata.artist?.toString(),
                album = item.mediaMetadata.albumTitle?.toString(),
                artworkUri = item.mediaMetadata.artworkUri?.toString(),
                itemUri = item.localConfiguration?.uri?.toString(),
                isPlayable = item.mediaMetadata.isPlayable == true,
                isBrowsable = item.mediaMetadata.isBrowsable == true,
                mediaType = item.mediaMetadata.mediaType ?: 0
            )
        }
        AndroidAutoDatabaseView.replaceChildren(context, parentId, rows)
    }

    private fun AutoBrowseItemEntity.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(isPlayable)
            .setIsBrowsable(isBrowsable)
            .setMediaType(mediaType)
        artworkUri?.let { value -> runCatching { Uri.parse(value) }.getOrNull()?.let(metadata::setArtworkUri) }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .apply { itemUri?.let { setUri(Uri.parse(it)) } }
            .setMediaMetadata(metadata.build())
            .build()
    }
}
