package com.worn.gestureglasses

/**
 * Decides which detected hand is the signer's left and which is their right, as the sign model
 * expects (MediaPipe Holistic order). Uses the pose wrists when available; otherwise assumes the
 * outward camera is not mirrored, so the signer's right hand appears left of [centerX] (their face) in the image.
 */
object HandAssignment {
    const val LEFT = 0
    const val RIGHT = 1

    data class Point(val x: Float, val y: Float)

    /** Returns LEFT or RIGHT for each hand wrist, in the same order. At most one hand per side. */
    fun assign(handWrists: List<Point>, poseLeftWrist: Point?, poseRightWrist: Point?, centerX: Float = 0.5f): IntArray {
        if (handWrists.isEmpty()) return IntArray(0)
        if (poseLeftWrist == null || poseRightWrist == null) {
            val sides = IntArray(handWrists.size) { if (handWrists[it].x < centerX) RIGHT else LEFT }
            if (handWrists.size == 2 && sides[0] == sides[1]) {
                // Both on one half of the image: the one further left is the right hand.
                val firstIsLefter = handWrists[0].x < handWrists[1].x
                sides[0] = if (firstIsLefter) RIGHT else LEFT
                sides[1] = if (firstIsLefter) LEFT else RIGHT
            }
            return sides
        }
        if (handWrists.size == 1) {
            val h = handWrists[0]
            return intArrayOf(if (dist(h, poseLeftWrist) <= dist(h, poseRightWrist)) LEFT else RIGHT)
        }
        val (a, b) = handWrists
        val straight = dist(a, poseLeftWrist) + dist(b, poseRightWrist)
        val swapped = dist(a, poseRightWrist) + dist(b, poseLeftWrist)
        return if (straight <= swapped) intArrayOf(LEFT, RIGHT) else intArrayOf(RIGHT, LEFT)
    }

    private fun dist(p: Point, q: Point): Float {
        val dx = p.x - q.x
        val dy = p.y - q.y
        return dx * dx + dy * dy
    }
}
