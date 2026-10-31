package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class AvcFormatPolicyTest {
    private fun size(w: Int, h: Int) = AvcFormatPolicy.Size(w, h)
    private fun select(w: Int, h: Int, fps: Int = 30, fallback: Boolean = false,
                       wa: Int = 2, ha: Int = 2, supports: (AvcFormatPolicy.Size, Int) -> Boolean) =
        AvcFormatPolicy.select(size(w, h), fps, wa, ha, fallback, supports)

    @Test fun `reject invalid control sizes including overflow and trailing text`() {
        listOf("0x720", "1280x0", "-1x720", "2147483648x720", "1280x720junk", "auto").forEach {
            assertNull(it, AvcFormatPolicy.parseSize(it))
        }
        assertEquals(size(1280, 720), AvcFormatPolicy.parseSize("1280x720"))
    }

    @Test fun `other encoders larger limits cannot approve selected encoder`() {
        val software: (AvcFormatPolicy.Size, Int) -> Boolean = { s, _ -> s.width <= 4096 && s.height <= 4096 }
        val selected: (AvcFormatPolicy.Size, Int) -> Boolean = { s, _ -> s.width <= 1280 && s.height <= 720 }
        assertEquals(size(1920, 1080), select(1920, 1080, supports = software))
        assertThrows(IllegalArgumentException::class.java) { select(1920, 1080, supports = selected) }
        assertEquals(size(1280, 720), select(1920, 1080, fallback = true, supports = selected))
    }

    @Test fun `portrait is checked after rotation and fallback keeps portrait aspect`() {
        val selected: (AvcFormatPolicy.Size, Int) -> Boolean = { s, _ -> s.width <= 1920 && s.height <= 1080 }
        assertEquals(size(1920, 1080), select(1920, 1080, supports = selected))
        assertThrows(IllegalArgumentException::class.java) { select(1080, 1920, supports = selected) }
        val portrait = select(1080, 1920, fallback = true, supports = selected)
        assertEquals(size(594, 1056), portrait)
        assertEquals(1080L * portrait.height, 1920L * portrait.width)
    }

    @Test fun `sensor and user quarter turns validate their effective frame shape`() {
        val selected: (AvcFormatPolicy.Size, Int) -> Boolean = { s, _ -> s.width <= 1920 && s.height <= 1080 }
        for (sensor in listOf(90, 270)) {
            val (w, h) = CameraGlPipe.frameSize(1920, 1080, sensor, 0)
            assertThrows(IllegalArgumentException::class.java) { select(w, h, supports = selected) }
            val (turnedW, turnedH) = CameraGlPipe.frameSize(1920, 1080, sensor, 90)
            assertEquals(size(1920, 1080), select(turnedW, turnedH, supports = selected))
        }
    }

    @Test fun `joint size and rate limits override independent maxima`() {
        val selected: (AvcFormatPolicy.Size, Int) -> Boolean = { s, fps ->
            s.width <= 1920 && s.height <= 1920 && s.width.toLong() * s.height * fps <= 1280L * 720 * 60
        }
        assertEquals(size(1920, 1080), select(1920, 1080, fps = 24, supports = selected))
        assertThrows(IllegalArgumentException::class.java) { select(1920, 1080, fps = 60, supports = selected) }
        assertEquals(size(1280, 720), select(1920, 1080, fps = 60, fallback = true, supports = selected))
    }

    @Test fun `alignment and full format predicate both constrain fallback`() {
        var calls = 0
        val result = select(1920, 1080, fallback = true, wa = 16, ha = 16) { s, _ ->
            calls++
            assertEquals(0, s.width % 16)
            assertEquals(0, s.height % 16)
            s.width <= 1280
        }
        assertEquals(size(1280, 720), result)
        assertTrue(calls > 0)
        assertThrows(IllegalArgumentException::class.java) {
            select(1280, 720, fallback = true) { _, _ -> false }
        }
    }

    @Test fun `invalid size and rate never reach codec predicate`() {
        listOf(Triple(0, 720, 30), Triple(1280, -1, 30), Triple(1280, 720, 0)).forEach { (w, h, fps) ->
            assertThrows(IllegalArgumentException::class.java) {
                select(w, h, fps, fallback = true) { _, _ -> fail("Invalid format reached codec"); true }
            }
        }
    }
}
