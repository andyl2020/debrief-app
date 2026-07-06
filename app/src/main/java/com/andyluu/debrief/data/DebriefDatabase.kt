package com.andyluu.debrief.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import android.util.Base64
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

class Converters {
    @TypeConverter fun fromStatus(value: RecordingStatus): String = value.name
    @TypeConverter fun toStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)
    @TypeConverter fun fromAiStatus(value: AiPassStatus): String = value.name
    @TypeConverter fun toAiStatus(value: String): AiPassStatus = AiPassStatus.valueOf(value)
}

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptWordEntity::class,
        CommentEntity::class,
        SpeakerAliasEntity::class,
        AiRecordingEntity::class,
        ConversationSetEntity::class,
        SpeakerSuggestionEntity::class,
    ],
    version = 2,
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
            .addMigrations(MIGRATION_1_2)
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ai_recordings` (
                        `recordingId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `originalDisplayName` TEXT,
                        `suggestedFilename` TEXT,
                        `skipAiPass` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `provider` TEXT,
                        `lastRunAt` INTEGER,
                        PRIMARY KEY(`recordingId`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_recordings_recordingId` ON `ai_recordings` (`recordingId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `conversation_sets` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `speakerIds` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_sets_recordingId` ON `conversation_sets` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_sets_recordingId_orderIndex` ON `conversation_sets` (`recordingId`, `orderIndex`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `speaker_suggestions` (
                        `recordingId` TEXT NOT NULL,
                        `speakerId` TEXT NOT NULL,
                        `suggestedName` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `evidence` TEXT NOT NULL,
                        PRIMARY KEY(`recordingId`, `speakerId`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_speaker_suggestions_recordingId` ON `speaker_suggestions` (`recordingId`)")
            }
        }
    }
}
