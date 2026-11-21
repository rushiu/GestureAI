package com.worn.gestureglasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs entirely on the M6 glasses:
 * glasses camera -> MediaPipe gesture model -> big text on the display (+ spoken, if the glasses have a voice).
 *
 * Logs: `adb logcat -s Gesture`
 */
class MainActivity : AppCompatActivity() {

    // What to show / say for each gesture. Change the words on the right freely.
    // Signs from your own trained model that aren't listed here are shown by their folder name.
    private val phrases = mapOf(
        "Thumb_Up" to "Yes",
        "Thumb_Down" to "No",
        "Open_Palm" to "Hello",
        "Closed_Fist" to "Stop",
        "Pointing_Up" to "Wait",
        "Victory" to "Peace",
        "ILoveYou" to "I love you",
    )

    private lateinit var bigText: TextView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var recognizer: GestureRecognizer? = null
    private var modelError: String? = null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var voiceState = "starting voice"
    private var cameraState = "starting camera"

    private val smoother = Smoother()
    private var lastTimestamp = 0L
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var fps = 0

    private val clearText = Runnable { bigText.text = "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AutoStart.startWatching(this) // come back to the front whenever the glasses wake

        bigText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 64f
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(bigText, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(statusText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        setContentView(root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute { loadModel() }

        tts = TextToSpeech(this) { status -> onVoiceInit(status) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraState = "allow camera access"
            updateStatus()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    /**
     * Speech needs a text-to-speech engine installed on the glasses (they don't ship with one).
     * eSpeak NG works: adb install it, then
     * `adb shell settings put secure tts_default_synth com.reecedunn.espeak`.
     */
    private fun onVoiceInit(status: Int) {
        val engine = tts
        voiceState = if (status != TextToSpeech.SUCCESS || engine == null) {
            "no voice: no speech engine"
        } else {
            var result = engine.setLanguage(Locale.US)
            if (result < TextToSpeech.LANG_AVAILABLE) result = engine.setLanguage(Locale.ENGLISH)
            when (result) {
                TextToSpeech.LANG_MISSING_DATA -> "no voice: English voice missing"
                TextToSpeech.LANG_NOT_SUPPORTED -> "no voice: no English voice"
                else -> "voice on"
            }
        }
        voiceReady = voiceState == "voice on"
        Log.i(
            TAG,
            "voice: init status=$status default=${engine?.defaultEngine} " +
                "installed=${engine?.engines?.map { it.name }} -> $voiceState"
        )
        updateStatus()
    }

    private fun loadModel() {
        try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_FILE).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .build()
            recognizer = GestureRecognizer.createFromOptions(this, options)
            Log.i(TAG, "model loaded")
        } catch (e: Exception) {
            modelError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "model failed to load", e)
        }
        runOnUiThread { updateStatus() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraState = "NO CAMERA PERMISSION"
            updateStatus()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = pickCamera(provider)
            if (selector == null) {
                cameraState = "NO CAMERA FOUND"
                updateStatus()
                return@addListener
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { analyze(it) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
                cameraState = "camera on"
            } catch (e: Exception) {
                cameraState = "CAMERA BUSY: ${e.message}"
                Log.e(TAG, "camera failed", e)
            }
            updateStatus()
        }, ContextCompat.getMainExecutor(this))
    }

    /** The glasses' outward camera may be listed as back, front or external — take whatever exists. */
    private fun pickCamera(provider: ProcessCameraProvider): CameraSelector? {
        for (candidate in listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)) {
            try {
                if (provider.hasCamera(candidate)) return candidate
            } catch (e: Exception) {
                Log.w(TAG, "camera check failed", e)
            }
        }
        return provider.availableCameraInfos.firstOrNull()?.cameraSelector
    }

    private fun analyze(frame: ImageProxy) {
        val model = recognizer
        if (model == null) {
            frame.close()
            return
        }
        try {
            val image = BitmapImageBuilder(frame.toBitmap()).build()
            val rotation = ImageProcessingOptions.builder()
                .setRotationDegrees(frame.imageInfo.rotationDegrees)
                .build()
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = timestamp

            val result = model.recognizeForVideo(image, rotation, timestamp)
            var raw = NONE
            val hands = result.gestures()
            if (hands.isNotEmpty() && hands[0].isNotEmpty()) {
                val top = hands[0][0]
                if (top.score() >= MIN_SCORE && top.categoryName().isNotBlank()) raw = top.categoryName()
            }

            countFrame()
            val stable = smoother.update(raw)
            if (stable != null) runOnUiThread { announce(stable) }
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            frame.close()
        }
    }

    private fun announce(label: String) {
        val phrase = phrases[label] ?: label.replace('_', ' ')
        Log.i(TAG, "gesture $label -> $phrase")
        bigText.text = phrase
        bigText.removeCallbacks(clearText)
        bigText.postDelayed(clearText, 3000)
        if (voiceReady) tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "gesture")
    }

    private fun countFrame() {
        val now = SystemClock.uptimeMillis()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        frameCount++
        if (now - fpsWindowStart >= 1000) {
            fps = frameCount
            frameCount = 0
            fpsWindowStart = now
            runOnUiThread { updateStatus() }
        }
    }

    private fun updateStatus() {
        val model = when {
            modelError != null -> "MODEL ERROR: $modelError"
            recognizer == null -> "loading model"
            else -> "model ok"
        }
        statusText.text = "$cameraState · $model · $fps fps · $voiceState"
    }

    // Tell the auto-return watcher whether we're on screen. Set before CameraX reacts to the
    // lifecycle, so our own camera opening is never mistaken for another app's.
    override fun onStart() {
        AutoStart.onScreen = true
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        AutoStart.onScreen = false
        AutoStart.leftScreenAt = SystemClock.uptimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        cameraExecutor.execute { recognizer?.close() }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Gesture"
        private const val MODEL_FILE = "gesture_recognizer.task"
        private const val CAMERA_REQUEST = 1
        private const val MIN_SCORE = 0.6f
        const val NONE = "None"
    }
}

/**
 * Turns a noisy stream of per-frame guesses into clean events:
 * a gesture must win [needed] of the last [window] frames, a held gesture fires once,
 * and two events are at least [cooldownMs] apart.
 */
class Smoother(
    private val window: Int = 6,
    private val needed: Int = 4,
    private val cooldownMs: Long = 1500,
) {
    private val history = ArrayDeque<String>()
    private var lastSent: String? = null
    private var lastSentAt = 0L

    fun update(raw: String): String? {
        history.addLast(raw)
        if (history.size > window) history.removeFirst()

        val (winner, count) = history.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.toPair()
        if (winner.equals(MainActivity.NONE, ignoreCase = true)) {
            lastSent = null // hand gone: the same gesture may fire again
            return null
        }
        if (count < needed || winner == lastSent) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastSentAt < cooldownMs) return null

        lastSent = winner
        lastSentAt = now
        return winner
    }
}
