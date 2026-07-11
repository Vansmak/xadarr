package com.arflix.tv.data.model

import java.io.Serializable

/**
 * Idle-state (pre-focus) hero config. "live_resume" resumes the last-played
 * live channel with Watch/Guide actions; "none" keeps today's behavior
 * (first real item in the first row). Unrecognized `type` values fall back
 * to "none" at the render site rather than crashing, same policy as an
 * unknown row/nav kind.
 */
data class HeroConfig(
    val type: String = "live_resume",
    val actions: List<String> = listOf("watch", "guide"),
) : Serializable

/** "apps_catalog" pins the installed_apps row to the bottom of Home with footer styling. */
data class FooterConfig(
    val type: String = "apps_catalog",
) : Serializable

data class HomeLayoutConfig(
    val hero: HeroConfig = HeroConfig(),
    val footer: FooterConfig = FooterConfig(),
) : Serializable
