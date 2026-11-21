plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.worn.gestureglasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.worn.gestureglasses"
        minSdk = 24          // the glasses run Android 9 (API 28)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // the glasses have an arm64 chip
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // MediaPipe and the sign model read their files straight out of the APK, so they must not be compressed.
    androidResources { noCompress += listOf("task", "tflite") }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.google.ai.edge.litert:litert:1.0.1") // runs the ASL sign model

    testImplementation("junit:junit:4.13.2")
}

// Downloads the models the first time you build (each file only if it's missing).
// To use your own signs, put your trained gesture_recognizer.task in app/src/main/assets/
// (replacing the stock one) and run again.
val mediapipe = "https://storage.googleapis.com/mediapipe-models"
val models = mapOf(
    "gesture_recognizer.task" to "$mediapipe/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task",
    // ASL signs: body and face points (the gesture model supplies the hand), then the pretrained
    // 250-sign model and its word list.
    "pose_landmarker_lite.task" to "$mediapipe/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
    "face_landmarker.task" to "$mediapipe/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
    "asl_signs.tflite" to "https://huggingface.co/sign/kaggle-asl-signs-1st-place/resolve/main/model.tflite",
    "sign_to_prediction_index_map.json" to
        "https://huggingface.co/sign/kaggle-asl-signs-1st-place/resolve/main/sign_to_prediction_index_map.json",
)
val assetsDir = file("src/main/assets")
val downloadModels by tasks.registering {
    onlyIf { models.keys.any { !assetsDir.resolve(it).exists() } }
    doLast {
        assetsDir.mkdirs()
        for ((name, url) in models) {
            val target = assetsDir.resolve(name)
            if (target.exists()) continue
            // uri(...) instead of java.net.URL: in a Gradle script, `java` means the java {} settings block.
            uri(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            println("Downloaded $name")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }
