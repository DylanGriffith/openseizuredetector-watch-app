package uk.org.openseizuredetector.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import uk.org.openseizuredetector.R
import uk.org.openseizuredetector.presentation.theme.OpenSeizureDetectorWatchTheme
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), SensorEventListener {

    private val tag = "MainActivity"
    private val pathHrData = "/osd/hr_data"

    private lateinit var sensorManager: SensorManager
    private var hrSensor: Sensor? = null
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private val heartRate = mutableIntStateOf(0)


    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startHrSensor()
            } else {
                // Handle permission denial
                Log.w(tag, "Required permissions not granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)


        setContent {
            WearApp("Android", heartRate.intValue)
        }

        if (hrSensor != null) {
            val permissions = arrayOf(
                Manifest.permission.BODY_SENSORS,
                "android.permission.health.READ_HEART_RATE"
            )
            if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                startHrSensor()
            } else {
                permissionLauncher.launch(permissions)
            }
        } else {
            Log.e(tag, "Heart rate sensor not available")
        }
    }

    private fun startHrSensor() {
        hrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        startHrSensor()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val hr = event.values[0].toInt()
            Log.d(tag, "Heart Rate: $hr")
            // Update UI
            heartRate.intValue = hr
            sendHrData(hr)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    private fun sendHrData(hr: Int) {
        try {
            val json = JSONObject()
            json.put("hr", hr)
            val data = json.toString().toByteArray(StandardCharsets.UTF_8)

            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathHrData, data)
                        .addOnSuccessListener { Log.d(tag, "Sent HR data to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending HR data", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending HR data", e)
        }
    }
}

@Composable
fun WearApp(greetingName: String, heartRate: Int = 0) {
    OpenSeizureDetectorWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            HeartRateText(heartRate = "$heartRate")
        }
    }
}

@Composable
fun HeartRateText(heartRate: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = stringResource(R.string.heart_rate_text, heartRate)
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Preview Android", 75)
}