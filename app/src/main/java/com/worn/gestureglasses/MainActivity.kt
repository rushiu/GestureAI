package com.worn.gestureglasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Runs entirely on the M6 glasses:
 * glasses camera -> MediaPipe gesture model (held hand shapes) + ASL sign model (moving signs)
 * -> big text on the display (+ spoken, if the glasses have a voice).
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
        // ASL signs: the sign model's word list runs some words together. Other signs show as-is.
        "hello" to "Hello",
        "thankyou" to "Thank you",
        "please" to "Please",
        "yes" to "Yes",
        "no" to "No",
        "callonphone" to "Call on phone",
        "frenchfries" to "French fries",
        "glasswindow" to "Window",
        "haveto" to "Have to",
        "hesheit" to "He / she / it",
        "minemy" to "Mine / my",
        "weus" to "We / us",
        "icecream" to "Ice cream",
    )

    // ASL signs (pretrained 250-sign model, e.g. "thank you"), read after the signer waves, alongside gestures.
    // Loaded on the camera thread at start-up. Only touched on that thread.
    @Volatile private var signLandmarks: LandmarkExtractor? = null
    @Volatile private var signClassifier: SignClassifier? = null
    @Volatile private var signModelError: String? = null
    private val segmenter = SignSegmenter()
    private var lastWrist: FloatArray? = null
    private var lastWristAt = 0L

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
        // Load the ASL models now, so signs are read as soon as the signer waves.
        loadSignModels()
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
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = timestamp
            val bitmap = frame.toBitmap().upright(frame.imageInfo.rotationDegrees)

            // After the wave, ASL signs are read too. Then hands are only looked for above mid-torso
            // (see LandmarkExtractor), which skips a hand resting at the hip and is faster for both.
            val signs = signLandmarks.takeIf { consented && signClassifier != null }
            val area = signs?.searchArea(bitmap, timestamp)

            // One hand tracker for both: the gesture model finds the hand's points while it classifies.
            val handsStart = SystemClock.uptimeMillis()
            val result = model.recognizeForVideo(BitmapImageBuilder(area?.bitmap ?: bitmap).build(), timestamp)
            val handsMs = SystemClock.uptimeMillis() - handsStart

            var raw = NONE
            val hands = result.gestures()
            if (hands.isNotEmpty() && hands[0].isNotEmpty() && result.landmarks().isNotEmpty() &&
                handIsClear(result.landmarks()[0])
            ) {
                val guesses = hands[0]
                val top = guesses[0]
                val runnerUp = guesses.getOrNull(1)?.score() ?: 0f
                // Confident, and clearly ahead of the next guess: near-ties are skipped.
                if (top.score() >= MIN_SCORE && top.score() - runnerUp >= MIN_MARGIN &&
                    top.categoryName().isNotBlank()
                ) raw = top.categoryName()
            }

            if (result.landmarks().isNotEmpty()) lastHandAt = SystemClock.uptimeMillis()
            countFrame()
            if (DUMP_SIGN_CLIPS) logGesture(result, bitmap, timestamp)

            if (signs != null && area != null) {
                // Gestures are held hand shapes; a moving hand is left to the ASL reader. Otherwise flat-hand
                // signs like "thank you" would also show as Open_Palm ("Hello").
                if (handMoving(result.landmarks(), area.yScale, signs, timestamp)) raw = NONE
                analyzeSigns(bitmap, area, result.landmarks(), handsMs, timestamp)
            }

            val stable = smoother.update(raw)
            if (stable != null) runOnUiThread { onStableGesture(stable) }
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            frame.close()
        }
    }

    /**
     * A hand the gesture model can read well: whole (no point at the image's edge, where fingers get cut
     * off) and big enough (far-away hands are a few pixels across and get misread). Coordinates are 0..1
     * across the image the model saw (the search area after the wave). Only the gesture path uses this;
     * the ASL reader judges hands its own way.
     */
    private fun handIsClear(points: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        if (points.isEmpty()) return false
        val xs = points.map { it.x() }
        val ys = points.map { it.y() }
        val inside = xs.all { it in EDGE..(1 - EDGE) } && ys.all { it in EDGE..(1 - EDGE) }
        val size = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
        return inside && size >= MIN_HAND_SIZE
    }

    private var lastGestureLogAt = 0L

    /** Debugging: what the gesture model sees, at most twice a second, plus the picture while waiting for a wave. */
    private fun logGesture(result: GestureRecognizerResult, bitmap: Bitmap, timestamp: Long) {
        if (timestamp - lastGestureLogAt < 500) return
        lastGestureLogAt = timestamp
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val wrist = result.landmarks().firstOrNull()?.get(0)
        Log.i(
            TAG,
            "seen: " + if (wrist == null) "no hand" else
                "${top?.categoryName()} ${"%.2f".format(top?.score() ?: 0f)} at (${"%.2f".format(wrist.x())}, ${"%.2f".format(wrist.y())})" +
                    " · consented=$consented",
        )
        if (!consented) dumpFrame(bitmap, null, timestamp)
    }

    /** True unless the hand's wrist is held still (below [GESTURE_STILL_SPEED] shoulder widths per second). */
    private fun handMoving(
        hands: List<List<NormalizedLandmark>>,
        yScale: Float,
        signs: LandmarkExtractor,
        timestamp: Long,
    ): Boolean {
        val wrist = hands.firstOrNull()?.get(0)
        if (wrist == null) {
            lastWrist = null
            return false
        }
        val here = floatArrayOf(wrist.x(), wrist.y() * yScale)
        val before = lastWrist
        val dt = timestamp - lastWristAt
        lastWrist = here
        lastWristAt = timestamp
        if (before == null || dt <= 0) return true // just appeared: not known to be held still yet
        val scale = signs.bodyScale(timestamp) ?: 0.2f
        val speed = hypot(here[0] - before[0], here[1] - before[1]) / scale * 1000f / dt
        return speed >= GESTURE_STILL_SPEED
    }

    /**
     * ASL signs: body + face points are added to the hand, the frames are cut into one clip per sign,
     * and each clip gets one prediction (the sign model looks at the whole movement, so no Smoother).
     */
    private fun analyzeSigns(
        bitmap: Bitmap,
        area: LandmarkExtractor.SearchArea,
        hands: List<List<NormalizedLandmark>>,
        handsMs: Long,
        timestamp: Long,
    ) {
        val landmarks = signLandmarks ?: return
        val classifier = signClassifier ?: return

        val frame = landmarks.extract(bitmap, area, hands, handsMs, timestamp)
        if (frame.handsVisible) lastHandAt = SystemClock.uptimeMillis()
        if (DUMP_SIGN_CLIPS && frame.points != null) dumpFrame(bitmap, landmarks.lastFaceCrop, timestamp)

        val clip = segmenter.onFrame(timestamp, frame.points) ?: return
        val modelStart = SystemClock.uptimeMillis()
        val sign = classifier.classify(clip)
        val modelMs = SystemClock.uptimeMillis() - modelStart
        val raw = segmenter.lastRaw?.first?.size ?: 0
        Log.i(
            TAG,
            "sign ${sign.sign} p=${"%.2f".format(sign.probability)} (then ${sign.runnersUp}) " +
                "from $raw frames, ended ${segmenter.lastEnd}, model ${modelMs}ms",
        )
        if (DUMP_SIGN_CLIPS) segmenter.lastRaw?.let { (frames, times) -> dumpClip(frames, times, sign.sign) }
        if (sign.probability >= MIN_SIGN_PROBABILITY) runOnUiThread { onStableGesture(sign.sign) }
    }

    private fun Bitmap.upright(degrees: Int): Bitmap =
        if (degrees == 0) this
        else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private var dumpedFrames = 0

    /** Debugging: saves the camera frame and face crop for each raised-hand frame (first 300 per run). */
    private fun dumpFrame(bitmap: Bitmap, faceCrop: Bitmap?, timestamp: Long) {
        if (dumpedFrames++ >= 300) return
        try {
            val dir = java.io.File(getExternalFilesDir(null), "frames").apply { mkdirs() }
            java.io.File(dir, "${timestamp}_camera.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            faceCrop?.let { crop ->
                java.io.File(dir, "${timestamp}_face.jpg").outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame dump failed", e)
        }
    }

    /**
     * Debugging: saves each clip's raw points so they can be checked against the model on a computer.
     * `adb pull /sdcard/Android/data/com.worn.gestureglasses/files/clips`
     * Format: int frames, int floatsPerFrame, then frames x long timestampMs, then frames x floats (little-endian).
     */
    private fun dumpClip(frames: List<FloatArray>, times: List<Long>, guess: String) {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "clips").apply { mkdirs() }
            val per = frames.first().size
            val buf = java.nio.ByteBuffer.allocate(8 + frames.size * 8 + frames.size * per * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(frames.size).putInt(per)
            times.forEach { buf.putLong(it) }
            frames.forEach { f -> f.forEach { buf.putFloat(it) } }
            java.io.File(dir, "clip_${System.currentTimeMillis()}_$guess.bin").writeBytes(buf.array())
        } catch (e: Exception) {
            Log.w(TAG, "clip dump failed", e)
        }
    }

    /** Runs on the camera thread; takes a few seconds on the glasses. */
    private fun loadSignModels() {
        runOnUiThread { updateStatus() }
        try {
            signLandmarks = LandmarkExtractor(this)
            signClassifier = SignClassifier(this)
            Log.i(TAG, "ASL model loaded")
        } catch (e: Exception) {
            signModelError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "ASL model failed to load", e)
        }
        runOnUiThread { updateStatus() }
    }

    /** Consent gate: until the signer waves, only the wave itself is acted on. */
    private fun onStableGesture(label: String) {
        if (consented) {
            announce(label)
            return
        }
        if (label in START_LABELS) {
            if (::cameraExecutor.isInitialized) cameraExecutor.execute { segmenter.reset() }
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
            "resume" -> {
                paused = false
                smoother.reset()
                cameraExecutor.execute { segmenter.reset() }
            }
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
        val gestureModel = when {
            modelError != null -> "MODEL ERROR: $modelError"
            recognizer == null -> "loading model"
            else -> "model ok"
        }
        val signModel = when {
            signModelError != null -> "ASL MODEL ERROR: $signModelError"
            signClassifier == null -> "loading ASL"
            else -> "ASL ok"
        }
        val model = "$gestureModel · $signModel"
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
        cameraExecutor.execute {
            recognizer?.close()
            signLandmarks?.close()
            signClassifier?.close()
        }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Gesture"
        private const val MODEL_FILE = "gesture_recognizer.task"
        private const val CAMERA_REQUEST = 1
        /** How sure the gesture model must be (0..1). Was 0.6; 0.7 cuts most wrong readings. */
        private const val MIN_SCORE = 0.7f
        /** The top gesture guess must beat the next one by this much. */
        private const val MIN_MARGIN = 0.2f
        /** Ignore hands touching the outer 2% of the image (partly cut off). */
        private const val EDGE = 0.02f
        /** Ignore hands smaller than 12% of the image (too far away to read reliably). */
        private const val MIN_HAND_SIZE = 0.12f
        /** ASL signs below this are skipped (the model always picks one of its 250 words). */
        private const val MIN_SIGN_PROBABILITY = 0.4f
        /** Debugging: save each ASL clip, camera frames and face crops to the glasses, and log what the gesture model sees. Keep off outside testing: the consent banner promises no video is saved. */
        private const val DUMP_SIGN_CLIPS = false
        /** After the wave, a gesture only counts while the hand is held this still (shoulder widths per second). */
        private const val GESTURE_STILL_SPEED = 0.4f
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
        // The newest frame must agree too, so a hand passing between two signs doesn't fire a third.
        if (history.last() != winner) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastSentAt < cooldownMs) return null

        lastSent = winner
        lastSentAt = now
        return winner
    }
}
