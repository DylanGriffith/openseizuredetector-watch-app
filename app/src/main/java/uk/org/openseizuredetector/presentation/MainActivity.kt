package uk.org.openseizuredetector.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import uk.org.openseizuredetector.service.SensorDataService

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    private var boundService by mutableStateOf<SensorDataService?>(null)
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(tag, "Service connected")
            boundService = (service as SensorDataService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(tag, "Service disconnected")
            boundService = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startAndBindService()
            } else {
                Log.w(tag, "Required permissions not granted: $permissions")
                // Start anyway - accelerometer streaming needs no runtime permission,
                // only heart rate / notifications are degraded.
                startAndBindService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        val permissions = arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startAndBindService()
        } else {
            permissionLauncher.launch(permissions)
        }

        setContent {
            OsdApp(service = boundService)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, SensorDataService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        isBound = true
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundService = null
        }
    }

    private fun startAndBindService() {
        Log.d(tag, "Starting SensorDataService")
        startForegroundService(Intent(this, SensorDataService::class.java))
    }
}
