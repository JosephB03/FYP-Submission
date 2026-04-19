package com.example.fypdeadreckoning.helpers.location

import android.location.Location
import android.os.SystemClock
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Unit tests for [GPSConfidenceManager].
 * Tests each of the four weighted scores (accuracy, signal, sanity, staleness)
 */
// https://developer.android.com/training/testing/local-tests
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GPSConfidenceManagerTest {

    private lateinit var manager: GPSConfidenceManager

    // Reusable DR position (WGB area)
    private val drPosition = LatLon(51.893056, -8.500556)

    // Default DR uncertainty for tests that don't use sanity
    private val defaultDRUncertainty = 5.0f

    @Before
    fun setUp() {
        manager = GPSConfidenceManager()
        // Set the system clock to a set value so staleness tests are predictable
        ShadowSystemClock.advanceBy(Duration.ofSeconds(100))
    }

    private fun buildLocation(
        lat: Double = drPosition.lat,
        lon: Double = drPosition.lon,
        accuracyM: Float = 5f,
        ageMs: Long = 500  // How old the update is relative to "now"
    ): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            accuracy = accuracyM
            // Set the fix timestamp so that (SystemClock.now - this) = ageMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - (ageMs * 1_000_000L)
        }
    }

    // ACCURACY SCORE

    @Test
    fun `accuracy score - perfect accuracy gives score near 1`() {
        val location = buildLocation(accuracyM = 1f)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        // 1 - (1/20) = 0.95
        assertEquals(0.95f, manager.accuracyScore, 0.01f)
    }

    @Test
    fun `accuracy score - at MAX_ACCURACY gives score of 0`() {
        val location = buildLocation(accuracyM = GPSConfidenceManager.MAX_ACCURACY_M)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0f, manager.accuracyScore, 0.01f)
    }

    @Test
    fun `accuracy score - beyond MAX_ACCURACY gives 0`() {
        val location = buildLocation(accuracyM = 25f)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0f, manager.accuracyScore, 0.01f)
    }

    @Test
    fun `accuracy score - midpoint accuracy gives 0_5`() {
        // 10m accuracy with 20m max → 1 - (10/20) = 0.5
        val location = buildLocation(accuracyM = 10f)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0.5f, manager.accuracyScore, 0.01f)
    }

    @Test
    fun `accuracy score - no accuracy reported gives 0`() {
        val location = Location("gps").apply {
            latitude = 51.893056
            longitude = -8.500556
            // Don't call setAccuracy — hasAccuracy() will return false
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 500_000_000L
        }
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0f, manager.accuracyScore, 0.01f)
    }

    // STALENESS SCORE

    @Test
    fun `staleness score - fresh fix gives 1`() {
        val location = buildLocation(ageMs = 500)  // 0.5 seconds old
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(1f, manager.stalenessScore, 0.01f)
    }

    @Test
    fun `staleness score - fix at FRESH_UPDATE_AGE boundary gives 1`() {
        val location = buildLocation(ageMs = GPSConfidenceManager.FRESH_UPDATE_AGE_MS)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(1f, manager.stalenessScore, 0.01f)
    }

    @Test
    fun `staleness score - fix at MAX_UPDATE_AGE gives 0`() {
        val location = buildLocation(ageMs = GPSConfidenceManager.MAX_UPDATE_AGE_MS)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0f, manager.stalenessScore, 0.01f)
    }

    @Test
    fun `staleness score - fix beyond MAX_UPDATE_AGE gives 0`() {
        val location = buildLocation(ageMs = 10_000)  // 10 seconds old
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0f, manager.stalenessScore, 0.01f)
    }

    @Test
    fun `staleness score - midpoint age gives 0_5`() {
        // Midpoint between FRESH (2000ms) and MAX (5000ms) = 3500ms
        val midpointMs = (GPSConfidenceManager.FRESH_UPDATE_AGE_MS +
                GPSConfidenceManager.MAX_UPDATE_AGE_MS) / 2
        val location = buildLocation(ageMs = midpointMs)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(0.5f, manager.stalenessScore, 0.1f)
    }

    // SANITY SCORE

    @Test
    fun `sanity score - first fix always gives 1 (nothing to compare)`() {
        val location = buildLocation()
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)
        assertEquals(1f, manager.sanityScore, 0.01f)
    }

    @Test
    fun `sanity score - small jump within allowed range gives 1`() {
        // First fix to establish a previous location
        val first = buildLocation(lat = 51.893056, lon = -8.500556)
        manager.onLocationUpdate(first, drPosition, defaultDRUncertainty)

        // Second fix very close to the first (~2m north)
        val second = buildLocation(lat = 51.893076, lon = -8.500556)
        manager.onLocationUpdate(second, drPosition, defaultDRUncertainty)

        assertEquals(1f, manager.sanityScore, 0.01f)
    }

    @Test
    fun `sanity score - large jump with low uncertainty is penalised`() {
        val first = buildLocation(lat = 51.893056, lon = -8.500556, accuracyM = 3f)
        manager.onLocationUpdate(first, drPosition, 2f)  // Low DR uncertainty

        // ~50m jump north — way beyond allowed range for low uncertainty
        // Combined = 3 + 2 = 5m, allowed = 1.5 × 5 = 7.5m
        val second = buildLocation(lat = 51.89350, lon = -8.500556, accuracyM = 3f)
        manager.onLocationUpdate(second, drPosition, 2f)

        assertTrue(
            "Sanity score should be penalised for a large jump with low uncertainty",
            manager.sanityScore < 0.5f
        )
    }

    @Test
    fun `sanity score - same jump tolerated with high uncertainty`() {
        val first = buildLocation(lat = 51.893056, lon = -8.500556, accuracyM = 10f)
        manager.onLocationUpdate(first, drPosition, 12f)  // High DR uncertainty

        // Same ~50m jump, but now combined = 10 + 12 = 22m, allowed = 1.5 × 22 = 33m
        val second = buildLocation(lat = 51.89350, lon = -8.500556, accuracyM = 10f)
        manager.onLocationUpdate(second, drPosition, 12f)

        assertTrue(
            "Sanity score should be lenient when both GPS and DR are uncertain",
            manager.sanityScore > 0.5f
        )
    }

    @Test
    fun `sanity score - uncertainty affects allowed jump distance`() {
        // Establish a previous fix
        val first = buildLocation(lat = 51.893056, lon = -8.500556, accuracyM = 5f)
        manager.onLocationUpdate(first, drPosition, 5f)

        // ~20m jump. Combined = 5 + 5 = 10m, allowed = 1.5 × 10 = 15m → penalised
        val jumpLocation = buildLocation(lat = 51.893236, lon = -8.500556, accuracyM = 5f)

        // With LOW DR uncertainty (5m): likely penalised
        manager.onLocationUpdate(jumpLocation, drPosition, 5f)
        val scoreLowUncertainty = manager.sanityScore

        // Reset and try again with HIGH DR uncertainty
        val manager2 = GPSConfidenceManager()
        manager2.onLocationUpdate(first, drPosition, 5f)
        // Same jump, but DR uncertainty is now 14m
        // Combined = 5 + 14 = 19m, allowed = 1.5 × 19 = 28.5m → likely fine
        manager2.onLocationUpdate(jumpLocation, drPosition, 14f)
        val scoreHighUncertainty = manager2.sanityScore

        assertTrue(
            "Higher DR uncertainty should produce a more lenient sanity score",
            scoreHighUncertainty >= scoreLowUncertainty
        )
    }

    // COMBINED SCORE

    @Test
    fun `combined score - good fix gives high confidence`() {
        val location = buildLocation(accuracyM = 3f, ageMs = 200)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)

        // Accuracy: 1 - 3/20 = 0.85
        // Sanity: 1.0 (first fix)
        // Staleness: 1.0 (200ms)
        // Signal: 0.0 (no GNSS measurements received yet)
        // Combined = 0.20×0.85 + 0.25×0 + 0.30×1.0 + 0.25×1.0 = 0.72
        val combined = manager.combinedScore()
        assertTrue("Good fix should produce confidence > 0.5", combined > 0.5f)
    }

    @Test
    fun `combined score - poor fix gives low confidence`() {
        val location = buildLocation(accuracyM = 25f, ageMs = 6000)
        manager.onLocationUpdate(location, drPosition, defaultDRUncertainty)

        // Accuracy: 0 (25m > 20m max)
        // Staleness: 0 (6s > 5s max)
        // Signal: 0 (no measurements)
        // Sanity: 1 (first fix)
        // Combined = 0.20*0 + 0.25*0 + 0.30*1 + 0.25*0 = 0.30
        val combined = manager.combinedScore()
        assertTrue("Poor fix should produce confidence ≤ 0.5", combined <= 0.5f)
    }

    @Test
    fun `combined score - weights sum to 1`() {
        val totalWeight = GPSConfidenceManager.WEIGHT_ACCURACY +
                GPSConfidenceManager.WEIGHT_SIGNAL +
                GPSConfidenceManager.WEIGHT_SANITY +
                GPSConfidenceManager.WEIGHT_STALENESS
        assertEquals(1.0f, totalWeight, 0.001f)
    }

    // AUGMENTATION

    @Test
    fun `tryAugmentPosition - below threshold returns original position`() {
        val gpsLocation = buildLocation(lat = 51.894, lon = -8.501)
        val result = manager.tryAugmentPosition(drPosition, gpsLocation, 0.3f)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(drPosition.lon, result.position.lon, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    @Test
    fun `tryAugmentPosition - above threshold moves toward GPS`() {
        val gpsLat = 51.894  // ~120m north of drPosition
        val gpsLocation = buildLocation(lat = gpsLat, lon = -8.500556)
        val result = manager.tryAugmentPosition(drPosition, gpsLocation, 0.8f)

        // Result should be between DR and GPS (closer to DR since nudge < 1)
        assertTrue(
            "Augmented lat should be between DR and GPS",
            result.position.lat > drPosition.lat && result.position.lat < gpsLat
        )
        assertTrue("Nudged metres should be positive", result.nudgedMetres > 0.0)
    }

    @Test
    fun `tryAugmentPosition - higher confidence gives bigger nudge`() {
        val gpsLocation = buildLocation(lat = 51.894, lon = -8.500556)

        val resultLow = manager.tryAugmentPosition(drPosition, gpsLocation, 0.6f)
        val resultHigh = manager.tryAugmentPosition(drPosition, gpsLocation, 0.95f)

        // Higher confidence should move further toward GPS
        assertTrue(
            "Higher confidence should produce a larger nudge",
            resultHigh.position.lat > resultLow.position.lat
        )
        assertTrue(
            "Higher confidence should produce larger nudged metres",
            resultHigh.nudgedMetres > resultLow.nudgedMetres
        )
    }
}