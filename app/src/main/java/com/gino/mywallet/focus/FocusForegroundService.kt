package com.gino.mywallet.focus

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.notify.NotificationHelper
import com.gino.mywallet.notify.NotifyIds
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FocusForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                FocusSessionController.startFresh()
                startFg()
                startLoop()
            }
            ACTION_PAUSE -> {
                FocusSessionController.pause(leftApp = false)
                refreshOngoing()
            }
            ACTION_RESUME -> {
                if (UsageAccess.isOurAppForeground(this)) {
                    FocusSessionController.resume()
                }
                refreshOngoing()
            }
            ACTION_STOP -> {
                stopSelfSafe()
            }
            else -> {
                if (FocusSessionController.snapshot().phase == FocusPhase.Idle) {
                    FocusSessionController.startFresh()
                }
                startFg()
                startLoop()
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        val s = FocusSessionController.snapshot()
        val notif = NotificationHelper.focusOngoing(
            this,
            "專注進行中",
            ongoingText(s.displayMs, s.cashYuan, s.tickets, s.phase)
        )
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                ServiceCompat.startForeground(
                    this,
                    NotifyIds.NOTIF_FOCUS,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(NotifyIds.NOTIF_FOCUS, notif)
            }
            else -> startForeground(NotifyIds.NOTIF_FOCUS, notif)
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                val delayMs = Random.nextLong(3_000L, 5_001L)
                delay(delayMs)
                val phase = FocusSessionController.snapshot().phase
                if (phase == FocusPhase.Settled || phase == FocusPhase.Idle) break
                FocusSessionController.onTick(UsageAccess.isOurAppForeground(this@FocusForegroundService))
                refreshOngoing()
            }
        }
        // 另開 UI 刷新節奏（約 1s）
        scope.launch {
            while (isActive) {
                delay(1_000L)
                val phase = FocusSessionController.snapshot().phase
                if (phase == FocusPhase.Settled || phase == FocusPhase.Idle) break
                if (phase == FocusPhase.Running) {
                    FocusSessionController.onTick(UsageAccess.isOurAppForeground(this@FocusForegroundService))
                    refreshOngoing()
                }
            }
        }
    }

    private fun refreshOngoing() {
        val s = FocusSessionController.snapshot()
        if (s.phase == FocusPhase.Idle || s.phase == FocusPhase.Settled) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NotifyIds.NOTIF_FOCUS,
            NotificationHelper.focusOngoing(
                this,
                if (s.phase == FocusPhase.Paused) "專注已暫停" else "專注進行中",
                ongoingText(s.displayMs, s.cashYuan, s.tickets, s.phase)
            )
        )
    }

    private fun ongoingText(ms: Long, cash: Int, tickets: Int, phase: FocusPhase): String {
        val sign = if (cash >= 0) "+$cash" else "$cash"
        val status = when (phase) {
            FocusPhase.Paused -> "暫停"
            FocusPhase.Running -> if (FocusRules.rewardMinutes(ms) >= FocusRules.MAX_REWARD_MINUTES) "獎勵已滿" else "計時中"
            else -> ""
        }
        return "${FocusRules.formatElapsed(ms)} · $sign 元 · 券預算${FocusRules.ticketEvYuan(ms)} · $status"
    }

    private fun stopSelfSafe() {
        loopJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gino.mywallet.focus.START"
        const val ACTION_PAUSE = "com.gino.mywallet.focus.PAUSE"
        const val ACTION_RESUME = "com.gino.mywallet.focus.RESUME"
        const val ACTION_STOP = "com.gino.mywallet.focus.STOP"

        fun start(context: Context) {
            val i = Intent(context, FocusForegroundService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, FocusForegroundService::class.java).setAction(ACTION_PAUSE)
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, FocusForegroundService::class.java).setAction(ACTION_RESUME)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FocusForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
