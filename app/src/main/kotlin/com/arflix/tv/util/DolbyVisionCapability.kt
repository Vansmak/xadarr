package com.arflix.tv.util

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Answers "can this device's own Dolby Vision decoder actually handle this exact
 * profile/level" before we ever hand a stream to ExoPlayer — see
 * [[project_dv_atmos_passthrough_2026-07-30]] memory for the investigation this came
 * out of. On this hardware (NVIDIA Shield), the DV-branded codec exists but silently
 * hangs instead of throwing when asked to decode a profile/level it doesn't truly
 * support, which crashes the whole process rather than failing cleanly — so this check
 * has to run *before* prepare(), not react to a playback error that never fires.
 *
 * Deliberately reuses Media3's own decoder-capability logic (the same code path that
 * already correctly identifies unsupported profile/level combos in logcat as
 * "[codec.profileLevel] NoSupport") rather than reimplementing Dolby's profile/level
 * bitmask mapping by hand — that mapping is easy to get subtly wrong, and a wrong
 * "supported" verdict here means a live crash, not just an unnecessary transcode.
 *
 * Any uncertainty (missing decoder, query failure, malformed input) resolves to
 * `false` — the safe/tested transcode fallback — on purpose.
 */
object DolbyVisionCapability {

    fun isDirectPlaySupported(
        dvProfile: Int?,
        dvLevel: Int?,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        codecTag: String = "dvhe"
    ): Boolean {
        if (dvProfile == null || dvLevel == null) return true // not a DV stream, nothing to check
        return runCatching {
            val codecs = "$codecTag.${dvProfile.pad2()}.${dvLevel.pad2()}"
            val formatBuilder = Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs(codecs)
            if (videoWidth > 0 && videoHeight > 0) {
                formatBuilder.setWidth(videoWidth).setHeight(videoHeight)
            }
            val format = formatBuilder.build()
            val decoders = MediaCodecUtil.getDecoderInfos(
                MimeTypes.VIDEO_DOLBY_VISION,
                /* requiresSecureDecoder = */ false,
                /* requiresTunnelingDecoder = */ false
            )
            decoders.isNotEmpty() && decoders.any { it.isFormatSupported(format) }
        }.getOrDefault(false)
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
