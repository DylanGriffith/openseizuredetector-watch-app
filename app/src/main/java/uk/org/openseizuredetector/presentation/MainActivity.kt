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
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import uk.org.openseizuredetector.R
import uk.org.openseizuredetector.presentation.theme.OpenSeizureDetectorWatchTheme
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), SensorEventListener, MessageClient.OnMessageReceivedListener {

    private val tag = "MainActivity"
    private val pathHrData = "/osd/hr_data"
    private val pathAccelData = "/osd/accel_data"
    private val pathSettings = "/osd/settings"
    private val pathRequestData = "/osd/request_data"
    private val pathRequestSettings = "/osd/send_settings"

    private lateinit var sensorManager: SensorManager
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private val heartRate = mutableIntStateOf(0)


    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startSensors()
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
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)


        setContent {
            WearApp(heartRate.intValue)
        }

        val permissions = arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
        )
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startSensors()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startSensors() {
        hrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, 40000) // 25Hz
        }
    }

    override fun onResume() {
        super.onResume()
        startSensors()
        messageClient.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        messageClient.removeListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                Log.d(tag, "Heart Rate: $hr")
                heartRate.intValue = hr
                sendHrData(hr)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                sendAccelData(x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(tag, "onMessageReceived: ${messageEvent.path}")
        // TODO: I'm never getting the settings request here
        if (messageEvent.path == pathRequestSettings) {
            sendSettings()
        }
    }

    private fun sendSettings() {
        try {
            val json = JSONObject()
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            json.put("version", versionName)
            json.put("name", getString(R.string.app_name))
            json.put("sample_freq", 25)

            val data = json.toString().toByteArray(StandardCharsets.UTF_8)

            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathSettings, data)
                        .addOnSuccessListener { Log.d(tag, "Sent settings to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending settings", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending settings", e)
        }
    }

    private fun sendHrData(hr: Int) {
        // TODO: The setting request is never coming through from the app so manually sending here as a temporary workaround
        sendSettings()
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

    private fun sendAccelData(x: Float, y: Float, z: Float) {
        try {
            val json = JSONObject()
            json.put("x", x)
            json.put("y", y)
            json.put("z", z)
            val data = json.toString().toByteArray(StandardCharsets.UTF_8)

            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathAccelData, data)
                        .addOnSuccessListener { Log.d(tag, "Sent accel data to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending accel data", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending accel data", e)
        }
    }
}

@Composable
fun WearApp(heartRate: Int = 0) {
    OpenSeizureDetectorWatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            HeartRateText(heartRate = heartRate.toString())
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
    WearApp(75)
}
