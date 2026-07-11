package com.arflix.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.skin.XadarrSkin
import com.arflix.tv.ui.theme.AnimationConstants
import com.arflix.tv.ui.theme.ArflixTypography
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveDims
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.util.settingsDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

val AppTopBarHeight = 82.dp
val AppTopBarTopPadding = 0.dp
val AppTopBarContentTopInset = 98.dp
/** On mobile/tablet where the topbar is hidden, use a small status-bar-like inset instead. */
val MobileContentTopInset = 16.dp
val AppTopBarHorizontalPadding = 28.dp

// CUSTOM is unreachable here: navItems() filters it out before this is ever
// called (customNavEntries()/topBarFocusedCustomEntry() handle CUSTOM
// entries on a separate path — they never map to a SidebarItem). Falls back
// to HOME rather than throwing in case a future caller ever slips one through.
fun com.arflix.tv.data.model.NavSectionKind.toSidebarItem(): SidebarItem = when (this) {
    com.arflix.tv.data.model.NavSectionKind.SEARCH -> SidebarItem.SEARCH
    com.arflix.tv.data.model.NavSectionKind.HOME -> SidebarItem.HOME
    com.arflix.tv.data.model.NavSectionKind.DISCOVER -> SidebarItem.DISCOVER
    com.arflix.tv.data.model.NavSectionKind.TV -> SidebarItem.TV
    com.arflix.tv.data.model.NavSectionKind.CAMERAS -> SidebarItem.CAMERAS
    com.arflix.tv.data.model.NavSectionKind.SETTINGS -> SidebarItem.SETTINGS
    com.arflix.tv.data.model.NavSectionKind.CUSTOM -> SidebarItem.HOME
}

private fun SidebarItem.toNavSectionKind(): com.arflix.tv.data.model.NavSectionKind = when (this) {
    SidebarItem.SEARCH -> com.arflix.tv.data.model.NavSectionKind.SEARCH
    SidebarItem.HOME -> com.arflix.tv.data.model.NavSectionKind.HOME
    SidebarItem.DISCOVER -> com.arflix.tv.data.model.NavSectionKind.DISCOVER
    SidebarItem.TV -> com.arflix.tv.data.model.NavSectionKind.TV
    SidebarItem.CAMERAS -> com.arflix.tv.data.model.NavSectionKind.CAMERAS
    SidebarItem.SETTINGS -> com.arflix.tv.data.model.NavSectionKind.SETTINGS
}

/** Section config for a given nav item, or null if not customized (use defaults). */
fun navSectionFor(
    item: SidebarItem,
    sections: List<com.arflix.tv.data.model.NavSectionConfig>
): com.arflix.tv.data.model.NavSectionConfig? = sections.firstOrNull { it.kind == item.toNavSectionKind() }

// Navigation items that appear CENTERED in the top bar, in the user's configured
// order (or declaration order if `sections` is empty, i.e. not yet customized).
// Icon-only items are grouped at the end of the list — visually and in focus
// order they sit immediately next to the Settings gear rather than wherever
// their configured `order` would otherwise place them, so a compacted item
// doesn't leave an odd gap in the middle of a row of full labeled chips.
// CAMERAS is included only when a Neolink URL is configured. A section explicitly
// hidden via customization is excluded regardless of that gate.
// Settings is NOT in this list — it's rendered as a standalone gear icon on the right.
fun navItems(
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): List<SidebarItem> {
    if (sections.isEmpty()) {
        return SidebarItem.entries.filter { it != SidebarItem.SETTINGS && (it != SidebarItem.CAMERAS || neolinkConfigured) }
    }
    return sections
        .filter { it.visible && it.kind != com.arflix.tv.data.model.NavSectionKind.SETTINGS && it.kind != com.arflix.tv.data.model.NavSectionKind.CUSTOM }
        .filter { it.kind != com.arflix.tv.data.model.NavSectionKind.CAMERAS || neolinkConfigured }
        .sortedWith(compareBy({ it.iconOnly }, { it.order }))
        .map { it.kind.toSidebarItem() }
}

/**
 * Open-ended nav entries (kind == CUSTOM) — rendered after the fixed
 * [navItems] chips and before Settings. Unlike fixed items they don't map to
 * a [SidebarItem]/screen; activating one resolves `target` via NavTargets
 * instead (see topBarFocusedCustomEntry).
 */
fun customNavEntries(
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): List<com.arflix.tv.data.model.NavSectionConfig> = sections
    .filter { it.visible && it.kind == com.arflix.tv.data.model.NavSectionKind.CUSTOM }
    .sortedWith(compareBy({ it.iconOnly }, { it.order }))

fun topBarMaxIndex(
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): Int {
    val navCount = navItems(neolinkConfigured, sections).size + customNavEntries(sections).size
    return if (hasProfile) navCount + 1 else navCount
}

fun topBarSelectedIndex(
    selectedItem: SidebarItem,
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): Int {
    val items = navItems(neolinkConfigured, sections)
    if (selectedItem == SidebarItem.SETTINGS) return topBarMaxIndex(hasProfile, neolinkConfigured, sections)
    val base = items.indexOf(selectedItem)
    if (base < 0) return -1
    return if (hasProfile) base + 1 else base
}

fun topBarFocusedItem(
    focusedIndex: Int,
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): SidebarItem? {
    val items = navItems(neolinkConfigured, sections)
    val customCount = customNavEntries(sections).size
    if (hasProfile && focusedIndex == 0) return null // profile avatar focused
    val itemIndex = if (hasProfile) focusedIndex - 1 else focusedIndex
    if (itemIndex == items.size + customCount) return SidebarItem.SETTINGS
    if (itemIndex >= items.size) return null // falls within the custom-entry range
    return items.getOrNull(itemIndex)
}

/** The CUSTOM nav entry at [focusedIndex], or null if focus is elsewhere (fixed item, Settings, profile avatar). */
fun topBarFocusedCustomEntry(
    focusedIndex: Int,
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): com.arflix.tv.data.model.NavSectionConfig? {
    val items = navItems(neolinkConfigured, sections)
    val customs = customNavEntries(sections)
    if (hasProfile && focusedIndex == 0) return null
    val itemIndex = if (hasProfile) focusedIndex - 1 else focusedIndex
    val customIdx = itemIndex - items.size
    return customs.getOrNull(customIdx)
}

// ── Home-variant index math ──────────────────────────────────────────────────
// Home renders its chip row left-aligned with profile+settings clustered on
// the right (profile is NOT index 0 here, unlike every other screen still on
// the legacy profile-first layout above). Kept as separate functions rather
// than branching the existing ones so the 6 screens still on the old model
// are completely untouched by this — see AppTopBar(homeVariant=true).

private fun homeTopBarNavCount(neolinkConfigured: Boolean, sections: List<com.arflix.tv.data.model.NavSectionConfig>): Int =
    navItems(neolinkConfigured, sections).size + customNavEntries(sections).size

fun homeTopBarMaxIndex(
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): Int {
    val navCount = homeTopBarNavCount(neolinkConfigured, sections)
    return navCount + (if (hasProfile) 1 else 0) // index of Settings
}

/** Index at which the profile avatar sits, or null if there's no profile to show. */
fun homeTopBarProfileFocusIndex(
    hasProfile: Boolean,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): Int? {
    if (!hasProfile) return null
    return homeTopBarNavCount(neolinkConfigured, sections)
}

fun homeTopBarSelectedIndex(
    selectedItem: SidebarItem,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): Int {
    if (selectedItem == SidebarItem.SETTINGS) return -1 // Settings is never the "current" screen from Home
    return navItems(neolinkConfigured, sections).indexOf(selectedItem)
}

fun homeTopBarFocusedItem(
    focusedIndex: Int,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): SidebarItem? {
    val items = navItems(neolinkConfigured, sections)
    if (focusedIndex !in items.indices) return null // custom range, profile, settings, or out of bounds
    return items[focusedIndex]
}

/** The CUSTOM nav entry at [focusedIndex] in the Home-variant layout, or null. */
fun homeTopBarFocusedCustomEntry(
    focusedIndex: Int,
    neolinkConfigured: Boolean = true,
    sections: List<com.arflix.tv.data.model.NavSectionConfig> = emptyList()
): com.arflix.tv.data.model.NavSectionConfig? {
    val customIdx = focusedIndex - navItems(neolinkConfigured, sections).size
    return customNavEntries(sections).getOrNull(customIdx)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTopBar(
    selectedItem: SidebarItem,
    isFocused: Boolean,
    focusedIndex: Int,
    profile: Profile? = null,
    profileCount: Int = 1,
    clockFormat: String = "24h",
    syncStatus: com.arflix.tv.data.repository.CloudSyncStatus = com.arflix.tv.data.repository.CloudSyncStatus.NOT_SIGNED_IN,
    hasUpdateBadge: Boolean = false,
    homeVariant: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Always show the profile avatar when a profile exists — it's clickable
    // and opens the profile switcher. The name text was removed per the mockup
    // (avatar-only, no label).
    val showProfile = profile != null
    val hasProfile = showProfile
    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    val currentTime = rememberTopBarTime(clockFormat)
    val selectedIndex = remember(selectedItem, hasProfile, neolinkConfigured, navSections, homeVariant) {
        if (homeVariant) homeTopBarSelectedIndex(selectedItem, neolinkConfigured, navSections)
        else topBarSelectedIndex(selectedItem, hasProfile, neolinkConfigured, navSections)
    }
    val settingsIndex = if (homeVariant) homeTopBarMaxIndex(hasProfile, neolinkConfigured, navSections)
        else topBarMaxIndex(hasProfile, neolinkConfigured, navSections)
    val settingsFocused = isFocused && focusedIndex == settingsIndex
    val settingsSelected = selectedItem == SidebarItem.SETTINGS
    val profileFocusIndex = if (homeVariant) homeTopBarProfileFocusIndex(hasProfile, neolinkConfigured, navSections) else (if (hasProfile) 0 else null)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTopBarContentTopInset)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.36f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppTopBarHeight)
                .padding(start = AppTopBarHorizontalPadding, end = AppTopBarHorizontalPadding, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (homeVariant) {
            // ── LEFT: all nav + custom chips, left-aligned, configured order ──
            // (navItems()/customNavEntries() already sort icon-only trailing;
            // no separate cluster needed once nothing is being centered.)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                navItems(neolinkConfigured, navSections).forEachIndexed { index, item ->
                    val section = navSectionFor(item, navSections)
                    TopBarNavChip(
                        item = item,
                        isFocused = isFocused && focusedIndex == index,
                        isSelected = selectedIndex == index,
                        labelOverride = section?.label,
                        iconOnly = section?.iconOnly == true
                    )
                }
                val fixedCount = navItems(neolinkConfigured, navSections).size
                customNavEntries(navSections).forEachIndexed { index, entry ->
                    val itemFocusIndex = fixedCount + index
                    TopBarCustomNavChip(
                        label = entry.label ?: entry.customId.orEmpty(),
                        isFocused = isFocused && focusedIndex == itemFocusIndex,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── RIGHT: network, clock, profile avatar, settings gear ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TopBarNetworkStatus()
                Text(
                    text = currentTime,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.55f)
                )
                if (showProfile && profile != null && profileFocusIndex != null) {
                    TopBarProfileAvatar(
                        profile = profile,
                        isFocused = isFocused && focusedIndex == profileFocusIndex
                    )
                }
                TopBarSettingsGear(
                    isFocused = settingsFocused,
                    isSelected = settingsSelected,
                    hasBadge = hasUpdateBadge
                )
            }
            return@Row
        }

            // ── LEFT: Profile avatar (only if multiple profiles) ──
            if (showProfile && profile != null) {
                TopBarProfileAvatar(
                    profile = profile,
                    isFocused = isFocused && focusedIndex == 0
                )
                Spacer(modifier = Modifier.width(16.dp))
            }

            // ── CENTER: Navigation chips (Search, Home, Watchlist, TV) ──
            // Labeled (icon+text) chips only — icon-only chips render in the
            // right-side cluster below, grouped next to Settings instead of
            // floating in this centered row. Focus index math still comes from
            // the item's position in the full navItems() list, so D-pad
            // traversal order is unaffected by which row draws it.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    navItems(neolinkConfigured, navSections).forEachIndexed { index, item ->
                        val section = navSectionFor(item, navSections)
                        if (section?.iconOnly == true) return@forEachIndexed
                        val itemFocusIndex = if (hasProfile) index + 1 else index
                        TopBarNavChip(
                            item = item,
                            isFocused = isFocused && focusedIndex == itemFocusIndex,
                            isSelected = selectedIndex == itemFocusIndex,
                            labelOverride = section?.label,
                            iconOnly = false
                        )
                    }
                    val fixedCount = navItems(neolinkConfigured, navSections).size
                    customNavEntries(navSections).forEachIndexed { index, entry ->
                        if (entry.iconOnly) return@forEachIndexed
                        val itemFocusIndex = (if (hasProfile) 1 else 0) + fixedCount + index
                        TopBarCustomNavChip(
                            label = entry.label ?: entry.customId.orEmpty(),
                            isFocused = isFocused && focusedIndex == itemFocusIndex,
                        )
                    }
                }
            }

            // ── RIGHT: Icon-only nav chips + Settings gear + network + clock ──
            // Icon-only chips are grouped here, right next to Settings, but
            // still render BEFORE the gear so Settings stays the last
            // navigable item in the bar, same as always.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                navItems(neolinkConfigured, navSections).forEachIndexed { index, item ->
                    val section = navSectionFor(item, navSections)
                    if (section?.iconOnly != true) return@forEachIndexed
                    val itemFocusIndex = if (hasProfile) index + 1 else index
                    TopBarNavChip(
                        item = item,
                        isFocused = isFocused && focusedIndex == itemFocusIndex,
                        isSelected = selectedIndex == itemFocusIndex,
                        labelOverride = section?.label,
                        iconOnly = true
                    )
                }
                run {
                    val fixedCount = navItems(neolinkConfigured, navSections).size
                    customNavEntries(navSections).forEachIndexed { index, entry ->
                        if (!entry.iconOnly) return@forEachIndexed
                        val itemFocusIndex = (if (hasProfile) 1 else 0) + fixedCount + index
                        TopBarCustomNavChip(
                            label = entry.label ?: entry.customId.orEmpty(),
                            isFocused = isFocused && focusedIndex == itemFocusIndex,
                        )
                    }
                }

                // Settings gear icon (no text label)
                TopBarSettingsGear(
                    isFocused = settingsFocused,
                    isSelected = settingsSelected,
                    hasBadge = hasUpdateBadge
                )

                TopBarNetworkStatus()

                Text(
                    text = currentTime,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBarNavChip(
    item: SidebarItem,
    isFocused: Boolean,
    isSelected: Boolean,
    labelOverride: String? = null,
    iconOnly: Boolean = false
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.2f)
            isSelected -> Color.White.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_chip_bg"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.62f)
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_icon_color"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.68f)
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_text_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "topbar_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) LiveColors.Accent else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_chip_border"
    )
    val label = labelOverride ?: if (item == SidebarItem.TV) {
        stringResource(R.string.topbar_tv)
    } else {
        stringResource(item.labelRes)
    }

    Row(
        modifier = Modifier
            .border(LiveDims.FocusBorder, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        if (!iconOnly) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = if (isFocused || isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Chip for an open-ended (kind == CUSTOM) nav entry — no [SidebarItem]/icon to
 * draw on, so label-only. Never shows a "selected" state since custom targets
 * (seeall:*, guide, etc.) are one-shot actions, not persistent screens.
 */
@Composable
private fun TopBarCustomNavChip(
    label: String,
    isFocused: Boolean,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_custom_chip_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFocused) Color.White else Color.White.copy(alpha = 0.68f),
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_custom_text_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "topbar_custom_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) LiveColors.Accent else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_custom_chip_border"
    )
    Row(
        modifier = Modifier
            .border(LiveDims.FocusBorder, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Settings gear icon — no text label, just the icon. Placed on the far right
 * of the top bar per the mockup. Receives focus/selection state for D-pad nav.
 */
@Composable
private fun TopBarSettingsGear(
    isFocused: Boolean,
    isSelected: Boolean,
    hasBadge: Boolean = false
) {
    val iconColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.5f)
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_settings_color"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.2f)
            isSelected -> Color.White.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_settings_bg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "topbar_settings_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) LiveColors.Accent else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_settings_border"
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .border(LiveDims.FocusBorder, borderColor, CircleShape)
            .clip(CircleShape)
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.settings),
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )

        // Update Badge
        if (hasBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(com.arflix.tv.ui.theme.AccentRed)
            )
        }
    }
}

/**
 * Profile avatar only — no name text. Just the circular avatar with gradient/icon.
 * Shown only when multiple profiles exist.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TopBarProfileAvatar(
    profile: Profile,
    isFocused: Boolean
) {
    val containerColor by animateColorAsState(
        targetValue = if (isFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_profile_bg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "topbar_profile_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) LiveColors.Accent else Color.Transparent,
        animationSpec = tween(AnimationConstants.DURATION_FAST),
        label = "topbar_profile_border"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .border(LiveDims.FocusBorder, borderColor, CircleShape)
            .clip(CircleShape)
            .background(containerColor)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarVisual(
                profile = profile,
                letterFontSize = 13.sp,
                iconPadding = 4.dp
            )
        }
    }
}

@Composable
internal fun rememberTopBarTime(clockFormat: String): String {
    val context = LocalContext.current
    var resolvedFormat by remember(clockFormat) { mutableStateOf(clockFormat) }
    var currentTime by remember(resolvedFormat) { mutableStateOf(topBarCurrentTime(resolvedFormat)) }

    // AppTopBar is used on multiple screens that don't all have SettingsUiState.
    // Read the persisted clock format directly so the clock updates app-wide.
    LaunchedEffect(context, clockFormat) {
        runCatching {
            val prefs = context.settingsDataStore.data.first()
            val saved = prefs.asMap().entries
                .firstOrNull { (key, _) -> key.name.endsWith("_clock_format") }
                ?.value as? String
            resolvedFormat = saved ?: clockFormat
        }
    }

    LaunchedEffect(resolvedFormat) {
        while (true) {
            currentTime = topBarCurrentTime(resolvedFormat)
            val now = System.currentTimeMillis()
            val delayToNextMinute = 60_000L - (now % 60_000L)
            delay(delayToNextMinute.coerceIn(1_000L, 60_000L))
        }
    }
    return currentTime
}

private fun topBarCurrentTime(clockFormat: String): String {
    val pattern = when (clockFormat) {
        "12h" -> "h:mm a"
        else -> "HH:mm"
    }
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())
    return sdf.format(Date())
}

private enum class NetworkType { WIFI, ETHERNET, NONE }

@Composable
internal fun TopBarNetworkStatus() {
    val context = LocalContext.current
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var networkType by remember { mutableStateOf(currentNetworkType(cm)) }

    DisposableEffect(Unit) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkType = currentNetworkType(cm) }
            override fun onLost(network: Network) { networkType = currentNetworkType(cm) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networkType = currentNetworkType(cm)
            }
        }
        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    val connected = networkType != NetworkType.NONE
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (connected) Color(0xFF22C55E) else Color(0xFFDC2626))
    )
}

private fun currentNetworkType(cm: ConnectivityManager): NetworkType {
    val network = cm.activeNetwork ?: return NetworkType.NONE
    val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.WIFI
        else -> NetworkType.NONE
    }
}
