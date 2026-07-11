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
    SEARCH, HOME, DISCOVER, TV, CAMERAS, SETTINGS,
    // Open-ended nav item resolved by `target` instead of a fixed destination
    // (e.g. Movies -> "seeall:recent", Recordings -> "recordings"). Added for
    // home_layout nav customization — see NavTargetResolver.
    CUSTOM
}

/**
 * Per-profile customization of a nav destination: rename, icon-only, hide, and
 * reorder. `label = null` and `iconOnly = false` reproduce today's fixed
 * behavior exactly, so an unmigrated/default profile renders identically to
 * before this existed.
 *
 * `customId` and `target` are only meaningful when `kind == CUSTOM`: `customId`
 * is a stable identity for the item (e.g. "movies") since CUSTOM entries don't
 * have a dedicated enum case, and `target` is a route string resolved by
 * NavTargetResolver (e.g. "guide", "seeall:recent", "app:cameras").
 */
data class NavSectionConfig(
    val kind: NavSectionKind,
    val customId: String? = null,
    val label: String? = null,
    val target: String? = null,
    val iconOnly: Boolean = false,
    val visible: Boolean = true,
    val order: Int,
) : Serializable
