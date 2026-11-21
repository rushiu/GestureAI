package com.worn.gestureglasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftmaxTest {
    @Test fun sumsToOneAndKeepsOrder() {
        val p = softmax(floatArrayOf(-2.7f, 3.1f, 0f))
        assertEquals(1f, p.sum(), 1e-5f)
        assertEquals(1, p.indices.maxBy { p[it] })
    }
}
