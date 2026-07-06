package com.andyluu.debrief.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import android.util.Base64
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

class Converters {
    @TypeConverter fun fromStatus(value: RecordingStatus): String = value.name
    @TypeConverter fun toStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)
}

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptWordEntity::class,
        CommentEntity::class,
        SpeakerAliasEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DebriefDatabase : RoomDatabase() {
    abstract fun dao(): DebriefDao

    companion object {
        @Volatile private var instance: DebriefDatabase? = null

        fun get(context: Context): DebriefDatabase = instance ?: synchronized(this) {
            val secrets = SecureSecretStore(context.applicationContext)
            val encodedPassphrase = secrets.get("database_passphrase") ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
            }.let { bytes ->
                Base64.encodeToString(bytes, Base64.NO_WRAP).also { secrets.put("database_passphrase", it) }
            }
            val passphrase = Base64.decode(encodedPassphrase, Base64.NO_WRAP)
            System.loadLibrary("sqlcipher")
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DebriefDatabase::class.java,
                "debrief.db",
            ).openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL(
                        """CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(
                            recording_id UNINDEXED,
                            recording_name,
                            speaker_id UNINDEXED,
                            timestamp_ms UNINDEXED,
                            body,
                            kind UNINDEXED,
                            tokenize = 'unicode61 remove_diacritics 2'
                        )""".trimIndent()
                    )
                }
            }).build().also { instance = it }
        }
    }
}
