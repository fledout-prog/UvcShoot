package com.example.uvcshoot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

/**
 * Persistent foreground service that owns the USB/UVC camera pipeline.
 *
 * Design contract:
 *  - Initially starts as a bound service (via [android.content.Context.bindService]).
 *    It promotes itself to a foreground service ([promoteToForeground]) only
 *    once [UvcController] has engaged a USB device; this satisfies the
 *    runtime precondition for the `connectedDevice` FGS type and avoids the
 *    [SecurityException] that would occur if [startForeground] were called
 *    before [android.hardware.usb.UsbManager.requestPermission].
 *  - The Activity binds to this service to get a [LocalBinder] reference.
 *  - [attachSurface] / [detachSurface] are called by the Activity when its
 *    SurfaceView is created/destroyed.  The camera pipeline continues
 *    running without a surface; frames are silently dropped by the native
 *    layer until a surface is re-attached.
 *  - Future capture/trigger commands are routed through [requestCapture].
 *  - Returns START_STICKY so the OS will restart the service after a
 *    resource reclaim, subject to Android background execution limits.
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_CHANNEL_ID = "uvcshoot_camera"
        private const val NOTIFICATION_ID = 1
    }

    // -----------------------------------------------------------------------
    // Binder — returned to the Activity on bind
    // -----------------------------------------------------------------------
    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    private val binder = LocalBinder()

    // -----------------------------------------------------------------------
    // Camera controller (owns native handle, USB lifecycle, stream state)
    // -----------------------------------------------------------------------
    private lateinit var uvcController: UvcController

    /** Guards against calling startForeground() more than once. */
    private var isForeground = false

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate — initialising camera controller")
        // Pass a callback so UvcController can promote us to foreground the
        // moment a USB device is engaged (permission requested or already
        // granted).  This satisfies the Android runtime precondition for the
        // connectedDevice foreground-service type and avoids the
        // SecurityException that occurs when startForeground() is called
        // before any USB device interaction.
        uvcController = UvcController(applicationContext, onUsbDeviceEngaged = ::promoteToForeground)
        uvcController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand flags=$flags startId=$startId")
        // START_STICKY: re-create service after being killed by the OS
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind — Activity connected")
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind — Activity reconnected")
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind — Activity disconnected; service stays alive in background")
        // Detach the preview surface so the native window reference is released,
        // but leave the camera pipeline running for immediate capture readiness.
        uvcController.detachSurface()
        // Return true so onRebind is called when the Activity reconnects.
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        uvcController.release()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Public API — called through LocalBinder by the Activity
    // -----------------------------------------------------------------------

    /**
     * Promote the service to a foreground service now that a USB device has
     * been engaged (permission requested or already granted).  This is the
     * earliest safe moment to call [startForeground] for the
     * `connectedDevice` type, because Android requires that
     * [android.hardware.usb.UsbManager.requestPermission] has been called
     * before [startForeground] is invoked with that type.
     *
     * Also calls [startService] on this service so it becomes a "started"
     * service and survives after all clients unbind (i.e. the Activity goes
     * to the background), which is the desired always-on behaviour.
     *
     * Idempotent — does nothing if already in foreground.
     */
    internal fun promoteToForeground() {
        if (isForeground) return
        isForeground = true
        Log.d(TAG, "promoteToForeground — USB device engaged, promoting to foreground")
        // Make the service a started service so it lives beyond client unbind.
        startService(Intent(this, CameraService::class.java))
        // Promote to foreground; on API 29+ supply the explicit service type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    /** Attach the Activity's preview Surface; frames will be rendered onto it. */
    fun attachSurface(surface: Surface) {
        Log.d(TAG, "attachSurface")
        uvcController.attachSurface(surface)
    }

    /**
     * Detach the current preview Surface without stopping the camera pipeline.
     * Subsequent frames are silently dropped in the native layer.
     */
    fun detachSurface() {
        Log.d(TAG, "detachSurface")
        uvcController.detachSurface()
    }

    /** Request a still capture. Currently a stub; wired for future implementation. */
    fun requestCapture() {
        Log.d(TAG, "requestCapture")
        uvcController.requestCapture()
    }

    /** Returns true when the MJPEG stream is active and the camera is open. */
    fun isStreaming(): Boolean = uvcController.isStreaming()

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            flags
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
