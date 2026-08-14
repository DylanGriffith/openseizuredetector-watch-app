package uk.org.openseizuredetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uk.org.openseizuredetector.R
import uk.org.openseizuredetector.presentation.MainActivity
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreground service that:
 *  - samples the accelerometer at 25Hz, converts to milli-g (1000 = 1g, the unit the
 *    phone-side OSD and ML algorithms expect) and sends 1-second batches to the phone
 *  - samples heart rate and sends it every few seconds
 *  - sends watch settings (battery, version, sample rate) periodically
 *  - receives the detection state back from the phone and drives local alerting
 *    (vibration on WARNING, vibration + beep on ALARM)
 *  - lets the user pause alarms for an hour or dismiss a false alarm, forwarding
 *    those actions to the phone
 */
class SensorDataService : Service(), SensorEventListener, MessageClient.OnMessageReceivedListener {

    private val tag = "SensorDataService"

    private lateinit var sensorManager: SensorManager
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private lateinit var alerter: Alerter
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val handler = Handler(Looper.getMainLooper())

    // Accelerometer batching - buffer 25 samples (1 second at 25Hz)
    private val accelBuffer = ArrayList<Double>(BATCH_SIZE)

    // Latest heart rate reading (sent on a timer, not on change, so the phone's
    // "HR frozen" fault check sees a regular stream)
    private var latestHr = -1

    // Cache of connected node IDs so we don't look them up for every message
    private var cachedNodeIds: List<String> = emptyList()
    private var nodeCacheTimeMillis = 0L

    // Alerting suppression
    private var pausedUntilMillis = 0L
    private var dismissGraceUntilMillis = 0L
    private var lastAlarmStateMillis = 0L

    // Alarm state currently shown as a notification (UNKNOWN = none showing)
    private var shownAlarmNotificationState = AlarmStates.UNKNOWN

    private val _uiState = MutableStateFlow(WatchUiState())
    val uiState: StateFlow<WatchUiState> = _uiState.asStateFlow()

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

        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("health_monitoring")
        } else {
            this
        }
        sensorManager = attributionContext.getSystemService(SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        alerter = Alerter(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        messageClient.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "SensorDataService started, action=${intent?.action}")
        // Actions triggered from the alarm notification buttons
        when (intent?.action) {
            ACTION_DISMISS_ALARM -> {
                dismissAlarm()
                return START_STICKY
            }
            ACTION_PAUSE_ALARMS -> {
                pauseAlarms()
                return START_STICKY
            }
        }
        startSensors()
        handler.removeCallbacks(periodicTickRunnable)
        handler.removeCallbacks(settingsRunnable)
        handler.post(periodicTickRunnable)
        // First settings send after a short delay to allow initial node connection
        handler.postDelayed(settingsRunnable, 2000L)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "SensorDataService destroyed")
        sensorManager.unregisterListener(this)
        messageClient.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        alerter.release()
    }

    // ---------------------------------------------------------------- sensors

    private fun startSensors() {
        hrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, ACCEL_SAMPLE_PERIOD_US)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                Log.d(tag, "onSensorChanged: HR=$hr")
                if (hr > 0) {
                    latestHr = hr
                    _uiState.update { it.copy(heartRate = hr) }
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Android reports m/s^2; the phone-side algorithms expect milli-g
                // (1000 = 1g), the same units the Garmin watch app sends.
                val magnitudeMg = sqrt((x * x + y * y + z * z).toDouble()) *
                        1000.0 / SensorManager.GRAVITY_EARTH
                // Round to 0.1 milli-g to keep the JSON payload small
                accelBuffer.add((magnitudeMg * 10).roundToInt() / 10.0)
                //Log.d(tag, "onSensorChanged: Accel=$magnitudeMg mg buffer=${accelBuffer.size}")

                if (accelBuffer.size >= BATCH_SIZE) {
                   Log.d(tag, "onSensorChanged: Sending Data...")
                   sendBatchedAccelData()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    // ------------------------------------------------------------- messaging

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(tag, "onMessageReceived: ${messageEvent.path}")
        when (messageEvent.path) {
            MessagePaths.ALARM_STATE -> handleAlarmState(messageEvent.data)
            MessagePaths.REQUEST_SETTINGS -> sendSettings()
        }
    }

    private fun handleAlarmState(data: ByteArray) {
        try {
            val json = JSONObject(String(data, StandardCharsets.UTF_8))
            val state = json.optInt("alarm_state", AlarmStates.UNKNOWN)
            val phrase = json.optString("alarm_phrase", "")
            Log.d(tag, "handleAlarmState: state=$state phrase=$phrase")

            lastAlarmStateMillis = System.currentTimeMillis()
            // A fresh OK/MUTE from the phone means the dismiss has been processed
            if (state == AlarmStates.OK || state == AlarmStates.MUTE) {
                dismissGraceUntilMillis = 0
            }
            _uiState.update {
                it.copy(alarmState = state, alarmPhrase = phrase, phoneConnected = true)
            }
            updateAlerting()
        } catch (e: Exception) {
            Log.e(tag, "Error parsing alarm state", e)
        }
    }

    private fun sendBatchedAccelData() {
        if (accelBuffer.isEmpty()) return
        Log.d(tag, "sendBatchedAccelData: sending ${accelBuffer.size} samples")
        try {
            val jsonArray = JSONArray()
            accelBuffer.forEach { jsonArray.put(it) }
            accelBuffer.clear()
            val json = JSONObject().put("samples", jsonArray)
            Log.d(tag, "sendBatchedAccelData: sending ${json.toString()} ")
            sendMessage(MessagePaths.ACCEL_DATA, json.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending batched accel data", e)
        }
    }

    private fun sendHrData() {
        if (latestHr <= 0) return
        try {
            val json = JSONObject().put("hr", latestHr)
            Log.d(tag, "sendHrData: sending ${json.toString()}")
            sendMessage(MessagePaths.HR_DATA, json.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending HR data", e)
        }
    }

    private fun sendSettings() {
        try {
            val json = JSONObject()
            json.put("version", packageManager.getPackageInfo(packageName, 0).versionName)
            json.put("name", getString(R.string.app_name))
            json.put("sample_freq", SAMPLE_FREQ_HZ)
            val battery = readBatteryPc()
            if (battery >= 0) {
                json.put("battery", battery)
                _uiState.update { it.copy(batteryPc = battery) }
            }
            sendMessage(MessagePaths.SETTINGS, json.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.e(tag, "Error creating or sending settings", e)
        }
    }

    private fun sendUserAction(json: JSONObject) {
        sendMessage(MessagePaths.USER_ACTION, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Send a message to all connected nodes, using a short-lived cache of node IDs
     * so we don't do a node lookup for every one of the ~1/sec data messages.
     */
    private fun sendMessage(path: String, data: ByteArray) {
        val now = System.currentTimeMillis()
        if (cachedNodeIds.isNotEmpty() && now - nodeCacheTimeMillis < NODE_CACHE_MS) {
            cachedNodeIds.forEach { nodeId -> sendToNode(nodeId, path, data) }
            return
        }
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            cachedNodeIds = nodes.map { it.id }
            nodeCacheTimeMillis = System.currentTimeMillis()
            if (nodes.isEmpty()) {
                Log.w(tag, "sendMessage: no connected nodes for $path")
                return@addOnSuccessListener
            }
            nodes.forEach { node -> sendToNode(node.id, path, data) }
        }.addOnFailureListener { e ->
            Log.e(tag, "Error getting connected nodes", e)
            cachedNodeIds = emptyList()
        }
    }

    private fun sendToNode(nodeId: String, path: String, data: ByteArray) {
        messageClient.sendMessage(nodeId, path, data)
            .addOnFailureListener { e ->
                Log.e(tag, "Error sending $path to $nodeId", e)
                cachedNodeIds = emptyList()
            }
    }

    // ------------------------------------------------------------ user actions

    /** Pause all alarms (watch and phone) for an hour; sensor data keeps streaming. */
    fun pauseAlarms() {
        Log.i(tag, "pauseAlarms()")
        pausedUntilMillis = System.currentTimeMillis() + PAUSE_DURATION_MS
        _uiState.update { it.copy(pausedUntilMillis = pausedUntilMillis) }
        updateAlerting()
        sendUserAction(JSONObject().put("action", "mute").put("seconds", PAUSE_DURATION_MS / 1000))
        handler.removeCallbacks(pauseExpiryRunnable)
        handler.postDelayed(pauseExpiryRunnable, PAUSE_DURATION_MS)
    }

    /** Cancel a pause started with [pauseAlarms]. */
    fun cancelPause() {
        Log.i(tag, "cancelPause()")
        pausedUntilMillis = 0
        handler.removeCallbacks(pauseExpiryRunnable)
        _uiState.update { it.copy(pausedUntilMillis = 0) }
        updateAlerting()
        sendUserAction(JSONObject().put("action", "unmute"))
    }

    /**
     * Dismiss a false alarm: silence the watch immediately and tell the phone to
     * accept the alarm (which also mutes its audible alarms for its default period).
     */
    fun dismissAlarm() {
        Log.i(tag, "dismissAlarm()")
        // Keep local alerts quiet until the phone confirms with a fresh OK/MUTE state
        dismissGraceUntilMillis = System.currentTimeMillis() + DISMISS_GRACE_MS
        updateAlerting()
        sendUserAction(JSONObject().put("action", "accept"))
    }

    // --------------------------------------------------------------- alerting

    private fun updateAlerting() {
        val now = System.currentTimeMillis()
        val state = _uiState.value
        val suppressed = now < pausedUntilMillis ||
                now < dismissGraceUntilMillis ||
                !state.phoneConnected
        when {
            suppressed -> {
                alerter.stopAll()
                cancelAlarmNotification()
            }

            state.alarmState in AlarmStates.ALARMING -> {
                alerter.startAlarm()
                showAlarmNotification(state)
            }

            state.alarmState == AlarmStates.WARNING -> {
                alerter.startWarning()
                showAlarmNotification(state)
            }

            else -> {
                alerter.stopAll()
                cancelAlarmNotification()
            }
        }
    }

    /**
     * Post a full-screen, high-priority notification so the alarm surfaces even when
     * the screen is off or another app is in the foreground.  The full-screen intent
     * opens MainActivity (which has the big dismiss button); the notification itself
     * carries Dismiss and Pause actions for one-tap handling.
     */
    private fun showAlarmNotification(state: WatchUiState) {
        if (shownAlarmNotificationState == state.alarmState) return
        shownAlarmNotificationState = state.alarmState

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreenIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val dismissIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SensorDataService::class.java).setAction(ACTION_DISMISS_ALARM),
            flags
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SensorDataService::class.java).setAction(ACTION_PAUSE_ALARMS),
            flags
        )

        val title = if (state.alarmState in AlarmStates.ALARMING) "SEIZURE ALARM" else "Seizure warning"
        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(state.alarmPhrase.ifBlank { AlarmStates.name(state.alarmState) })
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "Dismiss", dismissIntent)
            .addAction(0, "Pause 1h", pauseIntent)
            .build()
        notificationManager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun cancelAlarmNotification() {
        if (shownAlarmNotificationState == AlarmStates.UNKNOWN) return
        shownAlarmNotificationState = AlarmStates.UNKNOWN
        notificationManager.cancel(ALARM_NOTIFICATION_ID)
    }

    // -------------------------------------------------------------- periodics

    /** 5s tick: send HR, check phone connection freshness, refresh alerting. */
    private val periodicTickRunnable = object : Runnable {
        override fun run() {
            sendHrData()
            val connected =
                System.currentTimeMillis() - lastAlarmStateMillis < PHONE_TIMEOUT_MS
            if (connected != _uiState.value.phoneConnected) {
                Log.i(tag, "phoneConnected -> $connected")
                _uiState.update { it.copy(phoneConnected = connected) }
                updateAlerting()
            }
            handler.postDelayed(this, PERIODIC_TICK_MS)
        }
    }

    /**
     * 60s tick: send settings. The phone clears its "have settings" flag every
     * 60s, so it needs them at least that often.
     */
    private val settingsRunnable = object : Runnable {
        override fun run() {
            sendSettings()
            handler.postDelayed(this, SETTINGS_INTERVAL_MS)
        }
    }

    private val pauseExpiryRunnable = Runnable {
        Log.i(tag, "pause expired")
        pausedUntilMillis = 0
        _uiState.update { it.copy(pausedUntilMillis = 0) }
        updateAlerting()
    }

    // ------------------------------------------------------------------ misc

    private fun readBatteryPc(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun createNotificationChannel() {
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

        // Alarm channel: high importance so it pops up, but silent - the Alerter
        // owns vibration and sound, and channel effects would fight with it.
        val alarmChannel = NotificationChannel(
            ALARM_CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(alarmChannel)
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
        private const val ALARM_NOTIFICATION_ID = 2
        private const val ALARM_CHANNEL_ID = "seizure_alarm_channel"

        const val ACTION_DISMISS_ALARM = "uk.org.openseizuredetector.action.DISMISS_ALARM"
        const val ACTION_PAUSE_ALARMS = "uk.org.openseizuredetector.action.PAUSE_ALARMS"

        private const val SAMPLE_FREQ_HZ = 25
        private const val ACCEL_SAMPLE_PERIOD_US = 1_000_000 / SAMPLE_FREQ_HZ
        private const val BATCH_SIZE = SAMPLE_FREQ_HZ // 1 second of samples

        private const val PERIODIC_TICK_MS = 5_000L
        private const val SETTINGS_INTERVAL_MS = 60_000L
        private const val NODE_CACHE_MS = 30_000L
        private const val PHONE_TIMEOUT_MS = 15_000L

        private const val PAUSE_DURATION_MS = 3_600_000L // 1 hour
        private const val DISMISS_GRACE_MS = 60_000L
    }
}
