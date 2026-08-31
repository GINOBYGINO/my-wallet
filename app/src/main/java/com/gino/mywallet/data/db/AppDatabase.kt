package com.gino.mywallet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gino.mywallet.data.dao.AllocationDao
import com.gino.mywallet.data.dao.CalendarEventDao
import com.gino.mywallet.data.dao.CourseDao
import com.gino.mywallet.data.dao.LoanDao
import com.gino.mywallet.data.dao.LotteryDao
import com.gino.mywallet.data.dao.MetaDao
import com.gino.mywallet.data.dao.PassDao
import com.gino.mywallet.data.dao.TaskDao
import com.gino.mywallet.data.dao.TransactionDao
import com.gino.mywallet.data.dao.WalletDao
import com.gino.mywallet.data.dao.WalletRateDao
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LoanOfferEntity
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.data.entity.WalletRateEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN category TEXT NOT NULL DEFAULT '其他'")
        db.query("SELECT balanceCents FROM wallets WHERE id = 'real'").use { c ->
            if (c.moveToFirst()) {
                val bal = c.getLong(0)
                if (bal != 0L) {
                    db.execSQL(
                        "UPDATE wallets SET balanceCents = balanceCents + ? WHERE id = 'living'",
                        arrayOf(bal)
                    )
                    db.execSQL(
                        """
                        INSERT INTO transactions (type, amountCents, fromWalletId, toWalletId, note, createdAt)
                        VALUES ('MIGRATE', ?, 'real', 'living', '現實錢包廢止／餘額轉入伙雜費', ?)
                        """.trimIndent(),
                        arrayOf(bal, System.currentTimeMillis())
                    )
                }
                db.execSQL("UPDATE wallets SET archived = 1, balanceCents = 0 WHERE id = 'real'")
            }
        }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wallet_rates (
                walletId TEXT NOT NULL PRIMARY KEY,
                monthlyRateBp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val defaults = listOf(
            "invest" to 500,
            "study" to 300,
            "bci" to 450,
            "social" to 600,
            "vtuber" to 650,
            "acg" to 700,
            "living" to 800
        )
        defaults.forEach { (id, bp) ->
            db.execSQL(
                "INSERT OR REPLACE INTO wallet_rates (walletId, monthlyRateBp) VALUES (?, ?)",
                arrayOf(id, bp)
            )
        }
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN nextFreeAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN drawCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN repeatRuleJson TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.2.0')"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS courses (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                weekday INTEGER NOT NULL,
                periodStart INTEGER NOT NULL,
                periodEnd INTEGER NOT NULL,
                location TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.3.0')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.NOTIFICATIONS_ENABLED}', '1')"
        )
    }
}

/** 抽獎券拆成三種；舊 tickets 欄位改視為 🎰 拉霸次數 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN bjTickets INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN scratchTickets INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.4.0')"
        )
    }
}

/** 中央欠債（meta） */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.CENTRAL_DEBT_PRINCIPAL}', '0')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.CENTRAL_DEBT_SINCE_MS}', '0')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.5.0')"
        )
    }
}

/** 個人行程 + 課表可編輯 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calendar_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                dateYmd TEXT NOT NULL,
                weekday INTEGER NOT NULL,
                periodStart INTEGER NOT NULL,
                periodEnd INTEGER NOT NULL,
                location TEXT NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.6.0')"
        )
    }
}

/** 行程簡化：單次日期 + 可選分鐘時間 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calendar_events_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                dateYmd TEXT NOT NULL,
                timeMinutes INTEGER NOT NULL,
                location TEXT NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO calendar_events_new (id, title, dateYmd, timeMinutes, location, note, createdAt)
            SELECT id, title, dateYmd, -1, location, note, createdAt
            FROM calendar_events
            WHERE dateYmd != ''
            """.trimIndent()
        )
        db.execSQL("DROP TABLE calendar_events")
        db.execSQL("ALTER TABLE calendar_events_new RENAME TO calendar_events")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.7.0')"
        )
    }
}

/** 刪除閒置 lottery_pool；任務可選 dueAt */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS lottery_pool")
        db.execSQL("ALTER TABLE tasks ADD COLUMN dueAt INTEGER")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.8.0')"
        )
    }
}

/** Triple Draw 券 + 冷卻輪替 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN drawTickets INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN cooldownIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.9.0')"
        )
    }
}

@Database(
    entities = [
        WalletEntity::class,
        WalletRateEntity::class,
        AllocationRuleEntity::class,
        TransactionEntity::class,
        LoanOfferEntity::class,
        LoanEntity::class,
        TaskEntity::class,
        LotteryStateEntity::class,
        PassSeasonEntity::class,
        PassTierEntity::class,
        AppMetaEntity::class,
        CourseEntity::class,
        CalendarEventEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun walletRateDao(): WalletRateDao
    abstract fun allocationDao(): AllocationDao
    abstract fun transactionDao(): TransactionDao
    abstract fun loanDao(): LoanDao
    abstract fun taskDao(): TaskDao
    abstract fun lotteryDao(): LotteryDao
    abstract fun passDao(): PassDao
    abstract fun metaDao(): MetaDao
    abstract fun courseDao(): CourseDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "my_wallet.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                    )
                    .build()
                    .also { instance = it }
            }

        fun resetInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

fun defaultWalletRates(): List<WalletRateEntity> = listOf(
    WalletRateEntity(WalletCodes.CENTRAL, 200), // 中央市場利率預設 2%/月
    WalletRateEntity(WalletCodes.INVEST, 500),
    WalletRateEntity(WalletCodes.STUDY, 300),
    WalletRateEntity(WalletCodes.BCI, 450),
    WalletRateEntity(WalletCodes.SOCIAL, 600),
    WalletRateEntity(WalletCodes.VTUBER, 650),
    WalletRateEntity(WalletCodes.ACG, 700),
    WalletRateEntity(WalletCodes.LIVING, 800)
)

suspend fun ensureSeed(db: AppDatabase) {
    if (db.walletDao().getAll().isNotEmpty()) {
        if (db.walletRateDao().getAll().isEmpty()) {
            db.walletRateDao().upsertAll(defaultWalletRates())
        } else if (db.walletRateDao().get(WalletCodes.CENTRAL) == null) {
            db.walletRateDao().upsert(WalletRateEntity(WalletCodes.CENTRAL, 200))
        }
        if (db.courseDao().getAll().isEmpty()) {
            db.courseDao().upsertAll(defaultCourses())
        }
        if (db.metaDao().get(MetaKeys.NOTIFICATIONS_ENABLED) == null) {
            db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, "1"))
        }
        db.metaDao().upsert(AppMetaEntity(MetaKeys.VERSION, "1.9.0"))
        return
    }
    val wallets = listOf(
        WalletEntity(WalletCodes.CENTRAL, "中央帳戶", isCentral = true, sortOrder = 0),
        WalletEntity(WalletCodes.LIVING, "伙雜費", sortOrder = 1),
        WalletEntity(WalletCodes.BCI, "專案", sortOrder = 2),
        WalletEntity(WalletCodes.INVEST, "投資", sortOrder = 3),
        WalletEntity(WalletCodes.VTUBER, "興趣", sortOrder = 4),
        WalletEntity(WalletCodes.ACG, "嗜好", sortOrder = 5),
        WalletEntity(WalletCodes.STUDY, "學業", sortOrder = 6),
        WalletEntity(WalletCodes.SOCIAL, "社交", sortOrder = 7)
    )
    db.walletDao().upsertAll(wallets)
    db.walletRateDao().upsertAll(defaultWalletRates())

    db.allocationDao().upsertAll(
        listOf(
            AllocationRuleEntity(WalletCodes.CENTRAL, 4000, isTaxShare = true),
            AllocationRuleEntity(WalletCodes.LIVING, 5000),
            AllocationRuleEntity(WalletCodes.BCI, 1000)
        )
    )
    db.metaDao().upsert(AppMetaEntity(MetaKeys.TAX_BP, "4000"))
    db.metaDao().upsert(AppMetaEntity(MetaKeys.VERSION, "1.9.0"))
    db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, "1"))
    db.lotteryDao().upsertState(
        LotteryStateEntity()
    )
    db.courseDao().upsertAll(defaultCourses())
}
