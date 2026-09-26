package uk.org.openseizuredetector.service

/**
 * Alarm state values - must match the phone app's
 * uk.org.openseizuredetector.data.AlarmState.
 */
object AlarmStates {
    const val UNKNOWN = -1
    const val OK = 0
    const val WARNING = 1
    const val ALARM = 2
    const val FALL = 3
    const val FAULT = 4
    const val MANUAL = 5
    const val MUTE = 6
    const val NETFAULT = 7

    /** States for which the local alarm (vibration + beep) should sound. */
    val ALARMING = setOf(ALARM, FALL, MANUAL)

    fun name(state: Int): String = when (state) {
        OK -> "OK"
        WARNING -> "WARNING"
        ALARM -> "ALARM"
        FALL -> "FALL"
        FAULT -> "FAULT"
        MANUAL -> "MANUAL ALARM"
        MUTE -> "MUTE"
        NETFAULT -> "NET FAULT"
        else -> "---"
    }
}

/** Wearable MessageClient paths shared with the phone app's SdDataSourceAw. */
object MessagePaths {
    // watch -> phone
    const val ACCEL_DATA = "/osd/accel_data"
    const val HR_DATA = "/osd/hr_data"
    const val SETTINGS = "/osd/settings"
    const val USER_ACTION = "/osd/user_action"

    // phone -> watch
    const val ALARM_STATE = "/osd/alarm_state"
    const val REQUEST_SETTINGS = "/osd/send_settings"
}

/** Snapshot of everything the UI needs, published by SensorDataService. */
data class WatchUiState(
    val alarmState: Int = AlarmStates.UNKNOWN,
    val alarmPhrase: String = "",
    val heartRate: Int = -1,
    val batteryPc: Int = -1,
    val phoneConnected: Boolean = false,
    val pausedUntilMillis: Long = 0,
    val audibleAlarmEnabled: Boolean = true,
    val vibrateOnlyAlerts: Boolean = false,
    val audibleWarningEnabled: Boolean = true,
    val lastAlarmLatencyMs: Long = -1,
    val lastAlarmStateAgeMs: Long = -1,
    val lastAlarmStateSeq: Long = -1,
)
