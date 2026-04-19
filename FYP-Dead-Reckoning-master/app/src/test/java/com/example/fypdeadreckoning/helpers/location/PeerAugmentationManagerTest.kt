package com.example.fypdeadreckoning.helpers.location

import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [PeerAugmentationManager.tryAugmentPosition].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeerAugmentationManagerTest {

    private lateinit var manager: PeerAugmentationManager

    // Reusable DR position (WGB area)
    private val drPosition = LatLon(51.893056, -8.500556)

    // Floor used in most tests
    private val floor = 0

    @Before
    fun setUp() {
        manager = PeerAugmentationManager(RuntimeEnvironment.getApplication())
        SettingsManager.setBleNudgeFactor(0.20f)
    }

    private fun buildPeer(
        deviceId: Short = 1,
        lat: Double = drPosition.lat,
        lon: Double = drPosition.lon,
        uncertaintyM: Float = 3f,
        floorNumber: Int = floor,
        rssi: Int = -60
    ): PeerAugmentationManager.PeerInfo {
        return PeerAugmentationManager.PeerInfo(
            deviceId = deviceId,
            position = LatLon(lat, lon),
            uncertaintyMetres = uncertaintyM,
            floorNumber = floorNumber,
            sequence = 0,
            rssi = rssi,
            lastSeenMs = System.currentTimeMillis(),
            isDebug = 0
        )
    }

    @Test
    fun `tryAugmentPosition - no peers returns original position`() {
        val result = manager.tryAugmentPosition(drPosition, floor)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(drPosition.lon, result.position.lon, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    @Test
    fun `tryAugmentPosition - one peer is not enough`() {
        // Only 1 peer, MIN_PEERS = 2
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = 51.89350))
        val result = manager.tryAugmentPosition(drPosition, floor)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    @Test
    fun `tryAugmentPosition - peers on wrong floor are excluded`() {
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = 51.89350, floorNumber = 1))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = 51.89350, floorNumber = 1))
        val result = manager.tryAugmentPosition(drPosition, 0)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    @Test
    fun `tryAugmentPosition - high uncertainty peers are excluded`() {
        // Uncertainty > PEER_TRUST_UNCERTAINTY_M (8.0m)
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = 51.89350, uncertaintyM = 10f))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = 51.89350, uncertaintyM = 12f))
        val result = manager.tryAugmentPosition(drPosition, floor)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    // Not enough far peers

    @Test
    fun `tryAugmentPosition - nearby peers do not trigger augmentation`() {
        // ~10m north, within CUTOFF_RANGE (20m)
        val nearLat = 51.893146  // ~10m north
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = nearLat))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = nearLat))
        val result = manager.tryAugmentPosition(drPosition, floor)
        assertEquals(drPosition.lat, result.position.lat, 0.000001)
        assertEquals(0.0, result.nudgedMetres, 0.001)
    }

    // Augmentation calculation

    @Test
    fun `tryAugmentPosition - far peers trigger augmentation toward centroid`() {
        // ~50m north
        val farLat = 51.89350
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = farLat))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = farLat))
        manager.setUncertaintyForTest(5f)

        val result = manager.tryAugmentPosition(drPosition, floor)
        assertTrue("Augmented lat should move toward peers", result.position.lat > drPosition.lat)
        assertTrue("Nudge should be positive", result.nudgedMetres > 0.0)
    }

    @Test
    fun `tryAugmentPosition - more certain peers pull harder`() {
        // Two peers at different uncertainties, offset in different directions
        // Peer 1: low uncertainty (2m), east
        // Peer 2: high uncertainty (6m), west
        val peerEastLon = -8.499856  // ~50m east
        val peerWestLon = -8.501256  // ~50m west
        manager.addPeerForTest(buildPeer(deviceId = 1, lon = peerEastLon, uncertaintyM = 2f))
        manager.addPeerForTest(buildPeer(deviceId = 2, lon = peerWestLon, uncertaintyM = 6f))
        manager.setUncertaintyForTest(5f)

        val result = manager.tryAugmentPosition(drPosition, floor)
        // Weighted centroid should be closer to the more certain (east) peer
        // So augmented position should shift east (higher lon, i.e., less negative)
        assertTrue(
            "Augmented position should shift toward more certain peer (east)",
            result.position.lon > drPosition.lon
        )
    }

    @Test
    fun `tryAugmentPosition - higher own uncertainty increases nudge`() {
        val farLat = 51.89350
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = farLat, uncertaintyM = 3f))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = farLat, uncertaintyM = 3f))

        // Low own uncertainty
        manager.setUncertaintyForTest(3f)
        val resultLow = manager.tryAugmentPosition(drPosition, floor)

        // Reset peers (tryAugmentPosition doesn't remove non-expired peers)
        // High own uncertainty
        manager.setUncertaintyForTest(8f)
        val resultHigh = manager.tryAugmentPosition(drPosition, floor)

        assertTrue(
            "Higher own uncertainty should produce larger nudge",
            resultHigh.nudgedMetres > resultLow.nudgedMetres
        )
    }

    @Test
    fun `tryAugmentPosition - nudge factor settings geuinely affects augmentation`() {
        val farLat = 51.89350
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = farLat))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = farLat))
        manager.setUncertaintyForTest(5f)

        // Zero nudge factor -> no augmentation
        SettingsManager.setBleNudgeFactor(0f)
        val resultZero = manager.tryAugmentPosition(drPosition, floor)

        // Higher nudge factor -> larger augmentation
        SettingsManager.setBleNudgeFactor(0.4f)
        val resultHigh = manager.tryAugmentPosition(drPosition, floor)

        assertEquals(0.0, resultZero.nudgedMetres, 0.001)
        assertTrue("Higher nudge factor should produce augmentation", resultHigh.nudgedMetres > 0.0)
    }

    @Test
    fun `tryAugmentPosition - augmented position stays between DR and centroid`() {
        val farLat = 51.89350
        manager.addPeerForTest(buildPeer(deviceId = 1, lat = farLat))
        manager.addPeerForTest(buildPeer(deviceId = 2, lat = farLat))
        manager.setUncertaintyForTest(5f)

        val result = manager.tryAugmentPosition(drPosition, floor)

        // Augmented lat should be between DR and peer centroid
        assertTrue(
            "Augmented lat should be >= DR lat",
            result.position.lat >= drPosition.lat
        )
        assertTrue(
            "Augmented lat should be <= centroid lat",
            result.position.lat <= farLat
        )
    }
}
