package com.gino.mywallet.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gino.mywallet.data.entity.AllocationRuleEntity
import com.gino.mywallet.data.entity.AppMetaEntity
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.LoanEntity
import com.gino.mywallet.data.entity.LoanOfferEntity
import com.gino.mywallet.data.entity.LotteryStateEntity
import com.gino.mywallet.data.entity.PassSeasonEntity
import com.gino.mywallet.data.entity.PassTierEntity
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

    @Query("SELECT * FROM tasks WHERE status = 'OPEN' ORDER BY createdAt DESC")
    suspend fun getOpen(): List<TaskEntity>
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

    @Update
    suspend fun updateTier(tier: PassTierEntity)

    @Update
    suspend fun updateTiers(tiers: List<PassTierEntity>)
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

    @Insert
    suspend fun insert(event: CalendarEventEntity): Long

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: Long)
}
