package com.gino.mywallet.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
import com.gino.mywallet.data.entity.PendingPhysicalTransferEntity
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.StockDailyBarEntity
import com.gino.mywallet.data.entity.StockHoldingEntity
import com.gino.mywallet.data.entity.StockWatchEntity
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TransactionEntity
import com.gino.mywallet.data.entity.WalletEntity
import com.gino.mywallet.data.entity.WalletRateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets WHERE archived = 0 ORDER BY sortOrder")
    fun observeAll(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE archived = 0 ORDER BY sortOrder")
    suspend fun getAll(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun getById(id: String): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wallet: WalletEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(wallets: List<WalletEntity>)

    @Update
    suspend fun update(wallet: WalletEntity)

    @Query("UPDATE wallets SET balanceCents = balanceCents + :delta WHERE id = :id")
    suspend fun adjustBalance(id: String, delta: Long)

    @Query("UPDATE wallets SET balanceCents = :balance WHERE id = :id")
    suspend fun setBalance(id: String, balance: Long)

    @Query("UPDATE wallets SET canReceiveIncome = :can WHERE id = :id")
    suspend fun setCanReceiveIncome(id: String, can: Boolean)
}

@Dao
interface WalletRateDao {
    @Query("SELECT * FROM wallet_rates ORDER BY walletId")
    fun observeAll(): Flow<List<WalletRateEntity>>

    @Query("SELECT * FROM wallet_rates")
    suspend fun getAll(): List<WalletRateEntity>

    @Query("SELECT * FROM wallet_rates WHERE walletId = :id")
    suspend fun get(id: String): WalletRateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rate: WalletRateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rates: List<WalletRateEntity>)
}

@Dao
interface AllocationDao {
    @Query("SELECT * FROM allocation_rules")
    fun observeAll(): Flow<List<AllocationRuleEntity>>

    @Query("SELECT * FROM allocation_rules")
    suspend fun getAll(): List<AllocationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<AllocationRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AllocationRuleEntity)

    @Query("DELETE FROM allocation_rules")
    suspend fun clear()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY createdAt ASC")
    suspend fun getByType(type: String): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE createdAt >= :fromMs AND createdAt < :toMs " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getBetween(fromMs: Long, toMs: Long): List<TransactionEntity>
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loan_offers WHERE active = 1 ORDER BY annualRateBp")
    fun observeOffers(): Flow<List<LoanOfferEntity>>

    @Query("SELECT * FROM loan_offers WHERE active = 1 ORDER BY annualRateBp")
    suspend fun getActiveOffers(): List<LoanOfferEntity>

    @Insert
    suspend fun insertOffer(offer: LoanOfferEntity): Long

    @Update
    suspend fun updateOffer(offer: LoanOfferEntity)

    @Query("SELECT * FROM loans ORDER BY startAt DESC")
    fun observeLoans(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans ORDER BY startAt DESC")
    suspend fun getLoans(): List<LoanEntity>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun getLoan(id: Long): LoanEntity?

    @Insert
    suspend fun insertLoan(loan: LoanEntity): Long

    @Update
    suspend fun updateLoan(loan: LoanEntity)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE title LIKE :pattern")
    suspend fun deleteByTitleLike(pattern: String)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM tasks WHERE status = 'OPEN' AND title LIKE '%' || :needle || '%'")
    suspend fun deleteOpenByTitleContains(needle: String): Int

    @Query("UPDATE tasks SET planId = NULL WHERE planId = :planId")
    suspend fun clearPlanId(planId: String)

    @Query("DELETE FROM tasks WHERE planId = :planId AND status = 'OPEN'")
    suspend fun deleteOpenByPlanId(planId: String): Int

    @Query("SELECT * FROM tasks WHERE status = 'OPEN' ORDER BY createdAt DESC")
    suspend fun getOpen(): List<TaskEntity>

    @Query(
        """
        SELECT * FROM tasks
        WHERE status IN ('DONE', 'FAILED')
        ORDER BY COALESCE(resolvedAt, createdAt) DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentResolved(limit: Int): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'DONE' AND resolvedAt BETWEEN :from AND :to")
    suspend fun countDoneBetween(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'DONE'")
    suspend fun countDoneAll(): Int
}

@Dao
interface FocusSessionDao {
    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Query("SELECT * FROM focus_sessions ORDER BY endedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE endedAt BETWEEN :from AND :to ORDER BY endedAt DESC")
    suspend fun getBetween(from: Long, to: Long): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_sessions")
    suspend fun getAll(): List<FocusSessionEntity>

    @Query("SELECT COALESCE(SUM(effectiveMs), 0) FROM focus_sessions")
    suspend fun sumEffectiveMsAll(): Long
}

@Dao
interface LotteryDao {
    @Query("SELECT * FROM lottery_state WHERE id = 1")
    fun observeState(): Flow<LotteryStateEntity?>

    @Query("SELECT * FROM lottery_state WHERE id = 1")
    suspend fun getState(): LotteryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: LotteryStateEntity)
}

@Dao
interface PassDao {
    @Query("SELECT * FROM pass_seasons WHERE active = 1 LIMIT 1")
    fun observeActiveSeason(): Flow<PassSeasonEntity?>

    @Query("SELECT * FROM pass_seasons WHERE active = 1 LIMIT 1")
    suspend fun getActiveSeason(): PassSeasonEntity?

    @Insert
    suspend fun insertSeason(season: PassSeasonEntity): Long

    @Update
    suspend fun updateSeason(season: PassSeasonEntity)

    @Query("UPDATE pass_seasons SET active = 0 WHERE active = 1")
    suspend fun deactivateAllSeasons()

    @Query("SELECT * FROM pass_tiers WHERE seasonId = :seasonId ORDER BY tierIndex")
    fun observeTiers(seasonId: Long): Flow<List<PassTierEntity>>

    @Query("SELECT * FROM pass_tiers WHERE seasonId = :seasonId ORDER BY tierIndex")
    suspend fun getTiers(seasonId: Long): List<PassTierEntity>

    @Insert
    suspend fun insertTier(tier: PassTierEntity): Long

    @Insert
    suspend fun insertTiers(tiers: List<PassTierEntity>)

    @Query("DELETE FROM pass_tiers WHERE seasonId = :seasonId")
    suspend fun deleteTiers(seasonId: Long)

    @Transaction
    suspend fun replaceTiers(seasonId: Long, tiers: List<PassTierEntity>) {
        deleteTiers(seasonId)
        insertTiers(tiers)
    }

    @Update
    suspend fun updateTier(tier: PassTierEntity)

    @Update
    suspend fun updateTiers(tiers: List<PassTierEntity>)

    @Query("UPDATE pass_tiers SET claimed = 1 WHERE id = :tierId AND claimed = 0")
    suspend fun markClaimedIfUnclaimed(tierId: Long): Int
}

@Dao
interface MetaDao {
    @Query("SELECT * FROM app_meta WHERE key = :key")
    suspend fun get(key: String): AppMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: AppMetaEntity)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY weekday, periodStart")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY weekday, periodStart")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(courses: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM courses")
    suspend fun clear()
}

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_events ORDER BY dateYmd, timeMinutes")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events ORDER BY dateYmd, timeMinutes")
    suspend fun getAll(): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: Long): CalendarEventEntity?

    @Insert
    suspend fun insert(event: CalendarEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CalendarEventEntity): Long

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()
}

@Dao
interface PendingPhysicalTransferDao {
    @Query(
        "SELECT * FROM pending_physical_transfers WHERE confirmedAt IS NULL ORDER BY createdAt ASC"
    )
    fun observePending(): Flow<List<PendingPhysicalTransferEntity>>

    @Insert
    suspend fun insert(item: PendingPhysicalTransferEntity): Long

    @Update
    suspend fun update(item: PendingPhysicalTransferEntity)

    @Query(
        "SELECT * FROM pending_physical_transfers WHERE confirmedAt IS NULL ORDER BY createdAt ASC"
    )
    suspend fun getPending(): List<PendingPhysicalTransferEntity>

    @Query(
        "UPDATE pending_physical_transfers SET confirmedAt = :at WHERE confirmedAt IS NULL"
    )
    suspend fun confirmAll(at: Long = System.currentTimeMillis())

    @Query(
        "UPDATE pending_physical_transfers SET confirmedAt = :at WHERE id = :id AND confirmedAt IS NULL"
    )
    suspend fun confirmById(id: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM pending_physical_transfers WHERE confirmedAt IS NULL")
    suspend fun deleteAllUnconfirmed()
}

@Dao
interface StockDao {
    // ----- 持股 -----
    @Query("SELECT * FROM stock_holdings ORDER BY symbol")
    fun observeHoldings(): Flow<List<StockHoldingEntity>>

    @Query("SELECT * FROM stock_holdings ORDER BY symbol")
    suspend fun getHoldings(): List<StockHoldingEntity>

    @Query("SELECT * FROM stock_holdings WHERE symbol = :symbol")
    suspend fun getHolding(symbol: String): StockHoldingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHolding(holding: StockHoldingEntity)

    @Query("DELETE FROM stock_holdings WHERE symbol = :symbol")
    suspend fun deleteHolding(symbol: String)

    @Query(
        "UPDATE stock_holdings SET lastPriceCents = :priceCents, lastQuotedAt = :at WHERE symbol = :symbol"
    )
    suspend fun updateHoldingPrice(symbol: String, priceCents: Long, at: Long)

    // ----- 觀察清單 -----
    @Query("SELECT * FROM stock_watchlist ORDER BY wantBuy DESC, symbol")
    fun observeWatchlist(): Flow<List<StockWatchEntity>>

    @Query("SELECT * FROM stock_watchlist ORDER BY wantBuy DESC, symbol")
    suspend fun getWatchlist(): List<StockWatchEntity>

    @Query("SELECT * FROM stock_watchlist WHERE symbol = :symbol")
    suspend fun getWatch(symbol: String): StockWatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatch(watch: StockWatchEntity)

    @Query("DELETE FROM stock_watchlist WHERE symbol = :symbol")
    suspend fun deleteWatch(symbol: String)

    @Query(
        "UPDATE stock_watchlist SET lastPriceCents = :priceCents, lastQuotedAt = :at WHERE symbol = :symbol"
    )
    suspend fun updateWatchPrice(symbol: String, priceCents: Long, at: Long)

    // ----- 日 K -----
    @Query("SELECT * FROM stock_daily_bars WHERE symbol = :symbol ORDER BY dateYmd")
    suspend fun getBars(symbol: String): List<StockDailyBarEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBars(bars: List<StockDailyBarEntity>)

    @Query("DELETE FROM stock_daily_bars WHERE symbol = :symbol AND dateYmd < :minYmd")
    suspend fun pruneBars(symbol: String, minYmd: String)

    @Query("DELETE FROM stock_daily_bars WHERE symbol = :symbol")
    suspend fun deleteBars(symbol: String)

    @Query("SELECT MAX(dateYmd) FROM stock_daily_bars WHERE symbol = :symbol")
    suspend fun latestBarDate(symbol: String): String?
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans ORDER BY createdAt DESC")
    suspend fun getAll(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getById(id: String): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exams WHERE archived = 0 ORDER BY createdAt DESC LIMIT 1")
    fun observeActiveExam(): Flow<ExamEntity?>

    @Query("SELECT * FROM exams WHERE archived = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveExam(): ExamEntity?

    @Query("SELECT * FROM exams WHERE id = :id")
    suspend fun getExamById(id: Long): ExamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExam(exam: ExamEntity): Long

    @Update
    suspend fun updateExam(exam: ExamEntity)

    @Query("UPDATE exams SET archived = 1 WHERE archived = 0")
    suspend fun archiveAllActive()

    @Query("SELECT * FROM exam_items WHERE examId = :examId ORDER BY createdAt ASC, id ASC")
    fun observeItems(examId: Long): Flow<List<ExamItemEntity>>

    @Query("SELECT * FROM exam_items WHERE examId = :examId ORDER BY createdAt ASC, id ASC")
    suspend fun getItems(examId: Long): List<ExamItemEntity>

    @Query("SELECT * FROM exam_items WHERE id = :id")
    suspend fun getItemById(id: Long): ExamItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ExamItemEntity): Long

    @Update
    suspend fun updateItem(item: ExamItemEntity)

    @Query("DELETE FROM exam_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("SELECT * FROM exam_subjects WHERE examId = :examId ORDER BY subject ASC")
    fun observeSubjects(examId: Long): Flow<List<ExamSubjectEntity>>

    @Query("SELECT * FROM exam_subjects WHERE examId = :examId AND subject = :subject LIMIT 1")
    suspend fun getSubject(examId: Long, subject: String): ExamSubjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubject(subject: ExamSubjectEntity): Long

    @Update
    suspend fun updateSubject(subject: ExamSubjectEntity)

    @Transaction
    suspend fun archiveActiveAndCreate(title: String, dueAt: Long?, createdAt: Long): Long {
        archiveAllActive()
        return upsertExam(
            ExamEntity(title = title, dueAt = dueAt, archived = false, createdAt = createdAt)
        )
    }

    @Transaction
    suspend fun upsertSubjectNote(examId: Long, subject: String, note: String) {
        val existing = getSubject(examId, subject)
        if (existing == null) {
            upsertSubject(ExamSubjectEntity(examId = examId, subject = subject, note = note))
        } else {
            updateSubject(existing.copy(note = note))
        }
    }
}
