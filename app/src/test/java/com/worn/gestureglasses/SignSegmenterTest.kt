package com.worn.gestureglasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignSegmenterTest {
    private val f = FloatArray(1)

    @Test fun emitsClipAfterHandsLeave() {
        val s = SignSegmenter(gapMs = 500, minFrames = 3, outputFps = 10)
        for (t in 0L until 10L) assertNull(s.onFrame(t * 100, f))
        assertNull(s.onFrame(1200, null)) // gap 300 ms: still waiting
        assertEquals(10, s.onFrame(1500, null)!!.size)
    }

    @Test fun dropsShortBlips() {
        val s = SignSegmenter(gapMs = 500, minFrames = 6)
        s.onFrame(0, f); s.onFrame(100, f)
        assertNull(s.onFrame(700, null))
    }

    @Test fun splitsLongSigningAtMaxDuration() {
        val s = SignSegmenter(maxMs = 1000, minFrames = 1, outputFps = 10)
        var clip: List<FloatArray>? = null
        var t = 0L
        while (clip == null) { clip = s.onFrame(t, f); t += 100 }
        assertEquals(11, clip.size) // frames at 0..1000 ms
    }

    @Test fun slowGlassesFramesAreFilledInTo30Fps() {
        // 4 frames over 1.2 s (about 3 fps, like the glasses) -> 37 frames at 30 fps.
        val s = SignSegmenter(minFrames = 3)
        for ((i, t) in listOf(0L, 400L, 800L, 1200L).withIndex()) s.onFrame(t, floatArrayOf(i.toFloat()))
        val clip = s.onFrame(1700, null)!!
        assertEquals(37, clip.size)
        assertEquals(0f, clip.first()[0], 1e-4f)
        assertEquals(3f, clip.last()[0], 1e-4f)
        assertEquals(1.5f, clip[18][0], 1e-3f) // 600 ms: halfway between frames 1 and 2
    }

    @Test fun missingPointsCopyTheNearerFrame() {
        val nan = Float.NaN
        val out = SignSegmenter.resample(
            listOf(floatArrayOf(0f, nan), floatArrayOf(10f, 5f)), listOf(0L, 100L), fps = 40,
        )
        assertArrayEquals(floatArrayOf(0f, 2.5f, 5f, 7.5f, 10f), FloatArray(out.size) { out[it][0] }, 1e-4f)
        assertTrue(out[1][1].isNaN()) // 25 ms: nearer the frame without the point
        assertEquals(5f, out[3][1], 0f) // 75 ms: nearer the frame with it
    }

    @Test fun restingHandAtHipIsNotSigning() {
        // Measured on the glasses: mid-torso ~0.62, resting wrist ~0.73, signing wrist 0.34-0.59.
        assertTrue(!SignSegmenter.anyHandRaised(listOf(0.73f), 0.62f))
        assertTrue(SignSegmenter.anyHandRaised(listOf(0.73f, 0.43f), 0.62f))
        assertTrue(SignSegmenter.anyHandRaised(listOf(0.8f), null))
        assertTrue(!SignSegmenter.anyHandRaised(emptyList(), 0.62f))
    }

    /** A frame whose face gives a shoulder width of 0.2, with the signer's right wrist at (x, y). */
    private fun pose(x: Float, y: Float) = FloatArray(543 * 3) { Float.NaN }.apply {
        val halfFace = 0.1f / LandmarkExtractor.SHOULDERS_PER_FACE_WIDTH
        this[454 * 3] = 0.5f + halfFace; this[454 * 3 + 1] = 0.3f
        this[234 * 3] = 0.5f - halfFace; this[234 * 3 + 1] = 0.3f
        this[522 * 3] = x; this[522 * 3 + 1] = y
    }

    @Test fun endsWhenTheHandStopsMoving() {
        val s = SignSegmenter(stillMs = 300, minFrames = 3)
        // Chin to forward ("thank you"), then held still while still raised.
        assertNull(s.onFrame(0, pose(0.5f, 0.40f)))
        assertNull(s.onFrame(300, pose(0.5f, 0.45f)))
        assertNull(s.onFrame(600, pose(0.5f, 0.55f)))
        val clip = s.onFrame(900, pose(0.5f, 0.555f)) // still since 600 ms -> done
        assertTrue(clip != null)
        assertEquals("still", s.lastEnd)
        assertNull(s.onFrame(1200, pose(0.5f, 0.556f)))
        // Hand stays still: no new clip; once it moves, a new one starts.
        assertNull(s.onFrame(1500, pose(0.5f, 0.556f)))
        assertTrue(!s.isCollecting)
        assertNull(s.onFrame(1800, pose(0.5f, 0.40f)))
        assertTrue(s.isCollecting)
    }

    @Test fun handHeldStillWithoutSigningIsDiscarded() {
        val s = SignSegmenter(maxMs = 1000, minFrames = 1)
        var clip: List<FloatArray>? = null
        var t = 0L
        while (t <= 1000) { clip = s.onFrame(t, pose(0.5f, 0.4f)) ?: clip; t += 250 }
        assertNull(clip)
    }

    @Test fun speedIsInShoulderWidthsPerSecond() {
        // Wrist moves 0.1 (half a shoulder width) in 500 ms -> 1 shoulder width per second.
        assertEquals(1f, SignSegmenter.wristSpeed(pose(0.5f, 0.4f), pose(0.5f, 0.5f), 500)!!, 1e-4f)
        assertNull(SignSegmenter.wristSpeed(FloatArray(1), FloatArray(1), 500))
    }
}
