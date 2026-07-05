package uk.org.openseizuredetector.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * Produces the watch's local alerts:
 *  - WARNING: repeating vibration
 *  - ALARM: repeating vibration plus an audible beep (if the watch has a speaker;
 *    otherwise a harsher vibration pattern)
 *
 * Vibration uses USAGE_ALARM so it still fires when the watch is in
 * do-not-disturb / bedtime mode.
 */
class Alerter(private val context: Context) {

    private enum class Mode { OFF, WARNING, ALARM }

    private val tag = "Alerter"
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val vibrationAttrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
    private val handler = Handler(Looper.getMainLooper())
    private val hasSpeaker =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    private var toneGenerator: ToneGenerator? = null
    private var mode = Mode.OFF

    private val beepRunnable = object : Runnable {
        override fun run() {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_DURATION_MS)
            } catch (e: Exception) {
                Log.e(tag, "Error playing beep tone", e)
            }
            handler.postDelayed(this, BEEP_INTERVAL_MS)
        }
    }

    fun startWarning() {
        if (mode == Mode.WARNING) return
        Log.i(tag, "startWarning()")
        stopBeep()
        vibrate(longArrayOf(0, 800, 400))
        mode = Mode.WARNING
    }

    fun startAlarm() {
        if (mode == Mode.ALARM) return
        Log.i(tag, "startAlarm() - hasSpeaker=$hasSpeaker")
        if (hasSpeaker) {
            vibrate(longArrayOf(0, 800, 400))
            startBeep()
        } else {
            // No speaker to escalate to - use a longer, more insistent vibration instead
            vibrate(longArrayOf(0, 1200, 300))
        }
        mode = Mode.ALARM
    }

    fun stopAll() {
        if (mode == Mode.OFF) return
        Log.i(tag, "stopAll()")
        vibrator.cancel()
        stopBeep()
        mode = Mode.OFF
    }

    fun release() {
        stopAll()
    }

    private fun vibrate(pattern: LongArray) {
        vibrator.cancel()
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), vibrationAttrs)
    }

    private fun startBeep() {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (e: Exception) {
                Log.e(tag, "Cannot create ToneGenerator - beep unavailable", e)
                return
            }
        }
        handler.post(beepRunnable)
    }

    private fun stopBeep() {
        handler.removeCallbacks(beepRunnable)
        toneGenerator?.let {
            try {
                it.stopTone()
                it.release()
            } catch (e: Exception) {
                Log.e(tag, "Error releasing ToneGenerator", e)
            }
        }
        toneGenerator = null
    }

    companion object {
        private const val BEEP_DURATION_MS = 800
        private const val BEEP_INTERVAL_MS = 1500L
    }
}
