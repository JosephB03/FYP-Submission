package com.example.fypdeadreckoning.activity.debuggers

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.storage.CalibrationKeys
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.storage.dataStore
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeDeltaOrientation
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeEulerOrientation
import com.example.fypdeadreckoning.helpers.orientation.MagneticFieldOrientation
import com.example.fypdeadreckoning.helpers.steps.DynamicStepCounter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


// TODO see about implementing bias
class MovementTestActivity : AppCompatActivity(), SensorEventListener {
    private val TAG = "MovementTestActivity"
    private val ACTIVITY_PERMISSION_REQUEST_CODE = 101
    private val LOCATION_PERMISSION_REQUEST_CODE = 102

    // Buttons
    private var startButton: Button? = null
    private var stopButton: Button? = null

    // TextViews
    private var drLocationText: TextView? = null
    private var gpsLocationText: TextView? = null
    private var errorText: TextView? = null
    private var stepsText: TextView? = null
    private var distanceText: TextView? = null

    // Sensors
    private var sensorAccelerometer: Sensor? = null
    private var sensorLinearAcceleration: Sensor? = null
    private var sensorStepCounter: Sensor? = null
    private var sensorMagneticField: Sensor? = null
    private var sensorGyroscope: Sensor? = null
    private var sensorGravity: Sensor? = null

    // Managers
    private var sensorManager: SensorManager? = null

    // Create dedicated thread for sensors to avoid race conditions
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Step back off
    private var lastStepTime = 0L
    private val minStepInterval = 200L // milliseconds between steps

    // GPS/Location variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isGPSInitialized = false
    private var isGPSAcquired = false

    // Step counter variables
    private var stridelength: Double = 0.75
    private var stepSensitivity: Double = 1.0
    private var dynamicStepCounter: DynamicStepCounter? = null

    // Running variables
    private var wasRunning = false
    private var isRunning = false
    private var isHeadingInitialized = false
    private var isDRInitialized: Boolean = false
    private var isHeadingValid: Boolean = false

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
    private var currentPosDR: LatLon = LatLon(0.0, 0.0)
    private var drPositionsArray: ArrayList<LatLon> = ArrayList()

    // GPS variables
    private var currentPosGPS: LatLon = LatLon(0.0, 0.0)
    private var gpsPositionsArray: ArrayList<LatLon> = ArrayList()

    // Error variables
    private var cumulativeError: Double = 0.0
    private var errorCount: Double = 0.0

    // Test variables
    private var metresMoved = 0.0
    private var stepsTaken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movement_test)

        // Initialize UI components
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        drLocationText = findViewById(R.id.drLocationText)
        gpsLocationText = findViewById(R.id.gpsLocationText)
        errorText = findViewById(R.id.errorText)
        stepsText = findViewById(R.id.stepsText)
        distanceText = findViewById(R.id.distanceText)


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
        sensorAccelerometer = sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorLinearAcceleration = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorStepCounter = sensorManager!!.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        sensorMagneticField = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorGyroscope = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorGravity = sensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY)

        // Initialize step counter
        dynamicStepCounter = DynamicStepCounter()

        // Initialize GPS/Location services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Callback defines action when a location update arrives
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation // Only need newest location
                if (location != null) {
                    currentPosGPS = LatLon(location.latitude, location.longitude)

                    // Update UI
                    runOnUiThread {
                        gpsLocationText!!.text = currentPosGPS.toString()
                    }

                    // First valid GPS location becomes the DR starting point
                    if (!isDRInitialized) { // Only use if accuracy is reasonable
                        currentPosDR = currentPosGPS
                        isDRInitialized = true
                        isGPSAcquired = true

                        Log.d(TAG, "DR initialized at GPS position: $currentPosDR")

                        checkAndStartTracking() // Check if we can start tracking
                    }
                }
            }
        }

        // Check permissions and start the sensors
        startButton!!.setOnClickListener {
            val activityResult = checkActivityRecognitionPermission()
            val locationResult = checkLocationPermission()
            Log.d(TAG, "Permissions checked")

            // If both permissions are granted
            if (activityResult && locationResult) {
                lifecycleScope.launch {
                    // Read all calibration values
                    val preferences = dataStore.data.first()
                    stridelength = preferences[CalibrationKeys.STRIDE_LENGTH_KEY] ?: -1.0
                    stepSensitivity = preferences[CalibrationKeys.STEP_COUNTER_SENSITIVITY_KEY] ?: -1.0
                }

                startTracking()

                startSensorListeners()

                isRunning = true
            }

            // Get permissions
            if (!activityResult){ requestActivityRecognitionPermission() }
            if (!locationResult) { requestLocationPermission() }
        }

        // Deactivates the sensors
        stopButton!!.setOnClickListener {
            sensorManager!!.unregisterListener(this@MovementTestActivity)

            startButton!!.isEnabled = true
            stopButton!!.isEnabled = false

            wasRunning = false
            isRunning = false
            isHeadingInitialized = false
        }

        val magneticFieldOrientation = MagneticFieldOrientation()

        initialDirection =
            magneticFieldOrientation.getDirection(gravityValues, magValues)

        gyroUOrientation = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
    }

    // Unregister listeners
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

    // If user quits app
    override fun onResume() {
        super.onResume()

        if (wasRunning) {
            startSensorListeners()
        }
    }

    // Safely quit sensorThread
    override fun onDestroy() {
        super.onDestroy()
        sensorThread?.quitSafely()
        sensorThread = null
    }

    // Begin GPS tracking
    private fun startTracking() {
        Log.d(TAG, "GPS tracking started")

        // Build the location request interface
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,  // Use GPS
            500  // Request update every 0.5 seconds
        ).apply {
            setMinUpdateIntervalMillis(200)  // Can receive updates as fast as every 0.2 seconds
        }.build()

        // Permission check required by API
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,       // Callback when location is updated
                Looper.getMainLooper()  // Receive callbacks on main thread
            )
        }
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
    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "GPS tracking stopped")
    }

    // Necessary for Kotlin, but not used
    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // Not yet implemented
    }

    // This only works as long as the sensor is registered
    // Sensors not registered by default
    override fun onSensorChanged(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        checkAndStartTracking() // Check if we can start tracking

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
        if (!isHeadingInitialized && gravityCount >= 5 && magCount >= 5) {
            Log.d(TAG, "Initialized Heading")
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
            isHeadingValid = true

            runOnUiThread {
                Toast.makeText(
                    this@MovementTestActivity,
                    "Heading calibrated: ${initialDirection.toInt()}°",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        // Only takes sensor data when test is running
        if (isRunning && isDRInitialized && isHeadingInitialized) {
            processSensors(event)
        }
    }

    private fun processSensors(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        when (event.sensor.type) {
            // Gyroscope check
            Sensor.TYPE_GYROSCOPE -> {
                val deltaOrientation: FloatArray = gyroCIntegration.calcDeltaOrientation(
                    event.timestamp,
                    event.values
                )!!
                gyroDirection += deltaOrientation[2].toDouble()

                // Use calibrated gyroscope for DCM calculations
                val identityDirection = gyroUOrientation!!.getDirection(deltaOrientation)
                eulerDirection = ExtraFunctions.polarAdd(initialDirection, identityDirection)
                currentHeading =
                    ExtraFunctions.calcCompassDirection(magDirection, eulerDirection)
            }
            // Magnetic Field check
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magDirection =
                    magneticFieldOrientation.getDirection(
                        gravityValues,
                        magValues
                    )
                currentHeading =
                    ExtraFunctions.calcCompassDirection(magDirection, eulerDirection)
            }
            // TODO MAY BE WRONG
            // Android step counter check
            Sensor.TYPE_STEP_COUNTER -> {
                // Back off in case both step counters fire together
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastStepTime < minStepInterval) {
                    return // Ignore this step, too soon after last one
                }
                lastStepTime = currentTime

                Log.d(TAG, "Step detected by API")

                processStep()
            }
            // Dynamic step check
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Calculate norm of acceleration
                val norm: Float = ExtraFunctions.calcNorm(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )
                // Returns true if a step was detected. We can ignore the internal step counting
                if (dynamicStepCounter!!.findStep(norm)) {
                    // Back off in case both step counters fire together
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastStepTime < minStepInterval) {
                        return // Ignore this step, too soon after last one
                    }
                    lastStepTime = currentTime

                    Log.d(TAG, "Step detected by Linear Acc")

                    processStep()
                }
            }
        }
    }

    // When a step is detected, find updates
    private fun processStep() {
        val bearing = ExtraFunctions.compassToBearing(currentHeading)
        val newDRLatLon = ExtraFunctions.bearingToLocation(
            currentPosDR.lat,
            currentPosDR.lon,
            stridelength,
            bearing
        )
        drPositionsArray.add(newDRLatLon)
        currentPosDR = newDRLatLon

        gpsPositionsArray.add(currentPosGPS)

        // TODO replace with correct margin for error calc
        val difference = ExtraFunctions.haversine(currentPosDR.lat, currentPosDR.lon, currentPosGPS.lat, currentPosGPS.lon)

        stepsTaken += 1
        metresMoved += stridelength

        runOnUiThread {
            drLocationText!!.text = newDRLatLon.toString()
            errorText!!.text = difference.toString()
            stepsText!!.text = stepsTaken.toString()
            distanceText!!.text = metresMoved.toString()
        }
    }

    // Check if activity tracking permission is granted
    private fun checkActivityRecognitionPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Check if location tracking permission is granted
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // Request activity tracking permission
    private fun requestActivityRecognitionPermission() {
        Log.d(TAG, "Requesting activity permission")
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
            ACTIVITY_PERMISSION_REQUEST_CODE
        )
    }

    // Request location tracking permission
    private fun requestLocationPermission() {
        Log.d(TAG, "Requesting location permission")
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }


    // Handle the result of the activity permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ACTIVITY_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start the sensors
                startSensorListeners()
            } else {
                // Permission denied
                Toast.makeText(this, "Activity permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startTracking()
            } else {
                Toast.makeText(this, "GPS Tracking permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // Registers listeners on start
    private fun startSensorListeners() {
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorAccelerometer,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorLinearAcceleration,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorStepCounter,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorMagneticField,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorGyroscope,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this@MovementTestActivity,
            sensorGravity,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )

        startButton!!.isEnabled = false
        stopButton!!.isEnabled = true
        wasRunning = true
    }
}