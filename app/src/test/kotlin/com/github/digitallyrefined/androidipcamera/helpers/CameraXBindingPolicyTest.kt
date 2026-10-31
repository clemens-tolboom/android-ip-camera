package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class CameraXBindingPolicyTest {
    private val all = CameraXBindingPolicy.candidates(display = true, encoder = true, still = true)

    private fun select(supported: (CameraXBinding) -> Boolean): Pair<CameraXBinding, List<CameraXBinding>> {
        val attempts = mutableListOf<CameraXBinding>()
        val (selected, camera) = CameraXBindingPolicy.bind(all) {
            attempts.add(it)
            require(supported(it)) { "unsupported combination" }
            "bound camera"
        }
        assertEquals("bound camera", camera)
        return selected to attempts
    }

    @Test
    fun `supported dual previews are kept without any fallback`() {
        val (selected, attempts) = select { true }
        assertEquals(CameraXBinding(true, true, true), selected)
        assertEquals(listOf(selected), attempts)
    }

    @Test
    fun `unsupported dual previews drop display but retain encoder and still`() {
        val (selected, attempts) = select { !it.display }
        assertEquals(CameraXBinding(false, true, true), selected)
        assertTrue(attempts.all { it.encoder && it.still })
    }

    @Test
    fun `still incompatibility can preserve both previews`() {
        val (selected, attempts) = select { !it.still }
        assertEquals(CameraXBinding(true, true, false), selected)
        assertTrue(attempts.all { it.encoder })
    }

    @Test
    fun `encoder plus analysis is preferred over CPU encoding with optional work`() {
        val (selected, attempts) = select { !it.encoder || (!it.display && !it.still) }
        assertEquals(CameraXBinding(false, true, false), selected)
        assertTrue(attempts.all { it.encoder })
    }

    @Test
    fun `CPU fallback occurs only after all surface combinations fail and can retain preview`() {
        val (selected, attempts) = select { !it.encoder }
        assertEquals(CameraXBinding(true, false, true), selected)
        assertEquals(4, attempts.dropLast(1).size)
        assertTrue(attempts.dropLast(1).all { it.encoder })
    }

    @Test
    fun `analysis alone remains available when all optional work is unsupported`() {
        val (selected, attempts) = select { !it.encoder && !it.display && !it.still }
        assertEquals(CameraXBinding(false, false, false), selected)
        assertEquals(all, attempts)
    }

    @Test
    fun `hidden preview and missing providers never introduce unrequested work or duplicate attempts`() {
        for (display in listOf(false, true)) {
            for (encoder in listOf(false, true)) {
                for (still in listOf(false, true)) {
                    val candidates = CameraXBindingPolicy.candidates(display, encoder, still)
                    assertEquals(CameraXBinding(display, encoder, still), candidates.first())
                    assertEquals(candidates.distinct(), candidates)
                    assertTrue(candidates.all { (!it.display || display) && (!it.encoder || encoder) && (!it.still || still) })
                    assertFalse(candidates.dropWhile { it.encoder }.any { it.encoder })
                }
            }
        }
    }

    @Test
    fun `total failure propagates the last bind error without publishing a selection`() {
        val failure = IllegalArgumentException("analysis unsupported")
        val attempts = mutableListOf<CameraXBinding>()
        try {
            CameraXBindingPolicy.bind(all) {
                attempts.add(it)
                throw failure
            }
            fail("Must propagate a failed session")
        } catch (e: IllegalArgumentException) {
            assertSame(failure, e)
            assertEquals(all, attempts)
        }
    }
}
