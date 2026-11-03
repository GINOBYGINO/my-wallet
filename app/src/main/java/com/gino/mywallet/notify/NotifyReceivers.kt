package com.gino.mywallet.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gino.mywallet.MyWalletApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotifyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotifyActions.FIRE) return
        val type = intent.getStringExtra(NotifyActions.TYPE) ?: return
        val title = intent.getStringExtra(NotifyActions.EXTRA_TITLE) ?: "提醒"
        val body = intent.getStringExtra(NotifyActions.EXTRA_BODY) ?: ""
        val extraId = intent.getLongExtra(NotifyActions.EXTRA_ID, 0L)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MyWalletApp ?: MyWalletApp.instance
                var notifTitle = title
                var notifBody = body
                if (type == NotifyActions.TYPE_LOTTO_DRAW) {
                    notifTitle = "樂透可開獎"
                    notifBody = "請到打卡頁手動開獎（不會自動入帳）"
                }
                val (channel, notifId) = when (type) {
                    NotifyActions.TYPE_LOTTERY -> NotifyIds.CHANNEL_LOTTERY to NotifyIds.NOTIF_LOTTERY
                    NotifyActions.TYPE_LOTTO_DRAW ->
                        NotifyIds.CHANNEL_LOTTERY to NotifyIds.NOTIF_LOTTO_DRAW
                    NotifyActions.TYPE_TASK ->
                        NotifyIds.CHANNEL_TASK to (NotifyIds.NOTIF_TASK_BASE + extraId.toInt())
                    NotifyActions.TYPE_CHARITY -> NotifyIds.CHANNEL_CHARITY to NotifyIds.NOTIF_CHARITY
                    NotifyActions.TYPE_COURSE ->
                        NotifyIds.CHANNEL_COURSE to (NotifyIds.NOTIF_COURSE_BASE + extraId.toInt())
                    NotifyActions.TYPE_EVENT ->
                        NotifyIds.CHANNEL_EVENT to (NotifyIds.NOTIF_EVENT_BASE + extraId.toInt())
                    NotifyActions.TYPE_LOAN ->
                        NotifyIds.CHANNEL_LOAN to (NotifyIds.NOTIF_LOAN_BASE + extraId.toInt())
                    else -> return@launch
                }
                NotificationHelper.show(context, channel, notifId, notifTitle, notifBody)
                NotificationScheduler.rescheduleAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NotificationScheduler.rescheduleAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
