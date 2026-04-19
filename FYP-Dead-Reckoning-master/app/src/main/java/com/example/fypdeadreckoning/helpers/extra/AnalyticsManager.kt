package com.example.fypdeadreckoning.helpers.extra

import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Analytics (
    // DR Position
    val drPosition: LatLon = LatLon(0.0, 0.0),

    // Sensors
    val totalSteps: Int = 0,
    val magHeading: Double = 0.0,
    val gyroHeading: Double = 0.0,
    val combinedHeading: Double = 0.0,

    // GPS Data
    val gpsScore: Float = 0F,
    val gpsReportedAccuracyScore: Float = 0F,
    val gpsCn0Score: Float = 0F,
    val gpsSanityScore: Float = 0F,
    val gpsStalenessScore: Float = 0F,

    val satelliteCount: Int = 0,
    val averageCn0DbHz: Double = 0.0,

    // BLE Data
    val peerCount: Int = 0,
    val trustedPeerCount: Int = 0,
    val farPeerCount: Int = 0,

    // Uncertainty data
    // TODO could include, already visible

    // Power Mode
    val powerMode: PowerModeManager.PowerMode = PowerModeManager.PowerMode.NORMAL,

    // Timestamp
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Central analytics manager that collects metrics from all subsystems
 * and sends an [Analytics] via [snapshot].
 *
 * Singleton pattern
 */
object AnalyticsManager {

    // Minimum interval between snapshot updates in ms
    private const val MIN_UPDATE_INTERVAL_MS = 500L

    private val _snapshot = MutableStateFlow(Analytics())
    
    val snapshot: StateFlow<Analytics> = _snapshot.asStateFlow()
    
    private var lastUpdateMs: Long = 0L
    
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
    }

    // Reporting method

    fun reportDRPosition(latLon: LatLon) {
        sendUpdate { copy(drPosition = latLon) }
    }

    fun reportHeading(magRad: Double, gyroRad: Double, combinedRad: Double) {
        sendUpdate { copy(magHeading = magRad, gyroHeading = gyroRad, combinedHeading = combinedRad) }
    }

    fun reportStep(totalSteps: Int) {
        sendUpdate { copy(totalSteps = totalSteps) }
    }

    fun reportGPSConfidence(
        accuracy: Float,
        signal: Float,
        sanity: Float,
        staleness: Float,
        combined: Float
    ) {
        sendUpdate {
            copy(
                gpsReportedAccuracyScore = accuracy,
                gpsCn0Score = signal,
                gpsSanityScore = sanity,
                gpsStalenessScore = staleness,
                gpsScore = combined
            )
        }
    }

    fun reportGNSSInfo(satellites: Int, avgCn0: Double) {
        sendUpdate { copy(satelliteCount = satellites, averageCn0DbHz = avgCn0) }
    }

    fun reportPeerMetrics(
        total: Int,
        trusted: Int,
        far: Int,
    ) {
        sendUpdate {
            copy(
                peerCount = total,
                trustedPeerCount = trusted,
                farPeerCount = far,
            )
        }
    }

    fun reportPowerMode(mode: PowerModeManager.PowerMode) {
        sendUpdate { copy(powerMode = mode) }
    }

    // Reset all metrics
    fun reset() {
        _snapshot.value = Analytics()
        lastUpdateMs = 0L
    }

    /**
     * Applies [transform] to the current snapshot and sends the result
     * if [MIN_UPDATE_INTERVAL_MS] has elapsed since the last update, or
     * if analytics is disabled
     */
    private fun sendUpdate(transform: Analytics.() -> Analytics) {
        val updated = _snapshot.value.transform().copy(timestampMs = System.currentTimeMillis())

        // Always store the latest state
        if (!_enabled.value) {
            _snapshot.value = updated
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastUpdateMs >= MIN_UPDATE_INTERVAL_MS) {
            _snapshot.value = updated
            lastUpdateMs = now
        } else {
            _snapshot.value = updated
        }
    }
}