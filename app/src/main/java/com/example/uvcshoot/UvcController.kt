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
 *  - When no surface is attached, frames are silently dropped in native
 *    code; the camera pipeline stays open for immediate capture readiness.
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
     * Attach a preview [Surface].  Sets [surfaceReady] and calls
     * [tryStartStream].  Safe to call repeatedly (e.g., on surfaceChanged).
     */
    fun attachSurface(surface: Surface) {
        Log.d("UVC", "attachSurface: surfaceReady=true streaming=$streaming")
        surfaceReady = true
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, surface)
            tryStartStream()
        }
    }

    /**
     * Detach the current preview Surface without stopping the camera or stream.
     * The native layer will silently drop frames until a surface is re-attached.
     * This keeps the camera pipeline alive for background capture readiness.
     */
    fun detachSurface() {
        Log.d("UVC", "detachSurface: surfaceReady=false — pipeline stays alive")
        surfaceReady = false
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, null)
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
        streaming = false
        cameraReady = false
        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
        }
        closeUsbConnection()
        Log.d("UVC", "handleUsbDetach: camera pipeline reset")
    }

    // -----------------------------------------------------------------------
    // USB connection helpers
    // -----------------------------------------------------------------------

    private fun openUsbConnectionAndSendToNative(device: UsbDevice) {
        closeUsbConnection()

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
        if (!probeOk) return

        cameraReady = true
        Log.d("UVC", "cameraReady=true surfaceReady=$surfaceReady → tryStartStream")
        tryStartStream()
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
