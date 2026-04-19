package com.example.fypdeadreckoning.helpers.location

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import com.example.fypdeadreckoning.helpers.dataClasses.AugmentResult
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.extra.AnalyticsManager
import com.example.fypdeadreckoning.helpers.extra.PowerModeManager
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// https://source.android.com/docs/core/connect/bluetooth/ble_advertising
// https://medium.com/@martijn.van.welie/making-android-ble-work-part-1-a736dcd53b02
// https://punchthrough.com/android-ble-guide/
class PeerAugmentationManager(private val context: Context) {

    companion object {
        private const val TAG = "PeerAugmentationManager"

        // Arbitrary UUIDs to recognise advertisements coming from app
        // Only bits 4-7 are relevant as per https://www.bluetooth.com/specifications/assigned-numbers/service-discovery
        val SERVICE_UUID: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

        // Estimated range
        const val CUTOFF_RANGE = 20f // metres

        // Max allowed uncertainty to trust a peer
        // TODO pull from uncertainty model rather than hardcoding
        const val PEER_TRUST_UNCERTAINTY_M = 8.0f

        // Peers required
        const val MIN_PEERS = 2

        // Maximum nudge toward the centroid of peers
        // Lower than GPS due to peer inaccuracies
        const val MAX_NUDGE_FACTOR = 0.20f

        // Advertisement rate
        const val DEFAULT_ADVERTISE_RATE_MS = 2_000L

        // How long a peer's entry is kept for
        const val PEER_EXPIRY_MS = DEFAULT_ADVERTISE_RATE_MS * 3

        // Payload layout in bytes
        // [0-3]    latitude   (Float, 4 bytes)
        // [4-7]    longitude  (Float, 4 bytes)
        // [8-9]    uncertainty in centimetres (UShort, 2 bytes) → 0–655.35 m
        // [10-11]  device ID  (Short, 2 bytes)
        // [12]     floor number (Byte, 1 byte, signed)
        // [13]     sequence   (Byte, 1 byte)
        // [14]     isDebug    (Byte, 1 byte)
        const val PAYLOAD_SIZE = 15
    }

    // Info stored in the advertisements
    data class PeerInfo(
        val deviceId: Short,
        val position: LatLon,
        val uncertaintyMetres: Float,
        val floorNumber: Int,
        val sequence: Byte,
        val rssi: Int,
        val lastSeenMs: Long,
        val isDebug: Byte
    )

    data class PeerMetrics(
        val total: Int,
        val trusted: Int,
        val outsideCutoff: Int
    )

    // Bluetooth variables
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    // State variables
    private var isAdvertising = false
    private var isScanning = false
    private var deviceId: Short = 0 // Prevents permissions error
    private var sequenceNumber: Byte = 0
    private var isDebug: Byte = 0
    @Volatile private var currentLat: Float = 0f
    @Volatile private var currentLon: Float = 0f
    @Volatile private var currentUncertaintyM: Float = 5f
    @Volatile private var isPositionReliable: Boolean = true
    @Volatile private var currentFloor: Int = 0
    @Volatile private var currentAdvertiseRateMs: Long = DEFAULT_ADVERTISE_RATE_MS
    private val peerCache = ConcurrentHashMap<Short, PeerInfo>()

    // Handles advertising timeout
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isAdvertising) {
                restartAdvertising()
                refreshHandler.postDelayed(this, currentAdvertiseRateMs)
            }
        }
    }

    // Callback
    var listener: BLEAugmentationListener? = null
    interface BLEAugmentationListener {
        fun onPeerDiscovered(peer: PeerInfo)
        fun onPeerLost(deviceId: Short)
        fun onAugmentationApplied(
            originalPosition: LatLon,
            augmentedPosition: LatLon,
            trustedPeerCount: Int,
            averageDiscrepancyM: Double
        )
    }

    fun start() {
        // Check permissions
        val hasAdvertise = context.checkSelfPermission(
            android.Manifest.permission.BLUETOOTH_ADVERTISE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasScan = context.checkSelfPermission(
            android.Manifest.permission.BLUETOOTH_SCAN
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAdvertise || !hasScan) {
            Log.w(TAG, "BLE permissions not granted - skipping peer augmentation")
            return
        }
        // Check if Bluetooth is enabled
        bluetoothAdapter = bluetoothManager.adapter
        val adapter = bluetoothAdapter
        if (bluetoothAdapter == null || !adapter!!.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled")
            return
        }

        // Check device supports advertisement
        if (!adapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Device does not support BLE advertising")
            return
        }

        deviceId = generateDeviceId()

        // Enable advertiser and scanners
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser failed")
            return
        }
        if (scanner == null){
            Log.e(TAG, "BLE scanner failed")
            return
        }

        startAdvertising()
        startScanning()

        // Begin periodic advertisement refresh so the payload stays current
        refreshHandler.postDelayed(refreshRunnable, currentAdvertiseRateMs)

        Log.d(TAG, "BLE peer augmentation started (deviceId=${deviceId})")
    }

    fun stop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        stopAdvertising()
        stopScanning()
        peerCache.clear()
        Log.d(TAG, "BLE peer augmentation stopped")
    }

    fun updateAdvertisedPosition(
        lat: Float, lon: Float, uncertaintyMetres: Float, isReliable: Boolean, floorNumber: Int = 0
    ) {
        currentLat = lat
        currentLon = lon
        currentUncertaintyM = uncertaintyMetres
        isPositionReliable = isReliable
        currentFloor = floorNumber
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        // Settings for advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = buildAdvertiseData()

        // Start advertising
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
        Log.d(TAG, "BLE advertising started")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
    }

    // Stop and start advertising, incrementing the sequence number
    @SuppressLint("MissingPermission")
    private fun restartAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        sequenceNumber++
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = buildAdvertiseData()
        // TODO double check this logic doesn't break anything
        if (isPositionReliable) {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } else {
            Log.d(TAG, "Stopped advertising. Position is unreliable")
            Toast.makeText(context, "Stopped Bluetooth advertising", Toast.LENGTH_SHORT).show()
        }
    }

    // Build advertising data, encoding it to a set payload format
    private fun buildAdvertiseData(): AdvertiseData {
        val payload = encodePayload(
            currentLat, currentLon, currentUncertaintyM,
            currentFloor, deviceId, sequenceNumber, isDebug
        )
        return AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .addServiceData(SERVICE_PARCEL_UUID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    }

    // Callback when advertising, outputting logs
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertise start SUCCESS")
        }

        // Check some error codes
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "Advertise start FAILED: $reason")
        }
    }

    // https://medium.com/@martijn.van.welie/making-android-ble-work-part-1-a736dcd53b02
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        // Filter for parcel UUID that matches this application
        val filter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_PARCEL_UUID)
            .build()

        // Set scanning settings
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0L)
            .build()

        // Start scanning
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scanning started")
    }

    @SuppressLint("MissingPermission")
    // If scanning, stop scanning
    private fun stopScanning() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    // Callback for Bluetooth scans
    // https://punchthrough.com/android-ble-guide/
    // https://developer.android.com/reference/kotlin/android/bluetooth/le/ScanCallback#public-methods
    private val scanCallback = object : ScanCallback() {
        // Default callback
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processScanResult(result)
        }
        // Callback for a batch of scan results
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { processScanResult(it) }
        }
        // Callback for failed scan
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }

    // Process the results from scanning Bluetooth advertisements
    // https://developer.android.com/reference/android/bluetooth/le/ScanRecord#getServiceData(android.os.ParcelUuid)
    private fun processScanResult(result: ScanResult) {

        val scanRecord = result.scanRecord ?: return
        val serviceData = scanRecord.getServiceData(SERVICE_PARCEL_UUID)
        if (serviceData == null) {
            Log.e(TAG, "Did not receive service data")
            return
        }

        if (serviceData.size < PAYLOAD_SIZE) {
            Log.w(TAG, "Received incorrect payload of (${serviceData.size} bytes)")
            return
        }

        // Don't detect your own ID (fallback)
        val decoded = decodePayload(serviceData) ?: return
        Log.d(TAG, "Decoded deviceId=${decoded.deviceId}, myId=$deviceId")
        if (decoded.deviceId == deviceId) return

        // Store peer
        val peer = PeerInfo(
            deviceId = decoded.deviceId,
            position = LatLon(decoded.lat.toDouble(), decoded.lon.toDouble()),
            uncertaintyMetres = decoded.uncertaintyMetres,
            floorNumber = decoded.floorNumber,
            sequence = decoded.sequence,
            rssi = result.rssi,
            lastSeenMs = System.currentTimeMillis(),
            isDebug = decoded.isDebug
        )

        // If new peer, cache them
        val isNew = !peerCache.containsKey(peer.deviceId)
        peerCache[peer.deviceId] = peer

        // If new peer, call listener
        if (isNew) {
            Log.d(TAG, "New peer: id=${peer.deviceId}, pos=${peer.position}, " +
                    "unc=${"%.1f".format(peer.uncertaintyMetres)}m, rssi=${peer.rssi}")
            listener?.onPeerDiscovered(peer)
        }

        // Report stats on every scan result so the overlay stays current
        val stats = getPeerMetrics()
        AnalyticsManager.reportPeerMetrics(
            total = stats.total,
            trusted = stats.trusted,
            far = stats.outsideCutoff
        )
    }

    // Payload data class
    private data class DecodedPayload(
        val lat: Float, val lon: Float, val uncertaintyMetres: Float,
        val deviceId: Short, val floorNumber: Int, val sequence: Byte,
        val isDebug: Byte
    )

    // Encode the payload to a ByteBuffer
    private fun encodePayload(
        lat: Float, lon: Float, uncertaintyMetres: Float,
        floorNumber: Int, deviceId: Short, sequence: Byte,
        isDebug: Byte
    ): ByteArray {
        val buffer = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(lat)
        buffer.putFloat(lon)
        val uncertaintyCm = (uncertaintyMetres * 100).toInt().coerceIn(0, 65535).toShort()
        buffer.putShort(uncertaintyCm)
        buffer.putShort(deviceId)
        buffer.put(floorNumber.coerceIn(-128, 127).toByte())
        buffer.put(sequence)
        buffer.put(isDebug)
        return buffer.array()
    }

    // Decode payload from ByteBuffer
    private fun decodePayload(data: ByteArray): DecodedPayload? {
        if (data.size < PAYLOAD_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val lat = buffer.getFloat()
            val lon = buffer.getFloat()
            val uncertaintyCm = buffer.getShort().toInt() and 0xFFFF
            val uncertaintyMetres = uncertaintyCm / 100.0f
            val devId = buffer.getShort()
            val floor = buffer.get().toInt()
            val seq = buffer.get()
            val isDebug = buffer.get()
            // Return a custom data class (we cannot return tuples)
            DecodedPayload(lat, lon, uncertaintyMetres, devId, floor, seq, isDebug)
        } catch (e: Exception) {
            Log.e(TAG, "Payload decode error: ${e.message}")
            null
        }
    }

    // Augment user position toward their peers
    fun tryAugmentPosition(currentDRPosition: LatLon, currentFloorNumber: Int): AugmentResult {
        removeExpiredPeers()
        // Filter for peers with valid uncertainty and on the same floor
        val trustedPeers = peerCache.values.filter { peer ->
            peer.uncertaintyMetres <= PEER_TRUST_UNCERTAINTY_M &&
                    peer.floorNumber == currentFloorNumber
        }
        // If we don't have enough peers, ignore
        if (trustedPeers.size < MIN_PEERS) return AugmentResult(currentDRPosition, 0.0)

        // Calculate distances to each peer and store in a map
        val peerDistances = trustedPeers.map { peer ->
            peer to ExtraFunctions.haversine(
                currentDRPosition.lat, currentDRPosition.lon,
                peer.position.lat, peer.position.lon
            )
        }
        // Filter for peers outside the cut off range.
        // This means there is a significant difference between their position and ours
        val farPeers = peerDistances.filter { it.second > CUTOFF_RANGE }
        // Check if our peers are far from us
        if (farPeers.size < MIN_PEERS) {
            // TODO reduce uncertainty when your peers confirm your position is accurate -
            // TODO (down to the highest of the available uncertainty values
            return AugmentResult(currentDRPosition, 0.0)
        } else {

            // Calculate centroid of far away peers
            // Weight each peer by 1/uncertainty so more certain peers pull harder
            val totalWeight = farPeers.sumOf { 1.0 / it.first.uncertaintyMetres }
            val centroidLat =
                farPeers.sumOf { it.first.position.lat / it.first.uncertaintyMetres } / totalWeight
            val centroidLon =
                farPeers.sumOf { it.first.position.lon / it.first.uncertaintyMetres } / totalWeight

            // Find the difference average difference between peers
            val avgDifference = farPeers.map { it.second }.average()
            // Calculate the amount to nudge toward the centroid and clamp
            val extraAmount = ((avgDifference - CUTOFF_RANGE) / CUTOFF_RANGE).coerceIn(0.0, 1.0)
            // If we're much more uncertain than peers, nudge harder
            // If we're equally or more certain, nudge less
            val avgPeerUncertainty =
                farPeers.map { it.first.uncertaintyMetres.toDouble() }.average()
            val certaintyRatio = (currentUncertaintyM / avgPeerUncertainty).coerceIn(0.5, 2.0)
            val nudge = SettingsManager.bleNudgeFactor.value * extraAmount * (certaintyRatio / 2.0)

            // Find new augmented position using spherical trig
            val distanceToCentroid = ExtraFunctions.haversine(
                currentDRPosition.lat, currentDRPosition.lon,
                centroidLat, centroidLon
            )

            val nudgedDistance = distanceToCentroid * nudge

            val bearingToCentroid = ExtraFunctions.calculateBearing(
                currentDRPosition.lat, currentDRPosition.lon,
                centroidLat, centroidLon
            )

            val augmented = ExtraFunctions.bearingToLocation(
                currentDRPosition.lat,
                currentDRPosition.lon,
                nudgedDistance,
                Math.toRadians(bearingToCentroid).toFloat()
            )
            // Call listener
            listener?.onAugmentationApplied(
                currentDRPosition, augmented, farPeers.size, avgDifference
            )
            return AugmentResult(augmented, nudgedDistance)
        }
    }

    fun getPeerCount(): Int = peerCache.size
    fun getPeers(): List<PeerInfo> = peerCache.values.toList()

    @VisibleForTesting
    internal fun addPeerForTest(peer: PeerInfo) {
        peerCache[peer.deviceId] = peer
    }

    @VisibleForTesting
    internal fun setUncertaintyForTest(uncertaintyM: Float) {
        currentUncertaintyM = uncertaintyM
    }

    fun applyPowerMode(mode: PowerModeManager.PowerMode) {
        currentAdvertiseRateMs = PowerModeManager.Ble.advertiseRateMs(mode)

        // Restart the refresh loop at the new rate
        if (isAdvertising) {
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, currentAdvertiseRateMs)
        }

        // Restart scanning with new scan mode
        if (isScanning) {
            stopScanning()
            startScanningWithMode(mode)
        }

        Log.d(TAG, "BLE power mode applied: advertise=${currentAdvertiseRateMs}ms")
    }

    // Mirrors startScanning(), but is away of the mode
    @SuppressLint("MissingPermission")
    private fun startScanningWithMode(mode: PowerModeManager.PowerMode) {
        val filter = ScanFilter.Builder()
            .setServiceUuid(SERVICE_PARCEL_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(PowerModeManager.Ble.scanMode(mode))
            .setReportDelay(0L)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    // Removes peers who have not been seen recently
    private fun removeExpiredPeers() {
        val now = System.currentTimeMillis()
        peerCache.entries.filter { now - it.value.lastSeenMs > PEER_EXPIRY_MS }
            .forEach { (id, _) ->
                peerCache.remove(id)
                listener?.onPeerLost(id)
            }
    }

    // Generate device ID using your MAC address, or time
    @SuppressLint("MissingPermission")
    private fun generateDeviceId(): Short {
        return try {
            val address = bluetoothAdapter?.address
            // 02:00:00:00:00:00 is a fallback address that BLE uses
            if (address != "02:00:00:00:00:00") {
                (address.hashCode() and 0xFFFF).toShort()
            } else {
                (System.nanoTime() and 0xFFFF).toShort()
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted, using time-based device ID")
            (System.nanoTime() and 0xFFFF).toShort()
        }
    }

    fun getPeerMetrics(): PeerMetrics {
        removeExpiredPeers()
        val all = peerCache.values.toList()
        val trusted = all.filter { it.uncertaintyMetres <= PEER_TRUST_UNCERTAINTY_M }

        val currentPos = LatLon(currentLat.toDouble(), currentLon.toDouble())
        val distances = all.map { peer ->
            ExtraFunctions.haversine(
                currentPos.lat, currentPos.lon,
                peer.position.lat, peer.position.lon
            )
        }
        val outsideCutoff = distances.count { it > CUTOFF_RANGE }

        return PeerMetrics(
            total = all.size,
            trusted = trusted.size,
            outsideCutoff = outsideCutoff
        )
    }
}