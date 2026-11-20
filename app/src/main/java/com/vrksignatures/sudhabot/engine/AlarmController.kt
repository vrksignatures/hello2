package com.vrksignatures.sudhabot.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * SECTION 9: High-Volume Audible Alarm Controller.
 *
 * Uses AudioManager.STREAM_ALARM at high/max safe volume + continuous vibration pattern
 * so the operator immediately hears any unrecognized failure or 5-retry exhaustion,
 * even if media volume is muted.
 */
class AlarmController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    @Volatile
    var isRinging: Boolean = false
        private set

    fun startHighVolumeAlarm() {
        if (isRinging) return
        isRinging = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val targetVol = (maxVol * 0.90f).toInt().coerceIn(1, maxVol)
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                } catch (_: SecurityException) {
                    // Do Not Disturb policy may restrict volume changes; still play on STREAM_ALARM
                }
            }

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
        } catch (_: Exception) {
            // Fallback: vibration will still alert the user
        }

        startVibration()
    }

    fun stopAlarm() {
        isRinging = false
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val prev = previousAlarmVolume
            if (audioManager != null && prev != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
            }
        } catch (_: Exception) {
        } finally {
            previousAlarmVolume = null
        }

        stopVibration()
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 600, 300, 600, 300)
            val vibrator = getVibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            getVibrator()?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
