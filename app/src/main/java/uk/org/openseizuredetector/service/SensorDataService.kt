package uk.org.openseizuredetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import uk.org.openseizuredetector.R
import java.nio.charset.StandardCharsets

class SensorDataService : Service(), SensorEventListener, MessageClient.OnMessageReceivedListener {

    private val tag = "SensorDataService"
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

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "SensorDataService created")
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        messageClient.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "SensorDataService started")
        startSensors()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "SensorDataService destroyed")
        sensorManager.unregisterListener(this)
        messageClient.removeListener(this)
    }

    private fun startSensors() {
        hrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, 40000) // 25Hz
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                Log.d(tag, "Heart Rate: $hr")
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
        // Send settings first to ensure connection
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sensor_data_service_channel"
    }
}