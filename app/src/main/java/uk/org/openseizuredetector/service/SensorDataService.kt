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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import uk.org.openseizuredetector.R
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

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

    // Handler for periodic tasks
    private val handler = Handler(Looper.getMainLooper())

    // Accelerometer batching - buffer 25 samples (1 second at 25Hz)
    private val accelBuffer = ArrayList<Double>(25)
    private val batchSize = 25

    // Heart rate - track last sent value
    private var lastHr = 0

    // Binder for activity communication
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SensorDataService = this@SensorDataService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(tag, "Service bound")
        return binder
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
        startPeriodicSettingsSending()
        return START_STICKY
    }

    /**
     * TODO: HACK: I can't work out how to get the mobile app messages through to the watch to request settings so I just sent it every 5s.
     */
    private fun startPeriodicSettingsSending() {
        val sendSettingsRunnable = object : Runnable {
            override fun run() {
                sendSettings()
                handler.postDelayed(this, 5000L)
            }
        }
        // Start first send after 2 seconds to allow initial node connection
        handler.postDelayed(sendSettingsRunnable, 2000L)
        Log.d(tag, "Started periodic settings sending (every 5 seconds)")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "SensorDataService destroyed")
        sensorManager.unregisterListener(this)
        messageClient.removeListener(this)
        handler.removeCallbacksAndMessages(null)
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
                // Update UI listener immediately
                if (hr != lastHr) {
                    lastHr = hr
                    // Send HR to phone when it changes
                    sendHrData(hr)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Calculate magnitude and add to buffer
                val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                accelBuffer.add(magnitude)

                // Send when buffer is full (25 samples)
                if (accelBuffer.size >= batchSize) {
                    sendBatchedAccelData()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(tag, "onMessageReceived: ${messageEvent.path}")
        if (messageEvent.path == pathRequestSettings) {
            Log.d(tag, "Settings requested by phone, sending...")
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
                if (nodes.isEmpty()) {
                    Log.w(tag, "No connected nodes found for settings")
                    return@addOnSuccessListener
                }
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathSettings, data)
                        .addOnSuccessListener { Log.d(tag, "Sent settings to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending settings", e) }
                }
            }.addOnFailureListener { e ->
                Log.e(tag, "Error getting connected nodes", e)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending settings", e)
        }
    }

    private fun sendHrData(hr: Int) {
        try {
            val json = JSONObject()
            json.put("hr", hr)
            val data = json.toString().toByteArray(StandardCharsets.UTF_8)

            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathHrData, data)
                        .addOnSuccessListener { Log.d(tag, "Sent HR data to ${node.displayName}: $hr") }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending HR data", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending HR data", e)
        }
    }

    /**
     * Send all buffered accelerometer data as a batch
     */
    private fun sendBatchedAccelData() {
        if (accelBuffer.isEmpty()) {
            return
        }

        try {
            val json = JSONObject()
            val jsonArray = JSONArray()
            accelBuffer.forEach { jsonArray.put(it) }
            json.put("samples", jsonArray)

            val data = json.toString().toByteArray(StandardCharsets.UTF_8)
            val numSamples = accelBuffer.size

            // Clear buffer after copying to JSON
            accelBuffer.clear()

            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, pathAccelData, data)
                        .addOnSuccessListener {
                            Log.d(tag, "Sent $numSamples accel samples to ${node.displayName}")
                        }
                        .addOnFailureListener { e -> Log.e(tag, "Error sending batched accel data", e) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending batched accel data", e)
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