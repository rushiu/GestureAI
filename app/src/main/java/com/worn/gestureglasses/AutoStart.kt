package com.worn.gestureglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Keeps Gesture Glasses on the display. The glasses' own launcher isn't able to list this app,
 * so it brings itself to the front:
 *  - when the glasses finish starting up (BootReceiver; the glasses' power manager may block this),
 *  - every time the glasses wake up,
 *  - about 2 s after another app (like the built-in camera) lets go of the camera, and
 *  - after [BACKGROUND_LIMIT_MS] in the background, whatever is on screen.
 * The last three are handled by WakeWatcher, a small always-on service.
 *
 * Turn it off from the Mac:  adb shell am stopservice com.worn.gestureglasses/.WakeWatcher
 * Remove it completely:      adb uninstall com.worn.gestureglasses
 */
object AutoStart {
    private const val TAG = "Gesture"

    /** How long another app may stay on screen before Gesture Glasses comes back. */
    const val BACKGROUND_LIMIT_MS = 20_000L
    /** Delay after another app releases the camera. */
    const val CAMERA_RETURN_DELAY_MS = 2_000L

    /** Set by MainActivity: true while it's the app on screen. */
    @Volatile var onScreen = false
    /** When MainActivity last left the screen (SystemClock.uptimeMillis). */
    @Volatile var leftScreenAt = 0L

    fun startWatching(context: Context) {
        val intent = Intent(context, WakeWatcher::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun bringToFront(context: Context, why: String) {
        Log.i(TAG, "auto-start: $why")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}

/** Opens the app once the glasses have started up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AutoStart.startWatching(context)
        AutoStart.bringToFront(context, "glasses started")
    }
}

/** Brings the app back over the launcher: on wake, after another app's camera use, and after a timeout. */
class WakeWatcher : Service() {
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AutoStart.bringToFront(context, "glasses woke (${intent.action})")
        }
    }
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    /** Returns only if we're still in the background and the display is on. */
    private fun returnIfAway(why: String) {
        val display = getSystemService(PowerManager::class.java)
        if (!AutoStart.onScreen && display.isInteractive) AutoStart.bringToFront(this, why)
    }

    // Every few seconds: back to the front once we've been away for BACKGROUND_LIMIT_MS.
    private val backgroundCheck = object : Runnable {
        override fun run() {
            val away = SystemClock.uptimeMillis() - AutoStart.leftScreenAt
            if (!AutoStart.onScreen && AutoStart.leftScreenAt > 0 && away >= AutoStart.BACKGROUND_LIMIT_MS) {
                AutoStart.leftScreenAt = SystemClock.uptimeMillis() // don't retry every tick if the start is blocked
                returnIfAway("in background ${away / 1000}s")
            }
            handler.postDelayed(this, 3_000)
        }
    }

    // When another app takes the camera while we're away, come back shortly after it lets go.
    // (Our own camera opening and closing happens while we're on screen, so it doesn't count.)
    private var otherAppHasCamera = false
    private val cameraWatch = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (!AutoStart.onScreen) otherAppHasCamera = true
        }

        override fun onCameraAvailable(cameraId: String) {
            if (!otherAppHasCamera) return
            otherAppHasCamera = false
            handler.postDelayed({ returnIfAway("another app released the camera") }, AutoStart.CAMERA_RETURN_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Android stops background services after a minute unless they show a notification.
        val channelId = "autostart"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Gesture Glasses", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Gesture Glasses")
            .setContentText("Opens when the glasses wake")
            .build()
        startForeground(1, notification)

        registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        registered = true

        getSystemService(CameraManager::class.java).registerAvailabilityCallback(cameraWatch, handler)
        PhoneLink.start(this) // listen for commands from the phone (lives as long as this service)
        handler.postDelayed(backgroundCheck, 3_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        if (registered) unregisterReceiver(wakeReceiver)
        getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cameraWatch)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
