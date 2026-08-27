package com.arflix.tv.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.arflix.tv.data.repository.ActivityFeedItem
import com.arflix.tv.data.repository.ActivityFeedRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream

/**
 * Refreshes the Activity Feed home-screen widget — mirrors TraktSyncWorker's plain-CoroutineWorker
 * + manual @EntryPoint Hilt-injection pattern (this codebase doesn't use @HiltWorker/@AssistedInject).
 *
 * Bitmap fetching happens here, never inside ActivityWidget's Glance composition (Glance
 * composables run synchronously during RemoteViews generation and cannot do network I/O).
 * Only local cache-file paths are persisted into Glance's Preferences state — never raw bitmaps.
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetRefreshWorkerEntryPoint {
        fun activityFeedRepository(): ActivityFeedRepository
    }

    private val deps: WidgetRefreshWorkerEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, WidgetRefreshWorkerEntryPoint::class.java)
    }

    companion object {
        const val WORK_NAME = "widget_refresh_worker"
        const val WORK_NAME_ONE_TIME = "widget_refresh_worker_one_time"
        private const val IMAGE_TARGET_PX = 200
    }

    // Widget class -> its placed instance ids. All five read the same WIDGET_STATE_KEY —
    // one refresh populates every placed widget, whether it's the combined view or a single
    // small category tile (see ActivityWidget.kt).
    private suspend fun placedWidgets(): Map<GlanceAppWidget, List<androidx.glance.GlanceId>> {
        val manager = GlanceAppWidgetManager(applicationContext)
        val widgets: List<GlanceAppWidget> = listOf(
            ActivityWidget(), PremieringWidget(), DownloadedWidget(), WatchedWidget(), GameDayWidget(),
        )
        return widgets.associateWith { widget ->
            runCatching { manager.getGlanceIds(widget::class.java) }.getOrDefault(emptyList())
        }
    }

    override suspend fun doWork(): Result {
        val widgetIds = placedWidgets().filterValues { it.isNotEmpty() }
        if (widgetIds.isEmpty()) {
            // No widget instances of any kind placed — don't waste battery/network on a periodic tick.
            return Result.success()
        }

        return try {
            val snapshot = deps.activityFeedRepository().getSnapshot()
            val allItems = snapshot.allItems()
            val imagePaths = allItems.associate { it.id to fetchAndCacheImage(it) }

            val state = WidgetStateModel(
                sections = listOf(
                    WidgetSectionModel(com.arflix.tv.data.repository.ActivityCategory.PREMIERING,
                        snapshot.premiering.map { it.toWidgetItemModel(imagePaths[it.id]) }),
                    WidgetSectionModel(com.arflix.tv.data.repository.ActivityCategory.DOWNLOADED,
                        snapshot.downloaded.map { it.toWidgetItemModel(imagePaths[it.id]) }),
                    WidgetSectionModel(com.arflix.tv.data.repository.ActivityCategory.WATCHED,
                        snapshot.watched.map { it.toWidgetItemModel(imagePaths[it.id]) }),
                    WidgetSectionModel(com.arflix.tv.data.repository.ActivityCategory.GAME_DAY,
                        snapshot.gameDay.map { it.toWidgetItemModel(imagePaths[it.id]) }),
                ),
                generatedAtMs = snapshot.generatedAtMs,
            )
            val json = state.toJson()

            widgetIds.forEach { (widget, glanceIds) ->
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(applicationContext, glanceId) { prefs ->
                        prefs[WIDGET_STATE_KEY] = json
                    }
                    widget.update(applicationContext, glanceId)
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    // RGB_565 + allowHardware(false): a HARDWARE-config bitmap crashes ImageProvider/RemoteViews.
    // Cached by item.id so a failed/skipped refresh just leaves the previous cycle's image in
    // place rather than needing separate eviction logic — overwritten every successful cycle.
    private suspend fun fetchAndCacheImage(item: ActivityFeedItem): String? {
        val url = item.imageUrl?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val request = ImageRequest.Builder(applicationContext)
                .data(url)
                .size(IMAGE_TARGET_PX, IMAGE_TARGET_PX)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build()
            val result = applicationContext.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: return null
            val cacheDir = File(applicationContext.cacheDir, "widget_cache").apply { mkdirs() }
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val file = File(cacheDir, "$safeId.webp")
            FileOutputStream(file).use { out ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
