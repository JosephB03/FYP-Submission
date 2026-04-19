package com.example.fypdeadreckoning.activity

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.lifecycleScope
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.databinding.ActivityMainBinding
import com.example.fypdeadreckoning.helpers.storage.CalibrationKeys
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.extra.UncertaintyModel
import com.example.fypdeadreckoning.helpers.storage.dataStore
import com.example.fypdeadreckoning.helpers.location.GPSConfidenceManager
import com.example.fypdeadreckoning.helpers.location.GeofenceManager
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeDeltaOrientation
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeEulerOrientation
import com.example.fypdeadreckoning.helpers.orientation.MagneticFieldOrientation
import com.example.fypdeadreckoning.helpers.steps.DynamicStepCounter
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import com.example.fypdeadreckoning.helpers.storage.DatabaseProvider
import com.example.fypdeadreckoning.helpers.storage.Building
import com.example.fypdeadreckoning.helpers.storage.Floor
import com.example.fypdeadreckoning.ui.home.HomeViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import android.location.LocationManager
import com.example.fypdeadreckoning.helpers.extra.AnalyticsManager
import com.example.fypdeadreckoning.helpers.extra.PowerModeManager
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import com.example.fypdeadreckoning.helpers.location.GeofenceStatus
import com.example.fypdeadreckoning.helpers.location.PeerAugmentationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// https://github.com/android/platform-samples/tree/main/samples/location/src/main/java/com/example/platform/location/geofencing
class MainActivity : AppCompatActivity(), SensorEventListener {
    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding

    private lateinit var homeViewModel: HomeViewModel    // For interacting with HomeFragment

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var geofenceManager: GeofenceManager

    companion object {
        const val GEOFENCE_ID = "wgb_geofence"
        const val ACTIVITY_PERMISSION_REQUEST_CODE = 101
        const val LOCATION_PERMISSION_REQUEST_CODE = 102
        const val BLUETOOTH_PERMISSION_REQUEST_CODE = 103

        const val HEADING_SAMPLES_NEEDED = 25
        const val UNCERTAINTY_REDUCTION_SCALE = 0.5
    }

    private lateinit var uncertaintyModel: UncertaintyModel

    // Sensors
    private var sensorLinearAcceleration: Sensor? = null
    private var sensorMagneticField: Sensor? = null
    private var sensorGyroscope: Sensor? = null
    private var sensorGravity: Sensor? = null
    private var sensorStepCounter: Sensor? = null
    private var lastAndroidStepCount: Float = -1f

    // Managers
    private var sensorManager: SensorManager? = null

    // Create dedicated thread for sensors to avoid race conditions
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // GPS/Location variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isGPSAcquired = false

    private val gpsConfidenceManager = GPSConfidenceManager()
    private var lastConfidentGPSLocation: android.location.Location? = null

    // Step counter variables
    private var stridelength: Double = 0.75
    private var stepSensitivity: Double = 1.0
    private var dynamicStepCounter: DynamicStepCounter? = null

    // Running variables
    private var wasRunning = false
    private var isRunning = false
    private var isHeadingInitialized = false
    private var isDRInitialized: Boolean = false

    // Heading variables
    private var initialDirection: Float = 0.0F
    private var gyroDirection = 0.0

    // TODO see in DirectionTest whether to update implementation
    private var eulerDirection: Float = 0.0F

    private var magDirection: Float = 0.0F

    private var gyroCIntegration = GyroscopeDeltaOrientation(0f)

    private var gyroUOrientation: GyroscopeEulerOrientation? = null

    private lateinit var gravityValues: FloatArray
    private lateinit var magValues: FloatArray

    var gravityCount: Int = 0
    var magCount: Int = 0
    lateinit var sumGravityValues: FloatArray
    lateinit var sumMagValues: FloatArray

    // Dead Reckoning variables
    private var currentHeading: Float = 0.0F

    // GPS variables
    private var isTrackingActive: Boolean = false
    private var currentPosGPS: LatLon = LatLon(0.0, 0.0)

    // Bluetooth
    private lateinit var peerAugmentation: PeerAugmentationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_debug, R.id.navigation_instructions, R.id.navigation_settings
            )
        )
        navView.setupWithNavController(navController)

        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        initializeSensors()
        initializeLocation()

        // TODO see placeholder values
        uncertaintyModel = UncertaintyModel(
            errorPerStep = PowerModeManager.Uncertainty.errorPerStep(PowerModeManager.mode.value),
            initialUncertainty = 5.0,
            maxUncertainty = 15.0
        )

        geofencingClient = LocationServices.getGeofencingClient(this)
        lifecycleScope.launch {
            initializeDatabase()          // wait for DB to be ready
            geofenceManager = GeofenceManager(this@MainActivity, lifecycleScope)
            geofenceManager.observePowerMode()
            setupGeofences()
            checkInitialGeofenceState()
        }

        peerAugmentation = PeerAugmentationManager(this)

        lifecycleScope.launch {
            PowerModeManager.mode.collect { mode ->
                onPowerModeChanged(mode)
                AnalyticsManager.reportPowerMode(mode)
            }
        }

        // Rebuild UncertaintyModel when confidence level changes
        lifecycleScope.launch {
            SettingsManager.confidenceLevel.collect { level ->
                val currentRadius = uncertaintyModel.getUncertaintyRadius()
                val currentSteps = uncertaintyModel.getStepsSinceReset()
                uncertaintyModel = UncertaintyModel(
                    errorPerStep = PowerModeManager.Uncertainty.errorPerStep(PowerModeManager.mode.value),
                    initialUncertainty = 5.0,
                    maxUncertainty = 15.0,
                    confidenceLevel = level
                )
                uncertaintyModel.setUncertainty(currentRadius)
                Log.d(TAG, "UncertaintyModel rebuilt with confidence level $level")
            }
        }

        // Toggle BLE augmentation mid-session
        lifecycleScope.launch {
            SettingsManager.bleAugmentationEnabled.collect { enabled ->
                if (isTrackingActive) {
                    if (enabled) {
                        peerAugmentation.start()
                    } else {
                        peerAugmentation.stop()
                    }
                }
            }
        }

        // Switch step counter mode
        lifecycleScope.launch {
            SettingsManager.useAndroidStepCounter.collect { useAndroid ->
                if (isTrackingActive) {
                    switchStepCounterMode(useAndroid)
                }
            }
        }

        loadPreferences()
    }

    fun resetUncertainty(newUncertainty: Float = 5.0f) {
        val wasAtMax = uncertaintyModel.resetUncertainty(newUncertainty.toDouble())
        homeViewModel.resetUncertainty(newUncertainty)
        homeViewModel.updateUncertaintyRadius(newUncertainty) // ← this drives the UI

        // Reports to the user that their previous uncertainty was high
        if (wasAtMax) {
            runOnUiThread {
                Toast.makeText(this, "Position uncertainty was high - location has been reset.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeSensors() {
        // Initialize sensor arrays
        gravityValues = FloatArray(3)
        magValues = FloatArray(3)
        sumGravityValues = FloatArray(3)
        sumMagValues = FloatArray(3)
        gravityCount = 0
        magCount = 0

        // Initialize managers
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Create dedicated thread for sensors
        sensorThread = HandlerThread("SensorThread").apply {
            start()
        }
        sensorHandler = Handler(sensorThread!!.looper)

        // Get sensor instances
        sensorLinearAcceleration = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorMagneticField = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorGyroscope = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorGravity = sensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY)
        sensorStepCounter = sensorManager!!.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        // Initialize step counter
        dynamicStepCounter = DynamicStepCounter()

        // Initialize orientation
        val magneticFieldOrientation = MagneticFieldOrientation()
        initialDirection = magneticFieldOrientation.getDirection(gravityValues, magValues)
        gyroUOrientation = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
    }


    private fun initializeLocation() {
        // Initialize GPS/Location services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Callback defines action when a location update arrives
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation // Only need newest location
                if (location != null) {
                    currentPosGPS = LatLon(location.latitude, location.longitude)
                    homeViewModel.updateGPSLocation(currentPosGPS.lat.toFloat(), currentPosGPS.lon.toFloat())

                    val currentDRLocation = homeViewModel.getCurrentUserLocation()

                    val currentPosDR = if (currentDRLocation != null) {
                        LatLon(currentDRLocation.x.toDouble(), currentDRLocation.y.toDouble())
                    } else {
                        currentPosGPS   // fall back to GPS if DR not yet initialised
                    }

                    val drUncertaintyMetres = uncertaintyModel.getUncertaintyRadius().toFloat()
                    val confidence = gpsConfidenceManager.onLocationUpdate(
                        location, currentPosDR, drUncertaintyMetres
                    )
                    AnalyticsManager.reportGPSConfidence(
                        accuracy = gpsConfidenceManager.accuracyScore,
                        signal = gpsConfidenceManager.signalScore,
                        sanity = gpsConfidenceManager.sanityScore,
                        staleness = gpsConfidenceManager.stalenessScore,
                        combined = confidence
                    )
                    AnalyticsManager.reportGNSSInfo(
                        gpsConfidenceManager.satelliteCount,
                        gpsConfidenceManager.averageCn0DbHz
                    )
                    if (confidence > GPSConfidenceManager.CONFIDENCE_THRESHOLD) {
                        lastConfidentGPSLocation = location
                        // Check if DR is running and GPS augmentation is enabled
                        if (isDRInitialized && isHeadingInitialized && SettingsManager.gpsAugmentationEnabled.value) {
                            val result = gpsConfidenceManager.tryAugmentPosition(
                                currentPosDR, location, confidence
                            )
                            homeViewModel.updateUserLocation(
                                result.position.lat.toFloat(),
                                result.position.lon.toFloat()
                            )
                            if (result.nudgedMetres > 0.0) {
                                val reduction = result.nudgedMetres * UNCERTAINTY_REDUCTION_SCALE
                                uncertaintyModel.reduceUncertainty(reduction)
                                homeViewModel.updateUncertaintyRadius(uncertaintyModel.getUncertaintyRadius().toFloat())
                                Log.d(TAG, "GPS augment reduced uncertainty by ${"%.2f".format(reduction)}m")
                            }
                            Log.d(TAG, "GPS augmentation applied (confidence=${"%.2f".format(confidence)})")
                        }
                    }

                    // TODO consider how to make this run before initial position in MapView as fallback
                    // First valid GPS location becomes the DR starting point
                    if (!isDRInitialized) { // Only use if accuracy is reasonable
                        isDRInitialized = true
                        isGPSAcquired = true

                        // Base uncertainty on initial GPS accuracy
                        val gpsAccuracyMetres = if (location.hasAccuracy()) {
                            location.accuracy.toDouble().coerceAtMost(5.0)
                        } else {
                            5.0 // Fallback
                        }
                        uncertaintyModel.resetUncertainty(gpsAccuracyMetres)
                        homeViewModel.resetUncertainty(gpsAccuracyMetres.toFloat())

                        // Log.d(TAG, "DR initialized at GPS position: $currentPosGPS")

                        checkAndStartTracking() // Check if we can start tracking
                    }
                }
            }
        }
    }

    private fun loadPreferences() {
        lifecycleScope.launch {
            val preferences = dataStore.data.first()
            stridelength = preferences[CalibrationKeys.STRIDE_LENGTH_KEY] ?: 0.75
            stepSensitivity = preferences[CalibrationKeys.STEP_COUNTER_SENSITIVITY_KEY] ?: 1.0

            if (stepSensitivity > 0) {
                dynamicStepCounter = DynamicStepCounter(stepSensitivity)
            }

            Log.d(TAG, "Loaded calibration - Stride: $stridelength, Sensitivity: $stepSensitivity")
        }
    }

    // GNSS measurement callback
    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            gpsConfidenceManager.onGnssMeasurements(event)
        }
    }

    private fun setupGeofences() {
        // TODO make dynamic when implementing server
        // Dynamically load geofence coordinates from ROOM
        lifecycleScope.launch(Dispatchers.IO) {
            val database = DatabaseProvider.getDatabase(this@MainActivity)
            val buildingDao = database.buildingDao()
            val buildings = buildingDao.getAllBuildings()
            buildings.forEach { building ->
                geofenceManager.addGeofence(
                    building.name,
                    building.geofenceX,
                    building.geofenceY,
                    building.geofenceRadius.toFloat()
                )
            }
            geofenceManager.registerGeofence()
        }
        Log.d(TAG, "Geofences registered successfully")
        Toast.makeText(this, "Geofencing enabled", Toast.LENGTH_SHORT).show()
    }

    /**
     * Sometimes when the app is started in a building, the ENTER or DWELL event is not fired
     * This method is called to do a manual check
     */
    private fun checkInitialGeofenceState() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) return@addOnSuccessListener

            val database = DatabaseProvider.getDatabase(this)
            lifecycleScope.launch(Dispatchers.IO) {
                val buildings = database.buildingDao().getAllBuildings()
                buildings.forEach { building ->
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        location.latitude, location.longitude,
                        building.geofenceX, building.geofenceY,
                        distance
                    )
                    if (distance[0] <= building.geofenceRadius) {
                        Log.d(TAG, "Already inside geofence: ${building.name}")
                        GeofenceStatus.entered(building.name)
                    }
                }
            }
        }
    }

    // Prepopulate the database
    private suspend fun initializeDatabase() {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val database = DatabaseProvider.getDatabase(this@MainActivity)
            val buildingDao = database.buildingDao()
            val floorDao = database.floorDao()

            // Check if already populated
            if (buildingDao.getAllBuildings().isEmpty()) {
                // Insert WGB building
                buildingDao.insert(
                    Building(
                        buildingId = 1,
                        name = "Western Gateway Building",
                        address = "UCC, Cork",
                        totalFloors = 2,
                        geofenceX = 51.893056,
                        geofenceY = -8.500556,
                        geofenceRadius = 65.0, // 65 is ideal, may be different for testing
                        startingPositionPixelX = 7544F,
                        startingPositionPixelY = 1970.4F,
                        startingPositionImage = null
                    )
                )

                // Insert ground floor
                floorDao.insert(
                    Floor(
                        floorId = 1,
                        buildingId = 1,
                        floorNumber = 0,
                        floorName = "Ground Floor",
                        localImagePath = "wgb_floor0.jpg", // Will use assets fallback
                        mapMetresX = 111.0,
                        mapMetresY = 71.0,
                        topLeftLat = 51.893229,
                        topLeftLon = -8.501462,
                        topRightLat = 51.893510,
                        topRightLon = -8.499926,
                        bottomLeftLat = 51.892618,
                        bottomLeftLon = -8.501169,
                        bottomRightLat = 51.892894,
                        bottomRightLon = -8.499636,
                    )
                )
                // Insert 1st floor
                floorDao.insert(
                    Floor(
                        floorId = 2,
                        buildingId = 1,
                        floorNumber = 1,
                        floorName = "1st Floor",
                        localImagePath = "wgb_floor1.jpg",
                        mapMetresX = 111.0,
                        mapMetresY = 71.0,
                        topLeftLat = 51.893229,
                        topLeftLon = -8.501462,
                        topRightLat = 51.893510,
                        topRightLon = -8.499926,
                        bottomLeftLat = 51.892618,
                        bottomLeftLon = -8.501169,
                        bottomRightLat = 51.892894,
                        bottomRightLon = -8.499636,
                    )
                )
            }
        }
    }

    // Begin GPS tracking
    fun startTracking() {
        Log.d(TAG, "GPS tracking started")

        // TODO make sure permissions checked in Setup

        if (isTrackingActive) {
            Log.d(TAG, "Already tracking")
        }

        startSensorListeners()
        startGPSTracking()
        if (SettingsManager.bleAugmentationEnabled.value) {
            peerAugmentation.start()
        }
        isTrackingActive = true

        homeViewModel.updateUncertaintyRadius(uncertaintyModel.getUncertaintyRadius().toFloat())

        Log.d(TAG, "Started tracking")

    }

    // Check if we can start tracking
    private fun checkAndStartTracking() {
        if (isDRInitialized && isHeadingInitialized && !isRunning) {
            isRunning = true
            runOnUiThread {
                Toast.makeText(this, "Ready to track", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Stop GPS tracking
    fun stopTracking() {
        sensorManager?.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
        isTrackingActive = false
        peerAugmentation.stop()
        AnalyticsManager.reset()
        Log.d(TAG, "Tracking stopped")
    }

    // Necessary for Kotlin, but not used
    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // Not yet implemented
    }

    // This only works as long as the sensor is registered
    // Sensors not registered by default
    override fun onSensorChanged(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        // TODO MAKE SURE THERE ARE NO LONGER RACE CONDITION ISSUES
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                // Copy event values so it doesn't get overwritten
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
                gravityCount++
                for (i in 0..2) sumGravityValues[i] += gravityValues[i]
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Copy event values so it doesn't get overwritten
                System.arraycopy(event.values, 0, magValues, 0, 3)
                magCount++
                for (i in 0..2) sumMagValues[i] += magValues[i]
            }
        }
        // Initialize heading once we have both gravity and magnetic data
        if (!isHeadingInitialized && gravityCount >= HEADING_SAMPLES_NEEDED && magCount >= HEADING_SAMPLES_NEEDED) {
            // Average readings for accuracy
            val avgGravity = FloatArray(3) { i -> sumGravityValues[i] / gravityCount }
            val avgMag = FloatArray(3) { i -> sumMagValues[i] / magCount }

            initialDirection = magneticFieldOrientation.getDirection(
                avgGravity,
                avgMag
            )
            // TODO make sure both are necessary
            gyroUOrientation = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
            gyroCIntegration = GyroscopeDeltaOrientation(0f)

            Log.d(TAG, "Heading initialized: $initialDirection degrees")

            isHeadingInitialized = true

            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Heading calibrated: ${initialDirection.toInt()}°",
                    Toast.LENGTH_SHORT
                ).show()
            }
            checkAndStartTracking() // Check if we can start tracking
        }
        // Only takes sensor data when test is running
        if (isTrackingActive && isDRInitialized && isHeadingInitialized) {
            processSensors(event)
        }
    }

    private fun processSensors(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        when (event.sensor.type) {
            // Gyroscope check
            Sensor.TYPE_GYROSCOPE -> {
                // NEGATE the Z-axis to fix East-West flip bug
                val correctedValues = event.values.clone()
                correctedValues[2] = -correctedValues[2]  // Flip Z-axis sign

                val deltaOrientation: FloatArray = gyroCIntegration.calcDeltaOrientation(
                    event.timestamp,
                    correctedValues  // Use corrected values
                )!!
                gyroDirection += deltaOrientation[2].toDouble()

                val identityDirection = gyroUOrientation!!.getDirection(deltaOrientation)
                eulerDirection = ExtraFunctions.polarAdd(initialDirection, identityDirection)
                currentHeading = ExtraFunctions.calcCompassDirection(magDirection, eulerDirection)
                AnalyticsManager.reportHeading(
                    magRad = magDirection.toDouble(),
                    gyroRad = eulerDirection.toDouble(),
                    combinedRad = currentHeading.toDouble()
                )
            }
            // Magnetic Field check
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magDirection = magneticFieldOrientation.getDirection(
                    gravityValues,
                    magValues
                )
                currentHeading = ExtraFunctions.calcCompassDirection(magDirection, eulerDirection)
                AnalyticsManager.reportHeading(
                    magRad = magDirection.toDouble(),
                    gyroRad = eulerDirection.toDouble(),
                    combinedRad = currentHeading.toDouble()
                )
            }
            // Dynamic step check
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Only use DynamicStepCounter if not using Android's step counter
                if (!SettingsManager.useAndroidStepCounter.value) {
                    val norm: Float = ExtraFunctions.calcNorm(
                        event.values[0],
                        event.values[1],
                        event.values[2]
                    )
                    if (dynamicStepCounter!!.findStep(norm)) {
                        Log.d(TAG, "Step detected by Linear Acc")
                        processStep()
                    }
                }
            }
            // Android built-in step counter
            Sensor.TYPE_STEP_COUNTER -> {
                if (SettingsManager.useAndroidStepCounter.value) {
                    val totalSteps = event.values[0]
                    if (lastAndroidStepCount < 0) {
                        lastAndroidStepCount = totalSteps
                    } else if (totalSteps > lastAndroidStepCount) {
                        val newSteps = (totalSteps - lastAndroidStepCount).toInt()
                        lastAndroidStepCount = totalSteps
                        repeat(newSteps) {
                            Log.d(TAG, "Step detected by Android Step Counter")
                            processStep()
                        }
                    }
                }
            }
        }
    }

    // When a step is detected, find updates
    private fun processStep() {
        val currentLocation = homeViewModel.getCurrentUserLocation()
        if (currentLocation == null) {
            Log.w(TAG, "Cannot process step - no current location available")
            return
        }

        gpsConfidenceManager.onStepDetected()

        val currentPosDR = LatLon(currentLocation.x.toDouble(), currentLocation.y.toDouble())

        // Log all heading components
        Log.d(TAG, "------- HEADING DEBUG -------")
        Log.d(TAG, "Mag direction: ${Math.toDegrees(magDirection.toDouble())}°")
        Log.d(TAG, "Euler direction: ${Math.toDegrees(eulerDirection.toDouble())}°")
        Log.d(TAG, "Current heading: ${Math.toDegrees(currentHeading.toDouble())}°")

        val bearing = ExtraFunctions.compassToBearing(currentHeading)
        Log.d(TAG, "Bearing (after conversion): ${Math.toDegrees(bearing.toDouble())}°")

        var newDRLatLon = ExtraFunctions.bearingToLocation(
            currentPosDR.lat,
            currentPosDR.lon,
            stridelength,
            bearing
        )

        val currentFloor = homeViewModel.getCurrentFloor()!!.floorNumber
        peerAugmentation.updateAdvertisedPosition(
            lat = newDRLatLon.lat.toFloat(),
            lon = newDRLatLon.lon.toFloat(),
            uncertaintyMetres = uncertaintyModel.getUncertaintyRadius().toFloat(),
            isReliable = !uncertaintyModel.isMaxUncertainty(),
            floorNumber = currentFloor
        )

        // Safe to overwrite, if no peers then no change
        if (SettingsManager.bleAugmentationEnabled.value) {
            val peerResult = peerAugmentation.tryAugmentPosition(
                newDRLatLon,
                currentFloorNumber = currentFloor
            )
            newDRLatLon = peerResult.position
            if (peerResult.nudgedMetres > 0.0) {
                val reduction = peerResult.nudgedMetres * UNCERTAINTY_REDUCTION_SCALE
                uncertaintyModel.reduceUncertainty(reduction)
                Log.d(TAG, "Peer augment reduced uncertainty by ${"%.2f".format(reduction)}m")
            }
        }

        // DEBUG: Log movement test
        val deltaLat = newDRLatLon.lat - currentPosDR.lat
        val deltaLon = newDRLatLon.lon - currentPosDR.lon
        Log.d(TAG, "Movement: Lat=$deltaLat, Lon=$deltaLon")
        Log.d(TAG, "------------------------------------------")

        homeViewModel.updateUserLocation(newDRLatLon.lat.toFloat(), newDRLatLon.lon.toFloat())

        AnalyticsManager.reportDRPosition(newDRLatLon)
        AnalyticsManager.reportStep(uncertaintyModel.getStepsSinceReset())
        val peerMetrics = peerAugmentation.getPeerMetrics()
        AnalyticsManager.reportPeerMetrics(
            total = peerMetrics.total,
            trusted = peerMetrics.trusted,
            far = peerMetrics.outsideCutoff
        )
        
        // Update uncertainty with each step
        uncertaintyModel.onStepDetected()

        // Update the map view with new uncertainty radius
        homeViewModel.updateUncertaintyRadius(uncertaintyModel.getUncertaintyRadius().toFloat())

        if (uncertaintyModel.isMaxUncertainty()) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Position uncertainty is high - consider updating your location.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun switchStepCounterMode(useAndroid: Boolean) {
        if (useAndroid) {
            // Unregister linear acceleration (no longer needed)
            sensorLinearAcceleration?.let {
                sensorManager?.unregisterListener(this, it)
            }
            // Register Android step counter
            sensorStepCounter?.let {
                sensorManager!!.registerListener(this, it,
                    SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            }
            lastAndroidStepCount = -1f
            Log.d(TAG, "Switched to Android Step Counter")
        } else {
            // Unregister Android step counter
            sensorStepCounter?.let {
                sensorManager?.unregisterListener(this, it)
            }
            // Re-register linear acceleration for dynamic step counting
            val speed = PowerModeManager.Sensors.sensorDelay(PowerModeManager.mode.value)
            sensorLinearAcceleration?.let {
                sensorManager!!.registerListener(this, it, speed, sensorHandler)
            }
            // Reset DynamicStepCounter EMA state to avoid false positives from stale thresholds
            dynamicStepCounter = DynamicStepCounter(dynamicStepCounter!!.sensitivity)
            Log.d(TAG, "Switched to Dynamic Step Counter")
        }
    }

    // Registers listeners on start
    private fun startSensorListeners() {
        val speed = PowerModeManager.Sensors.sensorDelay(PowerModeManager.mode.value)
        if (!SettingsManager.useAndroidStepCounter.value) {
            sensorManager!!.registerListener(
                this@MainActivity,
                sensorLinearAcceleration,
                speed,
                sensorHandler
            )
        }
        sensorManager!!.registerListener(
            this@MainActivity,
            sensorMagneticField,
            speed,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MainActivity,
            sensorGyroscope,
            speed,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MainActivity,
            sensorGravity,
            speed,
            sensorHandler
        )

        // Register Android step counter if that mode is active
        if (SettingsManager.useAndroidStepCounter.value) {
            sensorStepCounter?.let {
                sensorManager!!.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            }
            lastAndroidStepCount = -1f
        }

        wasRunning = true
    }


    private fun startGPSTracking() {
        // Permission check required by API
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        val mode = PowerModeManager.mode.value

        // Build the location request interface
        val locationRequest = LocationRequest.Builder(
            PowerModeManager.Gps.priority(mode),
            PowerModeManager.Gps.intervalMs(mode)
        ).apply {
            setMinUpdateIntervalMillis(PowerModeManager.Gps.fastestIntervalMs(mode))
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager.registerGnssMeasurementsCallback(
            gnssMeasurementsCallback,
            sensorHandler    // reuse the background handler
        )
    }

    // Called when a request to change mode is made
    private fun onPowerModeChanged(mode: PowerModeManager.PowerMode) {
        Log.d(TAG, "Applying power mode: $mode")

        // Sensors
        if (isTrackingActive) {
            restartSensorsForMode(mode)
        }

        // GPS
        if (isTrackingActive) {
            restartGPSForMode(mode)
        }

        // BLE
        peerAugmentation.applyPowerMode(mode)
    }

    // Sensor re-registration
    private fun restartSensorsForMode(mode: PowerModeManager.PowerMode) {
        // Unregister everything first
        sensorManager?.unregisterListener(this)

        val delay = PowerModeManager.Sensors.sensorDelay(mode)

        if (!SettingsManager.useAndroidStepCounter.value) {
            sensorManager!!.registerListener(
                this@MainActivity, sensorLinearAcceleration, delay, sensorHandler
            )
        }
        sensorManager!!.registerListener(
            this@MainActivity, sensorMagneticField, delay, sensorHandler
        )
        sensorManager!!.registerListener(
            this@MainActivity, sensorGyroscope, delay, sensorHandler
        )
        sensorManager!!.registerListener(
            this@MainActivity, sensorGravity, delay, sensorHandler
        )

        if (SettingsManager.useAndroidStepCounter.value) {
            sensorStepCounter?.let {
                sensorManager!!.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            }
        }

        Log.d(TAG, "Sensors re-registered at delay=$delay for mode=$mode")
    }

    // Restart for GPS
    private fun restartGPSForMode(mode: PowerModeManager.PowerMode) {
        // Remove existing updates
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Permission check required by API
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // Build new request with mode-specific parameters
        val locationRequest = LocationRequest.Builder(
            PowerModeManager.Gps.priority(mode),
            PowerModeManager.Gps.intervalMs(mode)
        ).apply {
            setMinUpdateIntervalMillis(PowerModeManager.Gps.fastestIntervalMs(mode))
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )

        locationManager.registerGnssMeasurementsCallback(
            gnssMeasurementsCallback, sensorHandler
        )

        Log.d(TAG, "GPS restarted: interval=${PowerModeManager.Gps.intervalMs(mode)}ms, " +
                "priority=${PowerModeManager.Gps.priority(mode)}")
    }

    fun togglePowerMode() {
        PowerModeManager.toggle()
    }

    override fun onStop() {
        super.onStop()
        sensorManager!!.unregisterListener(this)
        stopTracking()
    }

    override fun onPause() {
        super.onPause()
        sensorManager!!.unregisterListener(this)
        stopTracking()  // Stop when app goes to background
    }

    // Restart everything that was running before onPause
    override fun onResume() {
        super.onResume()

        if (isTrackingActive) {
            startSensorListeners()
        }
    }

    // Safely quit sensorThread
    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        peerAugmentation.stop()
        sensorThread?.quitSafely()
        sensorThread = null
        geofenceManager.deregisterGeofence()
    }

}