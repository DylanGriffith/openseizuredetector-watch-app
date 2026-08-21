# OpenSeizureDetector Wear App — Developer Getting Started Guide

## 1. Overview

This repository contains the **Wear OS watch app** for OpenSeizureDetector.  
The watch app:

1. Collects sensor data (accelerometer + heart rate)
2. Sends data to the companion phone app
3. Receives alarm state updates from the phone
4. Triggers local watch alerts (vibration/beep) and shows alarm UI

The core architecture is: **Foreground Service + MessageClient + Compose UI**.

---

## 2. Tech Stack and Build Targets

- **Kotlin**: 2.2.10
- **Android Gradle Plugin**: 9.2.1
- **SDK**: compile/min/target = 36 (Wear OS 5.1+)
- **UI**: Jetpack Compose + Wear Compose Material
- **Wear comms**: `com.google.android.gms:play-services-wearable:19.0.0`
- **Minification**: disabled (`isMinifyEnabled = false`)
  Main module: `app/`

---

## 3. Folder Structure

---


## 4. How the App Works (High-Level Flow)

1. `BootReceiver` starts `SensorDataService` after boot.
2. `SensorDataService` runs as a **foreground health service**.
3. Service subscribes to accelerometer + heart-rate sensors.
4. Service batches sensor readings and sends them to phone via Wearable `MessageClient`.
5. Service receives phone messages (alarm state / settings request).
6. Service updates `WatchUiState` (`StateFlow`) and controls local alerts via `Alerter`.
7. `MainActivity` binds to service and renders `OsdApp` Compose UI.

---

## 5. Key Classes and Responsibilities
| File | Responsibility | Key methods / behavior |
|---|---|---|
| `SensorDataService.kt` | Core runtime service: sensor capture, batching, watch↔phone messaging, alarm handling | `onCreate()`, `onSensorChanged()`, `sendBatchedAccelData()`, `sendHrData()`, `handleAlarmState()`, `pauseAlarms()`, `dismissAlarm()` |
| `AlarmStates.kt` | Shared constants + message paths + UI state model | `AlarmStates` constants, `AlarmStates.name()`, `MessagePaths`, `WatchUiState` |
| `Alerter.kt` | Local warning/alarm haptics + beep | `startWarning()`, `startAlarm()`, `stopAll()` |
| `MainActivity.kt` | Runtime permissions, service binding, Compose host | permission launcher, `onStart()/onStop()` bind lifecycle |
| `OsdApp.kt` | Watch UI rendering from service state | `OsdApp()`, `OsdScreen()`, `StatusBanner()` |
| `BootReceiver.kt` | Autostart service at boot | receives `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` |

---

## 6. Companion App Communication API

Transport is **Wearable Message API** (`MessageClient`), using path + UTF-8 JSON payload.

## 6.1 Watch → Phone paths

| Path | Frequency / trigger | Payload shape |
|---|---|---|
| `/osd/accel_data` | ~1/sec (batch of 1 second) | `{ "samples": [Int, Int, ...], "seq": Long, "sent_ms": Long }` |
| `/osd/hr_data` | ~1/5 sec | `{ "hr": Int, "seq": Long, "sent_ms": Long }` |
| `/osd/settings` | ~1/60 sec | `{ "version": String, "name": String, "sample_freq": Int, "battery": Int, "seq": Long, "sent_ms": Long }` |
| `/osd/user_action` | on user action | `{ "action": "mute" \| "unmute" \| "accept" }` |

## 6.2 Phone → Watch paths

| Path | Purpose | Payload shape |
|---|---|---|
| `/osd/alarm_state` | Update watch alarm/UI state | `{ "alarm_state": Int, "alarm_phrase": String }` |
| `/osd/send_settings` | Request immediate settings resend | (typically empty / no important body) |

---

## 7. Alarm States Contract (must match phone app)

Defined in `AlarmStates.kt`:

| Name | Value |
|---|---|
| `UNKNOWN` | -1 |
| `OK` | 0 |
| `WARNING` | 1 |
| `ALARM` | 2 |
| `FALL` | 3 |
| `FAULT` | 4 |
| `MANUAL` | 5 |
| `MUTE` | 6 |
| `NETFAULT` | 7 |

Local alert behavior:
- `WARNING` → vibration only
- `ALARM` / `FALL` / `MANUAL` → vibration + beep
- others → no alarm tone pattern

---

## 8. Sensor Processing Details

- **Accelerometer**
    - Sample rate: **25 Hz**
    - Internal storage: raw x/y/z
    - Sent data: **magnitude** in milli-g
    - Conversion:
        - `magnitude = sqrt(x² + y² + z²)`
        - normalized by `SensorManager.GRAVITY_EARTH`
        - scaled to milli-g (`*1000`)
- **Heart rate**
    - Sensor framework provides BPM value
    - Sent every ~5 seconds

Batching and efficiency:
- Accelerometer values sent in 1-second batches (~25 samples)
- Node ID lookup is cached (TTL ~10s) to reduce overhead

## 9. UI and State Model

`WatchUiState` fields:

- `alarmState: Int`
- `alarmPhrase: String`
- `heartRate: Int`
- `batteryPc: Int`
- `phoneConnected: Boolean`
- `pausedUntilMillis: Long`

UI is Compose-driven and reactive:
- service exposes `StateFlow<WatchUiState>`
- UI uses `collectAsState()`
- pause countdown updates every second
- status banner color/text maps from alarm state

---

## 10. Permissions and Manifest Notes

Manifest includes (not exhaustive grouping):
- `BODY_SENSORS`
- `android.permission.health.READ_HEART_RATE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_HEALTH`
- - `WAKE_LOCK`
- `POST_NOTIFICATIONS`
- `VIBRATE`
- `RECEIVE_BOOT_COMPLETED`
- plus full-screen intent usage permission

Runtime checks in `MainActivity` request:
- `BODY_SENSORS`
- `READ_HEART_RATE`
- `POST_NOTIFICATIONS`

---

## 11. Build, Run, and Deploy

## 11.1 Android Studio (recommended)

1. Open repo in Android Studio
2. Let Gradle sync complete
3. Pair/connect Wear OS device or emulator
4. Select `app` run configuration
5. Run/Debug

## 11.2 CLI
From repo root:
bash ./gradlew :app:assembleDebug ./gradlew :app:installDebug

(Install requires connected Wear target.)

---

## 12. Testing

Automated test coverage appears minimal at present; use a combination of Gradle checks and manual validation.

Useful Gradle tasks:

```bash
./gradlew :app:lint
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest

Manual validation checklist:
1.
Grant permissions on first launch  
2.
Confirm foreground service notification is present  
3.
Verify HR and battery values render in UI  
4.
Verify pause button mutes local alerts for ~1 hour  
5.
Trigger alarm from phone and confirm watch banner + vibration/beep behavior  
6.
Confirm dismiss action sends /osd/user_action with accept  
7.
Reboot watch and verify auto-start via BootReceiver  
 13. Debugging Tips
Logcat filtering examples:
adb logcat | grep -i -E "SensorDataService|Alerter|Alarm|Wearable|MessageClient"
Focus areas when debugging:
•
No phone comms: node discovery/cache, message path mismatch
No phone comms: node discovery/cache, message path mismatch
•
No HR data: runtime permission + sensor availability
•
No alerting: alarm state mapping + Alerter start/stop transitions
•
UI stale: service binding lifecycle (MainActivity) and StateFlow updates
 14. Operational Constants / Quirks
•
Alarm pause duration: 1 hour
•
Dismiss grace period: 5 seconds
•
Warning vibration pattern repeats (800ms on / 400ms off)
•
Alarm beep uses alarm stream tone generator
•
Service uses health foreground service type

15. Companion App API Contract Notes
To keep compatibility stable:
1.
Do not change MessagePaths strings without coordinated phone app update
2.
Keep alarm integer values synchronized with phone app constants
3.
Preserve JSON field names/types (alarm_state, samples, etc.)
4.
Treat watch app payload formats as wire contracts
 16. Recommended First Code Reading Order
1.
service/SensorDataService.kt  
2.
service/AlarmStates.kt  
presentation/MainActivity.kt  
5.
presentation/OsdApp.kt  
6.
service/BootReceiver.kt  
7.
AndroidManifest.xml + app/build.gradle.kts
This sequence gives quickest understanding of runtime flow and API surface.



