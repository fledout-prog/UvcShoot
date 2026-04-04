package com.example.uvcshoot

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("uvcshoot")
    }

    external fun getNativeVersion(): String
    external fun nativeInit(): Long
    external fun nativeRelease(handle: Long)
    external fun nativeSetSurface(handle: Long, surface: Surface?)
    external fun nativeSetFrameListener(handle: Long, listener: Any?)

    /**
     * If true, raw JPEG frames are also delivered to Kotlin via onMjpegFrame.
     * Use ONLY during photo capture, not during normal preview.
     * Keep false by default = zero Java overhead during preview.
     */
    external fun nativeSetDeliverFramesToJava(handle: Long, enable: Boolean)

    external fun nativeSetUsbDeviceInfo(
        handle: Long,
        fileDescriptor: Int,
        vendorId: Int,
        productId: Int,
        busDeviceName: String
    ): Boolean

    external fun nativeOpenUsbCamera(handle: Long): Boolean
    external fun nativeCloseUsbCamera(handle: Long)
    external fun nativeProbeAndOpenUvc(handle: Long): Boolean
    external fun nativeStartMjpegStream(handle: Long, width: Int, height: Int, fps: Int): Boolean
    external fun nativeStopStream(handle: Long)
}