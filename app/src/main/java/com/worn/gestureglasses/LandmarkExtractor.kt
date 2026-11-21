package com.worn.gestureglasses

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a camera frame into the 543 x 3 landmark layout the sign model was trained on
 * (MediaPipe Holistic order: face 0-467, left hand 468-488, pose 489-521, right hand 522-542).
 * Missing parts are NaN, as in the training data.
 *
 * Hands + face only: the sign model ignores the body (pose) points entirely (checked: removing or randomizing
 * them changes its output by exactly 0), so they're left as NaN. The hand points come from the gesture
 * recognizer, so gestures and ASL signs share one hand tracker. This class adds the face.
 *
 * Tuned for the glasses (measured on the M6, where every tracker costs 100-300 ms per frame):
 *  - The signer's face is only ~20 px across at 1-2 m, too small for the face tracker, so it runs on a
 *    zoomed crop around where the face was last seen. The body tracker runs only to find the face when
 *    it's been lost (someone new, or no face for a few seconds).
 *  - Everything else the body was used for is estimated from the face: mid-torso is about 1.6 face heights
 *    below the chin, shoulder width about 2.2 face widths, and the signer's right hand is the one on the
 *    left of their face in the picture.
 *  - Only hands above mid-torso count as signing, and once the face is known, [searchArea] limits the hand
 *    search to the part of the picture above mid-torso, so a hand resting at the hip costs nothing.
 *  - While a hand is raised, face runs every [faceEveryN]th frame; the last result is reused in between.
 */
class LandmarkExtractor(
    context: Context,
    private val faceEveryN: Int = 2,
) : Closeable {

    /** [points] is null when no hand is raised (not signing). */
    class Frame(val points: FloatArray?, val handsVisible: Boolean)

    /** Where to look for hands: [bitmap] is the top [yScale] of the picture (all of it when [wholePicture]). */
    class SearchArea(val bitmap: Bitmap, val yScale: Float, val wholePicture: Boolean)

    private fun base(asset: String) = BaseOptions.builder().setModelAssetPath(asset).build()

    // Only used to find the face when it's been lost.
    private val pose = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base("pose_landmarker_lite.task"))
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(1)
            .build(),
    )
    // IMAGE mode: the face crop moves with the head, so frame-to-frame tracking doesn't apply.
    private val face = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base("face_landmarker.task"))
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .build(),
    )

    /** Debugging: the last face crop given to the face tracker. */
    var lastFaceCrop: Bitmap? = null
        private set

    private var lastFace: FloatArray? = null // 468 * 3, full-frame coordinates
    private var lastFaceAt = 0L
    private var signingFrames = 0
    private val timing = Timing()

    private fun recentFace(timestampMs: Long) = lastFace?.takeIf { timestampMs - lastFaceAt < FACE_STALE_MS }

    /** The signer's shoulder width in picture widths (estimated from the face), if the face is known. */
    fun bodyScale(timestampMs: Long): Float? = recentFace(timestampMs)?.let { faceWidth(it) * SHOULDERS_PER_FACE_WIDTH }

    /** The part of the upright [bitmap] to search for hands: above mid-torso once the face is known. */
    fun searchArea(bitmap: Bitmap, timestampMs: Long): SearchArea {
        val h = bitmap.height
        // Rounded so the crop doesn't jitter from frame to frame.
        val cropBottom = recentFace(timestampMs)
            ?.let { ((midTorsoY(it) * h).toInt() / 16 * 16).coerceIn(h / 3, h) }
            ?: h
        if (cropBottom >= h) return SearchArea(bitmap, 1f, wholePicture = true)
        return SearchArea(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropBottom), cropBottom.toFloat() / h, false)
    }

    /**
     * [handLandmarks] are the hand points found in [area] (by the gesture recognizer), [handsMs] how long
     * that took. [bitmap] is the whole upright picture.
     */
    fun extract(
        bitmap: Bitmap,
        area: SearchArea,
        handLandmarks: List<List<NormalizedLandmark>>,
        handsMs: Long,
        timestampMs: Long,
    ): Frame {
        timing.add(HANDS_STAGE, handsMs)
        if (handLandmarks.isEmpty()) {
            timing.frameDone()
            return Frame(null, handsVisible = false)
        }
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Face lost: find the head once with the body tracker, then follow the face from there.
        if (recentFace(timestampMs) == null) {
            var t = SystemClock.uptimeMillis()
            val body = pose.detect(BitmapImageBuilder(bitmap).build()).landmarks().firstOrNull()
            timing.add(POSE_STAGE, SystemClock.uptimeMillis() - t)
            if (body != null) {
                t = SystemClock.uptimeMillis()
                updateFace(bitmap, faceCropFromPose(body, w, h), timestampMs)
                timing.add(FACE_STAGE, SystemClock.uptimeMillis() - t)
            }
        }
        val known = recentFace(timestampMs)

        // A hand found above mid-torso is raised by definition; on the whole picture, check.
        val raised = !area.wholePicture || SignSegmenter.anyHandRaised(
            handLandmarks.map { it[0].y() * area.yScale },
            known?.let { midTorsoY(it) },
        )
        if (!raised) {
            timing.frameDone()
            return Frame(null, handsVisible = true)
        }

        if (known != null && (signingFrames % faceEveryN == 0 || timestampMs - lastFaceAt > FACE_REFRESH_MS)) {
            val t = SystemClock.uptimeMillis()
            updateFace(bitmap, faceCropFromFace(known, w, h), timestampMs)
            timing.add(FACE_STAGE, SystemClock.uptimeMillis() - t)
        }
        signingFrames++

        val out = FloatArray(TOTAL * 3) { Float.NaN }
        val faceNow = recentFace(timestampMs)
        faceNow?.copyInto(out, FACE * 3)

        val yScale = area.yScale
        val wrists = handLandmarks.map { HandAssignment.Point(it[0].x(), it[0].y() * yScale) }
        val sides = HandAssignment.assign(wrists, null, null, centerX = faceNow?.get(NOSE_TIP * 3) ?: 0.5f)
        handLandmarks.forEachIndexed { i, lms ->
            val offset = if (sides[i] == HandAssignment.LEFT) LEFT_HAND else RIGHT_HAND
            for (j in 0 until min(HAND_COUNT, lms.size)) {
                out[(offset + j) * 3] = lms[j].x()
                out[(offset + j) * 3 + 1] = lms[j].y() * yScale
                out[(offset + j) * 3 + 2] = lms[j].z()
            }
        }
        timing.frameDone()
        return Frame(out, handsVisible = true)
    }

    /** Runs the face tracker on [crop] and keeps the result if a face was found. */
    private fun updateFace(bitmap: Bitmap, crop: IntArray, timestampMs: Long) {
        detectFace(bitmap, crop)?.let {
            lastFace = it
            lastFaceAt = timestampMs
        }
    }

    /**
     * Runs the face tracker on a square crop [left, top, size] of [bitmap], scaled up to [FACE_CROP_PX],
     * and maps the points back to full-frame coordinates.
     */
    private fun detectFace(bitmap: Bitmap, crop: IntArray): FloatArray? {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val (left, top, size) = crop
        val input = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, left, top, size, size), FACE_CROP_PX, FACE_CROP_PX, true)
        lastFaceCrop = input

        val landmarks = face.detect(BitmapImageBuilder(input).build()).faceLandmarks().firstOrNull() ?: return null
        val out = FloatArray(FACE_COUNT * 3)
        for (i in 0 until min(FACE_COUNT, landmarks.size)) {
            val lm = landmarks[i]
            out[i * 3] = (left + lm.x() * size) / w
            out[i * 3 + 1] = (top + lm.y() * size) / h
            out[i * 3 + 2] = lm.z() * size / w // z is scaled like x
        }
        return out
    }

    /** A square about 2.5 head-widths wide around the head found by the body tracker (nose and ears). */
    private fun faceCropFromPose(body: List<NormalizedLandmark>, w: Float, h: Float): IntArray {
        val nose = body[0]
        val earL = body[7]
        val earR = body[8]
        val headWidth = max(hypot((earL.x() - earR.x()) * w, (earL.y() - earR.y()) * h), MIN_HEAD_PX)
        return square(nose.x() * w, nose.y() * h - headWidth * 0.2f, headWidth * 2.5f, w, h)
    }

    /** A square twice the size of the face last seen, around it (the head moves little between frames). */
    private fun faceCropFromFace(f: FloatArray, w: Float, h: Float): IntArray {
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (i in 0 until FACE_COUNT) {
            minX = min(minX, f[i * 3]); maxX = max(maxX, f[i * 3])
            minY = min(minY, f[i * 3 + 1]); maxY = max(maxY, f[i * 3 + 1])
        }
        val size = max(max((maxX - minX) * w, (maxY - minY) * h) * 2f, MIN_HEAD_PX * 2)
        return square((minX + maxX) / 2 * w, (minY + maxY) / 2 * h, size, w, h)
    }

    private fun square(cx: Float, cy: Float, size: Float, w: Float, h: Float): IntArray {
        val s = min(size, min(w, h)).toInt()
        val left = (cx - s / 2f).toInt().coerceIn(0, (w - s).toInt())
        val top = (cy - s / 2f).toInt().coerceIn(0, (h - s).toInt())
        return intArrayOf(left, top, s)
    }

    /** Mid-torso is about 1.6 face heights (forehead to chin) below the chin. */
    private fun midTorsoY(f: FloatArray): Float {
        val chin = f[CHIN * 3 + 1]
        val faceHeight = chin - f[FOREHEAD * 3 + 1]
        return chin + MID_TORSO_FACE_HEIGHTS * faceHeight
    }

    private fun faceWidth(f: FloatArray): Float =
        hypot(f[LEFT_CHEEK * 3] - f[RIGHT_CHEEK * 3], f[LEFT_CHEEK * 3 + 1] - f[RIGHT_CHEEK * 3 + 1])

    override fun close() {
        pose.close()
        face.close()
    }

    /** Logs the average time per stage every few seconds (`adb logcat -s Gesture`), to guide speed tuning. */
    private class Timing {
        private val totals = LongArray(3)
        private val counts = IntArray(3)
        private var frames = 0
        private var windowStart = SystemClock.uptimeMillis()

        fun add(stage: Int, ms: Long) {
            totals[stage] += ms
            counts[stage]++
        }

        fun frameDone() {
            frames++
            val now = SystemClock.uptimeMillis()
            if (now - windowStart < 5000) return
            val avg = { i: Int -> if (counts[i] == 0) "-" else "${totals[i] / counts[i]}ms x${counts[i]}" }
            Log.i("Gesture", "timing over $frames frames: hands ${avg(HANDS_STAGE)}, pose ${avg(POSE_STAGE)}, face ${avg(FACE_STAGE)}")
            totals.fill(0)
            counts.fill(0)
            frames = 0
            windowStart = now
        }
    }

    companion object {
        const val TOTAL = 543
        private const val FACE = 0
        private const val FACE_COUNT = 468
        private const val LEFT_HAND = 468
        private const val HAND_COUNT = 21
        private const val RIGHT_HAND = 522
        // Face mesh points.
        private const val NOSE_TIP = 1
        private const val FOREHEAD = 10
        private const val CHIN = 152
        private const val RIGHT_CHEEK = 234
        private const val LEFT_CHEEK = 454
        const val SHOULDERS_PER_FACE_WIDTH = 2.2f
        private const val MID_TORSO_FACE_HEIGHTS = 1.6f // checked against measured body points: within ~0.03
        private const val FACE_CROP_PX = 256
        private const val MIN_HEAD_PX = 24f
        /** Look for the face again (with the body tracker) after this long without seeing it. */
        private const val FACE_STALE_MS = 5_000L
        /** While signing, refresh the face at least this often. */
        private const val FACE_REFRESH_MS = 600L
        private const val HANDS_STAGE = 0
        private const val POSE_STAGE = 1
        private const val FACE_STAGE = 2
    }
}
