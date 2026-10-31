package com.github.digitallyrefined.androidipcamera.helpers

/** Camera1 uses milli-FPS; Camera2 uses FPS. Keep selection independent of Android stubs. */
data class CaptureFpsRange(val lower: Int, val upper: Int)

object CaptureFpsSelector {
    /** Prefer an exact lock, then the narrowest range containing the target with least overshoot.
     * Unsupported targets fall back below the target before asking for a faster range.
     * [ceiling] is a stream-duration bound, not a throughput guarantee. Never invent a range.
     */
    fun select(ranges: List<CaptureFpsRange>, target: Int, ceiling: Int = Int.MAX_VALUE): CaptureFpsRange? {
        if (target <= 0) return null
        val valid = ranges.filter { it.lower > 0 && it.upper >= it.lower && it.upper <= ceiling }
        return valid.filter { target in it.lower..it.upper }
            .minWithOrNull(compareBy({ it.upper }, { -it.lower }))
            ?: valid.filter { it.upper < target }
                .maxWithOrNull(compareBy({ it.upper }, { it.lower }))
            ?: valid.minWithOrNull(compareBy({ it.lower }, { it.upper }))
    }

    /** Zero/missing durations mean unknown. Do not infer high-rate session support from AE alone. */
    fun sessionCeiling(durationsNs: List<Long>): Int =
        if (durationsNs.isEmpty() || durationsNs.any { it <= 0 }) 30
        else (1_000_000_000.0 / durationsNs.max() + 0.01).toInt().coerceAtLeast(1)
}

/** Requested/applied configuration only; deliberately does not claim measured sensor/output FPS. */
data class CaptureFpsStatus(
    val requested: Int,
    val range: CaptureFpsRange? = null,
    val state: String = "pending"
)
