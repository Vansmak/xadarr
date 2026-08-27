package com.arflix.tv.widget

import android.content.Context
import android.content.Intent
import com.arflix.tv.MainActivity
import com.arflix.tv.data.model.MediaType

// Deep-link-on-cold-start extras for a tapped widget item — mirrors ProgramReminderWorker's
// EXTRA_REMINDER_CHANNEL_ID mechanism exactly (see MainActivity.kt's pendingReminderChannelId):
// Glance's actionStartActivity fires a plain Intent synchronously, so the TMDB id must already
// be resolved (see ActivityFeedRepository) rather than looked up at tap time.
const val EXTRA_WIDGET_MEDIA_TYPE = "widget_media_type"
const val EXTRA_WIDGET_TMDB_ID = "widget_tmdb_id"

fun widgetTapIntent(context: Context, mediaTypeName: String?, tmdbId: Int?): Intent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    if (mediaTypeName != null && tmdbId != null) {
        intent.putExtra(EXTRA_WIDGET_MEDIA_TYPE, mediaTypeName)
        intent.putExtra(EXTRA_WIDGET_TMDB_ID, tmdbId)
    }
    return intent
}

data class WidgetDeepLinkRequest(val mediaType: MediaType, val tmdbId: Int)

fun parseWidgetDeepLink(intent: Intent?): WidgetDeepLinkRequest? {
    val mediaTypeName = intent?.getStringExtra(EXTRA_WIDGET_MEDIA_TYPE) ?: return null
    val tmdbId = intent.getIntExtra(EXTRA_WIDGET_TMDB_ID, -1).takeIf { it > 0 } ?: return null
    val mediaType = runCatching { MediaType.valueOf(mediaTypeName) }.getOrNull() ?: return null
    return WidgetDeepLinkRequest(mediaType, tmdbId)
}
