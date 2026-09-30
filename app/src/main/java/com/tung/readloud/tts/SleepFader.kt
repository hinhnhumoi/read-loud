package com.tung.readloud.tts

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.tung.readloud.data.EventLog
import kotlin.math.sqrt

/**
 * The gentle end of a sleep timer: lowers the voice over its last [FADE_SECONDS], and while it is fading
 * listens for a shake that asks for more time. It only watches; the service decides what a shake means.
 */
class SleepFader(
    context: Context,
    private val handler: Handler,
    /** Seconds of listening left before the timer stops playback, or null when there is nothing to fade toward. */
    private val secondsLeft: () -> Float?,
    private val setVolume: (Float) -> Unit,
    private val onShake: () -> Unit,
) {
    var fadeEnabled = true
    var shakeEnabled = true

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false
    /** What the voice is set to now; a speaker made mid-fade starts here. */
    var volume = 1f
        private set
    private var listening = false
    private var lastPeakAt = 0L
    private var lastShakeAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (g < SHAKE_G) return
            val now = SystemClock.elapsedRealtime()
            // Two jolts close together is a shake; one is the phone being put down or bumped.
            if (now - lastPeakAt in PEAK_MIN_GAP_MS..PEAK_MAX_GAP_MS && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                lastShakeAt = now
                lastPeakAt = 0L
                handler.post { if (listening) onShake() }
            } else if (now - lastPeakAt > PEAK_MIN_GAP_MS) {
                lastPeakAt = now
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Starts watching a timer that was just set or changed. */
    fun start() {
        if (!running) {
            running = true
            handler.post(tick)
        }
    }

    /** The timer is off: full volume again and no more listening. */
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        apply(1f)
        listen(false)
    }

    private fun update() {
        val left = secondsLeft()
        val fading = fadeEnabled && left != null && left <= FADE_SECONDS
        apply(if (fading) (left!! / FADE_SECONDS).coerceIn(MIN_VOLUME, 1f) else 1f)
        listen(fading && shakeEnabled)
    }

    private fun apply(value: Float) {
        if (value == volume) return
        volume = value
        setVolume(value)
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        val sensor = accelerometer ?: return
        if (on) {
            EventLog.log("Sleep timer: fading out, listening for a shake")
            lastPeakAt = 0L
            sensors?.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensors?.unregisterListener(shakeListener)
        }
    }

    companion object {
        const val FADE_SECONDS = 30f
        const val SHAKE_EXTEND_MINUTES = 10
        private const val MIN_VOLUME = 0.08f
        private const val TICK_MS = 500L
        private const val SHAKE_G = 1.6f
        private const val PEAK_MIN_GAP_MS = 120L
        private const val PEAK_MAX_GAP_MS = 900L
        private const val SHAKE_COOLDOWN_MS = 2_000L
    }
}
