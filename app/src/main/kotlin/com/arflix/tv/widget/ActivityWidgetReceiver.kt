package com.arflix.tv.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * A freshly-placed widget starts with empty Preferences state — without an immediate refresh
 * it would sit blank for up to the 15-minute periodic-work floor. onUpdate fires both on first
 * placement and on any subsequent platform-triggered update; enqueuing here (KEEP policy)
 * keeps it cheap even if the platform calls it more than once in quick succession.
 *
 * One shared WidgetRefreshWorker run populates state for every widget type at once (see
 * WidgetRefreshWorker's per-class glanceId lookups) — placing several small category widgets
 * doesn't mean several separate network fetches.
 */
private fun enqueueImmediateRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        WidgetRefreshWorker.WORK_NAME_ONE_TIME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
    )
}

abstract class BaseActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueueImmediateRefresh(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        enqueueImmediateRefresh(context)
    }
}

/** The original combined widget — every category in one scrollable list. */
class ActivityWidgetReceiver : BaseActivityWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
}

class PremieringWidgetReceiver : BaseActivityWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PremieringWidget()
}

class DownloadedWidgetReceiver : BaseActivityWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DownloadedWidget()
}

class WatchedWidgetReceiver : BaseActivityWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchedWidget()
}

class GameDayWidgetReceiver : BaseActivityWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GameDayWidget()
}
