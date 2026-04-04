package com.example.uvcshoot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Camera preview Activity.  No longer owns the camera lifecycle; it binds
 * to [CameraService] and acts only as a surface/UI bridge.
 *
 * Lifecycle contract:
 *  - onStart  → bind to [CameraService] (service is NOT started here;
 *               it promotes itself to foreground once a USB device is engaged)
 *  - onStop   → unbind (service continues in background if already foreground)
 *  - SurfaceHolder.Callback → attach/detach preview surface via service API
 *  - onKeyDown (volume-down / KEYCODE_CAMERA) → trigger capture
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var previewSurface: SurfaceView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private var cameraService: CameraService? = null
    private var serviceBound = false

    // -----------------------------------------------------------------------
    // Service connection
    // -----------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "onServiceConnected")
            cameraService = (binder as CameraService.LocalBinder).getService()
            serviceBound = true

            // If the SurfaceView already has a valid surface (e.g., quick
            // resume), attach it immediately so preview starts right away.
            val holder = previewSurface.holder
            if (holder.surface != null && holder.surface.isValid) {
                Log.d(TAG, "onServiceConnected: surface already valid — attaching")
                cameraService?.attachSurface(holder.surface)
            }
            updateStatus("Camera service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "onServiceDisconnected")
            cameraService = null
            serviceBound = false
            updateStatus("Camera service disconnected")
        }
    }

    // -----------------------------------------------------------------------
    // SurfaceHolder callbacks — routed to the service
    // -----------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceCreated")
            cameraService?.attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "surfaceChanged ${width}x${height}")
            // Re-attach so the native window is refreshed with the new dimensions.
            cameraService?.attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceDestroyed")
            cameraService?.detachSurface()
        }
    }

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewSurface = findViewById(R.id.previewSurface)
        captureButton = findViewById(R.id.captureButton)
        statusText = findViewById(R.id.statusText)

        previewSurface.holder.addCallback(surfaceCallback)

        captureButton.setOnClickListener { triggerCapture() }

        updateStatus("Starting camera service…")
    }

    override fun onStart() {
        super.onStart()
        bindToService()
    }

    override fun onStop() {
        if (serviceBound) {
            // Detach surface — service keeps the camera pipeline alive.
            cameraService?.detachSurface()
            unbindService(serviceConnection)
            serviceBound = false
            cameraService = null
            Log.d(TAG, "onStop: unbound from service — pipeline continues in background")
        }
        super.onStop()
    }

    override fun onDestroy() {
        previewSurface.holder.removeCallback(surfaceCallback)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Hardware trigger support
    // -----------------------------------------------------------------------

    /**
     * Intercept hardware key events that may be used as a physical capture
     * trigger (volume-down, dedicated camera button).  When a trigger key is
     * pressed the capture command is forwarded to [CameraService].
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA -> {
                Log.d(TAG, "Hardware key trigger: keyCode=$keyCode")
                triggerCapture()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun triggerCapture() {
        Log.d(TAG, "triggerCapture — serviceBound=$serviceBound")
        cameraService?.requestCapture()
            ?: Log.w(TAG, "triggerCapture: service not yet bound")
        updateStatus("Capture requested")
    }

    /**
     * Only bind to [CameraService]; do NOT start it as a foreground service here.
     *
     * The `connectedDevice` foreground-service type requires that
     * [android.hardware.usb.UsbManager.requestPermission] has already been
     * called before [android.app.Service.startForeground] is invoked.
     * Starting the FGS unconditionally at Activity start violates this
     * requirement and throws a [SecurityException] on modern Android.
     *
     * [CameraService] will promote itself to a foreground service (via
     * [CameraService.promoteToForeground]) only after [UvcController] has
     * engaged a USB device and satisfied the runtime precondition.
     */
    private fun bindToService() {
        val intent = Intent(this, CameraService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "bindToService")
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }
}