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

    // MediaPipe reads the model straight out of the APK, so it must not be compressed.
    androidResources { noCompress.add("task") }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Downloads Google's ready-made gesture model the first time you build.
// To use your own signs, put your trained gesture_recognizer.task in app/src/main/assets/
// (replacing this one) and run again.
val modelFile = file("src/main/assets/gesture_recognizer.task")
val downloadModel by tasks.registering {
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        // uri(...) instead of java.net.URL: in a Gradle script, `java` means the java {} settings block.
        uri(
            "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/" +
                "gesture_recognizer/float16/1/gesture_recognizer.task"
        ).toURL().openStream().use { input -> modelFile.outputStream().use { input.copyTo(it) } }
        println("Downloaded the gesture model to $modelFile")
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }
