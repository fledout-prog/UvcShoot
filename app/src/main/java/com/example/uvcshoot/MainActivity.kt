package com.example.uvcshoot

import android.os.Bundle
import android.view.SurfaceView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var previewSurface: SurfaceView
    private lateinit var captureButton: Button
    private lateinit var uvcController: UvcController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewSurface = findViewById(R.id.previewSurface)
        captureButton = findViewById(R.id.captureButton)

        uvcController = UvcController(this, previewSurface)

        captureButton.setOnClickListener {
            uvcController.requestCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        uvcController.onResume()
    }

    override fun onPause() {
        uvcController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uvcController.release()
        super.onDestroy()
    }
}