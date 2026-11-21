package com.worn.gestureglasses

import com.worn.gestureglasses.HandAssignment.LEFT
import com.worn.gestureglasses.HandAssignment.Point
import com.worn.gestureglasses.HandAssignment.RIGHT
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HandAssignmentTest {
    // Signer faces the camera: their left wrist is on the image right.
    private val poseLeft = Point(0.7f, 0.6f)
    private val poseRight = Point(0.3f, 0.6f)

    @Test fun matchesHandsToPoseWrists() {
        assertArrayEquals(intArrayOf(LEFT, RIGHT), HandAssignment.assign(listOf(Point(0.72f, 0.58f), Point(0.31f, 0.6f)), poseLeft, poseRight))
        assertArrayEquals(intArrayOf(RIGHT, LEFT), HandAssignment.assign(listOf(Point(0.31f, 0.6f), Point(0.72f, 0.58f)), poseLeft, poseRight))
    }

    @Test fun singleHandNearChinGoesToNearestWrist() {
        assertArrayEquals(intArrayOf(RIGHT), HandAssignment.assign(listOf(Point(0.4f, 0.3f)), poseLeft, poseRight))
    }

    @Test fun fallsBackToImageSideWithoutPose() {
        assertArrayEquals(intArrayOf(RIGHT), HandAssignment.assign(listOf(Point(0.2f, 0.5f)), null, null))
        assertArrayEquals(intArrayOf(LEFT, RIGHT), HandAssignment.assign(listOf(Point(0.4f, 0.5f), Point(0.2f, 0.5f)), null, null))
    }

    @Test fun withoutPoseSplitsAtTheFace() {
        // Signer's face at x = 0.7: a hand at 0.6 is left of it in the picture, so it's their right hand.
        assertArrayEquals(intArrayOf(RIGHT), HandAssignment.assign(listOf(Point(0.6f, 0.4f)), null, null, centerX = 0.7f))
        assertArrayEquals(intArrayOf(LEFT), HandAssignment.assign(listOf(Point(0.8f, 0.4f)), null, null, centerX = 0.7f))
    }
}
