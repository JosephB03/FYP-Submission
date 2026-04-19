package com.example.fypdeadreckoning.helpers.location

import android.annotation.SuppressLint
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
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Acts as Bluetooth beacons.
 * A device will have the Map open and be using Peer Augmentation
 * This must be active with at least 2 devices, who base their location off of the "real" user
 */
class PeerAugmentationDebug(private val context: Context) {

    companion object {
        private const val TAG = "PeerConfidenceTest"

        // Arbitrary UUIDs to recognise advertisements coming from app
        // Only bits 4-7 are relevant as per https://www.bluetooth.com/specifications/assigned-numbers/service-discovery
        val SERVICE_UUID: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

        // Estimated range
        const val CUTOFF_RANGE = 20f // metres

        // Advertisement rate
        const val ADVERTISE_RATE_MS = 2_000L

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
    private var isDebug: Byte = 1
    private var sequenceNumber: Byte = 0
    @Volatile private var currentLat: Float = 0f
    @Volatile private var currentLon: Float = 0f
    @Volatile private var currentUncertaintyM: Float = 0f // Always have 0 uncertainty
    @Volatile private var currentFloor: Int = 0
    @Volatile private var hasReceivedPosition: Boolean = false // Don't advertise until we have a real position
    private val peerCache = ConcurrentHashMap<Short, PeerInfo>()
    // Random bearing so multiple debug devices don't all stack at the same point
    private val bearingDegrees: Float = (Math.random() * 360).toFloat()

    // Handles advertising timeout
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isAdvertising) {
                restartAdvertising()
                refreshHandler.postDelayed(this, ADVERTISE_RATE_MS)
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

        // Start scanning immediately, but defer advertising until we receive a real user's position
        startScanning()

        // Begin periodic advertisement refresh so the payload stays current
        refreshHandler.postDelayed(refreshRunnable, ADVERTISE_RATE_MS)

        Log.d(TAG, "BLE peer augmentation started (deviceId=${deviceId}, bearing=${bearingDegrees.toInt()}°)")
    }

    fun stop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        stopAdvertising()
        stopScanning()
        peerCache.clear()
        hasReceivedPosition = false
        Log.d(TAG, "BLE peer augmentation stopped")
    }

    fun updateAdvertisedPosition(
        lat: Float, lon: Float, uncertaintyMetres: Float, floorNumber: Int = 0
    ) {
        currentLat = lat
        currentLon = lon
        currentUncertaintyM = uncertaintyMetres
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
        if (!hasReceivedPosition) {
            Log.d(TAG, "Skipping advertisement - no position from real user yet")
            return
        }
        sequenceNumber++
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = buildAdvertiseData()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
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

    /**
     * When we receive an advertisement from the "real" user, set position to be cutoff+20m north.
     * Assumes there is only 1 "real" user. Will update with any user available.
     */
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

        // Check if the peer is a real user (not a debugger)
        if (peer.isDebug.toInt() == 0) {
            // Each debug device uses its own random bearing
            val newPosition = ExtraFunctions.bearingToLocation(
                peer.position.lat,
                peer.position.lon,
                (CUTOFF_RANGE + 20).toDouble(), // 20 metres outside cutoff range
                Math.toRadians(bearingDegrees.toDouble()).toFloat()
            )

            // Update position
            updateAdvertisedPosition(
                newPosition.lat.toFloat(),
                newPosition.lon.toFloat(),
                0F,
                peer.floorNumber)

            // On first real-user detection, begin advertising
            if (!hasReceivedPosition) {
                hasReceivedPosition = true
                startAdvertising()
                Log.d(TAG, "First real user detected - started advertising at bearing ${bearingDegrees.toInt()}°")
            }
        }

        // If new peer, cache them
        val isNew = !peerCache.containsKey(peer.deviceId)
        peerCache[peer.deviceId] = peer

        // If new peer, call listener
        if (isNew) {
            Log.d(TAG, "New peer: id=${peer.deviceId}, pos=${peer.position}, " +
                    "unc=${"%.1f".format(peer.uncertaintyMetres)}m, rssi=${peer.rssi}")
            listener?.onPeerDiscovered(peer)
        }
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

    fun getPeerCount(): Int = peerCache.size

    // Generate device ID using your MAC address, or time
    @SuppressLint("MissingPermission")
    private fun generateDeviceId(): Short {
        return try {
            val address = bluetoothAdapter?.address
            if (address != null) {
                (address.hashCode() and 0xFFFF).toShort()
            } else {
                (System.nanoTime() and 0xFFFF).toShort()
            }
        } catch (_: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted, using time-based device ID")
            (System.nanoTime() and 0xFFFF).toShort()
        }
    }
}