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

object AutoBrowseCache {
    fun read(context: Context, parentId: String): List<MediaItem> =
        AutoBrowseDatabase.get(context).autoBrowseDao().children(parentId).map { it.toMediaItem() }

    fun find(context: Context, mediaId: String): MediaItem? =
        AutoBrowseDatabase.get(context).autoBrowseDao().item(mediaId)?.toMediaItem()

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
        AutoBrowseDatabase.get(context).autoBrowseDao().replaceChildren(parentId, rows)
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