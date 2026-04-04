package com.example.uvcshoot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbPermissionHelper(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.uvcshoot.USB_PERMISSION"
    }

    fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )

        usbManager.requestPermission(device, permissionIntent)
    }
}