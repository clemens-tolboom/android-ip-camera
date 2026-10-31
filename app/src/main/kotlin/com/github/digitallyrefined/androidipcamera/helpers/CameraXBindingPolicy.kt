package com.github.digitallyrefined.androidipcamera.helpers

/** Analysis remains required for motion, MJPEG and cached-frame snapshots in every combination. */
internal data class CameraXBinding(
    val display: Boolean,
    val encoder: Boolean,
    val still: Boolean
)

/** Try the requested session first: two Preview use cases are supported on some devices.
 * Exhaust combinations retaining the encoder surface before falling back to CPU-fed encoding.
 * Display and full-resolution stills are optional; snapshots can use the analysis frame cache.
 */
internal object CameraXBindingPolicy {
    fun candidates(display: Boolean, encoder: Boolean, still: Boolean): List<CameraXBinding> =
        listOf(true, false).flatMap { keepEncoder ->
            listOf(
                CameraXBinding(display, encoder && keepEncoder, still),
                CameraXBinding(false, encoder && keepEncoder, still),
                CameraXBinding(display, encoder && keepEncoder, false),
                CameraXBinding(false, encoder && keepEncoder, false)
            )
        }.distinct()

    /** Only publish a selected combination after binding succeeds. Failure propagates to the caller. */
    fun <T> bind(
        candidates: List<CameraXBinding>,
        attempt: (CameraXBinding) -> T
    ): Pair<CameraXBinding, T> {
        require(candidates.isNotEmpty())
        var failure: Exception? = null
        for (candidate in candidates) {
            try {
                return candidate to attempt(candidate)
            } catch (e: Exception) {
                failure = e
            }
        }
        throw checkNotNull(failure)
    }
}
