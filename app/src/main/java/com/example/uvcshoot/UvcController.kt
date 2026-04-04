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
    private val surfaceView: SurfaceView
) : SurfaceHolder.Callback {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbPermissionHelper = UsbPermissionHelper(context)

    private var receiverRegistered = false
    private var usbConnection: UsbDeviceConnection? = null

    private var nativeHandle: Long = 0L
    private var nativeCameraOpened = false

    private var surfaceReady = false
    private var currentSurface: Surface? = null

    private val captureFrameListener = object {
        @Suppress("unused")
        fun onMjpegFrame(jpegBytes: ByteArray, width: Int, height: Int) {
            Log.d(TAG, "Capture frame: \\${jpegBytes.size} bytes \\${width}x\\${height}")
            if (nativeHandle != 0L) {
                NativeBridge.nativeSetDeliverFramesToJava(nativeHandle, false)
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbPermissionHelper.ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device == null) return
            if (granted) {
                Log.d(TAG, "USB permission GRANTED for \\${device.deviceName}")
                openUsbConnectionAndSendToNative(device)
            } else {
                Log.w(TAG, "USB permission DENIED for \\${device.deviceName}")
            }
        }
    }

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        currentSurface = holder.surface
        surfaceReady = true
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged \\$width x \\$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        surfaceReady = false
        currentSurface = null
        if (nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, null)
        }
    }

    fun onResume() {
        Log.d(TAG, "onResume")

        if (nativeHandle == 0L) {
            nativeHandle = NativeBridge.nativeInit()
            Log.d(TAG, "Native handle created: \\$nativeHandle (\\${NativeBridge.getNativeVersion()})")
        }

        NativeBridge.nativeSetFrameListener(nativeHandle, captureFrameListener)

        if (surfaceReady && currentSurface != null) {
            NativeBridge.nativeSetSurface(nativeHandle, currentSurface)
        }

        registerUsbReceiver()
        scanUsbDevices()
    }

    fun onPause() {
        Log.d(TAG, "onPause")
        unregisterUsbReceiver()

        if (nativeHandle != 0L) {
            NativeBridge.nativeSetDeliverFramesToJava(nativeHandle, false)
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            nativeCameraOpened = false
        }

        closeUsbConnection()
    }

    fun release() {
        Log.d(TAG, "release")
        unregisterUsbReceiver()

        if (nativeHandle != 0L) {
            NativeBridge.nativeSetDeliverFramesToJava(nativeHandle, false)
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            nativeCameraOpened = false
            NativeBridge.nativeSetFrameListener(nativeHandle, null)
            NativeBridge.nativeRelease(nativeHandle)
            nativeHandle = 0L
        }

        closeUsbConnection()
    }

    fun requestCapture() {
        Log.d(TAG, "requestCapture")
        if (nativeHandle != 0L && nativeCameraOpened) {
            NativeBridge.nativeSetDeliverFramesToJava(nativeHandle, true)
        }
    }

    private fun openUsbConnectionAndSendToNative(device: UsbDevice) {
        closeUsbConnection()

        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "usbManager.openDevice returned null for \\$device.deviceName")
            return
        }
        usbConnection = connection

        val fd = connection.fileDescriptor
        Log.d(TAG, "USB fd=\\$fd vid=\\${device.vendorId} pid=\\${device.productId}")

        if (nativeHandle == 0L) { Log.e(TAG, "nativeHandle is 0"); return }

        if (!NativeBridge.nativeSetUsbDeviceInfo(
                nativeHandle, fd, device.vendorId, device.productId, device.deviceName)) {
            Log.e(TAG, "nativeSetUsbDeviceInfo failed"); return
        }

        if (!NativeBridge.nativeOpenUsbCamera(nativeHandle)) {
            Log.e(TAG, "nativeOpenUsbCamera failed"); return
        }
        nativeCameraOpened = true

        if (!NativeBridge.nativeProbeAndOpenUvc(nativeHandle)) {
            Log.e(TAG, "nativeProbeAndOpenUvc failed"); return
        }

        val ok = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
        Log.d(TAG, "nativeStartMjpegStream 1280x720@30 -> \\$ok")
    }

    private fun closeUsbConnection() {
        usbConnection?.close()
        usbConnection = null
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(UsbPermissionHelper.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    private fun scanUsbDevices() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) { Log.d(TAG, "No USB devices"); return }
        Log.d(TAG, "USB devices: \\$devices.size")
        devices.values.forEach { device ->
            if (isLikelyUvcCamera(device)) {
                Log.d(TAG, "UVC camera: \\$device.deviceName vid=\\${device.vendorId}")
                if (usbManager.hasPermission(device)) {
                    openUsbConnectionAndSendToNative(device)
                } else {
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

    companion object {
        private const val TAG = "UvcController"
    }
}