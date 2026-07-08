package com.arflix.tv.data.model

import java.io.Serializable

/**
 * The fixed set of nav destinations Xadarr can show in its top bar / sidebar.
 * Deliberately 1:1 with [com.arflix.tv.ui.components.SidebarItem] — Smart Home
 * isn't included since it doesn't have a real nav-bar slot yet (it opens via
 * the Shield remote's Menu key or a Settings row, and Joe's noted that entry
 * point isn't final). Add it here once it gets a proper tab.
 */
enum class NavSectionKind {
    SEARCH, HOME, DISCOVER, TV, CAMERAS, SETTINGS
}

/**
 * Per-profile customization of a nav destination: rename, icon-only, hide, and
 * reorder. `label = null` and `iconOnly = false` reproduce today's fixed
 * behavior exactly, so an unmigrated/default profile renders identically to
 * before this existed.
 */
data class NavSectionConfig(
    val kind: NavSectionKind,
    val label: String? = null,
    val iconOnly: Boolean = false,
    val visible: Boolean = true,
    val order: Int,
) : Serializable
