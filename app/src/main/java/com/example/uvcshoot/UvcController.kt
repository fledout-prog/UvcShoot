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
    private var nativeCameraOpened = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d("UVC", "surfaceCreated")
            if (nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, holder.surface)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d("UVC", "surfaceChanged ${width}x${height} format=$format")
            if (nativeHandle != 0L) {
                NativeBridge.nativeSetSurface(nativeHandle, holder.surface)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d("UVC", "surfaceDestroyed")
            if (nativeHandle != 0L) {
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

        // Register surface callback — if the surface already exists, deliver it now
        previewSurface.holder.addCallback(surfaceCallback)
        val surface: Surface? = previewSurface.holder.surface?.takeIf {
            previewSurface.holder.surface.isValid
        }
        if (surface != null && nativeHandle != 0L) {
            NativeBridge.nativeSetSurface(nativeHandle, surface)
        }

        registerUsbReceiver()
        scanUsbDevices()
    }

    fun onPause() {
        Log.d("UVC", "onPause")
        unregisterUsbReceiver()

        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            nativeCameraOpened = false
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

        if (nativeHandle != 0L) {
            NativeBridge.nativeStopStream(nativeHandle)
            NativeBridge.nativeCloseUsbCamera(nativeHandle)
            nativeCameraOpened = false
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
        nativeCameraOpened = openOk
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

        val streamOk = NativeBridge.nativeStartMjpegStream(nativeHandle, 1280, 720, 30)
        Log.d("UVC", "nativeStartMjpegStream result=$streamOk")
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
