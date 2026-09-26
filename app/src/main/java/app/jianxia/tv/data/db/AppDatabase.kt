package app.jianxia.tv.data.db

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val kind: String,
    val epgUrl: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val addedAt: Long,
)

@Entity(tableName = "history", indices = [Index(value = ["updatedAt"])])
data class HistoryEntity(
    @PrimaryKey val titleKey: String,
    val title: String,
    val pic: String?,
    val year: String?,
    val episodeName: String,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lineId: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val titleKey: String,
    val title: String,
    val pic: String?,
    val year: String?,
    val typeName: String?,
    val payload: String,
    val addedAt: Long,
)

@Entity(tableName = "skips")
data class SkipEntity(
    @PrimaryKey val titleKey: String,
    val introMs: Long,
    val outroMs: Long,
)

@Entity(tableName = "line_choices")
data class LineChoiceEntity(
    @PrimaryKey val titleKey: String,
    val lineId: String,
    val updatedAt: Long,
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, addedAt ASC")
    fun observe(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, addedAt ASC")
    suspend fun list(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sources")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sources")
    suspend fun maxOrder(): Int
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT 40")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE titleKey = :key")
    suspend fun history(key: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(item: HistoryEntity)

    @Query("DELETE FROM history WHERE titleKey = :key")
    suspend fun deleteHistory(key: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE titleKey = :key")
    suspend fun favorite(key: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE titleKey = :key")
    suspend fun deleteFavorite(key: String)

    @Query("DELETE FROM favorites")
    suspend fun clearFavorites()

    @Query("SELECT * FROM skips WHERE titleKey = :key")
    suspend fun skip(key: String): SkipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkip(item: SkipEntity)

    @Query("SELECT * FROM line_choices WHERE titleKey = :key")
    suspend fun line(key: String): LineChoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLine(item: LineChoiceEntity)

    @Query("DELETE FROM line_choices WHERE titleKey = :key")
    suspend fun deleteLine(key: String)
}

@Database(
    entities = [
        SourceEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class,
        SkipEntity::class,
        LineChoiceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jianxia.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
