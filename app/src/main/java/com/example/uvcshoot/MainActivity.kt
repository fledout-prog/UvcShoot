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
 *  - onCreate → bind to [CameraService] once; binding is kept alive across
 *               HOME/return cycles so the camera session is never needlessly
 *               destroyed for transient background transitions.
 *  - onDestroy → unbind from [CameraService] (triggers full session teardown
 *                only when the Activity is truly being destroyed).
 *  - SurfaceHolder.Callback:
 *      surfaceCreated   → evaluate preview health via [CameraService.evaluatePreviewRecoveryNeeded]
 *                         and dispatch to the cheapest applicable recovery path
 *                         (ATTACH_ONLY / STREAM_RESTART_ONLY / SOFT_PREVIEW_RECOVERY / HARD_RECOVER);
 *                         schedule post-create health watchdog.
 *      surfaceChanged   → refresh native surface handle (dimension update).
 *      surfaceDestroyed → stop stream + clear surface ref; camera stays open.
 *  - onResume → if service bound and surface valid: evaluate preview health and
 *               dispatch recovery; schedule post-resume health watchdog.
 *  - onKeyDown (volume-down / KEYCODE_CAMERA) → trigger capture.
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
            cameraService = (binder as CameraService.LocalBinder).getService()
            serviceBound = true
            val holder = previewSurface.holder
            val surfaceValid = holder.surface.isValid
            val decision = cameraService?.evaluatePreviewRecoveryNeeded()
            Log.d(
                TAG,
                "UVC_STATE: SERVICE_BIND — surfaceValid=$surfaceValid " +
                    "streaming=${cameraService?.isStreaming()} decision=$decision"
            )

            if (surfaceValid) {
                val surface = holder.surface
                when (decision) {
                    PreviewRecoveryDecision.NO_OP -> {
                        Log.d(TAG, "UVC_HEALTH: SERVICE_BIND → NO_OP")
                    }
                    PreviewRecoveryDecision.ATTACH_ONLY -> {
                        Log.d(TAG, "UVC_HEALTH: SERVICE_BIND → ATTACH_ONLY")
                        cameraService?.attachSurface(surface)
                    }
                    PreviewRecoveryDecision.STREAM_RESTART_ONLY -> {
                        Log.d(TAG, "UVC_HEALTH: SERVICE_BIND → STREAM_RESTART_ONLY")
                        cameraService?.attachSurface(surface)
                    }
                    PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY -> {
                        Log.d(TAG, "UVC_HEALTH: SERVICE_BIND → SOFT_PREVIEW_RECOVERY")
                        cameraService?.softPreviewRecovery(surface)
                    }
                    PreviewRecoveryDecision.HARD_RECOVER, null -> {
                        Log.d(TAG, "UVC_HEALTH: SERVICE_BIND → HARD_RECOVER")
                        cameraService?.hardRecoverCameraSession(surface)
                    }
                }
                cameraService?.schedulePreviewWatchdog()
            } else {
                // Surface not yet available; surfaceCreated will handle attachment when it fires.
                Log.d(TAG, "UVC_STATE: SERVICE_BIND — surface not yet valid, waiting for surfaceCreated")
            }
            updateStatus("Camera service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "UVC_STATE: SERVICE_DISCONNECT — unexpected service disconnect")
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
            val decision = cameraService?.evaluatePreviewRecoveryNeeded()
            Log.d(
                TAG,
                "UVC_STATE: SURFACE_CREATE — serviceBound=$serviceBound " +
                    "streaming=${cameraService?.isStreaming()} " +
                    "surfaceHash=${System.identityHashCode(holder.surface)} " +
                    "decision=$decision"
            )
            val surface = holder.surface
            when (decision) {
                PreviewRecoveryDecision.NO_OP -> {
                    Log.d(TAG, "UVC_HEALTH: SURFACE_CREATE → NO_OP")
                }
                PreviewRecoveryDecision.ATTACH_ONLY -> {
                    Log.d(TAG, "UVC_HEALTH: SURFACE_CREATE → ATTACH_ONLY")
                    cameraService?.attachSurface(surface)
                }
                PreviewRecoveryDecision.STREAM_RESTART_ONLY -> {
                    Log.d(TAG, "UVC_HEALTH: SURFACE_CREATE → STREAM_RESTART_ONLY")
                    cameraService?.attachSurface(surface)
                }
                PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY -> {
                    Log.d(TAG, "UVC_HEALTH: SURFACE_CREATE → SOFT_PREVIEW_RECOVERY")
                    cameraService?.softPreviewRecovery(surface)
                }
                PreviewRecoveryDecision.HARD_RECOVER, null -> {
                    Log.d(TAG, "UVC_HEALTH: SURFACE_CREATE → HARD_RECOVER")
                    cameraService?.hardRecoverCameraSession(surface)
                }
            }
            cameraService?.schedulePreviewWatchdog()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(
                TAG,
                "UVC_STATE: SURFACE_CHANGE — ${width}x${height} serviceBound=$serviceBound " +
                    "streaming=${cameraService?.isStreaming()} " +
                    "surfaceHash=${System.identityHashCode(holder.surface)}"
            )
            // Surface dimensions changed; refresh the native window handle
            // without a full pipeline teardown.
            cameraService?.attachSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(
                TAG,
                "UVC_STATE: SURFACE_DESTROY — serviceBound=$serviceBound streaming=${cameraService?.isStreaming()} " +
                    "surfaceHash=${System.identityHashCode(holder.surface)}"
            )
            // Surface is truly gone: stop the stream and clear the surface reference in the
            // controller. The camera session (USB/UVC handle) remains open so it does not
            // need to be fully re-probed when the surface comes back (e.g. on return from HOME).
            cameraService?.onSurfaceDestroyed()
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

        Log.d(TAG, "UVC_STATE: ACT_CREATE — binding to CameraService (binding kept alive across HOME/return)")
        updateStatus("Starting camera service…")
        // Bind here (not in onStart) so the service binding — and its camera session —
        // survive HOME/temporary background transitions without a destructive teardown.
        bindToService()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "UVC_STATE: ACT_START — serviceBound=$serviceBound")
    }

    override fun onResume() {
        super.onResume()
        val holder = previewSurface.holder
        val surfaceValid = holder.surface.isValid
        val decision = if (serviceBound && surfaceValid) cameraService?.evaluatePreviewRecoveryNeeded() else null
        Log.d(
            TAG,
            "UVC_STATE: ACT_RESUME — serviceBound=$serviceBound surfaceValid=$surfaceValid " +
                "streaming=${cameraService?.isStreaming()} decision=$decision"
        )
        if (serviceBound && surfaceValid) {
            val surface = holder.surface
            when (decision) {
                PreviewRecoveryDecision.NO_OP -> {
                    Log.d(TAG, "UVC_HEALTH: ACT_RESUME → NO_OP")
                }
                PreviewRecoveryDecision.ATTACH_ONLY -> {
                    Log.d(TAG, "UVC_HEALTH: ACT_RESUME → ATTACH_ONLY")
                    cameraService?.attachSurface(surface)
                }
                PreviewRecoveryDecision.STREAM_RESTART_ONLY -> {
                    Log.d(TAG, "UVC_HEALTH: ACT_RESUME → STREAM_RESTART_ONLY")
                    cameraService?.attachSurface(surface)
                }
                PreviewRecoveryDecision.SOFT_PREVIEW_RECOVERY -> {
                    Log.d(TAG, "UVC_HEALTH: ACT_RESUME → SOFT_PREVIEW_RECOVERY")
                    cameraService?.softPreviewRecovery(surface)
                }
                PreviewRecoveryDecision.HARD_RECOVER, null -> {
                    Log.d(TAG, "UVC_HEALTH: ACT_RESUME → HARD_RECOVER")
                    cameraService?.hardRecoverCameraSession(surface)
                }
            }
            cameraService?.schedulePreviewWatchdog()
        }
    }

    override fun onStop() {
        Log.d(TAG, "UVC_STATE: ACT_STOP — keeping service binding alive (camera session preserved for resume)")
        // Intentionally NOT unbinding here. The service binding and camera session must survive
        // HOME / temporary background transitions. Unbinding is deferred to onDestroy so that
        // full teardown only happens when the Activity is truly being destroyed.
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "UVC_STATE: ACT_DESTROY — unbinding service (full teardown on true Activity destruction)")
        previewSurface.holder.removeCallback(surfaceCallback)
        if (serviceBound) {
            // onUnbind will perform the full camera session teardown.
            unbindService(serviceConnection)
            serviceBound = false
            cameraService = null
        }
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