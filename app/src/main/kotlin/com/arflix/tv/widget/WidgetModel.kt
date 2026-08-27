package com.arflix.tv.widget

import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.repository.ActivityCategory
import com.arflix.tv.data.repository.ActivityFeedItem
import com.arflix.tv.data.repository.ActivityFeedSnapshot
import org.json.JSONArray
import org.json.JSONObject

// The compact shape actually persisted into Glance's per-instance Preferences state — deliberately
// separate from the domain ActivityFeedItem/ActivityFeedSnapshot (data/repository), which know
// nothing about local image cache paths or JSON persistence. Never carries raw bitmaps: only a
// local cache file path (see WidgetRefreshWorker), titles/subtitles, and deep-link ids.
val WIDGET_STATE_KEY = stringPreferencesKey("activity_widget_state")

data class WidgetItemModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val imagePath: String?,
    val mediaTypeName: String?,
    val tmdbId: Int?,
)

data class WidgetSectionModel(
    val category: ActivityCategory,
    val items: List<WidgetItemModel>,
)

data class WidgetStateModel(
    val sections: List<WidgetSectionModel>,
    val generatedAtMs: Long,
)

fun WidgetStateModel.toJson(): String {
    val root = JSONObject()
    root.put("generatedAtMs", generatedAtMs)
    val sectionsArr = JSONArray()
    sections.forEach { section ->
        val sectionObj = JSONObject()
        sectionObj.put("category", section.category.name)
        val itemsArr = JSONArray()
        section.items.forEach { item ->
            itemsArr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("subtitle", item.subtitle)
                if (item.imagePath != null) put("imagePath", item.imagePath)
                if (item.mediaTypeName != null) put("mediaTypeName", item.mediaTypeName)
                if (item.tmdbId != null) put("tmdbId", item.tmdbId)
            })
        }
        sectionObj.put("items", itemsArr)
        sectionsArr.put(sectionObj)
    }
    root.put("sections", sectionsArr)
    return root.toString()
}

fun parseWidgetState(json: String): WidgetStateModel {
    if (json.isBlank()) return WidgetStateModel(emptyList(), 0L)
    return runCatching {
        val root = JSONObject(json)
        val sectionsArr = root.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until sectionsArr.length()).mapNotNull { i ->
            val sectionObj = sectionsArr.optJSONObject(i) ?: return@mapNotNull null
            val category = runCatching { ActivityCategory.valueOf(sectionObj.optString("category")) }.getOrNull()
                ?: return@mapNotNull null
            val itemsArr = sectionObj.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).mapNotNull { j ->
                val itemObj = itemsArr.optJSONObject(j) ?: return@mapNotNull null
                WidgetItemModel(
                    id = itemObj.optString("id"),
                    title = itemObj.optString("title"),
                    subtitle = itemObj.optString("subtitle"),
                    imagePath = itemObj.optString("imagePath").ifBlank { null },
                    mediaTypeName = itemObj.optString("mediaTypeName").ifBlank { null },
                    tmdbId = itemObj.optInt("tmdbId").takeIf { itemObj.has("tmdbId") },
                )
            }
            WidgetSectionModel(category, items)
        }
        WidgetStateModel(sections, root.optLong("generatedAtMs"))
    }.getOrDefault(WidgetStateModel(emptyList(), 0L))
}

fun ActivityFeedItem.toWidgetItemModel(imagePath: String?): WidgetItemModel = WidgetItemModel(
    id = id,
    title = title,
    subtitle = subtitle,
    imagePath = imagePath,
    mediaTypeName = mediaType?.name,
    tmdbId = resolvedTmdbId,
)

fun ActivityFeedSnapshot.allItems(): List<ActivityFeedItem> = premiering + downloaded + watched + gameDay
