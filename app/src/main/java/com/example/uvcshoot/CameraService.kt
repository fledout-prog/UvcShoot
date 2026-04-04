package com.example.uvcshoot

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * Service that owns the USB/UVC camera pipeline.
 *
 * Design contract:
 *  - Runs as a bound service (via [android.content.Context.bindService]).
 *    Foreground promotion via [promoteToForeground] is currently disabled
 *    because the `connectedDevice` FGS type requires an active USB device
 *    context at the exact moment [startForeground] is called; calling it too
 *    early throws a [SecurityException] on Android 14+ (targetSdk 36).
 *    The app therefore runs as a bound/local service until a safe, well-gated
 *    promotion path is implemented.
 *  - The Activity binds to this service to get a [LocalBinder] reference.
 *  - [attachSurface] / [detachSurface] are called by the Activity when its
 *    SurfaceView is created/destroyed.  On detach the MJPEG stream is stopped
 *    and restarted cleanly on the next [attachSurface]; the USB/UVC context
 *    stays open so there is no camera re-open overhead on resume.
 *  - Future capture/trigger commands are routed through [requestCapture].
 *  - Returns START_STICKY so the OS will restart the service after a
 *    resource reclaim, subject to Android background execution limits.
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
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

    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate — initialising camera controller")
        // Pass the callback so UvcController can notify us when a USB device is
        // engaged.  promoteToForeground() is currently a no-op (see its KDoc),
        // but the hook is kept so future implementations can re-enable FGS
        // promotion without changing the controller code.
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
     * Foreground promotion is temporarily disabled.
     *
     * The `connectedDevice` FGS type requires Android to verify an active USB
     * device/accessory context at the moment [startForeground] is called.
     * Calling it before the USB permission is actually granted (not just
     * requested) throws a [SecurityException] on Android 14+ (API 34,
     * targetSdk 36).  Until a safe, well-gated promotion path is implemented
     * this method is intentionally a no-op so the service runs as a
     * bound/local service and the app does not crash on startup.
     *
     * TODO: Re-enable foreground promotion after confirming USB permission
     *       grant via [android.hardware.usb.UsbManager.openDevice] succeeds.
     */
    internal fun promoteToForeground() {
        Log.d(TAG, "promoteToForeground — skipped (connectedDevice FGS promotion deferred)")
    }

    /** Attach the Activity's preview Surface; frames will be rendered onto it. */
    fun attachSurface(surface: Surface) {
        Log.d(TAG, "attachSurface")
        uvcController.attachSurface(surface)
    }

    /**
     * Detach the current preview Surface and stop the active MJPEG stream.
     * The camera pipeline (USB connection + UVC context) remains open so the
     * next [attachSurface] call cleanly restarts the stream.
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

    /**
     * Inspect and recover preview state after standby, screen-off, or any
     * condition that may have disrupted the camera/stream/surface alignment.
     *
     * Delegates to [UvcController.recoverPreviewIfNeeded].  Safe to call from
     * [MainActivity.onResume] or any other recovery trigger point.
     */
    fun recoverPreviewIfNeeded() {
        Log.d(TAG, "recoverPreviewIfNeeded — delegating to controller")
        uvcController.recoverPreviewIfNeeded()
    }
}
