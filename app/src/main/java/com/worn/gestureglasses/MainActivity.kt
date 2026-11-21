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
import org.json.JSONObject
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
    private lateinit var consentText: TextView
    private lateinit var hintText: TextView
    /** The last few signs along the bottom, so a glance away doesn't lose the conversation. */
    private lateinit var historyText: TextView
    private val history = ArrayDeque<String>()
    /** Until this time, the welcome card is shown (set each time the app comes to the front). */
    private var welcomeUntil = 0L

    // Consent: the camera watches someone else's hands, so nothing is read until they wave hello.
    // After CONSENT_TIMEOUT_MS with no hands in view, it goes back to waiting for a wave.
    @Volatile private var consented = false
    @Volatile private var lastHandAt = 0L
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var recognizer: GestureRecognizer? = null
    private var modelError: String? = null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var voiceState = "starting voice"
    private var cameraState = "starting camera"

    private val smoother = Smoother()
    private var lastTimestamp = 0L
    @Volatile private var paused = false
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var fps = 0

    private val clearText = Runnable {
        bigText.text = ""
        updateHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AutoStart.startWatching(this) // come back to the front whenever the glasses wake
        PhoneLink.commandHandler = ::onPhoneCommand

        bigText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 64f
            gravity = Gravity.CENTER
        }
        // Technical status for the team (fps, model, link), kept dim so judges aren't distracted.
        statusText = TextView(this).apply {
            setTextColor(Color.rgb(90, 90, 90))
            textSize = 11f
            setPadding(16, 8, 16, 8)
        }
        historyText = TextView(this).apply {
            setTextColor(Color.rgb(0, 170, 0)) // dimmer green than the current sign
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START // keep the newest signs visible
        }
        // Onboarding for whoever just put the glasses on: a welcome card, then a hint until the signer waves.
        hintText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 0)
        }
        consentText = TextView(this).apply {
            setTextColor(Color.rgb(255, 200, 0)) // amber, like a recording light
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 0)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(hintText, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bigText, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // Just below the glasses' own status bar (clock, battery) at the top of the display.
            addView(consentText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = 44 })
            // Above the dim status line at the very bottom.
            addView(historyText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 34 })
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
                // CPU on purpose: the glasses' GPU delegate measured 2 fps vs 6-7 fps on CPU (no OpenCL).
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
        if (model == null || paused) { // paused: phone sent {"cmd":"pause"}
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

            if (result.landmarks().isNotEmpty()) lastHandAt = SystemClock.uptimeMillis()

            countFrame()
            val stable = smoother.update(raw)
            if (stable != null) runOnUiThread { onStableGesture(stable) }
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            frame.close()
        }
    }

    /** Consent gate: until the signer waves, only the wave itself is acted on. */
    private fun onStableGesture(label: String) {
        if (consented) {
            announce(label)
            return
        }
        if (label in START_LABELS) {
            consented = true
            lastHandAt = SystemClock.uptimeMillis()
            Log.i(TAG, "consent: signer waved ($label), reading signs")
            announce(label)
            updateStatus()
        }
    }

    /** Middle of the display when no sign is showing: welcome card first, then what to do next. */
    private fun updateHint() {
        val showingSign = bigText.text.isNotEmpty()
        hintText.text = when {
            showingSign -> ""
            SystemClock.uptimeMillis() < welcomeUntil ->
                "Gesture Glasses\n\nSigns from the person\nin front of you appear here"
            paused -> ""
            consented -> "Watching their hands…"
            else -> "Ask them to wave hello"
        }
        hintText.setTextColor(if (consented) Color.rgb(120, 120, 120) else Color.WHITE)
    }

    private fun addToHistory(phrase: String) {
        history.addLast(phrase)
        while (history.size > HISTORY_SIZE) history.removeFirst()
        historyText.text = history.joinToString("  ·  ")
    }

    private fun clearHistory() {
        history.clear()
        if (::historyText.isInitialized) historyText.text = ""
    }

    private fun checkConsentTimeout() {
        if (consented && SystemClock.uptimeMillis() - lastHandAt > CONSENT_TIMEOUT_MS) {
            consented = false
            smoother.reset()
            runOnUiThread { clearHistory() } // signer left: start the next conversation clean
            Log.i(TAG, "consent: no hands for ${CONSENT_TIMEOUT_MS / 1000}s, waiting for a wave")
        }
    }

    private fun announce(label: String) {
        val phrase = phrases[label] ?: label.replace('_', ' ')
        Log.i(TAG, "gesture $label -> $phrase")
        bigText.text = phrase
        addToHistory(phrase)
        bigText.removeCallbacks(clearText)
        bigText.postDelayed(clearText, 3000)
        if (voiceReady) tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "gesture")
        PhoneLink.send(JSONObject().put("event", "gesture").put("label", label).put("text", phrase))
    }

    /** Commands from the phone over Bluetooth (see PhoneLink). Runs on the main thread. */
    private fun onPhoneCommand(msg: JSONObject) {
        when (msg.optString("cmd")) {
            "pause" -> paused = true
            "resume" -> { paused = false; smoother.reset() }
            "text" -> {
                bigText.text = msg.optString("text")
                bigText.removeCallbacks(clearText)
                bigText.postDelayed(clearText, 3000)
            }
        }
        if (msg.optString("cmd") == "pause" || msg.optString("cmd") == "resume") {
            if (paused) fps = 0
            PhoneLink.send(JSONObject().put("event", "state").put("paused", paused))
        }
        updateStatus()
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
        checkConsentTimeout()
        consentText.text = when {
            paused -> "❚❚ Paused · the camera is not reading hands"
            cameraState == "starting camera" -> "Starting camera…"
            cameraState != "camera on" -> "Camera unavailable: $cameraState"
            consented -> "● Reading their signs on this device · no video saved"
            else -> "Waiting for a wave · nothing is read until then"
        }
        updateHint()
        val run = if (paused) "PAUSED" else "$fps fps"
        statusText.text = "$cameraState · $model · $run · $voiceState · ${PhoneLink.status}"
    }

    // Tell the auto-return watcher whether we're on screen. Set before CameraX reacts to the
    // lifecycle, so our own camera opening is never mistaken for another app's.
    override fun onStart() {
        AutoStart.onScreen = true
        super.onStart()
        // New wearer (glasses just put on / woken): show the welcome card, then the hint,
        // and ask for a fresh wave so the person signing consents again.
        welcomeUntil = SystemClock.uptimeMillis() + WELCOME_MS
        consented = false
        smoother.reset()
        clearHistory()
        updateStatus()
        hintText.postDelayed({ updateStatus() }, WELCOME_MS + 100)
    }

    override fun onStop() {
        super.onStop()
        AutoStart.onScreen = false
        AutoStart.leftScreenAt = SystemClock.uptimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (PhoneLink.commandHandler == ::onPhoneCommand) PhoneLink.commandHandler = null
        tts?.shutdown()
        cameraExecutor.execute { recognizer?.close() }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Gesture"
        private const val MODEL_FILE = "gesture_recognizer.task"
        private const val CAMERA_REQUEST = 1
        private const val MIN_SCORE = 0.6f
        /** Gestures that mean "you may read my signs": the stock open palm, or a trained "hello"/"start" sign. */
        private val START_LABELS = setOf("Open_Palm", "hello", "start")
        private const val CONSENT_TIMEOUT_MS = 30_000L
        private const val WELCOME_MS = 4_000L
        private const val HISTORY_SIZE = 4
        const val NONE = "None"
    }
}

/**
 * Turns a noisy stream of per-frame guesses into clean events:
 * a gesture must win [needed] of the last [window] frames, a held gesture fires once,
 * and two events are at least [cooldownMs] apart.
 */
class Smoother(
    // 3 of the last 5 frames: about 0.6 s at the glasses' 5-7 fps (was 4 of 6, about 1 s).
    private val window: Int = 5,
    private val needed: Int = 3,
    private val cooldownMs: Long = 1500,
) {
    private val history = ArrayDeque<String>()
    private var lastSent: String? = null
    private var lastSentAt = 0L

    fun reset() {
        history.clear()
        lastSent = null
    }

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
