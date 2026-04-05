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
 *  - onStop   → stop preview pipeline + unbind (service closes camera session
 *               via onUnbind, ensuring a clean state on next bind)
 *  - SurfaceHolder.Callback → hard-recover on surfaceCreated (the definitive
 *    recovery point after any standby/background cycle); attachSurface on
 *    surfaceChanged (dimension update only); stopPreviewPipeline on
 *    surfaceDestroyed.
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
            // resume where surfaceCreated was not re-fired), kick off a hard
            // recovery so the pipeline opens from a known-clean state rather
            // than assuming any prior state is still healthy.
            val holder = previewSurface.holder
            if (holder.surface != null && holder.surface.isValid) {
                Log.d(TAG, "onServiceConnected: surface already valid — triggering hard recovery")
                cameraService?.hardRecoverCameraSession(holder.surface)
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
            Log.d(
                TAG,
                "surfaceCreated — serviceBound=$serviceBound " +
                    "streaming=${cameraService?.isStreaming()}"
            )
            // Hard recovery: always tear down and reopen from clean state when
            // a new surface is created.  This is the primary recovery point
            // after standby, lock/unlock, or any background cycle.  It prevents
            // the pipeline from reopening on top of stale native/USB state —
            // which was the root cause of corrupted frames (bands/lines) and
            // black-screen resume.
            cameraService?.hardRecoverCameraSession(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(
                TAG,
                "surfaceChanged ${width}x${height} — serviceBound=$serviceBound " +
                    "streaming=${cameraService?.isStreaming()}"
            )
            // Surface dimensions changed; refresh the native window handle
            // without a full pipeline teardown.
            cameraService?.attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceDestroyed — serviceBound=$serviceBound")
            // Stop the MJPEG stream and detach the surface.  The camera
            // session is fully closed by CameraService.onUnbind() when the
            // Activity unbinds, so we only need a lightweight stream stop here.
            cameraService?.stopPreviewPipeline()
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

    override fun onResume() {
        super.onResume()
        // Hard recovery point: after returning from standby, background, or any
        // pause/resume cycle that did not trigger onStop (and therefore did not
        // rebind the service), ensure the pipeline is torn down and reopened
        // from a clean state if the service is already bound and the surface is
        // valid.  This covers the case where surfaceCreated is not re-fired
        // (surface survived the pause) and onServiceConnected is not re-fired
        // (service was already bound).
        val holder = previewSurface.holder
        val surfaceValid = holder.surface.isValid
        Log.d(
            TAG,
            "onResume — serviceBound=$serviceBound surfaceValid=$surfaceValid " +
                "streaming=${cameraService?.isStreaming()}"
        )
        if (serviceBound && surfaceValid) {
            Log.d(TAG, "onResume: service bound and surface valid — triggering hard recovery")
            cameraService?.hardRecoverCameraSession(holder.surface)
        }
    }

    override fun onStop() {
        if (serviceBound) {
            // Unbind from the service.  CameraService.onUnbind() performs the
            // full hard teardown (closeCameraSession: stop stream + close native
            // UVC + close USB connection) so the pipeline is in a clean state
            // when the Activity rebinds.
            unbindService(serviceConnection)
            serviceBound = false
            cameraService = null
            Log.d(TAG, "onStop: unbound from service — full hard teardown via onUnbind")
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