package com.gino.mywallet.focus

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.gino.mywallet.domain.FocusMode
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
        FocusSessionController.attach(applicationContext)
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
                // START_STICKY 重啟：只還原，絕不 startFresh（否則時間／主題歸零）
                FocusSessionController.restoreIfNeeded()
                if (!FocusSessionController.hasActiveSession()) {
                    stopSelfSafe()
                    return START_NOT_STICKY
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
            titleFor(s),
            ongoingText(s)
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
                if (phase == FocusPhase.Settled ||
                    phase == FocusPhase.Idle ||
                    phase == FocusPhase.Failed
                ) {
                    break
                }
                if (phase == FocusPhase.ShortComplete) {
                    refreshOngoing()
                    continue
                }
                FocusSessionController.onTick(UsageAccess.isOurAppForeground(this@FocusForegroundService))
                refreshOngoing()
                val after = FocusSessionController.snapshot().phase
                if (after == FocusPhase.Failed || after == FocusPhase.ShortComplete) {
                    refreshOngoing()
                    if (after == FocusPhase.Failed) break
                }
            }
        }
        // 另開 UI 刷新節奏（約 1s）
        scope.launch {
            while (isActive) {
                delay(1_000L)
                val phase = FocusSessionController.snapshot().phase
                if (phase == FocusPhase.Settled ||
                    phase == FocusPhase.Idle ||
                    phase == FocusPhase.Failed
                ) {
                    break
                }
                if (phase == FocusPhase.ShortComplete) {
                    refreshOngoing()
                    continue
                }
                if (phase == FocusPhase.Running) {
                    FocusSessionController.onTick(UsageAccess.isOurAppForeground(this@FocusForegroundService))
                    refreshOngoing()
                    if (FocusSessionController.snapshot().phase == FocusPhase.Failed) break
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
                titleFor(s),
                ongoingText(s)
            )
        )
    }

    private fun titleFor(s: FocusUiState): String = when (s.phase) {
        FocusPhase.Paused -> "專注已暫停"
        FocusPhase.ShortComplete -> "短期專注完成"
        FocusPhase.Failed -> "短期專注失敗"
        else -> if (s.mode == FocusMode.Short) "短期專注中" else "專注進行中"
    }

    private fun ongoingText(s: FocusUiState): String {
        val cash = s.cashYuan
        val sign = if (cash >= 0) "+$cash" else "$cash"
        return when {
            s.phase == FocusPhase.ShortComplete -> {
                val reward = s.shortDuration?.rewardYuan ?: 0
                "已完成 · 可領 +$reward 或轉長期"
            }
            s.phase == FocusPhase.Failed -> "$sign 元 · 將結算待轉移"
            s.mode == FocusMode.Short -> {
                val target = s.shortDuration?.targetMs ?: 0L
                val remain = (target - s.displayMs).coerceAtLeast(0L)
                "${FocusRules.formatCountdown(remain)} · 押金 $sign · 離開即失敗"
            }
            else -> {
                val status = when (s.phase) {
                    FocusPhase.Paused -> "暫停"
                    FocusPhase.Running ->
                        if (s.rewardCapped) "獎勵已滿" else "計時中"
                    else -> ""
                }
                "${FocusRules.formatElapsed(s.displayMs)} · $sign 元 · 券預算${s.ticketEvYuan} · $status"
            }
        }
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
