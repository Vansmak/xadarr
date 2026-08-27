package com.arflix.tv.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.arflix.tv.data.repository.ActivityCategory

private val WidgetBg = ColorProvider(Color(0xFF121212))
private val WidgetFg = ColorProvider(Color(0xFFEDEDED))
private val WidgetFgDim = ColorProvider(Color(0xFF9AA0A6))
private val WidgetAccent = ColorProvider(Color(0xFF4F7FB0))

private fun categoryLabel(category: ActivityCategory): String = when (category) {
    ActivityCategory.PREMIERING -> "Premiering"
    ActivityCategory.DOWNLOADED -> "Downloaded"
    ActivityCategory.WATCHED -> "Watched"
    ActivityCategory.GAME_DAY -> "Game Day"
}

// All five widget classes below read the same persisted WIDGET_STATE_KEY (refreshed once by
// WidgetRefreshWorker for all of them together — placing multiple small widgets doesn't mean
// multiple network fetches). Each just renders a different slice of it. Split into small
// per-category widgets after the combined one turned out to need a large footprint just to
// show anything useful in practice — this way a category no one cares about just isn't placed.

/** The original combined widget — every category in one scrollable list. */
class ActivityWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val model = currentWidgetState()
            CombinedWidgetContent(model)
        }
    }
}

class PremieringWidget : CategoryWidget(ActivityCategory.PREMIERING)
class DownloadedWidget : CategoryWidget(ActivityCategory.DOWNLOADED)
class WatchedWidget : CategoryWidget(ActivityCategory.WATCHED)
class GameDayWidget : CategoryWidget(ActivityCategory.GAME_DAY)

abstract class CategoryWidget(private val category: ActivityCategory) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val model = currentWidgetState()
            val items = model.sections.firstOrNull { it.category == category }?.items ?: emptyList()
            SingleCategoryWidgetContent(category, items)
        }
    }
}

@Composable
private fun currentWidgetState(): WidgetStateModel {
    val prefs = currentState<Preferences>()
    return parseWidgetState(prefs[WIDGET_STATE_KEY].orEmpty())
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetRefreshWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
        )
    }
}

@Composable
private fun WidgetHeader(title: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(color = WidgetFg, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "Refresh",
            style = TextStyle(color = WidgetAccent),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()),
        )
    }
}

@Composable
private fun CombinedWidgetContent(model: WidgetStateModel) {
    val nonEmptySections = model.sections.filter { it.items.isNotEmpty() }
    LazyColumn(modifier = GlanceModifier.fillMaxWidth().background(WidgetBg)) {
        item { WidgetHeader("Xadarr Activity") }
        if (nonEmptySections.isEmpty()) {
            item {
                Text(
                    text = "Nothing to show yet.",
                    style = TextStyle(color = WidgetFgDim),
                    modifier = GlanceModifier.padding(12.dp),
                )
            }
        }
        nonEmptySections.forEach { section ->
            item {
                Text(
                    text = categoryLabel(section.category),
                    style = TextStyle(color = WidgetFgDim, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            items(section.items, itemId = { it.id.hashCode().toLong() }) { widgetItem ->
                ActivityRow(widgetItem)
            }
        }
    }
}

@Composable
private fun SingleCategoryWidgetContent(category: ActivityCategory, items: List<WidgetItemModel>) {
    LazyColumn(modifier = GlanceModifier.fillMaxWidth().background(WidgetBg)) {
        item { WidgetHeader(categoryLabel(category)) }
        if (items.isEmpty()) {
            item {
                Text(
                    text = "Nothing right now.",
                    style = TextStyle(color = WidgetFgDim),
                    modifier = GlanceModifier.padding(12.dp),
                )
            }
        }
        items(items, itemId = { it.id.hashCode().toLong() }) { widgetItem ->
            ActivityRow(widgetItem)
        }
    }
}

@Composable
private fun ActivityRow(item: WidgetItemModel) {
    val context = LocalContext.current
    val intent = widgetTapIntent(context, item.mediaTypeName, item.tmdbId)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        val bitmap = item.imagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = item.title,
                modifier = GlanceModifier.size(40.dp).cornerRadius(6.dp),
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.title,
                style = TextStyle(color = WidgetFg),
                maxLines = 1,
            )
            Text(
                text = item.subtitle,
                style = TextStyle(color = WidgetAccent),
                maxLines = 1,
            )
        }
    }
}
