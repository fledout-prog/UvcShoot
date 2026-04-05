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
import android.util.Log
import android.view.Surface

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
 *    scratch, optionally pre-attaching a surface so the stream can start
 *    immediately once the camera is ready.
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
    // Both flags must be true before nativeStartMjpegStream is called.
    private var cameraReady = false   // true after nativeProbeAndOpenUvc succeeds
    private var surfaceReady = false  // true while a valid Surface is attached
    @Volatile private var streaming = false  // true while nativeStartMjpegStream is active

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

    /** Returns true while the MJPEG stream is running. */
    fun isStreaming(): Boolean = streaming

    // -----------------------------------------------------------------------
    // Gate: start stream only when both camera and surface are ready
    // -----------------------------------------------------------------------

    private fun tryStartStream() {
        when {
            nativeHandle == 0L -> {
                Log.d("UVC", "tryStartStream: skipped — native handle not initialized")
                return
            }
            streaming -> {
                Log.d("UVC", "tryStartStream: skipped — already streaming")
                return
            }
            !cameraReady || !surfaceReady -> {
                Log.d(
                    "UVC",
                    "tryStartStream: waiting — cameraReady=$cameraReady surfaceReady=$surfaceReady"
                )
                return
            }
        }
        val ok = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
        streaming = ok
        Log.d("UVC", "nativeStartMjpegStream result=$ok → streaming=$streaming")
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
                        Log.d("UVC", "USB permission GRANTED for ${device.deviceName}")
                        // Guard: if the camera was already opened (e.g. by a concurrent recovery
                        // path), do not re-open on top of a healthy session.
                        if (cameraReady) {
                            Log.d(
                                "UVC",
                                "USB permission granted but cameraReady=true — skipping duplicate open for ${device.deviceName}"
                            )
                            return
                        }
                        pendingCamera = device
                        openUsbConnectionAndSendToNative(device)
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
                            pendingCamera = device
                            openUsbConnectionAndSendToNative(device)
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
     * Attach a preview [Surface].  Sets [surfaceReady], passes the surface to
     * the native layer, and calls [tryStartStream].  Use this for dimension
     * changes ([surfaceChanged]) where the pipeline is already healthy and only
     * the surface handle needs refreshing.  For post-standby or post-background
     * recovery use [hardRecoverCameraSession] instead.
     */
    fun attachSurface(surface: Surface) {
        currentSurface = surface
        Log.d(
            "UVC",
            "attachSurface: cameraReady=$cameraReady streaming=$streaming " +
                "surfaceReady=$surfaceReady nativeHandle=$nativeHandle"
        )
        surfaceReady = true
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, surface)
            Log.d("UVC", "attachSurface: surface attached to native — calling tryStartStream")
            tryStartStream()
        } else {
            Log.w("UVC", "attachSurface: native handle not ready — stream deferred until camera opens")
        }
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
            "onSurfaceDestroyed: surface truly destroyed — clearing currentSurface and stopping stream " +
                "(cameraReady=$cameraReady streaming=$streaming)"
        )
        currentSurface = null
        stopPreviewPipeline()
    }

    /**
     * Detach the current preview Surface and stop the MJPEG stream.
     *
     * Stopping the stream explicitly (rather than silently dropping frames)
     * ensures [streaming] is `false` when the surface comes back, so
     * [attachSurface] → [tryStartStream] performs a clean restart.
     * The camera pipeline (USB connection + UVC context) remains open for
     * immediate capture readiness — only the render stream is paused.
     */
    fun detachSurface() {
        Log.d(
            "UVC",
            "detachSurface: streaming=$streaming cameraReady=$cameraReady — stopping stream, camera stays open"
        )
        stopPreviewPipeline()
    }

    /**
     * Stop the preview pipeline: stop the MJPEG stream and detach the surface
     * from the native layer.  The USB connection and UVC context remain open
     * so the camera does not need to be re-probed on the next [attachSurface].
     *
     * Sets [surfaceReady] to `false` and [streaming] to `false`.
     */
    fun stopPreviewPipeline() {
        Log.d(
            "UVC",
            "stopPreviewPipeline: streaming=$streaming cameraReady=$cameraReady — stopping stream, surface detached"
        )
        surfaceReady = false
        if (nativeHandle != 0L) {
            if (streaming) {
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
     * After this call all state flags are reset and the controller is in a
     * known-clean state suitable for a fresh [openUsbConnectionAndSendToNative]
     * or [hardRecoverCameraSession] call.  The native handle itself is
     * retained so [start] does not need to be called again.
     */
    fun closeCameraSession() {
        Log.d(
            "UVC",
            "closeCameraSession: hard teardown begin — streaming=$streaming " +
                "cameraReady=$cameraReady nativeHandle=$nativeHandle"
        )
        // Reset open guard so a fresh openUsbConnectionAndSendToNative is
        // allowed after this teardown without hitting the duplicate-open guard.
        isOpening = false
        stopPreviewPipeline()
        if (nativeHandle != 0L) {
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            Log.d("UVC", "closeCameraSession: nativeCloseUsbCamera called")
        }
        closeUsbConnection()
        cameraReady = false
        Log.d("UVC", "closeCameraSession: hard teardown complete — cameraReady=false streaming=false surfaceReady=false")
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

        isRecovering = true
        Log.d(
            "UVC",
            "hardRecoverCameraSession: begin — currentSurface=${currentSurface != null} " +
                "cameraReady=$cameraReady streaming=$streaming surfaceReady=$surfaceReady"
        )

        // Hard teardown.  Note: closeCameraSession does NOT clear currentSurface,
        // so the surface reference survives this teardown.
        closeCameraSession()

        val device = pendingCamera ?: findUvcDevice()
        if (device == null) {
            Log.w("UVC", "hardRecoverCameraSession: no USB device found — recovery deferred until device attach")
            // Pre-attach current surface so the stream fires as soon as a device is
            // seen via the USB-attach broadcast path.
            val cs = currentSurface
            if (cs != null && nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, cs)
                surfaceReady = true
                Log.d("UVC", "hardRecoverCameraSession: surface pre-attached (waiting for USB device)")
            } else {
                Log.d(
                    "UVC",
                    "hardRecoverCameraSession: no surface to pre-attach " +
                        "(currentSurface=${currentSurface != null} nativeHandle=$nativeHandle)"
                )
            }
            isRecovering = false
            return
        }

        if (usbManager.hasPermission(device)) {
            Log.d(
                "UVC",
                "hardRecoverCameraSession: USB permission OK — reopening from clean state, " +
                    "device=${device.deviceName} currentSurface=${currentSurface != null}"
            )
            pendingCamera = device
            // openUsbConnectionAndSendToNative will reattach currentSurface and start the
            // stream internally once the camera is ready (see post-open step inside that method).
            openUsbConnectionAndSendToNative(device)
        } else {
            Log.d(
                "UVC",
                "hardRecoverCameraSession: no USB permission — requesting, pre-attaching surface " +
                    "(currentSurface=${currentSurface != null})"
            )
            // Pre-attach so the stream starts automatically once permission is granted
            // and openUsbConnectionAndSendToNative fires from the broadcast receiver.
            val cs = currentSurface
            if (cs != null && nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, cs)
                surfaceReady = true
                Log.d("UVC", "hardRecoverCameraSession: surface pre-attached (waiting for USB permission)")
            } else {
                Log.d(
                    "UVC",
                    "hardRecoverCameraSession: no surface to pre-attach for permission wait " +
                        "(currentSurface=${currentSurface != null})"
                )
            }
            usbPermissionHelper.requestPermission(usbManager, device)
        }

        isRecovering = false
        Log.d(
            "UVC",
            "hardRecoverCameraSession: end — cameraReady=$cameraReady streaming=$streaming " +
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
     * - cameraReady && !streaming → restart stream (most common post-standby case)
     * - !cameraReady && USB device reachable with permission → reopen camera
     * - !cameraReady && USB device present without permission → re-request permission
     * - already streaming → log and skip
     */
    fun recoverPreviewIfNeeded() {
        Log.d(
            "UVC",
            "recoverPreviewIfNeeded: cameraReady=$cameraReady streaming=$streaming " +
                "surfaceReady=$surfaceReady nativeHandle=$nativeHandle"
        )
        when {
            !surfaceReady -> {
                Log.d("UVC", "recoverPreviewIfNeeded: surface not attached — nothing to recover yet")
            }
            cameraReady && streaming -> {
                Log.d("UVC", "recoverPreviewIfNeeded: already streaming — no action needed")
            }
            cameraReady && !streaming -> {
                Log.d("UVC", "recoverPreviewIfNeeded: camera ready but stream inactive — restarting stream")
                tryStartStream()
            }
            !cameraReady -> {
                Log.d("UVC", "recoverPreviewIfNeeded: camera not ready — scanning for USB device")
                val device = findUvcDevice()
                if (device != null) {
                    if (usbManager.hasPermission(device)) {
                        Log.d("UVC", "recoverPreviewIfNeeded: USB device found with permission — reopening camera")
                        pendingCamera = device
                        openUsbConnectionAndSendToNative(device)
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
        Log.d("UVC", "requestCapture (stub) — streaming=$streaming cameraReady=$cameraReady")
    }

    /**
     * Release all resources.  Called from [CameraService.onDestroy].
     * After this the controller must not be used again.
     */
    fun release() {
        Log.d("UVC", "UvcController.release")
        unregisterUsbReceiver()

        isOpening = false
        isRecovering = false
        streaming = false
        cameraReady = false
        surfaceReady = false

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
        Log.d("UVC", "handleUsbDetach: device detached — tearing down camera pipeline, preserving surface state")
        // Stop the stream and close the native/USB camera session.
        // surfaceReady is intentionally preserved: if the surface is still
        // alive when the device reconnects, the post-open step inside
        // openUsbConnectionAndSendToNative() will call nativeSetSurface and
        // start the stream automatically.
        // Reset isOpening so a fresh open is allowed on re-attach.
        isOpening = false
        streaming = false
        cameraReady = false
        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            Log.d("UVC", "handleUsbDetach: nativeStopStream called")
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            Log.d("UVC", "handleUsbDetach: nativeCloseUsbCamera called")
        }
        closeUsbConnection()
        Log.d("UVC", "handleUsbDetach: camera pipeline reset — cameraReady=false streaming=false surfaceReady=$surfaceReady")
    }

    // -----------------------------------------------------------------------
    // USB connection helpers
    // -----------------------------------------------------------------------

    private fun openUsbConnectionAndSendToNative(device: UsbDevice) {
        // Guard: if an open is already in flight, suppress this duplicate call.
        // This prevents back-to-back nativeOpenUsbCamera invocations that occur
        // when rapid lifecycle events (onResume + surfaceCreated + onServiceConnected,
        // or two USB-permission grants from two requestPermission calls) all reach
        // this path within milliseconds of each other.
        if (isOpening) {
            Log.d(
                "UVC",
                "openUsbConnectionAndSendToNative: DUPLICATE OPEN SUPPRESSED — " +
                    "open already in progress for ${device.deviceName}"
            )
            return
        }
        isOpening = true
        Log.d("UVC", "openUsbConnectionAndSendToNative: starting open for ${device.deviceName}")

        // Close any leftover USB connection from a previous (failed) open before
        // entering the guarded try block that owns isOpening.
        closeUsbConnection()

        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e("UVC", "usbManager.openDevice returned null for ${device.deviceName}")
                return
            }
            usbConnection = connection

            val fd = connection.fileDescriptor
            Log.d("UVC", "USB connection opened fd=$fd for ${device.deviceName}")

            if (nativeHandle == 0L) {
                Log.e("UVC", "Native handle is 0 — cannot send USB device info")
                return
            }

            val infoOk = NativeBridge.nativeSetUsbDeviceInfo(
                nativeHandle, fd,
                device.vendorId, device.productId,
                device.deviceName
            )
            Log.d("UVC", "nativeSetUsbDeviceInfo result=$infoOk")
            if (!infoOk) {
                Log.e("UVC", "Failed to pass USB device info to native")
                return
            }

            val openOk = NativeBridge.nativeOpenUsbCamera(nativeHandle)
            Log.d("UVC", "nativeOpenUsbCamera result=$openOk")
            if (!openOk) {
                Log.e("UVC", "nativeOpenUsbCamera failed")
                return
            }

            val probeOk = NativeBridge.nativeProbeAndOpenUvc(nativeHandle)
            Log.d("UVC", "nativeProbeAndOpenUvc result=$probeOk")
            if (!probeOk) {
                Log.e("UVC", "nativeProbeAndOpenUvc failed — aborting post-open")
                cameraReady = false
                return
            }

            cameraReady = true
            Log.d(
                "UVC",
                "POST-OPEN: camera open success — cameraReady=true " +
                    "currentSurface=${currentSurface != null} surfaceReady=$surfaceReady"
            )

            // --- Deterministic post-open surface-attach + stream-start ---
            // Always call nativeSetSurface explicitly here, regardless of
            // whether the surface was "pre-attached" before the open.  The
            // native camera open (nativeOpenUsbCamera / nativeProbeAndOpenUvc)
            // may reset the native surface handle internally, making any
            // pre-attached surface stale.  Relying on the pre-attached state
            // (surfaceReady=true from hardRecoverCameraSession) was the root
            // cause of the regression where the stream never started after a
            // successful open.
            val cs = currentSurface
            if (cs != null && nativeHandle != 0L) {
                Log.d("UVC", "POST-OPEN: valid surface found — invoking nativeSetSurface")
                NativeBridge.nativeSetSurface(nativeHandle, cs)
                surfaceReady = true
                Log.d("UVC", "POST-OPEN: nativeSetSurface done — invoking nativeStartMjpegStream")
                val streamOk = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
                streaming = streamOk
                Log.d("UVC", "POST-OPEN: nativeStartMjpegStream result=$streamOk → streaming=$streaming")
            } else {
                Log.d(
                    "UVC",
                    "POST-OPEN: skipping attach/start — no valid surface available " +
                        "(currentSurface=${currentSurface != null} nativeHandle=$nativeHandle); " +
                        "stream will start when attachSurface is called"
                )
            }
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
                    pendingCamera = device
                    openUsbConnectionAndSendToNative(device)
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
