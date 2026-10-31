package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFpsSelectorTest {
    private fun range(low: Int, high: Int) = CaptureFpsRange(low, high)

    @Test fun `requested fixed rate wins over widest automatic range`() {
        val ranges = listOf(range(10, 30), range(30, 30), range(60, 60))
        assertEquals(range(60, 60), CaptureFpsSelector.select(ranges, 60))
        assertEquals(range(30, 30), CaptureFpsSelector.select(ranges, 30))
    }

    @Test fun `variable range minimizes overshoot then exposure variation`() {
        val ranges = listOf(range(10, 30), range(15, 30), range(30, 60))
        assertEquals(range(15, 30), CaptureFpsSelector.select(ranges, 24))
        assertEquals(range(30, 60), CaptureFpsSelector.select(ranges, 60))
    }

    @Test fun `unsupported target falls back below before exceeding target`() {
        val ranges = listOf(range(15, 30), range(30, 30), range(60, 60))
        assertEquals(range(30, 30), CaptureFpsSelector.select(ranges, 50))
        assertEquals(range(30, 30), CaptureFpsSelector.select(ranges.take(2), 60))
        assertEquals(range(15, 30), CaptureFpsSelector.select(ranges, 10))
    }

    @Test fun `session ceiling excludes advertised but incompatible high rates`() {
        val ranges = listOf(range(10, 30), range(30, 30), range(60, 60))
        assertEquals(range(30, 30), CaptureFpsSelector.select(ranges, 60, 30))
        assertNull(CaptureFpsSelector.select(listOf(range(60, 60)), 60, 30))
    }

    @Test fun `selection does not depend on advertisement order`() {
        val ranges = listOf(range(10, 30), range(15, 30), range(30, 30), range(30, 60), range(60, 60))
        fun permutations(items: List<CaptureFpsRange>): List<List<CaptureFpsRange>> =
            if (items.isEmpty()) listOf(emptyList())
            else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
        for (order in permutations(ranges)) {
            for (target in listOf(10, 24, 30, 50, 60, 90)) {
                assertEquals(CaptureFpsSelector.select(ranges, target), CaptureFpsSelector.select(order, target))
            }
        }
    }

    @Test fun `Camera1 milli FPS uses the same policy without rounding`() {
        assertEquals(range(29970, 29970), CaptureFpsSelector.select(
            listOf(range(10000, 30000), range(29970, 29970)), 29970))
    }

    @Test fun `empty invalid or unavailable metadata retains automatic fallback`() {
        assertNull(CaptureFpsSelector.select(emptyList(), 60))
        assertNull(CaptureFpsSelector.select(listOf(range(0, 30), range(30, 15)), 30))
        assertNull(CaptureFpsSelector.select(listOf(range(30, 30)), 0))
        assertEquals(30, CaptureFpsSelector.sessionCeiling(emptyList()))
        assertEquals(30, CaptureFpsSelector.sessionCeiling(listOf(16_666_667, 0)))
    }

    @Test fun `slowest output bounds rate with tolerance for rounded nanoseconds`() {
        assertEquals(60, CaptureFpsSelector.sessionCeiling(listOf(16_666_667)))
        assertEquals(30, CaptureFpsSelector.sessionCeiling(listOf(16_666_667, 33_333_334)))
        assertEquals(15, CaptureFpsSelector.sessionCeiling(listOf(66_666_667)))
    }
}
