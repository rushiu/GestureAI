package com.worn.gestureglasses

/**
 * Cuts the frame stream into one clip per sign. A clip starts when a hand is raised and ends when:
 *  - the hand has stopped moving for [stillMs], after travelling at least [minTravel] (the sign is done), or
 *  - no hand has been raised for [gapMs], or
 *  - it reaches [maxMs] / [maxFrames].
 * Clips shorter than [minFrames], or that barely moved, are discarded as noise.
 * After a clip ends on stillness, a hand that stays still doesn't start a new clip until it moves again.
 *
 * Movement is measured at the wrists, in shoulder widths per second, so it doesn't depend on how far
 * away the signer stands.
 *
 * The sign model was trained on ~30 fps video, but the glasses only manage a few fps in ASL mode,
 * so a sign arrives as a handful of frames and looks far too fast to the model. Each clip is therefore
 * resampled to [outputFps] using the real frame times before it's returned.
 */
class SignSegmenter(
    private val gapMs: Long = 500,
    private val maxMs: Long = 2000,
    private val minFrames: Int = 3, // the glasses manage only a few fps with hand + pose + face
    private val maxFrames: Int = 120,
    private val outputFps: Int = 30,
    private val stillMs: Long = 300,
    private val stillSpeed: Float = 0.4f, // shoulder widths per second
    private val minTravel: Float = 0.5f, // shoulder widths
) {
    private val frames = ArrayList<FloatArray>()
    private val times = ArrayList<Long>()
    private var lastHandMs = 0L
    private var travel = 0f
    private var measured = false
    private var stillSinceMs = -1L
    private var waitForMotion = false
    private var previous: FloatArray? = null
    private var previousMs = 0L

    val isCollecting: Boolean get() = frames.isNotEmpty()

    /** Why the last clip ended (for the log): "still", "lowered" or "max". */
    var lastEnd = ""
        private set

    fun reset() {
        frames.clear()
        times.clear()
        travel = 0f
        measured = false
        stillSinceMs = -1L
    }

    /** [frame] is null when no hand is raised. Returns a finished clip at [outputFps], or null. */
    fun onFrame(timestampMs: Long, frame: FloatArray?): List<FloatArray>? {
        if (frame == null) {
            previous = null
            waitForMotion = false
            return if (frames.isNotEmpty() && timestampMs - lastHandMs >= gapMs) flush("lowered") else null
        }
        val speed = previous?.let { wristSpeed(it, frame, timestampMs - previousMs) }
        val prev = previous
        val prevMs = previousMs
        previous = frame
        previousMs = timestampMs
        lastHandMs = timestampMs

        if (waitForMotion) {
            if (speed == null || speed < stillSpeed) return null
            waitForMotion = false
            if (prev != null) { // start the new clip from the frame the movement started at
                frames.add(prev)
                times.add(prevMs)
            }
        }

        if (frames.isNotEmpty() && speed != null) {
            measured = true
            travel += speed * (timestampMs - times.last()) / 1000f
            if (speed < stillSpeed) {
                if (stillSinceMs < 0) stillSinceMs = times.last()
            } else {
                stillSinceMs = -1L
            }
        }
        frames.add(frame)
        times.add(timestampMs)

        if (travel >= minTravel && stillSinceMs >= 0 && timestampMs - stillSinceMs >= stillMs) {
            waitForMotion = true
            return flush("still")
        }
        return if (timestampMs - times.first() >= maxMs || frames.size >= maxFrames) flush("max") else null
    }

    /** The raw frames and their times behind the last returned clip (for debugging). */
    var lastRaw: Pair<List<FloatArray>, List<Long>>? = null
        private set

    private fun flush(why: String): List<FloatArray>? {
        val barelyMoved = measured && travel < minTravel / 2
        val clip = if (frames.size >= minFrames && !barelyMoved) resample(frames, times, outputFps) else null
        if (clip != null) {
            lastRaw = frames.toList() to times.toList()
            lastEnd = why
        }
        reset()
        return clip
    }

    companion object {
        private const val LEFT_WRIST = 468 * 3
        private const val RIGHT_WRIST = 522 * 3
        private const val RIGHT_CHEEK = 234 * 3
        private const val LEFT_CHEEK = 454 * 3

        /**
         * Fastest wrist movement between two frames, in shoulder widths per second (estimated as
         * [LandmarkExtractor.SHOULDERS_PER_FACE_WIDTH] face widths; image widths if there's no face).
         * Null if the frames don't share a visible wrist.
         */
        fun wristSpeed(a: FloatArray, b: FloatArray, dtMs: Long): Float? {
            if (b.size < 543 * 3 || dtMs <= 0) return null
            val fw = kotlin.math.hypot(b[LEFT_CHEEK] - b[RIGHT_CHEEK], b[LEFT_CHEEK + 1] - b[RIGHT_CHEEK + 1])
            val sw = fw * LandmarkExtractor.SHOULDERS_PER_FACE_WIDTH
            val scale = if (sw.isNaN() || sw < 0.01f) 1f else sw
            var best: Float? = null
            for (w in intArrayOf(LEFT_WRIST, RIGHT_WRIST)) {
                val d = kotlin.math.hypot(b[w] - a[w], b[w + 1] - a[w + 1])
                if (!d.isNaN()) best = maxOf(best ?: 0f, d)
            }
            return best?.let { it / scale * 1000f / dtMs }
        }

        /**
         * A hand counts as signing only when its wrist is above mid-torso (image y grows downward). On the
         * glasses a resting hand at the hip stays in view the whole time and would otherwise keep a sign from
         * ever ending. If mid-torso isn't known, any visible hand counts.
         */
        fun anyHandRaised(wristYs: List<Float>, midTorsoY: Float?): Boolean {
            if (wristYs.isEmpty()) return false
            if (midTorsoY == null) return true
            return wristYs.any { it < midTorsoY }
        }

        /**
         * Linear interpolation between neighbouring frames at a fixed frame rate.
         * Where either neighbour is missing a point (NaN), the nearer frame's value is used instead.
         */
        fun resample(frames: List<FloatArray>, timesMs: List<Long>, fps: Int): List<FloatArray> {
            val start = timesMs.first()
            val duration = timesMs.last() - start
            val stepMs = 1000.0 / fps
            val count = (duration / stepMs).toInt() + 1
            if (count <= frames.size) return frames.toList()

            val out = ArrayList<FloatArray>(count)
            var j = 0
            for (k in 0 until count) {
                val t = start + k * stepMs
                while (j < timesMs.size - 2 && timesMs[j + 1] < t) j++
                val a = frames[j]
                val b = frames[j + 1]
                val span = (timesMs[j + 1] - timesMs[j]).toDouble()
                val w = if (span <= 0) 0f else ((t - timesMs[j]) / span).coerceIn(0.0, 1.0).toFloat()
                val nearer = if (w < 0.5f) a else b
                out.add(FloatArray(a.size) { i ->
                    val x = a[i]
                    val y = b[i]
                    if (x.isNaN() || y.isNaN()) nearer[i] else x + (y - x) * w
                })
            }
            return out
        }
    }
}
