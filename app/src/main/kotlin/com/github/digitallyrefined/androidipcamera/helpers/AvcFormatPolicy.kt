package com.github.digitallyrefined.androidipcamera.helpers

/** Pure selection policy; the predicate must check the complete format on one selected codec. */
internal object AvcFormatPolicy {
    data class Size(val width: Int, val height: Int)

    fun parseSize(value: String): Size? {
        val match = Regex("([0-9]+)x([0-9]+)").matchEntire(value) ?: return null
        val w = match.groupValues[1].toIntOrNull() ?: return null
        val h = match.groupValues[2].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) Size(w, h) else null
    }

    fun select(
        requested: Size, fps: Int, widthAlignment: Int, heightAlignment: Int,
        allowFallback: Boolean, supports: (Size, Int) -> Boolean
    ): Size {
        require(requested.width > 0 && requested.height > 0 && fps > 0) {
            "Invalid AVC format ${requested.width}x${requested.height} @$fps"
        }
        require(widthAlignment > 0 && heightAlignment > 0)
        fun valid(size: Size) = size.width % widthAlignment == 0 &&
            size.height % heightAlignment == 0 && supports(size, fps)
        if (valid(requested)) return requested
        if (allowFallback) {
            // Exact aspect ratio, no independent clamps, no orientation or rate change.
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val divisor = gcd(requested.width, requested.height)
            val unitW = requested.width / divisor
            val unitH = requested.height / divisor
            // MediaCodec dimensions are bounded; stop unreasonable inputs before a long scan.
            require(requested.width <= 32768 && requested.height <= 32768) { "AVC size is too large" }
            for (scale in divisor - 1 downTo 1) {
                val candidate = Size(unitW * scale, unitH * scale)
                if (valid(candidate)) return candidate
            }
        }
        throw IllegalArgumentException("Unsupported AVC format ${requested.width}x${requested.height} @$fps (alignment ${widthAlignment}x$heightAlignment)")
    }
}
