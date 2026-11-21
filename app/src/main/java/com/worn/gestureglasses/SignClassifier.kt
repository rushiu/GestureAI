package com.worn.gestureglasses

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Pretrained 250-sign ASL model: 1st place in Google's Kaggle "ASL Signs" competition
 * (huggingface.co/sign/kaggle-asl-signs-1st-place, MIT). Includes thankyou, hello, please, yes, no...
 * Input: [frames, 543, 3] float32, any number of frames (see LandmarkExtractor). Output: 250 logits.
 */
class SignClassifier(context: Context) : Closeable {

    data class Prediction(val sign: String, val probability: Float, val runnersUp: String)

    private val interpreter: Interpreter
    private val labels: Array<String>

    init {
        val fd = context.assets.openFd(MODEL_FILE)
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))

        val json = JSONObject(context.assets.open(LABELS_FILE).bufferedReader().use { it.readText() })
        labels = Array(json.length()) { "" }
        json.keys().forEach { labels[json.getInt(it)] = it }
    }

    fun classify(clip: List<FloatArray>): Prediction {
        val values = LandmarkExtractor.TOTAL * 3
        interpreter.resizeInput(0, intArrayOf(clip.size, LandmarkExtractor.TOTAL, 3))
        interpreter.allocateTensors()

        val input = ByteBuffer.allocateDirect(clip.size * values * 4).order(ByteOrder.nativeOrder())
        val floats = input.asFloatBuffer()
        clip.forEach { floats.put(it) }

        val logits = FloatArray(labels.size)
        interpreter.run(input, logits)

        val probs = softmax(logits)
        val ranked = probs.indices.sortedByDescending { probs[it] }
        val runnersUp = ranked.drop(1).take(2).joinToString { "${labels[it]} ${"%.2f".format(probs[it])}" }
        return Prediction(labels[ranked[0]], probs[ranked[0]], runnersUp)
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_FILE = "asl_signs.tflite"
        const val LABELS_FILE = "sign_to_prediction_index_map.json"
    }
}

/** The sign model outputs raw scores; this turns them into probabilities. */
fun softmax(logits: FloatArray): FloatArray {
    val max = logits.max()
    val exps = FloatArray(logits.size) { exp(logits[it] - max) }
    val sum = exps.sum()
    return FloatArray(exps.size) { exps[it] / sum }
}
