package com.example.processA

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.LocalFtsIndexHelper
import com.example.engine.ImportantNotificationFilter

/**
 * Schedules and fires local Android CATEGORY_REMINDER notifications when a direct
 * CalendarContract write isn't available, so reminders always complete and save
 * automatically without waiting for manual "Save" on a Calendar template screen.
 */
object NovaReminderScheduler {

    const val REMINDER_CHANNEL_ID = ImportantNotificationFilter.NOVA_REMINDER_CHANNEL_ID
    private const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
    private const val EXTRA_REMINDER_ID = "extra_reminder_id"
    private const val EXTRA_REMINDER_WHEN = "extra_reminder_when"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Nova Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled reminders created automatically by Nova"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun scheduleReminder(
        context: Context,
        title: String,
        triggerAtMillis: Long,
        formattedTimeLabel: String
    ): Boolean {
        ensureChannel(context)
        val reminderId = ((title + "|" + triggerAtMillis).hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)

        val alarmIntent = Intent(context, NovaReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_WHEN, formattedTimeLabel)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val scheduled = runCatching {
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                true
            } else {
                false
            }
        }.onFailure { ex ->
            Log.w("NovaReminderScheduler", "Alarm schedule fallback: ${ex.message}")
        }.getOrDefault(false)

        return scheduled
    }

    internal fun postFiredReminderNotification(
        context: Context,
        title: String,
        whenLabel: String,
        notificationId: Int
    ) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Reminder: $title")
            .setContentText(if (whenLabel.isNotBlank()) "Scheduled for $whenLabel" else "Reminder due now")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notif)
        }

        // Also archive into 14-day notification archive + FTS so briefings and "read notifications" include it accurately
        val fts = LocalFtsIndexHelper(context)
        runCatching {
            val now = System.currentTimeMillis()
            val remBody = if (whenLabel.isNotBlank()) "Scheduled for $whenLabel" else "Reminder due now"
            val assessment = ImportantNotificationFilter.assessImportance(
                type = ImportantNotificationFilter.ImportantNotificationType.REMINDER,
                title = title,
                body = remBody,
                appLabel = "Nova Reminders"
            )
            fts.upsertNotificationArchive(
                alert = ImportantNotificationFilter.ImportantNotificationAlert(
                    id = "rem_fired_$notificationId",
                    key = "rem_fired_$notificationId",
                    packageName = context.packageName,
                    appLabel = "Nova Reminders",
                    type = ImportantNotificationFilter.ImportantNotificationType.REMINDER,
                    title = title,
                    body = remBody,
                    timestampMs = now,
                    spokenPrompt = "Reminder alert: $title, $remBody.",
                    shortLabel = "Reminder • $title",
                    importanceScore = assessment.score,
                    importanceTier = assessment.tier,
                    flagReasons = assessment.flagReasons
                ),
                markRead = false,
                nowMs = now,
                indexFts = true
            )
        }
        runCatching { fts.close() }
    }
}

class NovaReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("extra_reminder_title")?.trim().orEmpty().ifEmpty { "Nova Reminder" }
        val whenLabel = intent.getStringExtra("extra_reminder_when")?.trim().orEmpty()
        val id = intent.getIntExtra("extra_reminder_id", (System.currentTimeMillis() and 0x7FFFFFFF).toInt())
        NovaReminderScheduler.postFiredReminderNotification(context, title, whenLabel, id)
    }
}
