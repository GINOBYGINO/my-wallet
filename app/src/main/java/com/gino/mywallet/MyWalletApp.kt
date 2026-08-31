package com.gino.mywallet

import android.app.Application
import com.gino.mywallet.data.db.AppDatabase
import com.gino.mywallet.data.db.ensureSeed
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.notify.NotificationHelper
import com.gino.mywallet.notify.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyWalletApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set
    lateinit var engine: EconomyEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        openDb()
        NotificationHelper.ensureChannels(this)
        appScope.launch {
            ensureSeed(database)
            engine.ensureCharitySettlement()
            engine.refreshCurrentPassRewards()
            NotificationScheduler.rescheduleAll(this@MyWalletApp)
        }
    }

    private fun openDb() {
        database = AppDatabase.get(this)
        engine = EconomyEngine(
            walletDao = database.walletDao(),
            walletRateDao = database.walletRateDao(),
            allocationDao = database.allocationDao(),
            transactionDao = database.transactionDao(),
            loanDao = database.loanDao(),
            taskDao = database.taskDao(),
            lotteryDao = database.lotteryDao(),
            passDao = database.passDao(),
            metaDao = database.metaDao(),
            courseDao = database.courseDao(),
            calendarEventDao = database.calendarEventDao()
        )
    }

    /** 清空個人資料後重建種子 */
    suspend fun wipeAndReseed() {
        database.clearAllTables()
        ensureSeed(database)
        engine.ensureCharitySettlement()
        engine.refreshCurrentPassRewards()
        NotificationScheduler.rescheduleAll(this)
    }

    companion object {
        lateinit var instance: MyWalletApp
            private set
    }
}
