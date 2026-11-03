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
import com.gino.mywallet.data.dao.ExamDao
import com.gino.mywallet.data.dao.FocusSessionDao
import com.gino.mywallet.data.dao.LoanDao
import com.gino.mywallet.data.dao.LotteryDao
import com.gino.mywallet.data.dao.MetaDao
import com.gino.mywallet.data.dao.PassDao
import com.gino.mywallet.data.dao.PendingPhysicalTransferDao
import com.gino.mywallet.data.dao.PlanDao
import com.gino.mywallet.data.dao.StockDao
import com.gino.mywallet.data.dao.TaskDao
import com.gino.mywallet.data.dao.TransactionDao
import com.gino.mywallet.data.dao.WalletDao
import com.gino.mywallet.data.dao.WalletRateDao
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.ExamEntity
import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamSubjectEntity
import com.gino.mywallet.data.entity.FocusSessionEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LoanOfferEntity
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.PendingPhysicalTransferEntity
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.StockDailyBarEntity
import com.gino.mywallet.data.entity.StockHoldingEntity
import com.gino.mywallet.data.entity.StockWatchEntity
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.data.entity.WalletRateEntity
import com.gino.mywallet.domain.AcademicCalendar

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

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_physical_transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                amountCents INTEGER NOT NULL,
                fromWalletId TEXT NOT NULL,
                taskId INTEGER,
                taskTitle TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                confirmedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.0.0')"
        )
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM pending_physical_transfers WHERE confirmedAt IS NULL")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.1.0')"
        )
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE pending_physical_transfers
            ADD COLUMN toWalletId TEXT NOT NULL DEFAULT 'living'
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.2.0')"
        )
    }
}

/** 人脈管理獨立表 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS people (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                displayName TEXT NOT NULL,
                normalizedName TEXT NOT NULL,
                closeness INTEGER,
                birthdayMonth INTEGER,
                birthdayDay INTEGER,
                birthdayYear INTEGER,
                contactEveryDays INTEGER,
                lastContactedAt INTEGER,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_people_normalizedName ON people(normalizedName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_people_displayName ON people(displayName)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_fields (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                value TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_fields_personId ON person_fields(personId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS people_tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_people_tags_name ON people_tags(name)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_tag_links (
                personId INTEGER NOT NULL,
                tagId INTEGER NOT NULL,
                PRIMARY KEY(personId, tagId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_tag_links_tagId ON person_tag_links(tagId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_interactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                summary TEXT NOT NULL,
                theirUpdate TEXT NOT NULL,
                promises TEXT NOT NULL,
                occurredAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_person_interactions_personId_occurredAt ON person_interactions(personId, occurredAt)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_topics (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER NOT NULL,
                topic TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                resolvedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_person_topics_personId_resolvedAt ON person_topics(personId, resolvedAt)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER,
                title TEXT NOT NULL,
                note TEXT NOT NULL,
                type TEXT NOT NULL,
                dueAt INTEGER,
                status TEXT NOT NULL,
                generatedForKey TEXT,
                createdAt INTEGER NOT NULL,
                resolvedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_tasks_status_dueAt ON person_tasks(status, dueAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_tasks_personId ON person_tasks(personId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_person_tasks_generatedForKey ON person_tasks(generatedForKey)"
        )

        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.3.0')"
        )
    }
}

/** 人脈 P1：關係／禮物／重要事件 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_relations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                fromPersonId INTEGER NOT NULL,
                toPersonId INTEGER NOT NULL,
                relationType TEXT NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_relations_fromPersonId ON person_relations(fromPersonId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_relations_toPersonId ON person_relations(toPersonId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_gifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER NOT NULL,
                direction TEXT NOT NULL,
                title TEXT NOT NULL,
                valueCents INTEGER,
                at INTEGER NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_gifts_personId_at ON person_gifts(personId, at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_life_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                personId INTEGER NOT NULL,
                title TEXT NOT NULL,
                at INTEGER NOT NULL,
                remindDaysBefore INTEGER NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_life_events_personId_at ON person_life_events(personId, at)")

        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.4.0')"
        )
    }
}

/** 移除人脈（People/PRM）相關表 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS person_life_events")
        db.execSQL("DROP TABLE IF EXISTS person_gifts")
        db.execSQL("DROP TABLE IF EXISTS person_relations")
        db.execSQL("DROP TABLE IF EXISTS person_tasks")
        db.execSQL("DROP TABLE IF EXISTS person_topics")
        db.execSQL("DROP TABLE IF EXISTS person_interactions")
        db.execSQL("DROP TABLE IF EXISTS person_tag_links")
        db.execSQL("DROP TABLE IF EXISTS people_tags")
        db.execSQL("DROP TABLE IF EXISTS person_fields")
        db.execSQL("DROP TABLE IF EXISTS people")
    }
}

/** 專注場次紀錄（成就頁統計用） */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS focus_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                intent TEXT NOT NULL,
                category TEXT NOT NULL,
                linkedTaskIdsJson TEXT NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER NOT NULL,
                effectiveMs INTEGER NOT NULL,
                cashYuan INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '2.5.0')"
        )
    }
}

/** 投資模組：持股、觀察清單、日 K 快取 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_holdings (
                symbol TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                market TEXT NOT NULL,
                sharesMilli INTEGER NOT NULL,
                costCents INTEGER NOT NULL,
                lastPriceCents INTEGER NOT NULL,
                lastQuotedAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_watchlist (
                symbol TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                market TEXT NOT NULL,
                note TEXT NOT NULL,
                targetPriceCents INTEGER NOT NULL,
                wantBuy INTEGER NOT NULL,
                lastPriceCents INTEGER NOT NULL,
                lastQuotedAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_daily_bars (
                symbol TEXT NOT NULL,
                dateYmd TEXT NOT NULL,
                openCents INTEGER NOT NULL,
                highCents INTEGER NOT NULL,
                lowCents INTEGER NOT NULL,
                closeCents INTEGER NOT NULL,
                volume INTEGER NOT NULL,
                PRIMARY KEY (symbol, dateYmd)
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.10.0')"
        )
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN appearAt INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN planId TEXT")
        db.execSQL("ALTER TABLE courses ADD COLUMN weeksJson TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plans (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                note TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.SEMESTER_WEEK1_MONDAY}', '2026-09-07')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.SEMESTER_WEEKS}', '18')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.11.0')"
        )
    }
}

/** 現金／提款卡拆分 + 樂透券欄位 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE wallets SET name = '現金' WHERE id = 'living'")
        var livingRateBp = 800
        db.query("SELECT monthlyRateBp FROM wallet_rates WHERE walletId = 'living'").use { c ->
            if (c.moveToFirst()) livingRateBp = c.getInt(0)
        }
        db.execSQL(
            """
            INSERT OR IGNORE INTO wallets
            (id, name, balanceCents, isCentral, canReceiveIncome, sortOrder, archived)
            VALUES ('card', '提款卡', 0, 0, 0, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            "INSERT OR IGNORE INTO wallet_rates (walletId, monthlyRateBp) VALUES ('card', ?)",
            arrayOf(livingRateBp)
        )
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN lottoTickets INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN lottoPending INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN lottoDrawAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lottery_state ADD COLUMN lottoLastResult TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.12.0')"
        )
    }
}

/** 樂透自選 3 碼 picks JSON */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE lottery_state ADD COLUMN lottoPicksJson TEXT NOT NULL DEFAULT '[]'"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.12.1')"
        )
    }
}

/** 借據續借：記錄月息已計到何時，避免和自動續借重複計息 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE loans ADD COLUMN interestCoveredUntil INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.13.0')"
        )
    }
}

/** 消消樂／輪盤（mathTickets）券欄位 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE lottery_state ADD COLUMN matchTickets INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE lottery_state ADD COLUMN mathTickets INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.14.0')"
        )
    }
}

/** 考試範圍進度：進行中考試 + 範圍格（初次／複習） */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exams (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                dueAt INTEGER,
                archived INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exam_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                examId INTEGER NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                firstCompletedAt INTEGER,
                reviewAvailableAt INTEGER,
                reviewCompletedAt INTEGER,
                queueOrder INTEGER,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_exam_items_examId ON exam_items(examId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_exam_items_examId_queueOrder ON exam_items(examId, queueOrder)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.15.0')"
        )
    }
}

/** 考試範圍格加上科目 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE exam_items ADD COLUMN subject TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.15.1')"
        )
    }
}

/** 考試單元備註（後改為課程層級，此欄保留） */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE exam_items ADD COLUMN note TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.15.2')"
        )
    }
}

/** 課程層級備註 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exam_subjects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                examId INTEGER NOT NULL,
                subject TEXT NOT NULL,
                note TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_exam_subjects_examId_subject ON exam_subjects(examId, subject)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.15.3')"
        )
    }
}

/** 任務：dueAt／appearAt → plannedAt；中間 Tab 預設行事曆 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN plannedAt INTEGER")
        db.execSQL("UPDATE tasks SET plannedAt = COALESCE(appearAt, dueAt)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                note TEXT NOT NULL,
                category TEXT NOT NULL,
                repeatable INTEGER NOT NULL,
                repeatRuleJson TEXT NOT NULL,
                status TEXT NOT NULL,
                rewardJson TEXT NOT NULL,
                penaltyJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                resolvedAt INTEGER,
                plannedAt INTEGER,
                planId TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO tasks_new (
                id, title, note, category, repeatable, repeatRuleJson, status,
                rewardJson, penaltyJson, createdAt, resolvedAt, plannedAt, planId
            )
            SELECT
                id, title, note, category, repeatable, repeatRuleJson, status,
                rewardJson, penaltyJson, createdAt, resolvedAt, plannedAt, planId
            FROM tasks
            """.trimIndent()
        )
        db.execSQL("DROP TABLE tasks")
        db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.MIDDLE_TAB}', 'calendar')"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO app_meta (key, value) VALUES ('${MetaKeys.VERSION}', '1.16.0')"
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
        CalendarEventEntity::class,
        PendingPhysicalTransferEntity::class,
        FocusSessionEntity::class,
        StockHoldingEntity::class,
        StockWatchEntity::class,
        StockDailyBarEntity::class,
        PlanEntity::class,
        ExamEntity::class,
        ExamItemEntity::class,
        ExamSubjectEntity::class
    ],
    version = 28,
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
    abstract fun pendingPhysicalTransferDao(): PendingPhysicalTransferDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun stockDao(): StockDao
    abstract fun planDao(): PlanDao
    abstract fun examDao(): ExamDao

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
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                        MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                        MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
                        MIGRATION_26_27, MIGRATION_27_28
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
    WalletRateEntity(WalletCodes.LIVING, 800),
    WalletRateEntity(WalletCodes.CARD, 800)
)

suspend fun ensureSeed(db: AppDatabase) {
    if (db.walletDao().getAll().isNotEmpty()) {
        if (db.walletRateDao().getAll().isEmpty()) {
            db.walletRateDao().upsertAll(defaultWalletRates())
        } else if (db.walletRateDao().get(WalletCodes.CENTRAL) == null) {
            db.walletRateDao().upsert(WalletRateEntity(WalletCodes.CENTRAL, 200))
        }
        ensureCardWallet(db)
        ensureFutureCentralWallet(db)
        if (db.courseDao().getAll().isEmpty()) {
            db.courseDao().upsertAll(defaultCourses())
        }
        if (db.metaDao().get(MetaKeys.NOTIFICATIONS_ENABLED) == null) {
            db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, "1"))
        }
        if (db.metaDao().get(MetaKeys.MIDDLE_TAB) == null) {
            db.metaDao().upsert(AppMetaEntity(MetaKeys.MIDDLE_TAB, "calendar"))
        }
        ensureSemesterMeta(db)
        db.metaDao().upsert(AppMetaEntity(MetaKeys.VERSION, "1.16.0"))
        return
    }
    val wallets = listOf(
        WalletEntity(WalletCodes.CENTRAL, "中央帳戶", isCentral = true, sortOrder = 0),
        futureCentralWallet(),
        WalletEntity(WalletCodes.LIVING, "現金", sortOrder = 1),
        WalletEntity(WalletCodes.CARD, "提款卡", canReceiveIncome = false, sortOrder = 1),
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
    db.metaDao().upsert(AppMetaEntity(MetaKeys.VERSION, "1.16.0"))
    db.metaDao().upsert(AppMetaEntity(MetaKeys.NOTIFICATIONS_ENABLED, "1"))
    db.metaDao().upsert(AppMetaEntity(MetaKeys.MIDDLE_TAB, "calendar"))
    ensureSemesterMeta(db)
    db.lotteryDao().upsertState(
        LotteryStateEntity()
    )
    db.courseDao().upsertAll(defaultCourses())
}

private fun futureCentralWallet() = WalletEntity(
    id = WalletCodes.CENTRAL_FUTURE,
    name = "未來中央帳戶",
    canReceiveIncome = false,
    sortOrder = 0
)

/** 舊庫若缺未來中央則補上。暫存戶不計利率。 */
suspend fun ensureFutureCentralWallet(db: AppDatabase) {
    if (db.walletDao().getById(WalletCodes.CENTRAL_FUTURE) == null) {
        db.walletDao().upsert(futureCentralWallet())
    }
}

/** 舊庫若缺提款卡則補上（確保種子路徑也覆蓋） */
suspend fun ensureCardWallet(db: AppDatabase) {
    if (db.walletDao().getById(WalletCodes.CARD) == null) {
        db.walletDao().upsert(
            WalletEntity(
                id = WalletCodes.CARD,
                name = "提款卡",
                balanceCents = 0,
                canReceiveIncome = false,
                sortOrder = 1
            )
        )
    }
    val living = db.walletDao().getById(WalletCodes.LIVING)
    if (living != null && living.name == "伙雜費") {
        db.walletDao().upsert(living.copy(name = "現金"))
    }
    if (db.walletRateDao().get(WalletCodes.CARD) == null) {
        val bp = db.walletRateDao().get(WalletCodes.LIVING)?.monthlyRateBp ?: 800
        db.walletRateDao().upsert(WalletRateEntity(WalletCodes.CARD, bp))
    }
}

suspend fun ensureSemesterMeta(db: AppDatabase) {
    if (db.metaDao().get(MetaKeys.SEMESTER_WEEK1_MONDAY) == null) {
        db.metaDao().upsert(
            AppMetaEntity(MetaKeys.SEMESTER_WEEK1_MONDAY, AcademicCalendar.DEFAULT_WEEK1_MONDAY)
        )
    }
    if (db.metaDao().get(MetaKeys.SEMESTER_WEEKS) == null) {
        db.metaDao().upsert(
            AppMetaEntity(MetaKeys.SEMESTER_WEEKS, AcademicCalendar.DEFAULT_WEEKS.toString())
        )
    }
}
