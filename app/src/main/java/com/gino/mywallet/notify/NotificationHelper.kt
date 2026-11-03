package com.gino.mywallet.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gino.mywallet.MainActivity
import com.gino.mywallet.R

object NotificationHelper {
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            Triple(NotifyIds.CHANNEL_LOTTERY, "打卡領券", "每日拉霸券與樂透開獎提醒"),
            Triple(NotifyIds.CHANNEL_TASK, "任務提醒", "任務時間到提醒"),
            Triple(NotifyIds.CHANNEL_CHARITY, "公益", "本月公益提醒"),
            Triple(NotifyIds.CHANNEL_COURSE, "課表", "上課前提醒"),
            Triple(NotifyIds.CHANNEL_EVENT, "行程", "個人行程提醒"),
            Triple(NotifyIds.CHANNEL_LOAN, "借貸", "借款到期提醒"),
            Triple(NotifyIds.CHANNEL_FOCUS, "專注進行中", "專注計時進行中")
        ).forEach { (id, name, desc) ->
            val importance = if (id == NotifyIds.CHANNEL_FOCUS) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }
            nm.createNotificationChannel(
                NotificationChannel(id, name, importance).apply { description = desc }
            )
        }
    }

    fun openAppPending(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun show(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String
    ) {
        ensureChannels(context)
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPending(context, notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS 未授權
        }
    }

    fun focusOngoing(context: Context, title: String, body: String): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, NotifyIds.CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground))
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPending(context, NotifyIds.NOTIF_FOCUS))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
