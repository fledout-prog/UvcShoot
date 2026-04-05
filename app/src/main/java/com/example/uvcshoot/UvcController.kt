package com.example.uvcshoot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * Decision categories for preview recovery, ordered from cheapest to most expensive.
 * Returned by [UvcController.evaluatePreviewRecoveryNeeded] and acted on in
 * MainActivity lifecycle hooks.
 */
enum class PreviewRecoveryDecision {
    /** Preview pipeline is healthy; no action required. */
    NO_OP,
    /** Camera open and stream running; surface needs to be re-attached (generation changed). */
    ATTACH_ONLY,
    /** Camera open but stream not running; restart stream only without closing camera. */
    STREAM_RESTART_ONLY,
    /** Camera open, stream running, but preview is not healthy (surface mismatch or no recent frames). */
    SOFT_PREVIEW_RECOVERY,
    /** Camera not open; full pipeline teardown and reopen required. */
    HARD_RECOVER,
}

/**
 * Owns the USB/UVC camera pipeline: native handle, USB connection, UVC
 * stream state.  This class is instantiated and managed by [CameraService].
 *
 * Surface lifecycle is decoupled from camera lifecycle:
 *  - [attachSurface] / [detachSurface] can be called independently of
 *    [start] / [release].
 *  - [stopPreviewPipeline] stops the MJPEG stream and detaches the surface
 *    from the native layer while keeping the USB/UVC device open.
 *  - [closeCameraSession] performs a hard teardown: stops the stream, closes
 *    the native UVC camera, and closes the Java USB connection.  After this
 *    the controller is in a known-clean state ready for a fresh open.
 *  - [hardRecoverCameraSession] is the definitive recovery entry point: it
 *    calls [closeCameraSession] and then reopens the USB/UVC pipeline from
 *    scratch.  Both the async USB-permission-granted path and the
 *    already-granted/resume path converge into [tryStartPreview],
 *    which attempts to attach the current valid surface (if any) and starts
 *    the stream exactly once after a successful open.
 *  - [recoverPreviewIfNeeded] is a lightweight soft-recovery path kept for
 *    completeness; callers that know the pipeline may be stale should prefer
 *    [hardRecoverCameraSession].
 *
 * All calls are expected on the main thread unless noted otherwise.
 */
class UvcController(
    private val context: Context,
    /**
     * Optional callback invoked whenever a UVC device is engaged
     * (either because permission was already granted or because
     * [android.hardware.usb.UsbManager.requestPermission] was called).
     * [CameraService] uses this to promote itself to a foreground service
     * at the correct moment — after the runtime precondition for the
     * `connectedDevice` FGS type is satisfied.
     *
     * The callback may be invoked more than once (e.g. device detach/reattach
     * or multiple cameras at startup), so callers must make it idempotent.
     */
    private val onUsbDeviceEngaged: (() -> Unit)? = null,
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbPermissionHelper = UsbPermissionHelper(context)

    private var receiverRegistered = false
    private var pendingCamera: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    private var nativeHandle: Long = 0L

    // --- Lifecycle state machine ---
    // All five conditions must be simultaneously satisfied before the unified
    // readiness gate (tryStartPreview) will invoke nativeSetSurface +
    // nativeStartMjpegStream.
    private var usbPermissionGranted = false  // true once USB permission is known to be granted
    private var cameraOpened = false          // true after nativeProbeAndOpenUvc succeeds (was cameraReady)
    private var surfaceReady = false          // true while a valid Surface is attached
    @Volatile private var streaming = false   // true while nativeStartMjpegStream is active

    /**
     * Prevents duplicate nativeSetSurface + nativeStartMjpegStream invocations
     * while a start sequence is in progress.  Cleared in the `finally` block of
     * [tryStartPreview] so a failed start can be retried.
     */
    @Volatile private var isStarting = false

    /**
     * Last valid Surface provided by the Activity.  Persists across hard-recovery
     * cycles so it can be reattached after the camera reopens.  Only cleared when
     * the surface is truly destroyed ([onSurfaceDestroyed]).
     *
     * Marked `@Volatile` so reads in non-synchronized methods (e.g. [attachSurface],
     * [openUsbConnectionAndSendToNative]) always see the most recent write.
     */
    @Volatile private var currentSurface: Surface? = null

    /**
     * Guard against back-to-back [hardRecoverCameraSession] calls.  Set at the
     * start of recovery and cleared when the synchronous portion completes.
     * Prevents duplicate teardown+reopen sequences triggered by rapid lifecycle
     * callbacks (onResume + surfaceCreated + onServiceConnected).
     *
     * Marked `@Volatile` for cross-thread visibility (consistent with [streaming]).
     * Write-then-check atomicity within [hardRecoverCameraSession] is ensured by
     * its [@Synchronized] annotation.
     */
    @Volatile private var isRecovering = false

    /**
     * Guard against duplicate back-to-back [openUsbConnectionAndSendToNative] calls.
     * Set when an open sequence begins and cleared (in a finally block) when it
     * completes (success or failure).  A second call while one is in progress is
     * logged and suppressed immediately, preventing double-open races that arise
     * when rapid lifecycle events (onResume + surfaceCreated + onServiceConnected
     * or two USB-permission grants) all fire the open path in quick succession.
     *
     * Also cleared by [closeCameraSession] so a fresh open is always allowed
     * after a hard teardown.
     */
    @Volatile private var isOpening = false

    // --- Preview health state ---
    // These fields track the fine-grained health of the preview pipeline beyond
    // the single `cameraOpened` flag.  Used by [isPreviewHealthy],
    // [evaluatePreviewRecoveryNeeded], and [softPreviewRecovery].

    /**
     * Canonical name for the stream-running state; mirrors [streaming].
     * Exposed as a read-only property to allow [evaluatePreviewRecoveryNeeded]
     * to reference it by the documented name.
     */
    val streamRunning: Boolean get() = streaming

    /**
     * True when [NativeBridge.nativeSetSurface] has been called with the
     * current [currentSurface] (non-null) so the native render path is bound.
     * Reset to false whenever the surface is detached or the pipeline is torn down.
     */
    @Volatile private var surfaceAttached: Boolean = false

    /**
     * Monotonically incremented each time a genuinely new/replaced [Surface]
     * object is registered via [attachSurface].  Sameness is determined by
     * object identity (`===`), not value equality.
     */
    private var currentSurfaceGeneration: Long = 0L

    /**
     * The value of [currentSurfaceGeneration] at the time
     * [NativeBridge.nativeSetSurface] was last successfully called with a
     * non-null surface.  When this diverges from [currentSurfaceGeneration] the
     * native render path is bound to a stale surface and soft recovery is needed.
     */
    private var attachedSurfaceGeneration: Long = -1L

    /**
     * Timestamp (ms, [SystemClock.elapsedRealtime]) of the last confirmed frame
     * delivery to the active preview path.  Updated exclusively by
     * [FrameCallback.onFrame]; never set at stream-start time alone.
     * A value of 0 means no frame has been confirmed yet in this session.
     */
    @Volatile var lastFrameRenderedAtMs: Long = 0L

    /** Handler on the main looper, used to post the post-resume health watchdog. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The currently scheduled watchdog [Runnable], or null if none is pending. */
    private var watchdogRunnable: Runnable? = null

    /** Returns true while the MJPEG stream is running. */
    fun isStreaming(): Boolean = streaming

    /** Returns true when the UVC camera session is currently open. */
    fun isCameraOpen(): Boolean = cameraOpened

    // -----------------------------------------------------------------------
    // JNI frame-delivery callback
    // -----------------------------------------------------------------------

    /**
     * Registered with [NativeBridge.nativeSetFrameListener] so the native MJPEG
     * decoder can notify us when a decoded frame is about to be rendered onto the
     * preview [Surface].  This is the authoritative source for [lastFrameRenderedAtMs]:
     * it represents an actual frame reaching the active render path, not merely a
     * successful stream-start command.
     *
     * The method name and signature must match the JNI reflection lookup in the
     * native layer (`onFrame(byte[], int)`).
     */
    private inner class FrameCallback {
        @Suppress("unused") // invoked from JNI via reflection
        fun onFrame(data: ByteArray?, length: Int) {
            val now = SystemClock.elapsedRealtime()
            if (lastFrameRenderedAtMs == 0L) {
                Log.d(
                    "UVC",
                    "UVC_HEALTH: FIRST_FRAME — first frame confirmed at preview path at ${now}ms"
                )
            }
            lastFrameRenderedAtMs = now
        }
    }

    private val frameCallback = FrameCallback()

    // -----------------------------------------------------------------------
    // Unified readiness gate
    // -----------------------------------------------------------------------

    /**
     * Single deterministic gate that decides when to attach the preview surface
     * and start the MJPEG stream.
     *
     * Conditions required (ALL must be true simultaneously):
     *  1. USB permission granted ([usbPermissionGranted])
     *  2. Camera opened / native session ready ([cameraOpened] && [nativeHandle] ≠ 0)
     *  3. A currently valid [Surface] is available ([currentSurface]?.isValid == true)
     *  4. Not already streaming ([streaming] == false)
     *  5. Not already starting ([isStarting] == false)
     *
     * Every missing condition is logged individually so a missing prerequisite is
     * always visible in logcat.  Invoked from all paths that change one or more of
     * these conditions.
     */
    @Synchronized
    private fun tryStartPreview() {
        val cs = currentSurface
        val surfaceAvailable = cs != null && cs.isValid

        Log.d(
            "UVC",
            "tryStartPreview [READINESS-GATE]: " +
                "usbPermissionGranted=$usbPermissionGranted " +
                "cameraOpened=$cameraOpened " +
                "surfaceAvailable=$surfaceAvailable " +
                "streaming=$streaming " +
                "isStarting=$isStarting " +
                "nativeHandle=$nativeHandle"
        )

        if (!usbPermissionGranted) {
            Log.d("UVC", "tryStartPreview: NOT READY — prerequisite missing: usbPermissionGranted=false")
            return
        }
        if (!cameraOpened) {
            Log.d("UVC", "tryStartPreview: NOT READY — prerequisite missing: cameraOpened=false")
            return
        }
        if (nativeHandle == 0L) {
            Log.d("UVC", "tryStartPreview: NOT READY — prerequisite missing: nativeHandle=0")
            return
        }
        if (!surfaceAvailable) {
            Log.d(
                "UVC",
                "tryStartPreview: NOT READY — prerequisite missing: surfaceAvailable=false " +
                    "(currentSurface=${currentSurface != null} isValid=${cs?.isValid})"
            )
            // Clear a stale reference to a destroyed surface so it is not
            // accidentally reused once the surface is recreated.
            if (cs != null && !cs.isValid) {
                Log.d("UVC", "tryStartPreview: clearing stale/invalid surface reference")
                currentSurface = null
                surfaceReady = false
                surfaceAttached = false
            }
            return
        }
        if (streaming) {
            // Already streaming: refresh the native surface handle in case the
            // surface was recreated (e.g. surfaceChanged dimension update) without
            // restarting the pipeline.
            Log.d("UVC", "tryStartPreview: already streaming — refreshing surface reference only")
            if (cs != null && cs.isValid && nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, cs)
                surfaceAttached = true
                attachedSurfaceGeneration = currentSurfaceGeneration
                Log.d(
                    "UVC",
                    "tryStartPreview: nativeSetSurface called (streaming refresh) " +
                        "attachedSurfaceGeneration=$attachedSurfaceGeneration"
                )
            }
            return
        }
        if (isStarting) {
            Log.d("UVC", "tryStartPreview: duplicate start suppressed — start already in progress")
            return
        }

        // All prerequisites satisfied — start the preview pipeline exactly once.
        isStarting = true
        try {
            Log.d("UVC", "UVC_STATE: STREAM_START — all prerequisites satisfied, invoking nativeSetSurface")
            NativeBridge.nativeSetSurface(nativeHandle, cs)
            surfaceAttached = true
            attachedSurfaceGeneration = currentSurfaceGeneration
            surfaceReady = true
            // Register frame callback before starting stream so the first decoded
            // frame updates lastFrameRenderedAtMs via FrameCallback.onFrame().
            NativeBridge.nativeSetFrameListener(nativeHandle, frameCallback)
            Log.d("UVC", "UVC_STATE: STREAM_START — invoking nativeStartMjpegStream")
            val ok = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
            streaming = ok
            Log.d(
                "UVC",
                "UVC_STATE: STREAM_START — nativeStartMjpegStream result=$ok → streaming=$streaming " +
                    "surfaceAttached=$surfaceAttached attachedSurfaceGeneration=$attachedSurfaceGeneration"
            )
        } finally {
            isStarting = false
        }
    }

    // -----------------------------------------------------------------------
    // USB permission broadcast receiver
    // -----------------------------------------------------------------------

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbPermissionHelper.ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device == null) {
                        Log.d("UVC", "USB permission result: device is null")
                        return
                    }
                    if (granted) {
                        Log.d("UVC", "USB permission GRANTED for ${device.deviceName} — ASYNC-PERMISSION PATH")
                        usbPermissionGranted = true
                        // Guard: if the camera was already opened (cameraOpened) or an open is
                        // already in flight (isOpening), the permission grant is a late/duplicate
                        // broadcast.  The readiness gate (tryStartPreview) will handle starting
                        // the stream once all other conditions are met.
                        if (cameraOpened || isOpening) {
                            Log.d(
                                "UVC",
                                "ASYNC-PERMISSION PATH: camera already open or opening — " +
                                    "duplicate open suppressed (cameraOpened=$cameraOpened isOpening=$isOpening); " +
                                    "invoking tryStartPreview to ensure stream starts if ready"
                            )
                            tryStartPreview()
                            return
                        }
                        pendingCamera = device
                        openUsbConnectionAndSendToNative(device, entryPath = "async-permission")
                    } else {
                        Log.d("UVC", "USB permission DENIED for ${device.deviceName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isLikelyUvcCamera(device)) {
                        Log.d("UVC", "USB device attached: ${device.deviceName}")
                        // Notify before requestPermission / openDevice so the
                        // service can promote to foreground while the condition
                        // (requestPermission called) is being satisfied.
                        onUsbDeviceEngaged?.invoke()
                        if (usbManager.hasPermission(device)) {
                            usbPermissionGranted = true
                            pendingCamera = device
                            openUsbConnectionAndSendToNative(device, entryPath = "usb-attach-already-granted")
                        } else {
                            usbPermissionHelper.requestPermission(usbManager, device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    Log.d("UVC", "USB device detached: ${device?.deviceName}")
                    handleUsbDetach()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initialise the native bridge, register USB event receivers, and scan
     * for any already-connected UVC cameras.  Called once from
     * [CameraService.onCreate].
     */
    fun start() {
        Log.d("UVC", "UvcController.start")
        try {
            if (nativeHandle == 0L) {
                nativeHandle = NativeBridge.nativeInit()
                Log.d("UVC", "Native handle created: $nativeHandle")
            }
            Log.d("UVC", "Native bridge OK: ${NativeBridge.getNativeVersion()}")
        } catch (t: Throwable) {
            Log.e("UVC", "Native bridge init failed", t)
        }
        registerUsbReceiver()
        scanUsbDevices()
    }

    /**
     * Attach a preview [Surface].  Updates [currentSurface], increments
     * [currentSurfaceGeneration] when a genuinely new surface object is provided,
     * and invokes the unified readiness gate ([tryStartPreview]).
     *
     * [tryStartPreview] handles both cases:
     *  - Stream not yet started: attaches surface and starts stream when all
     *    prerequisites are met.
     *  - Stream already active (e.g. [surfaceChanged] dimension update): refreshes
     *    the native surface handle without restarting the pipeline.
     *
     * For post-standby or post-background recovery use [hardRecoverCameraSession]
     * instead.
     */
    fun attachSurface(surface: Surface) {
        val isNewSurface = surface !== currentSurface
        if (isNewSurface) {
            currentSurfaceGeneration++
            Log.d(
                "UVC",
                "UVC_HEALTH: attachSurface — NEW surface hash=${System.identityHashCode(surface)} " +
                    "generation=$currentSurfaceGeneration"
            )
        } else {
            Log.d(
                "UVC",
                "UVC_HEALTH: attachSurface — same surface reattached hash=${System.identityHashCode(surface)} " +
                    "generation=$currentSurfaceGeneration"
            )
        }
        currentSurface = surface
        surfaceReady = true
        Log.d(
            "UVC",
            "attachSurface: currentSurface updated — " +
                "cameraOpened=$cameraOpened streaming=$streaming " +
                "surfaceReady=$surfaceReady nativeHandle=$nativeHandle " +
                "— invoking tryStartPreview"
        )
        tryStartPreview()
    }

    /**
     * Called when the [android.view.SurfaceHolder] reports that the surface has
     * been truly destroyed (via [android.view.SurfaceHolder.Callback.surfaceDestroyed]).
     *
     * Unlike [stopPreviewPipeline] (which only pauses the stream but keeps
     * [currentSurface] so it can be reattached after recovery), this method also
     * clears [currentSurface] because the surface object is no longer valid.
     * Do NOT call this from [closeCameraSession] / hard recovery — the surface
     * may still be alive and should survive the pipeline teardown.
     */
    fun onSurfaceDestroyed() {
        Log.d(
            "UVC",
            "UVC_STATE: SURFACE_DETACH_ONLY — surface truly destroyed, clearing currentSurface and stopping stream " +
                "(cameraOpened=$cameraOpened streaming=$streaming)"
        )
        currentSurface = null
        surfaceReady = false
        surfaceAttached = false
        stopPreviewPipeline()
    }

    /**
     * Detach the current preview Surface and stop the MJPEG stream.
     *
     * Stopping the stream explicitly (rather than silently dropping frames)
     * ensures [streaming] is `false` when the surface comes back, so
     * [attachSurface] → [tryStartPreview] performs a clean restart.
     * The camera pipeline (USB connection + UVC context) remains open for
     * immediate capture readiness — only the render stream is paused.
     */
    fun detachSurface() {
        Log.d(
            "UVC",
            "detachSurface: streaming=$streaming cameraOpened=$cameraOpened — stopping stream, camera stays open"
        )
        stopPreviewPipeline()
    }

    /**
     * Stop the preview pipeline: stop the MJPEG stream and detach the surface
     * from the native layer.  The USB connection and UVC context remain open
     * so the camera does not need to be re-probed on the next [attachSurface].
     *
     * Sets [surfaceReady], [surfaceAttached], and [streaming] to `false`.
     */
    fun stopPreviewPipeline() {
        Log.d(
            "UVC",
            "UVC_STATE: SURFACE_DETACH_ONLY — stopPreviewPipeline: streaming=$streaming cameraOpened=$cameraOpened " +
                "(stream stopped, camera session kept open)"
        )
        surfaceReady = false
        surfaceAttached = false
        if (nativeHandle != 0L) {
            if (streaming) {
                Log.d("UVC", "stopPreviewPipeline: invoking nativeStopStream")
                NativeBridge.nativeStopStream(nativeHandle)
                streaming = false
                Log.d("UVC", "stopPreviewPipeline: nativeStopStream called")
            }
            NativeBridge.nativeSetSurface(nativeHandle, null)
            Log.d("UVC", "stopPreviewPipeline: surface detached from native")
        }
    }

    /**
     * Hard teardown: stop the MJPEG stream, close the native UVC camera
     * session, and close the Java USB connection.
     *
     * Symmetric teardown order:
     *  1. Reset open/start guards so a fresh cycle is possible after this.
     *  2. [nativeStopStream] if stream is active.
     *  3. Detach surface from native ([nativeSetSurface] null).
     *  4. [nativeCloseUsbCamera] if camera was open.
     *  5. Close Java USB connection.
     *  6. Reset all state flags including [usbPermissionGranted] so the next
     *     cycle re-verifies permission before opening.
     *
     * After this call the controller is in a known-clean state suitable for a
     * fresh [openUsbConnectionAndSendToNative] or [hardRecoverCameraSession] call.
     * The native handle itself is retained so [start] does not need to be called again.
     */
    fun closeCameraSession() {
        Log.d(
            "UVC",
            "UVC_STATE: FULL_TEARDOWN BEGIN — reason=closeCameraSession streaming=$streaming " +
                "cameraOpened=$cameraOpened nativeHandle=$nativeHandle"
        )
        // Reset open/start guards first so a fresh open is always allowed after teardown.
        isOpening = false
        isStarting = false

        // 1. Stop stream if active.
        if (streaming) {
            if (nativeHandle != 0L) {
                Log.d("UVC", "closeCameraSession: invoking nativeStopStream")
                NativeBridge.nativeStopStream(nativeHandle)
            }
            streaming = false
            Log.d("UVC", "closeCameraSession: stream stopped")
        }

        // 2. Detach surface from native (does not clear currentSurface so the
        //    Java Surface reference survives and can be reattached after recovery).
        surfaceReady = false
        surfaceAttached = false
        attachedSurfaceGeneration = -1L
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, null)
            Log.d("UVC", "closeCameraSession: surface detached from native")
        }

        // 3. Close native UVC camera session if it was open.
        //    `nativeHandle != 0L` is a defensive check: in the normal state machine
        //    nativeHandle is always non-zero when cameraOpened is true (nativeInit()
        //    in start() sets it before any open), but guarding here makes teardown
        //    safe even if release() races a teardown.
        if (cameraOpened && nativeHandle != 0L) {
            Log.d("UVC", "closeCameraSession: invoking nativeCloseUsbCamera")
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            Log.d("UVC", "closeCameraSession: nativeCloseUsbCamera called")
        }
        cameraOpened = false

        // 4. Close Java USB connection.
        closeUsbConnection()

        // 5. Reset permission flag so the next cycle re-verifies permission.
        usbPermissionGranted = false

        Log.d(
            "UVC",
            "UVC_STATE: FULL_TEARDOWN COMPLETE — " +
                "cameraOpened=false streaming=false surfaceReady=false usbPermissionGranted=false"
        )
    }

    /**
     * Definitive hard recovery: tear down the entire pipeline then reopen
     * from the last known USB device in a known-clean state.
     *
     * Unlike [recoverPreviewIfNeeded], which assumes the existing state is
     * healthy and only restarts the stream, this method explicitly tears down
     * everything first — preventing reopening on top of stale native/USB state
     * (the root cause of corrupted frames, bands/lines, and black-screen resume).
     *
     * @param surface Optional surface to attach after reopening.  If provided
     *                the stream will start immediately once the camera is ready.
     *                If `null`, call [attachSurface] separately when the surface
     *                becomes available.
     */
    @Synchronized
    fun hardRecoverCameraSession(surface: Surface? = null) {
        // Always update currentSurface when a new valid surface is provided.
        // This must happen BEFORE the isRecovering guard so that even a skipped
        // recovery benefits from the freshest surface when it completes.
        if (surface != null) {
            Log.d("UVC", "hardRecoverCameraSession: updating currentSurface from caller")
            currentSurface = surface
        }

        // Serialize: if a recovery is already running, the surface update above
        // is sufficient — the in-progress recovery will use it.
        if (isRecovering) {
            Log.d(
                "UVC",
                "hardRecoverCameraSession: recovery already in progress — surface updated, skipping duplicate " +
                    "(currentSurface=${currentSurface != null})"
            )
            return
        }

        // Guard: if the camera is already open and streaming, this is a duplicate
        // call from rapid lifecycle events (onResume + surfaceCreated + onServiceConnected
        // all firing within the same startup cycle).  Refresh the surface reference
        // without tearing down the healthy pipeline, and log the suppression.
        if (cameraOpened && streaming) {
            Log.d(
                "UVC",
                "hardRecoverCameraSession: DUPLICATE OPEN SUPPRESSED — camera already open and streaming; " +
                    "refreshing surface only (cameraOpened=$cameraOpened streaming=$streaming)"
            )
            val cs = currentSurface
            if (cs != null && cs.isValid && nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, cs)
                Log.d("UVC", "hardRecoverCameraSession: surface refreshed on live session")
            }
            return
        }

        isRecovering = true
        Log.d(
            "UVC",
            "UVC_STATE: FULL_TEARDOWN BEGIN — reason=hardRecoverCameraSession: currentSurface=${currentSurface != null} " +
                "cameraOpened=$cameraOpened streaming=$streaming surfaceReady=$surfaceReady"
        )

        // Hard teardown.  Note: closeCameraSession does NOT clear currentSurface,
        // so the surface reference survives this teardown.
        closeCameraSession()

        val device = pendingCamera ?: findUvcDevice()
        if (device == null) {
            Log.w("UVC", "hardRecoverCameraSession: no USB device found — recovery deferred until device attach")
            Log.d(
                "UVC",
                "hardRecoverCameraSession: waiting for USB device — " +
                    "currentSurface=${currentSurface != null} nativeHandle=$nativeHandle"
            )
            isRecovering = false
            return
        }

        if (usbManager.hasPermission(device)) {
            usbPermissionGranted = true
            Log.d(
                "UVC",
                "hardRecoverCameraSession: USB permission OK — ALREADY-GRANTED PATH — reopening from clean state, " +
                    "device=${device.deviceName} currentSurface=${currentSurface != null} usbPermissionGranted=true"
            )
            pendingCamera = device
            // openUsbConnectionAndSendToNative will set cameraOpened=true and call
            // tryStartPreview() once the camera is ready, attaching currentSurface and
            // starting the stream if all prerequisites are met.
            openUsbConnectionAndSendToNative(device, entryPath = "already-granted")
        } else {
            Log.d(
                "UVC",
                "hardRecoverCameraSession: no USB permission — requesting permission for device=${device.deviceName}; " +
                    "currentSurface=${currentSurface != null} will be used in tryStartPreview() after open"
            )
            // currentSurface is already saved; tryStartPreview() will use it once the
            // async permission grant fires openUsbConnectionAndSendToNative.
            usbPermissionHelper.requestPermission(usbManager, device)
        }

        isRecovering = false
        Log.d(
            "UVC",
            "hardRecoverCameraSession: end — cameraOpened=$cameraOpened streaming=$streaming " +
                "surfaceReady=$surfaceReady currentSurface=${currentSurface != null}"
        )
    }

    /**
     * Lightweight soft-recovery: inspect current state and attempt the minimal
     * action needed to get the stream running again.  Prefer
     * [hardRecoverCameraSession] when returning from background, lock/unlock,
     * or whenever the pipeline may be in a stale or corrupted state.
     *
     * Decision table:
     * - surface not ready  → nothing to do (surface callback will trigger later)
     * - cameraOpened && !streaming → call tryStartPreview (re-evaluates readiness gate)
     * - !cameraOpened && USB device reachable with permission → reopen camera
     * - !cameraOpened && USB device present without permission → re-request permission
     * - already streaming → log and skip
     */
    fun recoverPreviewIfNeeded() {
        Log.d(
            "UVC",
            "recoverPreviewIfNeeded: cameraOpened=$cameraOpened streaming=$streaming " +
                "surfaceReady=$surfaceReady nativeHandle=$nativeHandle"
        )
        when {
            !surfaceReady -> {
                Log.d("UVC", "recoverPreviewIfNeeded: surface not attached — nothing to recover yet")
            }
            cameraOpened && streaming -> {
                Log.d("UVC", "recoverPreviewIfNeeded: already streaming — no action needed")
            }
            cameraOpened && !streaming -> {
                Log.d("UVC", "recoverPreviewIfNeeded: camera ready but stream inactive — invoking tryStartPreview")
                tryStartPreview()
            }
            !cameraOpened -> {
                Log.d("UVC", "recoverPreviewIfNeeded: camera not ready — scanning for USB device")
                val device = findUvcDevice()
                if (device != null) {
                    if (usbManager.hasPermission(device)) {
                        Log.d("UVC", "recoverPreviewIfNeeded: USB device found with permission — reopening camera")
                        usbPermissionGranted = true
                        pendingCamera = device
                        openUsbConnectionAndSendToNative(device, entryPath = "soft-recovery")
                    } else {
                        Log.d("UVC", "recoverPreviewIfNeeded: USB device found but no permission — re-requesting")
                        usbPermissionHelper.requestPermission(usbManager, device)
                    }
                } else {
                    Log.w("UVC", "recoverPreviewIfNeeded: no UVC device found — cannot recover")
                }
            }
        }
    }

    /** Stub capture entry point; wired for future still-capture implementation. */
    fun requestCapture() {
        Log.d("UVC", "requestCapture (stub) — streaming=$streaming cameraOpened=$cameraOpened")
    }

    // -----------------------------------------------------------------------
    // Preview health monitoring
    // -----------------------------------------------------------------------

    /**
     * Returns true when the entire preview pipeline is healthy and actively
     * rendering frames on the current surface.
     *
     * All five conditions must be true simultaneously:
     *  - camera opened ([cameraOpened])
     *  - stream running ([streamRunning])
     *  - native render path bound to current surface ([surfaceAttached])
     *  - surface generation consistent ([currentSurfaceGeneration] == [attachedSurfaceGeneration])
     *  - a frame was confirmed recently by [FrameCallback] (within 1500 ms)
     *
     * [now] defaults to the current elapsed-realtime clock and may be passed
     * explicitly in tests.
     */
    fun isPreviewHealthy(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val recentFrame = lastFrameRenderedAtMs > 0L && (now - lastFrameRenderedAtMs) < 1500L
        val healthy = cameraOpened &&
            streamRunning &&
            surfaceAttached &&
            currentSurfaceGeneration == attachedSurfaceGeneration &&
            recentFrame
        Log.d(
            "UVC",
            "UVC_HEALTH: isPreviewHealthy=$healthy — " +
                "cameraOpened=$cameraOpened streamRunning=$streamRunning " +
                "surfaceAttached=$surfaceAttached " +
                "currentSurfaceGeneration=$currentSurfaceGeneration " +
                "attachedSurfaceGeneration=$attachedSurfaceGeneration " +
                "lastFrameRenderedAtMs=$lastFrameRenderedAtMs " +
                "recentFrame=$recentFrame"
        )
        return healthy
    }

    /**
     * Evaluates the current preview state and returns the minimum recovery
     * action needed.  Callers in lifecycle hooks use the returned value to
     * dispatch to the appropriate (cheapest) recovery path.
     *
     * Decision priority (first matching rule wins):
     *  1. Camera not open → [PreviewRecoveryDecision.HARD_RECOVER]
     *  2. Camera open, stream not running → [PreviewRecoveryDecision.STREAM_RESTART_ONLY]
     *  3. Camera open, stream running, surface not attached or generation mismatch
     *     → [PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY]
     *  4. Camera open, stream running, preview not healthy (no recent frames)
     *     → [PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY]
     *  5. Otherwise → [PreviewRecoveryDecision.NO_OP]
     */
    fun evaluatePreviewRecoveryNeeded(): PreviewRecoveryDecision {
        val now = SystemClock.elapsedRealtime()
        val decision = when {
            !cameraOpened ->
                PreviewRecoveryDecision.HARD_RECOVER

            cameraOpened && !streamRunning ->
                PreviewRecoveryDecision.STREAM_RESTART_ONLY

            cameraOpened && streamRunning &&
                (!surfaceAttached || currentSurfaceGeneration != attachedSurfaceGeneration) ->
                PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY

            cameraOpened && streamRunning && !isPreviewHealthy(now) ->
                PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY

            else -> PreviewRecoveryDecision.NO_OP
        }
        Log.d(
            "UVC",
            "UVC_HEALTH: evaluatePreviewRecoveryNeeded → $decision — " +
                "cameraOpened=$cameraOpened streamRunning=$streamRunning " +
                "surfaceAttached=$surfaceAttached " +
                "currentSurfaceGeneration=$currentSurfaceGeneration " +
                "attachedSurfaceGeneration=$attachedSurfaceGeneration " +
                "lastFrameRenderedAtMs=$lastFrameRenderedAtMs"
        )
        return decision
    }

    /**
     * Soft preview recovery: detach and re-attach the render surface, then
     * restart only the MJPEG stream path.  Does NOT close the USB camera —
     * the device session and UVC context stay open.
     *
     * Use when the camera is open and the stream may be running but the
     * preview is black due to a stale surface binding or a stuck stream.
     *
     * Escalates automatically to [hardRecoverCameraSession] if [cameraOpened]
     * is false (e.g. called after USB detach).
     */
    @Synchronized
    fun softPreviewRecovery(surface: Surface) {
        Log.d(
            "UVC",
            "UVC_HEALTH: softPreviewRecovery invoked — cameraOpened=$cameraOpened " +
                "streamRunning=$streamRunning surfaceAttached=$surfaceAttached " +
                "surfaceHash=${System.identityHashCode(surface)}"
        )
        if (!cameraOpened) {
            Log.d("UVC", "UVC_HEALTH: softPreviewRecovery — camera not open, escalating to hardRecoverCameraSession")
            hardRecoverCameraSession(surface)
            return
        }
        if (nativeHandle == 0L) {
            Log.w("UVC", "UVC_HEALTH: softPreviewRecovery — nativeHandle=0, cannot recover")
            return
        }

        val isNewSurface = surface !== currentSurface
        if (isNewSurface) {
            currentSurfaceGeneration++
            Log.d(
                "UVC",
                "UVC_HEALTH: softPreviewRecovery — new surface, generation=$currentSurfaceGeneration"
            )
        }
        currentSurface = surface
        surfaceReady = true

        // Force render-path rebind: detach → stop stream → re-attach → restart stream.
        // nativeCloseUsbCamera is deliberately NOT called — USB session stays open.
        NativeBridge.nativeSetSurface(nativeHandle, null)
        surfaceAttached = false
        attachedSurfaceGeneration = -1L

        if (streaming) {
            Log.d("UVC", "UVC_HEALTH: softPreviewRecovery — stopping active stream")
            NativeBridge.nativeStopStream(nativeHandle)
            streaming = false
        }

        NativeBridge.nativeSetSurface(nativeHandle, surface)
        surfaceAttached = true
        attachedSurfaceGeneration = currentSurfaceGeneration
        NativeBridge.nativeSetFrameListener(nativeHandle, frameCallback)

        val ok = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
        streaming = ok
        Log.d(
            "UVC",
            "UVC_HEALTH: softPreviewRecovery complete — streaming=$ok " +
                "surfaceAttached=$surfaceAttached attachedSurfaceGeneration=$attachedSurfaceGeneration"
        )
    }

    /**
     * Restart only the MJPEG stream without closing the USB camera.
     *
     * Use when the camera is open and the surface is already bound, but the
     * stream needs to be explicitly cycled (stop → re-attach surface → start).
     * Does nothing if the camera is not currently open.
     */
    fun restartPreviewStreamOnly() {
        Log.d(
            "UVC",
            "UVC_HEALTH: restartPreviewStreamOnly — cameraOpened=$cameraOpened streaming=$streaming"
        )
        if (!cameraOpened || nativeHandle == 0L) {
            Log.d("UVC", "UVC_HEALTH: restartPreviewStreamOnly — camera not open, skipping")
            return
        }
        if (streaming) {
            NativeBridge.nativeStopStream(nativeHandle)
            streaming = false
        }
        val cs = currentSurface
        if (cs != null && cs.isValid) {
            NativeBridge.nativeSetSurface(nativeHandle, cs)
            surfaceAttached = true
            attachedSurfaceGeneration = currentSurfaceGeneration
        }
        NativeBridge.nativeSetFrameListener(nativeHandle, frameCallback)
        val ok = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
        streaming = ok
        Log.d("UVC", "UVC_HEALTH: restartPreviewStreamOnly complete — streaming=$ok")
    }

    /**
     * Detach only the native render surface without stopping the stream or
     * closing the camera.  Use when the surface window is temporarily
     * invalidated but the camera and stream should remain alive.
     *
     * Contrast with [stopPreviewPipeline], which stops the stream and detaches
     * the surface, and [closeCameraSession], which does a full teardown.
     */
    fun detachSurfaceOnly() {
        Log.d(
            "UVC",
            "UVC_HEALTH: detachSurfaceOnly — streaming=$streaming cameraOpened=$cameraOpened " +
                "surfaceAttached=$surfaceAttached"
        )
        surfaceAttached = false
        attachedSurfaceGeneration = -1L
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, null)
            Log.d("UVC", "UVC_HEALTH: detachSurfaceOnly — nativeSetSurface(null) called, camera/stream preserved")
        }
    }

    /**
     * Schedule a one-shot post-resume health watchdog to fire ~1 300 ms from now.
     *
     * If the preview is still not healthy when it fires, [softPreviewRecovery]
     * is invoked automatically.  Any previously scheduled watchdog is cancelled
     * first so only one watchdog is pending at a time.
     *
     * Should be called from each lifecycle hook that may need recovery:
     * [android.app.Activity.onResume], [android.view.SurfaceHolder.Callback.surfaceCreated],
     * and [android.content.ServiceConnection.onServiceConnected].
     */
    fun schedulePreviewWatchdog() {
        cancelPreviewWatchdog()
        val r = Runnable {
            watchdogRunnable = null
            val now = SystemClock.elapsedRealtime()
            val healthy = isPreviewHealthy(now)
            Log.d(
                "UVC",
                "UVC_HEALTH: WATCHDOG fired — previewHealthy=$healthy " +
                    "cameraOpened=$cameraOpened streamRunning=$streamRunning " +
                    "surfaceAttached=$surfaceAttached " +
                    "currentSurfaceGeneration=$currentSurfaceGeneration " +
                    "attachedSurfaceGeneration=$attachedSurfaceGeneration " +
                    "lastFrameRenderedAtMs=$lastFrameRenderedAtMs"
            )
            if (!healthy) {
                val cs = currentSurface
                if (cs != null && cs.isValid) {
                    Log.d("UVC", "UVC_HEALTH: WATCHDOG — preview not healthy, triggering softPreviewRecovery")
                    softPreviewRecovery(cs)
                } else {
                    Log.d("UVC", "UVC_HEALTH: WATCHDOG — no valid surface available; skipping soft recovery")
                }
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, 1300L)
        Log.d("UVC", "UVC_HEALTH: WATCHDOG scheduled (1300 ms)")
    }

    private fun cancelPreviewWatchdog() {
        watchdogRunnable?.let {
            mainHandler.removeCallbacks(it)
            watchdogRunnable = null
            Log.d("UVC", "UVC_HEALTH: WATCHDOG cancelled")
        }
    }

    /**
     * Release all resources.  Called from [CameraService.onDestroy].
     * After this the controller must not be used again.
     */
    fun release() {
        Log.d("UVC", "UvcController.release")
        cancelPreviewWatchdog()
        unregisterUsbReceiver()

        isOpening = false
        isRecovering = false
        isStarting = false
        streaming = false
        cameraOpened = false
        surfaceReady = false
        surfaceAttached = false
        attachedSurfaceGeneration = -1L
        lastFrameRenderedAtMs = 0L
        usbPermissionGranted = false

        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            NativeBridge.nativeSetSurface(nativeHandle, null)
            NativeBridge.nativeRelease(nativeHandle)
            Log.d("UVC", "Native handle released")
            nativeHandle = 0L
        }

        closeUsbConnection()
    }

    // -----------------------------------------------------------------------
    // USB detach handler
    // -----------------------------------------------------------------------

    private fun handleUsbDetach() {
        Log.d(
            "UVC",
            "UVC_STATE: FULL_TEARDOWN BEGIN — reason=USB_DETACH: device detached, tearing down camera pipeline"
        )
        // Stop the stream and close the native/USB camera session.
        // surfaceReady is intentionally preserved: if the surface is still
        // alive when the device reconnects, tryStartPreview() will handle
        // reattaching and starting the stream automatically.
        // Reset isOpening/isStarting so a fresh open is allowed on re-attach.
        isOpening = false
        isStarting = false
        usbPermissionGranted = false
        streaming = false
        cameraOpened = false
        surfaceAttached = false
        attachedSurfaceGeneration = -1L
        if (nativeHandle != 0L) {
            Log.d("UVC", "handleUsbDetach: invoking nativeStopStream")
            NativeBridge.nativeStopStream(nativeHandle)
            Log.d("UVC", "handleUsbDetach: nativeStopStream called")
            Log.d("UVC", "handleUsbDetach: invoking nativeCloseUsbCamera")
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            Log.d("UVC", "handleUsbDetach: nativeCloseUsbCamera called")
        }
        closeUsbConnection()
        Log.d(
            "UVC",
            "UVC_STATE: FULL_TEARDOWN COMPLETE — reason=USB_DETACH: " +
                "cameraOpened=false streaming=false surfaceReady=$surfaceReady surfaceAttached=false"
        )
    }

    // -----------------------------------------------------------------------
    // USB connection helpers
    // -----------------------------------------------------------------------

    private fun openUsbConnectionAndSendToNative(device: UsbDevice, entryPath: String) {
        // Guard: if an open is already in flight, suppress this duplicate call.
        // This prevents back-to-back nativeOpenUsbCamera invocations that occur
        // when rapid lifecycle events (onResume + surfaceCreated + onServiceConnected,
        // or two USB-permission grants from two requestPermission calls) all reach
        // this path within milliseconds of each other.
        if (isOpening) {
            Log.d(
                "UVC",
                "openUsbConnectionAndSendToNative [$entryPath]: DUPLICATE OPEN SUPPRESSED — " +
                    "open already in progress for ${device.deviceName}"
            )
            return
        }
        isOpening = true
        Log.d("UVC", "openUsbConnectionAndSendToNative [$entryPath]: starting open for ${device.deviceName}")

        // Close any leftover USB connection from a previous (failed) open before
        // entering the guarded try block that owns isOpening.
        closeUsbConnection()

        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e("UVC", "[$entryPath] usbManager.openDevice returned null for ${device.deviceName}")
                return
            }
            usbConnection = connection

            val fd = connection.fileDescriptor
            Log.d("UVC", "[$entryPath] USB connection opened fd=$fd for ${device.deviceName}")

            if (nativeHandle == 0L) {
                Log.e("UVC", "[$entryPath] Native handle is 0 — cannot send USB device info")
                return
            }

            val infoOk = NativeBridge.nativeSetUsbDeviceInfo(
                nativeHandle, fd,
                device.vendorId, device.productId,
                device.deviceName
            )
            Log.d("UVC", "[$entryPath] nativeSetUsbDeviceInfo result=$infoOk")
            if (!infoOk) {
                Log.e("UVC", "[$entryPath] Failed to pass USB device info to native")
                return
            }

            val openOk = NativeBridge.nativeOpenUsbCamera(nativeHandle)
            Log.d("UVC", "[$entryPath] nativeOpenUsbCamera result=$openOk")
            if (!openOk) {
                Log.e("UVC", "[$entryPath] nativeOpenUsbCamera failed")
                return
            }

            val probeOk = NativeBridge.nativeProbeAndOpenUvc(nativeHandle)
            Log.d("UVC", "[$entryPath] nativeProbeAndOpenUvc result=$probeOk")
            if (!probeOk) {
                Log.e("UVC", "[$entryPath] nativeProbeAndOpenUvc failed — aborting")
                cameraOpened = false
                return
            }

            // Camera is ready — mark it open and let the unified readiness gate
            // decide whether to start the stream.  tryStartPreview() checks all
            // remaining prerequisites (usbPermissionGranted, valid surface, etc.)
            // and starts exactly once when all are satisfied.
            cameraOpened = true
            Log.d(
                "UVC",
                "UVC_STATE: CAMERA_OPEN — [$entryPath] camera opened successfully " +
                    "cameraOpened=true nativeHandle=$nativeHandle — invoking tryStartPreview"
            )
            tryStartPreview()
        } finally {
            isOpening = false
        }
    }

    private fun closeUsbConnection() {
        usbConnection?.close()
        usbConnection = null
        Log.d("UVC", "USB connection closed")
    }

    // -----------------------------------------------------------------------
    // Broadcast receiver registration
    // -----------------------------------------------------------------------

    private fun registerUsbReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(UsbPermissionHelper.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }

        receiverRegistered = true
        Log.d("UVC", "USB receiver registered (permission + attach + detach)")
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
        Log.d("UVC", "USB receiver unregistered")
    }

    // -----------------------------------------------------------------------
    // USB device scanning
    // -----------------------------------------------------------------------

    private fun scanUsbDevices() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            Log.d("UVC", "No USB devices connected at startup")
            return
        }
        Log.d("UVC", "USB devices found at startup: ${deviceList.size}")
        deviceList.values.forEach { device ->
            logUsbDevice(device)
            if (isLikelyUvcCamera(device)) {
                Log.d("UVC", "Possible UVC camera: ${device.deviceName}")
                // Notify before any permission/open work so the service can
                // promote to foreground while the runtime precondition for the
                // connectedDevice FGS type is being satisfied.
                onUsbDeviceEngaged?.invoke()
                if (usbManager.hasPermission(device)) {
                    Log.d("UVC", "USB permission already granted for ${device.deviceName}")
                    usbPermissionGranted = true
                    pendingCamera = device
                    openUsbConnectionAndSendToNative(device, entryPath = "scan-already-granted")
                } else {
                    Log.d("UVC", "Requesting USB permission for ${device.deviceName}")
                    usbPermissionHelper.requestPermission(usbManager, device)
                }
            }
        }
    }

    /** Returns the first connected UVC camera found in the USB device list, or null. */
    private fun findUvcDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isLikelyUvcCamera(it) }

    private fun isLikelyUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun logUsbDevice(device: UsbDevice) {
        Log.d(
            "UVC",
            "USB Device: name=${device.deviceName} vid=${device.vendorId} " +
                    "pid=${device.productId} class=${device.deviceClass} " +
                    "interfaces=${device.interfaceCount}"
        )
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.d(
                "UVC",
                "  Interface[$i]: class=${intf.interfaceClass} " +
                        "subclass=${intf.interfaceSubclass} endpoints=${intf.endpointCount}"
            )
        }
    }
}
