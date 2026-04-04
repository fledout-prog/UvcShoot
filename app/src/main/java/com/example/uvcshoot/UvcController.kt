package com.example.uvcshoot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.ImageView

class UvcController(
    private val context: Context,
    private val previewImage: ImageView
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbPermissionHelper = UsbPermissionHelper(context)

    private var receiverRegistered = false
    private var pendingCamera: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    private var nativeHandle: Long = 0L
    private var nativeCameraOpened = false

    private val frameListener = object {
        @Suppress("unused")
        fun onMjpegFrame(jpegBytes: ByteArray, width: Int, height: Int) {
            Log.d("UVC", "onMjpegFrame called bytes=${jpegBytes.size} width=$width height=$height")

            val patched = ensureJpegHasDht(jpegBytes)
            val bitmap = BitmapFactory.decodeByteArray(patched, 0, patched.size)

            if (bitmap == null) {
                Log.e("UVC", "BitmapFactory.decodeByteArray returned null even after DHT patch")
                return
            }

            previewImage.post {
                previewImage.setImageBitmap(bitmap)
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

            NativeBridge.nativeSetFrameListener(nativeHandle, frameListener)

            val version = NativeBridge.getNativeVersion()
            Log.d("UVC", "Native bridge OK: $version")
        } catch (t: Throwable) {
            Log.e("UVC", "Native bridge failed", t)
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
            NativeBridge.nativeSetFrameListener(nativeHandle, null)
        }

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
            NativeBridge.nativeSetFrameListener(nativeHandle, null)
        }

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

    private fun ensureJpegHasDht(input: ByteArray): ByteArray {
        if (input.size < 4) return input

        if (hasDht(input)) {
            return input
        }

        // Inseriamo DHT standard subito dopo SOI (FFD8)
        val result = ByteArray(2 + STANDARD_DHT.size + (input.size - 2))
        result[0] = input[0]
        result[1] = input[1]

        System.arraycopy(STANDARD_DHT, 0, result, 2, STANDARD_DHT.size)
        System.arraycopy(input, 2, result, 2 + STANDARD_DHT.size, input.size - 2)

        Log.d("UVC", "Inserted standard DHT into MJPEG frame")
        return result
    }

    private fun hasDht(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF) {
                var marker = data[i + 1].toInt() and 0xFF

                while (marker == 0xFF && i + 2 < data.size) {
                    i++
                    marker = data[i + 1].toInt() and 0xFF
                }

                if (marker == 0xC4) {
                    return true
                }

                // marker standalone
                if (marker == 0xD8 || marker == 0xD9) {
                    i += 2
                    continue
                }

                if (i + 3 >= data.size) break
                val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                if (length < 2) break
                i += 2 + length
            } else {
                i++
            }
        }
        return false
    }

    private val STANDARD_DHT = byteArrayOf(
        0xFF.toByte(), 0xC4.toByte(), 0x01, 0xA2.toByte(),
        0x00,
        0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        0x10,
        0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04,
        0x00, 0x00, 0x01, 0x7D,
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
        0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(),
        0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(),
        0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A,
        0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
        0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55,
        0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(),
        0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(),
        0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0xA3.toByte(),
        0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(),
        0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(),
        0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(),
        0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(),
        0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(),
        0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(),
        0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
        0x01,
        0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        0x11,
        0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04,
        0x00, 0x01, 0x02, 0x77,
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41,
        0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81.toByte(), 0x08, 0x14, 0x42, 0x91.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0xC1.toByte(), 0x09, 0x23, 0x33, 0x52, 0xF0.toByte(), 0x15, 0x62, 0x72, 0xD1.toByte(),
        0x0A, 0x16, 0x24, 0x34, 0xE1.toByte(), 0x25, 0xF1.toByte(), 0x17, 0x18, 0x19, 0x1A, 0x26,
        0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44,
        0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
        0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74,
        0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82.toByte(), 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(),
        0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(),
        0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(),
        0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(),
        0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(),
        0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE2.toByte(), 0xE3.toByte(),
        0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(),
        0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte()
    )
}
