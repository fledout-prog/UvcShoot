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
import android.view.SurfaceHolder
import android.view.SurfaceView

class UvcController(
    private val context: Context,
    private val previewSurface: SurfaceView
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbPermissionHelper = UsbPermissionHelper(context)

    private var receiverRegistered = false
    private var pendingCamera: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    private var nativeHandle: Long = 0L

    // --- Lifecycle state machine ---
    // Both flags must be true before nativeStartMjpegStream is called.
    // All reads/writes happen on the main thread, so no explicit locking is needed here.
    private var cameraReady = false   // true after nativeProbeAndOpenUvc succeeds
    private var surfaceReady = false  // true while a valid Surface is available
    private var streaming = false     // true while nativeStartMjpegStream is active

    /**
     * Gate function: start the MJPEG stream only when both the camera and the
     * surface are ready and the stream is not already running. This eliminates
     * the race between USB/camera open and surfaceCreated/surfaceChanged.
     */
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

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d("UVC", "surfaceCreated → surfaceReady=true")
            surfaceReady = true
            if (nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, holder.surface)
                tryStartStream()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d("UVC", "surfaceChanged ${width}x${height} format=$format → surfaceReady=true")
            surfaceReady = true
            if (nativeHandle != 0L) {
                // Always refresh the native window (dimensions may have changed).
                NativeBridge.nativeSetSurface(nativeHandle, holder.surface)
                // In case camera became ready before the surface appeared, start now.
                tryStartStream()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d("UVC", "surfaceDestroyed → surfaceReady=false")
            surfaceReady = false
            if (nativeHandle != 0L) {
                if (streaming) {
                    NativeBridge.nativeStopStream(nativeHandle)
                    streaming = false
                    Log.d("UVC", "surfaceDestroyed: stream stopped")
                }
                NativeBridge.nativeSetSurface(nativeHandle, null)
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbPermissionHelper.ACTION_USB_PERMISSION) return

            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            if (device == null) {
                Log.d("UVC", "USB permission result received but device is null")
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
    }

    fun onResume() {
        Log.d("UVC", "onResume")

        try {
            if (nativeHandle == 0L) {
                nativeHandle = NativeBridge.nativeInit()
                Log.d("UVC", "Native handle created: $nativeHandle")
            }

            val version = NativeBridge.getNativeVersion()
            Log.d("UVC", "Native bridge OK: $version")
        } catch (t: Throwable) {
            Log.e("UVC", "Native bridge failed", t)
        }

        // Register surface callback first so we never miss surfaceCreated/surfaceChanged.
        previewSurface.holder.addCallback(surfaceCallback)

        // If the surface is already valid (e.g., activity resumes without the surface
        // being destroyed), surfaceCreated won't fire again — handle it manually.
        val existingSurface: Surface? = previewSurface.holder.surface?.takeIf {
            previewSurface.holder.surface.isValid
        }
        if (existingSurface != null && nativeHandle != 0L) {
            Log.d("UVC", "onResume: surface already valid → surfaceReady=true")
            surfaceReady = true
            NativeBridge.nativeSetSurface(nativeHandle, existingSurface)
            // tryStartStream() is called once cameraReady is also set (in scanUsbDevices path)
        }

        registerUsbReceiver()
        scanUsbDevices()
    }

    fun onPause() {
        Log.d("UVC", "onPause")
        unregisterUsbReceiver()

        // Reset state machine — the stream and camera must be restarted on the next resume.
        streaming = false
        cameraReady = false
        surfaceReady = false

        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            NativeBridge.nativeSetSurface(nativeHandle, null)
        }

        previewSurface.holder.removeCallback(surfaceCallback)
        closeUsbConnection()
    }

    fun requestCapture() {
        Log.d("UVC", "Capture requested (stub)")
    }

    fun release() {
        unregisterUsbReceiver()

        streaming = false
        cameraReady = false
        surfaceReady = false

        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            NativeBridge.nativeSetSurface(nativeHandle, null)
        }

        previewSurface.holder.removeCallback(surfaceCallback)
        closeUsbConnection()

        if (nativeHandle != 0L) {
            NativeBridge.nativeRelease(nativeHandle)
            Log.d("UVC", "Native handle released")
            nativeHandle = 0L
        }
    }

    private fun openUsbConnectionAndSendToNative(device: UsbDevice) {
        closeUsbConnection()

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e("UVC", "usbManager.openDevice returned null")
            return
        }

        usbConnection = connection

        val fd = connection.fileDescriptor
        Log.d("UVC", "USB connection opened, fd=$fd for ${device.deviceName}")

        if (nativeHandle == 0L) {
            Log.e("UVC", "Native handle is 0, cannot send USB device info")
            return
        }

        val infoOk = NativeBridge.nativeSetUsbDeviceInfo(
            nativeHandle,
            fd,
            device.vendorId,
            device.productId,
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
            return
        }

        // Camera is ready. Mark it and let tryStartStream() decide whether to start
        // streaming now (if the surface is also ready) or defer until surfaceCreated fires.
        cameraReady = true
        Log.d("UVC", "cameraReady=true surfaceReady=$surfaceReady → calling tryStartStream")
        tryStartStream()
    }

    private fun closeUsbConnection() {
        usbConnection?.close()
        usbConnection = null
        Log.d("UVC", "USB connection closed")
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(UsbPermissionHelper.ACTION_USB_PERMISSION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                usbPermissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }

        receiverRegistered = true
        Log.d("UVC", "USB permission receiver registered")
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return

        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }

        receiverRegistered = false
        Log.d("UVC", "USB permission receiver unregistered")
    }

    private fun scanUsbDevices() {
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            Log.d("UVC", "No USB devices connected")
            return
        }

        Log.d("UVC", "USB devices found: ${deviceList.size}")

        deviceList.values.forEach { device ->
            logUsbDevice(device)

            if (isLikelyUvcCamera(device)) {
                Log.d("UVC", "Possible UVC camera found: ${device.deviceName}")

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
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) {
            return true
        }

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }

        return false
    }

    private fun logUsbDevice(device: UsbDevice) {
        Log.d(
            "UVC",
            "USB Device: " +
                    "name=${device.deviceName}, " +
                    "vendorId=${device.vendorId}, " +
                    "productId=${device.productId}, " +
                    "class=${device.deviceClass}, " +
                    "subclass=${device.deviceSubclass}, " +
                    "protocol=${device.deviceProtocol}, " +
                    "interfaces=${device.interfaceCount}"
        )

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.d(
                "UVC",
                "  Interface[$i]: " +
                        "class=${intf.interfaceClass}, " +
                        "subclass=${intf.interfaceSubclass}, " +
                        "protocol=${intf.interfaceProtocol}, " +
                        "endpoints=${intf.endpointCount}"
            )
        }
    }
}
