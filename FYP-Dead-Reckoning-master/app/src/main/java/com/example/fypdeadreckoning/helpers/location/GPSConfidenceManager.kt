package com.example.fypdeadreckoning.helpers.location
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.example.fypdeadreckoning.helpers.dataClasses.AugmentResult
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.extra.SettingsManager

/**
 * Computes a weighted GPS confidence score from four metrics:
 *
 *   1. Accuracy   (weight 0.25) – Location.getAccuracy();
 *   2. Signal     (weight 0.10) – Average Carrier-to-Noise Density (Cn0DbHz)
 *   3. Sanity     (weight 0.35) – Penalises GPS jumps that exceed the combined
 *                                  allowed uncertainty of GPS accuracy + DR uncertainty
 *   4. Staleness  (weight 0.30) – Penalises old fixes
 *
 * If the combined score exceeds CONFIDENCE_THRESHOLD, tryAugmentPosition() nudges the
 * dead-reckoning position toward the GPS estimate.  The nudge scales linearly with
 * confidence.
 */
class GPSConfidenceManager {

    companion object {
        private const val TAG = "GPSConfidenceManager"

        // Accuracy
        // GPS typically 3–5m outdoors,
        const val MAX_ACCURACY_M = 20f // metres

        // Signal (Cn0 dB-Hz)
        // Below this Cn0 a satellite is too noisy.
        const val CN0_FLOOR_DB_HZ = 20.0

        // Average Cn0 at or above this equals 1.0
        const val CN0_CEILING_DB_HZ = 45.0

        // Minimum number of satellites before we trust it.
        const val MIN_SATELLITES = 4

        // Sanity
        // Jumps within this multiple of combinedUncertainty get a score of 1.0
        const val SANITY_OK_MULTIPLIER = 1.5

        // Jumps beyond this multiple get a score of 0.0
        // Between OK and FAIL, the score decays linearly.
        const val SANITY_FAIL_MULTIPLIER = 3.5

        // If no step detected within this window the user is considered stationary
        const val STATIONARY_WINDOW_MS = 3_000L

        // Staleness
        const val MAX_UPDATE_AGE_MS = 5_000L

        // Updates fresher than this a get full staleness score
        const val FRESH_UPDATE_AGE_MS = 2_000L

        // Weights (sum to 1.0)
        const val WEIGHT_ACCURACY  = 0.25f
        const val WEIGHT_SIGNAL    = 0.10f
        const val WEIGHT_SANITY    = 0.35f
        const val WEIGHT_STALENESS = 0.30f

        // Augmentation
        // Minimum combined confidence before any DR nudge is applied
        const val CONFIDENCE_THRESHOLD = 0.5f

        // Maximum nudge toward the GPS update when confidence = 1.0.
        // Scales linearly
        // No longer relevant, stored in SettingsManager
        const val MAX_NUDGE_FACTOR = 0.30f
    }

    // Internal state
    private var previousGPSLocation: Location? = null
    private var lastStepTimestampMs: Long = 0L

    // Component scores (exposed for debug UI)
    var accuracyScore: Float = 0f; private set
    var signalScore: Float = 0f; private set
    var sanityScore: Float = 1f; private set
    var stalenessScore: Float = 1f; private set

    // GNSS measurement state
    var satelliteCount: Int = 0; private set
    var averageCn0DbHz: Double = 0.0; private set

    // Call from LocationCallback whenever a new GPS update arrives.
    fun onLocationUpdate(
        location: Location,
        drPosition: LatLon,
        drUncertaintyMetres: Float
    ): Float {
        accuracyScore  = calcAccuracyScore(location)
        sanityScore    = calcSanityScore(location, drUncertaintyMetres)
        stalenessScore = calcStalenessScore(location)
        // signalScore is updated independently via onGnssMeasurements()

        val combined = combinedScore()
        Log.d(
            TAG, "Confidence: %.3f  (acc=%.2f sig=%.2f san=%.2f stale=%.2f) sats=%d drUnc=%.1fm"
                .format(combined, accuracyScore, signalScore, sanityScore, stalenessScore,
                    satelliteCount, drUncertaintyMetres)
        )

        previousGPSLocation = location
        return combined
    }

    // Call from GnssMeasurementsEvent.Callback to keep signal score up-to-date.
    // Register the callback alongside your location updates.
    fun onGnssMeasurements(event: GnssMeasurementsEvent) {
        val validMeasurements = event.measurements.filter { m ->
            // Only use satellites that are actively tracking (STATE_TOW_DECODED or better)
            m.cn0DbHz >= CN0_FLOOR_DB_HZ &&
                    (m.state and GnssMeasurement.STATE_TOW_DECODED) != 0
        }

        satelliteCount = validMeasurements.size

        if (satelliteCount < MIN_SATELLITES) {
            signalScore = 0f
            averageCn0DbHz = 0.0
            Log.d(TAG, "Too few satellites ($satelliteCount < $MIN_SATELLITES) – signal score = 0")
            return
        }

        averageCn0DbHz = validMeasurements.map { it.cn0DbHz }.average()
        signalScore = calcSignalScore(averageCn0DbHz)
    }

    fun onStepDetected() {
        lastStepTimestampMs = System.currentTimeMillis()
    }

    // Checks current confidence, decides whether to nudge drPosition toward gpsLocation
    fun tryAugmentPosition(
        drPosition: LatLon,
        gpsLocation: Location,
        confidence: Float
    ): AugmentResult {
        if (confidence <= CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Confidence ${"%.3f".format(confidence)} <= threshold")
            return AugmentResult(drPosition, 0.0)
        }

        // 0 nudge at threshold, nudge factor at 1.0
        val t = (confidence - CONFIDENCE_THRESHOLD) / (1f - CONFIDENCE_THRESHOLD)
        val nudge = SettingsManager.gpsNudgeFactor.value * t

        // Find the distance and bearing from DR to GPS
        val distanceToGPS = ExtraFunctions.haversine(
            drPosition.lat, drPosition.lon,
            gpsLocation.latitude, gpsLocation.longitude
        )

        // Scale the distance by the nudge factor
        val nudgedDistance = distanceToGPS * nudge

        // Move along the correct bearing for that scaled distance
        val bearingToGPS = ExtraFunctions.calculateBearing(
            drPosition.lat, drPosition.lon,
            gpsLocation.latitude, gpsLocation.longitude
        )

        val augmented = ExtraFunctions.bearingToLocation(
            drPosition.lat,
            drPosition.lon,
            nudgedDistance,
            Math.toRadians(bearingToGPS).toFloat()  // bearingToLocation expects radians
        )
        Log.d(TAG, "Augment: nudge=$nudge DR($drPosition) → $augmented",
        )
        return AugmentResult(augmented, nudgedDistance)
    }

    // Current combined confidence score without triggering a recalculation.
    fun combinedScore(): Float {
        return (WEIGHT_ACCURACY  * accuracyScore +
                WEIGHT_SIGNAL    * signalScore +
                WEIGHT_SANITY    * sanityScore +
                WEIGHT_STALENESS * stalenessScore).coerceIn(0f, 1f)
    }

    // Returns 0 if accuracy > MAX_ACCURACY_M, otherwise a linear scale
    private fun calcAccuracyScore(location: Location): Float {
        if (!location.hasAccuracy()) return 0f
        val acc = location.accuracy
        if (acc > MAX_ACCURACY_M) return 0f
        return (1f - acc / MAX_ACCURACY_M).coerceIn(0f, 1f)
    }

    // Linear interpolation between CN0_FLOOR_DB_HZ and CN0_CEILING_DB_HZ
    private fun calcSignalScore(cn0Average: Double): Float {
        if (cn0Average <= CN0_FLOOR_DB_HZ)   return 0f
        if (cn0Average >= CN0_CEILING_DB_HZ) return 1f
        return ((cn0Average - CN0_FLOOR_DB_HZ) /
                (CN0_CEILING_DB_HZ - CN0_FLOOR_DB_HZ)).toFloat().coerceIn(0f, 1f)
    }

    // Checks whether the distance between the previous and new GPS fix is reasonable
    private fun calcSanityScore(newLocation: Location, drUncertaintyMetres: Float): Float {
        val previous = previousGPSLocation ?: return 1f // No update

        // Find distance travelled
        val jumpMetres = ExtraFunctions.haversine(
            previous.latitude, previous.longitude,
            newLocation.latitude, newLocation.longitude
        )

        // TODO decide is [isStationary] necessary to find a poor jump
        // Check if the user is stationary (as per DR)
        val isStationary = lastStepTimestampMs == 0L ||
                (System.currentTimeMillis() - lastStepTimestampMs) > STATIONARY_WINDOW_MS

        // Combined uncertainty range from GPS reported accuracy + DR uncertainty
        val gpsAccuracy = if (newLocation.hasAccuracy()) {
            newLocation.accuracy.toDouble()
        } else {
            MAX_ACCURACY_M.toDouble()  // fallback
        }

        val combinedUncertainty = gpsAccuracy + drUncertaintyMetres.toDouble()
        val okJump   = SANITY_OK_MULTIPLIER   * combinedUncertainty
        val failJump = SANITY_FAIL_MULTIPLIER * combinedUncertainty

        return when {
            jumpMetres <= okJump   -> 1f
            jumpMetres >= failJump -> 0f
            else -> (1.0 - (jumpMetres - okJump) / (failJump - okJump)).toFloat()
        }
    }


    // Penalises GPS updates based on age
    private fun calcStalenessScore(location: Location): Float {
        val updateAgeMs = (SystemClock.elapsedRealtimeNanos() -
                location.elapsedRealtimeNanos) / 1_000_000L

        // Avoid negative age
        if (updateAgeMs <= FRESH_UPDATE_AGE_MS) return 1f
        if (updateAgeMs >= MAX_UPDATE_AGE_MS)   return 0f

        // Linear decay between FRESH and MAX
        val score = 1f - ((updateAgeMs - FRESH_UPDATE_AGE_MS).toFloat() /
                (MAX_UPDATE_AGE_MS - FRESH_UPDATE_AGE_MS).toFloat())

        Log.d(TAG, "Staleness: fix age=${updateAgeMs}ms -> score=${"%.2f".format(score)}")
        return score.coerceIn(0f, 1f)
    }
}