package com.kantv.app.catalog

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val tvgId: String?,
    val name: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val encryptedRequest: String,
    /** Safe canonical identity only; it contains no URL, header, or credential material. */
    val identityKey: String? = null,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: Int = 1,
    val encryptedOrigin: String,
    val encryptedEpgUrls: String,
    val updatedAtMs: Long,
    /** Null is a pre-v4 catalogue whose ownership is inferred conservatively on first launch. */
    val mode: String? = null,
)

/** The subscription address is encrypted; all other fields are safe operational metadata. */
@Entity(tableName = "playlist_subscriptions")
data class PlaylistSubscriptionEntity(
    @PrimaryKey val id: String,
    val encryptedUrl: String,
    val lastAttemptAtMs: Long,
    /** Null means the subscription has not completed a successful snapshot yet. */
    val lastSuccessAtMs: Long?,
    val status: String,
    val channelCount: Int,
    val createdAtMs: Long = lastAttemptAtMs,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM channels ORDER BY position")
    fun observeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM channels LIMIT 1)")
    suspend fun hasChannels(): Boolean

    @Query("SELECT * FROM channels ORDER BY position")
    suspend fun allChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun channel(id: String): ChannelEntity?

    @Query("SELECT * FROM playlist WHERE id = 1 LIMIT 1")
    suspend fun playlist(): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist_subscriptions ORDER BY createdAtMs, id")
    suspend fun subscriptions(): List<PlaylistSubscriptionEntity>

    @Query("SELECT * FROM playlist_subscriptions ORDER BY createdAtMs, id")
    fun observeSubscriptions(): Flow<List<PlaylistSubscriptionEntity>>

    @Query("SELECT * FROM playlist_subscriptions WHERE id = :id LIMIT 1")
    suspend fun subscription(id: String): PlaylistSubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSubscription(subscription: PlaylistSubscriptionEntity)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteChannelsByIds(ids: List<String>)
}

@Database(
    entities = [ChannelEntity::class, PlaylistEntity::class, PlaylistSubscriptionEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile private var instance: CatalogDatabase? = null
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN identityKey TEXT")
            }
        }
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playlist_subscriptions " +
                        "(id TEXT NOT NULL, encryptedUrl TEXT NOT NULL, lastAttemptAtMs INTEGER NOT NULL, " +
                        "lastSuccessAtMs INTEGER, status TEXT NOT NULL, channelCount INTEGER NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL, PRIMARY KEY(id))",
                )
            }
        }
        /** v4 records whether the bundled, offline default was superseded by a user import. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist ADD COLUMN mode TEXT")
            }
        }

        fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CatalogDatabase::class.java,
                "kantv.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
