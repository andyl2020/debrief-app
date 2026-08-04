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
    @TypeConverter fun fromRepairRunStatus(value: RepairRunStatus): String = value.name
    @TypeConverter fun toRepairRunStatus(value: String): RepairRunStatus = RepairRunStatus.valueOf(value)
    @TypeConverter fun fromRepairRunMode(value: RepairRunMode): String = value.name
    @TypeConverter fun toRepairRunMode(value: String): RepairRunMode = RepairRunMode.valueOf(value)
    @TypeConverter fun fromTranscriptQualityStatus(value: TranscriptQualityStatus): String = value.name
    @TypeConverter fun toTranscriptQualityStatus(value: String): TranscriptQualityStatus = TranscriptQualityStatus.valueOf(value)
    @TypeConverter fun fromShareDraftStatus(value: ShareDraftStatus): String = value.name
    @TypeConverter fun toShareDraftStatus(value: String): ShareDraftStatus = ShareDraftStatus.valueOf(value)
    @TypeConverter fun fromSharePartStatus(value: SharePartStatus): String = value.name
    @TypeConverter fun toSharePartStatus(value: String): SharePartStatus = SharePartStatus.valueOf(value)
    @TypeConverter fun fromSharedLinkStatus(value: SharedLinkStatus): String = value.name
    @TypeConverter fun toSharedLinkStatus(value: String): SharedLinkStatus = SharedLinkStatus.valueOf(value)
}

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptWordEntity::class,
        CommentEntity::class,
        RedactionEntity::class,
        SpeakerAliasEntity::class,
        AiRecordingEntity::class,
        ConversationSetEntity::class,
        SpeakerSuggestionEntity::class,
        SuspectSpanEntity::class,
        RepairRunEntity::class,
        RepairEntity::class,
        TranscriptQualityReportEntity::class,
        ShareDraftEntity::class,
        SharePartEntity::class,
        SharedLinkEntity::class,
        CloudUsageEntity::class,
    ],
    version = 6,
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transcript_words` ADD COLUMN `confidence` REAL")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `suspect_spans` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `originalText` TEXT NOT NULL,
                        `flaggedWordCount` INTEGER NOT NULL,
                        `minConfidence` REAL NOT NULL,
                        `meanConfidence` REAL NOT NULL,
                        `score` REAL NOT NULL,
                        `resolved` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_suspect_spans_recordingId` ON `suspect_spans` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_suspect_spans_recordingId_startMs` ON `suspect_spans` (`recordingId`, `startMs`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `repair_runs` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `stageLabel` TEXT NOT NULL,
                        `completedSteps` INTEGER NOT NULL,
                        `totalSteps` INTEGER NOT NULL,
                        `selectionStartMs` INTEGER,
                        `selectionEndMs` INTEGER,
                        `fixedCount` INTEGER NOT NULL,
                        `inaudibleCount` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repair_runs_recordingId` ON `repair_runs` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repair_runs_recordingId_createdAt` ON `repair_runs` (`recordingId`, `createdAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `repairs` (
                        `id` TEXT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `original` TEXT NOT NULL,
                        `repaired` TEXT,
                        `type` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `applied` INTEGER NOT NULL,
                        `reverted` INTEGER NOT NULL,
                        `clipUri` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`runId`) REFERENCES `repair_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repairs_runId` ON `repairs` (`runId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repairs_recordingId` ON `repairs` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repairs_recordingId_startMs` ON `repairs` (`recordingId`, `startMs`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `transcript_quality_reports` (
                        `recordingId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `uploadMode` TEXT NOT NULL,
                        `audioDurationMs` INTEGER NOT NULL,
                        `transcriptStartMs` INTEGER,
                        `transcriptEndMs` INTEGER,
                        `segmentCount` INTEGER NOT NULL,
                        `wordCount` INTEGER NOT NULL,
                        `speakerCount` INTEGER NOT NULL,
                        `wordsPerMinute` REAL NOT NULL,
                        `warningCount` INTEGER NOT NULL,
                        `warningsText` TEXT NOT NULL,
                        `recommendation` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`recordingId`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_quality_reports_recordingId` ON `transcript_quality_reports` (`recordingId`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `redactions` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_redactions_recordingId` ON `redactions` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_redactions_recordingId_startMs` ON `redactions` (`recordingId`, `startMs`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `share_drafts` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `selectedSetIdsJson` TEXT NOT NULL,
                        `expiryDays` INTEGER NOT NULL,
                        `pinEnabled` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `stageLabel` TEXT NOT NULL,
                        `completedSteps` INTEGER NOT NULL,
                        `totalSteps` INTEGER NOT NULL,
                        `serverDraftId` TEXT,
                        `publicToken` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_share_drafts_recordingId` ON `share_drafts` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_share_drafts_status_updatedAt` ON `share_drafts` (`status`, `updatedAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `share_parts` (
                        `id` TEXT NOT NULL,
                        `draftId` TEXT NOT NULL,
                        `setId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceStartMs` INTEGER NOT NULL,
                        `sourceEndMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `metadataPath` TEXT NOT NULL,
                        `redactionRangesJson` TEXT NOT NULL,
                        `audioPath` TEXT,
                        `audioMimeType` TEXT NOT NULL,
                        `audioObjectId` TEXT,
                        `metadataObjectId` TEXT,
                        `audioSizeBytes` INTEGER NOT NULL,
                        `metadataSizeBytes` INTEGER NOT NULL,
                        `audioSha256` TEXT,
                        `metadataSha256` TEXT,
                        `audioPartsJson` TEXT NOT NULL,
                        `metadataPartsJson` TEXT NOT NULL,
                        `audioUploaded` INTEGER NOT NULL,
                        `metadataUploaded` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`draftId`) REFERENCES `share_drafts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_share_parts_draftId` ON `share_parts` (`draftId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_share_parts_draftId_position` ON `share_parts` (`draftId`, `position`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `shared_links` (
                        `id` TEXT NOT NULL,
                        `recordingId` TEXT,
                        `title` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `expiryDays` INTEGER NOT NULL,
                        `setCount` INTEGER NOT NULL,
                        `totalDurationMs` INTEGER NOT NULL,
                        `totalSizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `publishedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `revokedAt` INTEGER,
                        `lastSyncedAt` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_links_recordingId` ON `shared_links` (`recordingId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_links_status_expiresAt` ON `shared_links` (`status`, `expiresAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cloud_usage` (
                        `id` INTEGER NOT NULL,
                        `currentBytes` INTEGER NOT NULL,
                        `referenceBytes` INTEGER NOT NULL,
                        `activeLinks` INTEGER NOT NULL,
                        `providerBytes` INTEGER,
                        `measuredAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `billingNote` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
            }
        }
    }
}
