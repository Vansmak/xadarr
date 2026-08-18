package com.arflix.tv.data.repository

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.util.settingsDataStore
import com.arflix.tv.worker.ProgramReminderWorker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder the user set for a future program — enough to rebuild the notification and to
 *  navigate back to the right channel when it's tapped. */
data class ProgramReminder(
    val key: String,          // "${channelId}|${startUtcMillis}" — stable, cancel/dedupe key
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val startUtcMillis: Long,
)

private val PROGRAM_REMINDERS_KEY = stringPreferencesKey("program_reminders_json")

/** How long before a program starts to fire the reminder notification. */
private val REMINDER_LEAD_MINUTES = 5L

fun reminderKey(channelId: String, program: IptvProgram): String = "$channelId|${program.startUtcMillis}"

/**
 * Persists and schedules "remind me" reminders set from [ProgramInfoPopup]. Best-effort: uses
 * WorkManager, which on a TV box that's plugged in and awake generally fires reliably, but
 * Android can still defer or drop it if the device is deep asleep or the app's been force-killed
 * — there's no exact-alarm permission request here, so treat this as a nudge, not a guarantee.
 */
@Singleton
class ProgramReminderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    /** True if the app can actually show a notification right now. The worker checks this too
     *  and silently no-ops if it's false, so callers should surface it *before* scheduling —
     *  otherwise a reminder looks "set" but can never fire (Joe, 2026-08-15: set a reminder for
     *  an Indiana Fever game on ION and never got it — POST_NOTIFICATIONS was never granted). */
    fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun observeReminders(): Flow<List<ProgramReminder>> =
        context.settingsDataStore.data.map { prefs -> decode(prefs[PROGRAM_REMINDERS_KEY].orEmpty()) }

    suspend fun schedule(channelId: String, channelName: String, program: IptvProgram) {
        val key = reminderKey(channelId, program)
        val reminder = ProgramReminder(
            key = key,
            channelId = channelId,
            channelName = channelName,
            programTitle = program.title,
            startUtcMillis = program.startUtcMillis,
        )
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[PROGRAM_REMINDERS_KEY].orEmpty()).toMutableList()
            existing.removeAll { it.key == key }
            existing.add(reminder)
            prefs[PROGRAM_REMINDERS_KEY] = gson.toJson(existing)
        }

        val fireAtMillis = program.startUtcMillis - REMINDER_LEAD_MINUTES * 60_000L
        val delayMillis = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ProgramReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ProgramReminderWorker.INPUT_CHANNEL_ID to channelId,
                    ProgramReminderWorker.INPUT_CHANNEL_NAME to channelName,
                    ProgramReminderWorker.INPUT_PROGRAM_TITLE to program.title,
                    ProgramReminderWorker.INPUT_REMINDER_KEY to key,
                )
            )
            .addTag(ProgramReminderWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(channelId: String, program: IptvProgram) {
        val key = reminderKey(channelId, program)
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[PROGRAM_REMINDERS_KEY].orEmpty()).toMutableList()
            existing.removeAll { it.key == key }
            prefs[PROGRAM_REMINDERS_KEY] = gson.toJson(existing)
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    suspend fun consumeIfDue(key: String) {
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[PROGRAM_REMINDERS_KEY].orEmpty()).toMutableList()
            existing.removeAll { it.key == key }
            prefs[PROGRAM_REMINDERS_KEY] = gson.toJson(existing)
        }
    }

    private fun workName(key: String) = "program_reminder_$key"

    private fun decode(raw: String): List<ProgramReminder> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = TypeToken.getParameterized(List::class.java, ProgramReminder::class.java).type
            gson.fromJson<List<ProgramReminder>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
