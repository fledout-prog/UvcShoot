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
 *  - The Activity binds in its onCreate() and unbinds only in onDestroy(), so
 *    the binding (and the camera session) survive HOME/temporary background
 *    transitions.  [onUnbind] therefore signals true Activity destruction, not
 *    a transient background event.
 *  - [attachSurface] is the normal reattach path on resume: it updates the
 *    native surface binding and lets [UvcController.tryStartPreview] decide
 *    whether to (re)start the stream.
 *  - [hardRecoverCameraSession] is the full recovery entry point for first
 *    launch, USB detach/reattach, or unrecoverable native failure: it tears
 *    down the entire pipeline and reopens from a known-clean state.
 *  - [onSurfaceDestroyed] is called when the surface window is gone (e.g.
 *    screen off or HOME); it stops the stream and clears the surface reference
 *    without touching the camera session, so the camera stays ready for the
 *    next [surfaceCreated] → [attachSurface] cycle.
 *  - [closeCameraSession] is the hard teardown path, now called only from
 *    [onUnbind] (Activity destruction) and from [hardRecoverCameraSession].
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
        Log.d(TAG, "UVC_STATE: SERVICE_CREATE — initialising camera controller")
        uvcController = UvcController(applicationContext, onUsbDeviceEngaged = ::promoteToForeground)
        uvcController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand flags=$flags startId=$startId")
        // START_STICKY: re-create service after being killed by the OS
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "UVC_STATE: SERVICE_ONBIND — Activity connected")
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "UVC_STATE: SERVICE_ONREBIND — Activity reconnected")
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // onUnbind is now called only when the Activity is truly destroyed
        // (binding was moved to onCreate/onDestroy in MainActivity), not on
        // every HOME/background transition.  Perform full teardown here since
        // the session is no longer needed.
        Log.d(
            TAG,
            "UVC_STATE: SERVICE_UNBIND — Activity destroyed; performing full camera session teardown"
        )
        uvcController.closeCameraSession()
        // Return true so onRebind is called when the Activity reconnects.
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "UVC_STATE: SERVICE_DESTROY — releasing camera controller")
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
     * Stop the MJPEG stream and detach the surface from the native layer.
     * The USB/UVC pipeline stays open; call [attachSurface] to resume.
     */
    fun stopPreviewPipeline() {
        Log.d(TAG, "stopPreviewPipeline")
        uvcController.stopPreviewPipeline()
    }

    /**
     * Hard teardown: stop stream, close native UVC camera session, close Java
     * USB connection, and reset all state flags.  After this the pipeline is
     * in a known-clean state suitable for [hardRecoverCameraSession].
     */
    fun closeCameraSession() {
        Log.d(TAG, "closeCameraSession")
        uvcController.closeCameraSession()
    }

    /**
     * Definitive hard recovery: tear down the entire pipeline then reopen
     * from the last known USB device.  Optionally pre-attaches [surface] so
     * the stream starts as soon as the camera is ready.
     *
     * This replaces soft-retry / [recoverPreviewIfNeeded] as the primary
     * recovery path on surface creation and Activity resume.
     */
    fun hardRecoverCameraSession(surface: Surface? = null) {
        Log.d(TAG, "hardRecoverCameraSession — surface=${surface != null}")
        uvcController.hardRecoverCameraSession(surface)
    }

    /**
     * Called when the Activity's SurfaceView surface is truly destroyed
     * ([android.view.SurfaceHolder.Callback.surfaceDestroyed]).  Clears the
     * surface reference in the controller so it is not accidentally reattached
     * to a dead native window, then stops the MJPEG stream.
     *
     * This is distinct from [stopPreviewPipeline], which pauses the stream but
     * deliberately preserves the surface reference so it can be reattached after
     * hard recovery.  Use this method only when the surface object is gone.
     */
    fun onSurfaceDestroyed() {
        Log.d(TAG, "onSurfaceDestroyed")
        uvcController.onSurfaceDestroyed()
    }

    /**
     * Detach the current preview Surface and stop the active MJPEG stream.
     * Delegates to [stopPreviewPipeline]; kept for API compatibility.
     * The camera pipeline (USB connection + UVC context) remains open.
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

    /** Returns true when the UVC camera session is currently open. */
    fun isCameraOpen(): Boolean = uvcController.isCameraOpen()

    /**
     * Evaluate what recovery action is needed for the current preview state.
     * Returns a [PreviewRecoveryDecision] that lifecycle hooks use to dispatch
     * to the cheapest applicable recovery path.
     */
    fun evaluatePreviewRecoveryNeeded(): PreviewRecoveryDecision =
        uvcController.evaluatePreviewRecoveryNeeded()

    /**
     * Soft preview recovery: detach and re-attach the render surface and
     * restart the MJPEG stream without closing the USB camera.
     */
    fun softPreviewRecovery(surface: Surface) {
        Log.d(TAG, "softPreviewRecovery surfaceHash=${System.identityHashCode(surface)}")
        uvcController.softPreviewRecovery(surface)
    }

    /**
     * Restart only the MJPEG stream without closing the USB camera.
     * Use when the camera is open but the stream needs to be cycled.
     */
    fun restartPreviewStreamOnly() {
        Log.d(TAG, "restartPreviewStreamOnly")
        uvcController.restartPreviewStreamOnly()
    }

    /**
     * Schedule the post-resume health watchdog.  If the preview is still not
     * healthy ~1 300 ms after this call, [softPreviewRecovery] is invoked
     * automatically.
     */
    fun schedulePreviewWatchdog() {
        Log.d(TAG, "schedulePreviewWatchdog")
        uvcController.schedulePreviewWatchdog()
    }

    /**
     * Lightweight soft-recovery path.  Prefer [hardRecoverCameraSession] when
     * returning from background or after any standby cycle where the pipeline
     * may be in a stale or corrupted state.
     */
    fun recoverPreviewIfNeeded() {
        Log.d(TAG, "recoverPreviewIfNeeded — delegating to controller")
        uvcController.recoverPreviewIfNeeded()
    }
}
