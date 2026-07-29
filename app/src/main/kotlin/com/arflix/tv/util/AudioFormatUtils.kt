package com.arflix.tv.util

/**
 * Shared audio-format labeling, used by both the player (live decoded track info) and
 * home-server repositories (MediaStreams/Media attributes fetched before playback starts) so
 * "Atmos"/"DTS:X"/etc. detection doesn't drift between the two call sites.
 */
object AudioFormatUtils {

    fun detectCodecLabel(codec: String?, trackLabel: String?): String? {
        val haystack = buildString {
            codec?.let {
                append(it)
                append(' ')
            }
            trackLabel?.let { append(it) }
        }.lowercase()

        if (haystack.isBlank()) return null
        val hasAtmos = haystack.contains("atmos")

        return when {
            haystack.contains("dts:x") || haystack.contains("dtsx") || haystack.contains("dts x") -> "DTS:X"
            haystack.contains("dts-hd") || haystack.contains("dts hd") ||
                haystack.contains("dtshd") || haystack.contains("dca-ma") || haystack.contains("dca-hd") -> "DTS-HD"
            haystack.contains("truehd") && hasAtmos -> "TrueHD Atmos"
            haystack.contains("truehd") -> "TrueHD"
            (haystack.contains("eac3") || haystack.contains("e-ac3") || haystack.contains("dd+")) && hasAtmos ->
                "E-AC3 Atmos"
            haystack.contains("eac3") || haystack.contains("e-ac3") || haystack.contains("dd+") -> "E-AC3"
            haystack.contains("ac3") || haystack.contains("dd ") || haystack.endsWith("dd") -> "AC3"
            haystack.contains("dts") -> "DTS"
            haystack.contains("aac") -> "AAC"
            haystack.contains("mp3") -> "MP3"
            haystack.contains("opus") -> "Opus"
            haystack.contains("flac") -> "FLAC"
            hasAtmos -> "Atmos"
            else -> null
        }
    }

    fun channelLayoutLabel(channelCount: Int?): String? = when (channelCount) {
        8 -> "7.1"
        6 -> "5.1"
        else -> null
    }

    /**
     * Combines codec detection with a channel-layout suffix when the codec name alone
     * doesn't already convey it (e.g. "TrueHD Atmos 7.1", "E-AC3 5.1").
     */
    fun buildLabel(codec: String?, trackLabel: String?, channelCount: Int?): String? {
        val codecLabel = detectCodecLabel(codec, trackLabel)
        val channelLabel = channelLayoutLabel(channelCount)
        return when {
            codecLabel != null && channelLabel != null && !codecLabel.contains(channelLabel) ->
                "$codecLabel $channelLabel"
            codecLabel != null -> codecLabel
            else -> channelLabel
        }
    }
}
