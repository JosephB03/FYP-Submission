package com.example.fypdeadreckoning.helpers.extra

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central source of truth for the app's power mode.
 * Each subsystem (sensors, GPS, BLE, uncertainty) pulls from [mode] when
 * needed.
 */
object PowerModeManager {

    private const val TAG = "PowerModeManager"

    // Power mode definition
    enum class PowerMode {
        // Full accuracy
        NORMAL,

        // Reduced accuracy -> slower sensors, wider GPS, throttled BLE.
        LOW_POWER
    }

    // States
    private val _mode = MutableStateFlow(PowerMode.NORMAL)
    val mode: StateFlow<PowerMode> = _mode.asStateFlow()

    fun setMode(newMode: PowerMode) {
        if (_mode.value != newMode) {
            Log.d(TAG, "Power mode changed: ${_mode.value} → $newMode")
            _mode.value = newMode
        }
    }

    // Called when switching state
    fun toggle() {
        setMode(
            if (_mode.value == PowerMode.NORMAL) PowerMode.LOW_POWER
            else PowerMode.NORMAL
        )
    }

    // Getter
    fun isLowPower(): Boolean = _mode.value == PowerMode.LOW_POWER

    // Objects for each sub-system
    object Sensors {
        // SensorManager delay
        fun sensorDelay(mode: PowerMode): Int =
            when (mode) {
                PowerMode.NORMAL    -> android.hardware.SensorManager.SENSOR_DELAY_FASTEST
                PowerMode.LOW_POWER -> android.hardware.SensorManager.SENSOR_DELAY_GAME
            }
    }

    object Gps {
        // Location request interval in milliseconds
        fun intervalMs(mode: PowerMode): Long =
            when (mode) {
                PowerMode.NORMAL    -> 500L
                PowerMode.LOW_POWER -> 5_000L
            }

        // Minimum update interval in milliseconds
        fun fastestIntervalMs(mode: PowerMode): Long =
            when (mode) {
                PowerMode.NORMAL    -> 200L
                PowerMode.LOW_POWER -> 2_000L
            }

        //FusedLocationProvider priority constant.
        fun priority(mode: PowerMode): Int =
            when (mode) {
                PowerMode.NORMAL    -> com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
                PowerMode.LOW_POWER -> com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
    }

    object Ble {
        // Advertisement refresh interval in milliseconds.
        fun advertiseRateMs(mode: PowerMode): Long =
            when (mode) {
                PowerMode.NORMAL    -> 2_000L
                PowerMode.LOW_POWER -> 20_000L
            }

        // BLE scan mode constant.
        fun scanMode(mode: PowerMode): Int =
            when (mode) {
                PowerMode.NORMAL    -> android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
                PowerMode.LOW_POWER -> android.bluetooth.le.ScanSettings.SCAN_MODE_OPPORTUNISTIC
            }
    }

    object Geofence {
        fun dwellIntervalMs(mode: PowerMode): Int =
            when (mode) {
                PowerMode.NORMAL -> 10_000
                PowerMode.LOW_POWER -> 30_000
            }
    }

    object Uncertainty {
        // In low-power mode uncertainty grows faster
        // TODO validate via testing
        fun errorPerStep(mode: PowerMode): Double =
            when (mode) {
                PowerMode.NORMAL    -> 0.20
                PowerMode.LOW_POWER -> 0.50
            }
    }
}