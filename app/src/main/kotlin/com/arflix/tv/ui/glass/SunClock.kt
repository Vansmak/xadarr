package com.arflix.tv.ui.glass

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Offline sunrise/sunset (the NOAA "sunrise equation"), so the glass theme can go dark at sunset
 * like the launcher without a location permission or a network call. The place comes from the
 * device's time zone: a known city for common zones, otherwise the zone's offset as longitude.
 * Good to a few minutes, which is all a day/night switch needs.
 */
object SunClock {

    /** Night for this setting: "night" always, "day" never, "auto" from sunset to sunrise. */
    fun isNight(mode: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean = when (mode) {
        GlassMode.NIGHT -> true
        GlassMode.DAY -> false
        else -> {
            val (lat, lon) = placeFor(zone)
            val times = sunTimes(now, lat, lon, zone)
            if (times == null) {
                // Polar day/night: fall back to 6:00-18:00 like the launcher does without data.
                val hour = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
                hour < 6 || hour >= 18
            } else {
                now < times.first || now >= times.second
            }
        }
    }

    /** Sunrise and sunset (epoch ms) on the local date of [now], or null if the sun doesn't cross the horizon. */
    fun sunTimes(now: Long, lat: Double, lon: Double, zone: TimeZone): Pair<Long, Long>? {
        val noon = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val julian = noon / 86_400_000.0 + 2440587.5
        val n = (julian - 2451545.0).roundToLong().toDouble()
        val meanNoon = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * meanNoon).mod(360.0))
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372).mod(360.0))
        val transit = 2451545.0 + meanNoon + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val phi = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosW < -1.0 || cosW > 1.0) return null
        val w = Math.toDegrees(acos(cosW)) / 360.0
        fun toMillis(j: Double) = ((j - 2440587.5) * 86_400_000.0).roundToLong()
        return toMillis(transit - w) to toMillis(transit + w)
    }

    /** Latitude/longitude for a time zone: a representative city, else (40°, offset × 15°). */
    fun placeFor(zone: TimeZone): Pair<Double, Double> =
        PLACES[zone.id] ?: (if (zone.id.startsWith("Australia/") || zone.id.startsWith("America/Argentina")) -34.0 else 40.0) to
            zone.rawOffset / 3_600_000.0 * 15.0

    private val PLACES = mapOf(
        "America/Los_Angeles" to (34.05 to -118.24),
        "America/Vancouver" to (49.28 to -123.12),
        "America/Denver" to (39.74 to -104.99),
        "America/Phoenix" to (33.45 to -112.07),
        "America/Edmonton" to (53.55 to -113.49),
        "America/Chicago" to (41.88 to -87.63),
        "America/Mexico_City" to (19.43 to -99.13),
        "America/New_York" to (40.71 to -74.01),
        "America/Detroit" to (42.33 to -83.05),
        "America/Toronto" to (43.65 to -79.38),
        "America/Halifax" to (44.65 to -63.57),
        "America/Anchorage" to (61.22 to -149.90),
        "Pacific/Honolulu" to (21.31 to -157.86),
        "America/Sao_Paulo" to (-23.55 to -46.63),
        "Europe/London" to (51.51 to -0.13),
        "Europe/Dublin" to (53.35 to -6.26),
        "Europe/Lisbon" to (38.72 to -9.14),
        "Europe/Madrid" to (40.42 to -3.70),
        "Europe/Paris" to (48.86 to 2.35),
        "Europe/Amsterdam" to (52.37 to 4.90),
        "Europe/Brussels" to (50.85 to 4.35),
        "Europe/Berlin" to (52.52 to 13.40),
        "Europe/Zurich" to (47.38 to 8.54),
        "Europe/Rome" to (41.90 to 12.50),
        "Europe/Vienna" to (48.21 to 16.37),
        "Europe/Stockholm" to (59.33 to 18.07),
        "Europe/Oslo" to (59.91 to 10.75),
        "Europe/Copenhagen" to (55.68 to 12.57),
        "Europe/Warsaw" to (52.23 to 21.01),
        "Europe/Athens" to (37.98 to 23.73),
        "Europe/Istanbul" to (41.01 to 28.98),
        "Asia/Dubai" to (25.20 to 55.27),
        "Asia/Kolkata" to (28.61 to 77.21),
        "Asia/Singapore" to (1.35 to 103.82),
        "Asia/Tokyo" to (35.68 to 139.69),
        "Australia/Sydney" to (-33.87 to 151.21),
        "Australia/Melbourne" to (-37.81 to 144.96),
        "Australia/Perth" to (-31.95 to 115.86),
        "Pacific/Auckland" to (-36.85 to 174.76),
    )
}
