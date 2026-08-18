package com.arflix.tv.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arflix.tv.MainActivity
import com.arflix.tv.R
import com.arflix.tv.data.repository.ProgramReminderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Fires the "remind me" notification scheduled by [ProgramReminderRepository]. Best-effort —
 *  see that class's doc for why this isn't a guaranteed-delivery alarm. */
class ProgramReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProgramReminderWorkerEntryPoint {
        fun programReminderRepository(): ProgramReminderRepository
    }

    private val deps: ProgramReminderWorkerEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ProgramReminderWorkerEntryPoint::class.java)
    }

    companion object {
        const val TAG = "ProgramReminderWorker"
        const val CHANNEL_ID = "program_reminders"
        const val INPUT_CHANNEL_ID = "channel_id"
        const val INPUT_CHANNEL_NAME = "channel_name"
        const val INPUT_PROGRAM_TITLE = "program_title"
        const val INPUT_REMINDER_KEY = "reminder_key"
        const val EXTRA_REMINDER_CHANNEL_ID = "reminder_channel_id"
    }

    override suspend fun doWork(): Result {
        val channelId = inputData.getString(INPUT_CHANNEL_ID) ?: return Result.failure()
        val channelName = inputData.getString(INPUT_CHANNEL_NAME).orEmpty()
        val programTitle = inputData.getString(INPUT_PROGRAM_TITLE).orEmpty()
        val reminderKey = inputData.getString(INPUT_REMINDER_KEY).orEmpty()

        postNotification(channelId, channelName, programTitle)
        deps.programReminderRepository().consumeIfDue(reminderKey)
        return Result.success()
    }

    private fun postNotification(channelId: String, channelName: String, programTitle: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Program reminders",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Alerts you set for upcoming live TV programs" }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REMINDER_CHANNEL_ID, channelId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            channelId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(programTitle.ifBlank { "Starting soon" })
            .setContentText(channelName.ifBlank { "Live TV" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(channelId.hashCode(), notification)
    }
}
