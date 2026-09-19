package com.example.convertjpgtoheic

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Whether this device can actually produce HEIC at all.
 *
 * `HeifWriter` encodes through the platform HEVC encoder, and a device without a usable one fails
 * on every single photo. Probing once up front turns "300 identical failures" into one clear
 * message before anything starts.
 *
 * The test mirrors HeifWriter's own encoder selection, which has two paths. It first tries a
 * dedicated image encoder; only if that is missing, or cannot take the image's size, does it fall
 * back to the HEVC encoder with grid mode — and that fallback needs an encoder able to handle the
 * 512x512 tile grid mode uses.
 *
 * So either path alone is enough, and requiring both would refuse to start on a device that would
 * in fact have worked. Checking only that "some HEVC encoder exists" has the opposite fault: it
 * passes, and then every photo fails.
 */
object EncoderSupport {

    private const val TAG = "EncoderSupport"

    /** The tile size HeifWriter's grid mode encodes into. */
    private const val GRID_TILE = 512

    /** Cached because [MediaCodecList] enumeration is not free and the answer cannot change. */
    val hasHevcEncoder: Boolean by lazy { hasImageEncoder() || hasTileCapableHevcEncoder() }

    /** A dedicated HEIC image encoder, which HeifWriter prefers and uses without grid mode. */
    private fun hasImageEncoder(): Boolean =
        supportsAtLeast(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, requireTile = false)

    private fun hasTileCapableHevcEncoder(): Boolean =
        supportsAtLeast(MediaFormat.MIMETYPE_VIDEO_HEVC, requireTile = true)

    private fun supportsAtLeast(mimeType: String, requireTile: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            if (!info.isEncoder || info.isAlias) return@any false
            val capabilities = try {
                info.getCapabilitiesForType(mimeType)
            } catch (_: IllegalArgumentException) {
                return@any false // this codec does not handle that type
            }
            if (!requireTile) return@any true
            capabilities.videoCapabilities?.isSizeSupported(GRID_TILE, GRID_TILE) == true
        }
    }.onFailure { Log.w(TAG, "Could not enumerate codecs for $mimeType", it) }.getOrDefault(false)
}
