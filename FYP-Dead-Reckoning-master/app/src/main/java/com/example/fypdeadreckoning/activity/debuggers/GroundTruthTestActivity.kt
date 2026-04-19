package com.example.fypdeadreckoning.activity.debuggers

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.storage.CalibrationKeys
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.location.LocalCoordinateSystem
import com.example.fypdeadreckoning.helpers.location.Point2D
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
import kotlin.math.abs

//
class GroundTruthTestActivity : AppCompatActivity(), SensorEventListener {
    private val TAG = "GroundTruthTest"
    private val LOCATION_PERMISSION_REQUEST_CODE = 102

    private var statusText: TextView? = null
    private var point1InfoText: TextView? = null
    private var point2InfoText: TextView? = null
    private var currentPosText: TextView? = null
    private var errorMetricsText: TextView? = null
    private var stepsText: TextView? = null

    private var setPoint1Button: Button? = null
    private var setPoint2Button: Button? = null
    private var startTestButton: Button? = null
    private var finishTestButton: Button? = null
    private var newTestButton: Button? = null

    private var actualStepsInput: EditText? = null
    private var resultsDisplay: TextView? = null

    // Reference points (lat/lon)
    private var point1LatLon: LatLon? = null
    private var point2LatLon: LatLon? = null

    // Local coordinate system (origin at Point 1)
    private val point1Local = Point2D(0.0, 0.0)
    private var point2Local: Point2D? = null

    // Sensors
    private var sensorLinearAcceleration: Sensor? = null
    private var sensorMagneticField: Sensor? = null
    private var sensorGyroscope: Sensor? = null
    private var sensorGravity: Sensor? = null
    private var sensorManager: SensorManager? = null

    // Sensor thread
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentGPS: LatLon? = null

    // PDR variables
    private var strideLength: Double = 0.75
    private var stepSensitivity: Double = 1.0
    private var dynamicStepCounter: DynamicStepCounter? = null

    // Heading variables
    private var initialDirection: Float = 0.0F
    private var gyroDirection = 0.0
    private var eulerDirection: Float = 0.0F
    private var magDirection: Float = 0.0F
    private var currentHeading: Float = 0.0F

    private var gyroCIntegration = GyroscopeDeltaOrientation(0f)
    private var gyroUOrientation: GyroscopeEulerOrientation? = null

    private lateinit var gravityValues: FloatArray
    private lateinit var magValues: FloatArray
    private var gravityCount: Int = 0
    private var magCount: Int = 0
    private lateinit var sumGravityValues: FloatArray
    private lateinit var sumMagValues: FloatArray

    // Test state
    private var isTestRunning = false
    private var isHeadingInitialized = false
    private var testStartTime: Long = 0
    private var currentDR: Point2D = Point2D(0.0, 0.0)

    // Test data
    private var stepsTaken = 0
    private var distanceTraveled = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ground_truth_test)

        initializeUI()
        initializeSensors()
        initializeLocation()
        loadPreferences()
        updateUI()
    }

    private fun initializeUI() {
        statusText = findViewById(R.id.statusText)
        point1InfoText = findViewById(R.id.point1InfoText)
        point2InfoText = findViewById(R.id.point2InfoText)
        currentPosText = findViewById(R.id.currentPosText)
        errorMetricsText = findViewById(R.id.errorMetricsText)
        stepsText = findViewById(R.id.stepsText)

        setPoint1Button = findViewById(R.id.setPoint1Button)
        setPoint2Button = findViewById(R.id.setPoint2Button)
        startTestButton = findViewById(R.id.startTestButton)
        finishTestButton = findViewById(R.id.finishTestButton)
        newTestButton = findViewById(R.id.newTestButton)

        actualStepsInput = findViewById(R.id.actualStepsInput)
        resultsDisplay = findViewById(R.id.resultsDisplay)

        setPoint1Button!!.setOnClickListener { setReferencePoint(1) }
        setPoint2Button!!.setOnClickListener { setReferencePoint(2) }
        startTestButton!!.setOnClickListener { startTest() }
        finishTestButton!!.setOnClickListener { finishTest() }
        newTestButton!!.setOnClickListener { resetForNewTest() }
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

        // Initialize step counter
        dynamicStepCounter = DynamicStepCounter()

        // Initialize orientation
        val magneticFieldOrientation = MagneticFieldOrientation()
        initialDirection = magneticFieldOrientation.getDirection(gravityValues, magValues)
        gyroUOrientation = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
    }

    private fun initializeLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    currentGPS = LatLon(location.latitude, location.longitude)
                    updateCurrentPositionDisplay()
                }
            }
        }
    }

    private fun loadPreferences() {
        lifecycleScope.launch {
            val preferences = dataStore.data.first()
            strideLength = preferences[CalibrationKeys.STRIDE_LENGTH_KEY] ?: 0.75
            stepSensitivity = preferences[CalibrationKeys.STEP_COUNTER_SENSITIVITY_KEY] ?: 1.0

            if (stepSensitivity > 0) {
                dynamicStepCounter = DynamicStepCounter(stepSensitivity)
            }

            Log.d(TAG, "Loaded calibration - Stride: $strideLength, Sensitivity: $stepSensitivity")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setReferencePoint(pointNumber: Int) {
        if (currentGPS == null) {
            Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
            startGPSTracking()
            return
        }

        when (pointNumber) {
            1 -> {
                point1LatLon = currentGPS
                currentDR = point1Local

                point1InfoText!!.text = "Point 1 (Origin): ${currentGPS}\nLocal: $point1Local"
                Toast.makeText(this, "Point 1 set as origin", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Point 1 set: $currentGPS")
            }
            2 -> {
                if (point1LatLon == null) {
                    Toast.makeText(this, "Please set Point 1 first", Toast.LENGTH_SHORT).show()
                    return
                }

                point2LatLon = currentGPS

                point2Local = LocalCoordinateSystem.latLonToLocalXY(
                    point1LatLon!!.lat, point1LatLon!!.lon,
                    currentGPS!!.lat, currentGPS!!.lon
                )

                val distance = ExtraFunctions.haversine(
                    point1LatLon!!.lat, point1LatLon!!.lon,
                    point2LatLon!!.lat, point2LatLon!!.lon
                )
                val bearing = ExtraFunctions.calculateBearing(
                    point1LatLon!!.lat, point1LatLon!!.lon,
                    point2LatLon!!.lat, point2LatLon!!.lon
                )

                point2InfoText!!.text = "Point 2: ${currentGPS}\n" +
                        "Local: $point2Local\n" +
                        "Distance: $distance m\n" +
                        "Bearing: $bearing°"

                Toast.makeText(this, "Point 2 set: $distance m at $bearing°",
                    Toast.LENGTH_LONG).show()
                Log.d(TAG, "Point 2 set: $currentGPS ($distance m at $bearing°)")
            }
        }

        updateUI()
    }

    private fun startTest() {
        if (point1LatLon == null || point2LatLon == null) {
            Toast.makeText(this, "Please set both reference points first", Toast.LENGTH_LONG).show()
            return
        }

        // Reset everything
        stepsTaken = 0
        distanceTraveled = 0.0
        currentDR = point1Local
        isHeadingInitialized = false
        gravityCount = 0
        magCount = 0
        sumGravityValues = FloatArray(3)
        sumMagValues = FloatArray(3)

        testStartTime = System.currentTimeMillis()
        isTestRunning = true

        // Start sensors
        startSensorListeners()
        startGPSTracking()

        statusText!!.text = "Test Running - Walk to Point 2"
        Toast.makeText(this, "Test started! Walk to Point 2", Toast.LENGTH_SHORT).show()

        updateUI()
        Log.d(TAG, "Test started")
    }

    @SuppressLint("SetTextI18n")
    private fun finishTest() {
        if (!isTestRunning) {
            Toast.makeText(this, "No test running", Toast.LENGTH_SHORT).show()
            return
        }

        stopSensorListeners()
        stopGPSTracking()
        isTestRunning = false

        val actualSteps = actualStepsInput!!.text.toString().toIntOrNull() ?: 0

        // Uses the local coordinate system to avoid repeated conversions
        val expectedDistance = LocalCoordinateSystem.distance(point1Local, point2Local!!)
        val expectedBearing = LocalCoordinateSystem.bearing(point1Local, point2Local!!)

        val finalGPSLocal = LocalCoordinateSystem.latLonToLocalXY(
            point1LatLon!!.lat, point1LatLon!!.lon,
            currentGPS!!.lat, currentGPS!!.lon
        )
        val gpsDistance = LocalCoordinateSystem.distance(point1Local, finalGPSLocal)
        val gpsBearing = LocalCoordinateSystem.bearing(point1Local, finalGPSLocal)

        val drDistance = LocalCoordinateSystem.distance(point1Local, currentDR)
        val drBearing = LocalCoordinateSystem.bearing(point1Local, currentDR)

        // Errors
        val finalPositionError = LocalCoordinateSystem.distance(currentDR, finalGPSLocal)
        val relativePositionError = if (expectedDistance > 0) (finalPositionError / expectedDistance) * 100 else 0.0
        val distanceError = abs(drDistance - expectedDistance)

        var bearingError = abs(drBearing - expectedBearing)
        if (bearingError > 180) bearingError = 360 - bearingError

        val stepCountError = stepsTaken - actualSteps
        val errorPerStep = if (actualSteps > 0) finalPositionError / actualSteps else 0.0

        val durationSeconds = (System.currentTimeMillis() - testStartTime) / 1000

        displayResults(
            durationSeconds,
            expectedDistance, expectedBearing,
            gpsDistance, gpsBearing,
            drDistance, drBearing,
            finalPositionError, relativePositionError,
            distanceError, bearingError,
            stepsTaken, actualSteps, stepCountError,
            errorPerStep
        )

        statusText!!.text = "Test Complete - Review Results Below"
        updateUI()

        Log.d(TAG, "Test finished - Error per step: ${String.format("%.4f", errorPerStep)} m/step")
    }

    @SuppressLint("SetTextI18n")
    private fun displayResults(
        duration: Long,
        expectedDistance: Double, expectedBearing: Double,
        actualDistance: Double, actualBearing: Double,
        drDistance: Double, drBearing: Double,
        finalPositionError: Double, relativePositionError: Double,
        distanceError: Double, bearingError: Double,
        estimatedSteps: Int, actualSteps: Int, stepCountError: Int,
        errorPerStep: Double
    ) {
        val errorPerStepCm = errorPerStep * 100
        val resultsText = """
            Duration: ${duration}s
            
            POSITION ERROR
            Final Error: ${finalPositionError}m
            Relative Error: $relativePositionError%
            
            ERROR PER STEP
            $errorPerStep m/step
            ($errorPerStepCm cm/step)

            DISTANCE
            Expected: ${expectedDistance}m
            GPS Measured: ${actualDistance}m
            DR Estimate: ${drDistance}m
            Difference (DR-expected): ${distanceError}m
            
            BEARING
            Expected: $expectedBearing°
            GPS Measured: $actualBearing°
            DR Estimate: $drBearing°
            Bearing Error (DR-Expected): $bearingError°
            
            STEPS
            Detected: $estimatedSteps
            Actual: $actualSteps
            Error: $stepCountError steps
        """.trimIndent()

        resultsDisplay!!.text = resultsText
        resultsDisplay!!.visibility = View.VISIBLE

        // Scroll to results
        findViewById<ScrollView>(R.id.scrollView)?.post {
            findViewById<ScrollView>(R.id.scrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun resetForNewTest() {
        // Clear results
        resultsDisplay!!.text = ""
        resultsDisplay!!.visibility = View.GONE

        // Clear inputs
        actualStepsInput!!.text.clear()

        // Reset displays
        errorMetricsText!!.text = "Latest: N/A\nAverage: N/A"
        currentPosText!!.text = "Current Position:\nGPS: N/A\nDR: (0.000, 0.000)\nSteps: 0\nDistance: 0.00m"
        stepsText!!.text = "Steps: 0"

        statusText!!.text = "Ready for New Test"

        Toast.makeText(this, "Ready for new test", Toast.LENGTH_SHORT).show()

        updateUI()
    }

    private fun updateUI() {
        setPoint1Button!!.isEnabled = !isTestRunning
        setPoint2Button!!.isEnabled = !isTestRunning && point1LatLon != null
        startTestButton!!.isEnabled = !isTestRunning && point1LatLon != null && point2LatLon != null
        finishTestButton!!.isEnabled = isTestRunning
        newTestButton!!.isEnabled = !isTestRunning
    }

    @SuppressLint("SetTextI18n")
    private fun updateCurrentPositionDisplay() {
        runOnUiThread {
            val gpsLocal = if (currentGPS != null && point1LatLon != null) {
                LocalCoordinateSystem.latLonToLocalXY(
                    point1LatLon!!.lat, point1LatLon!!.lon,
                    currentGPS!!.lat, currentGPS!!.lon
                )
            } else null

            currentPosText!!.text = "Current Position:\n" +
                    "GPS: ${gpsLocal ?: "N/A"}\n" +
                    "DR:  $currentDR\n" +
                    "Steps: $stepsTaken\n" +
                    "Distance: ${String.format("%.2f", distanceTraveled)}m"
        }
    }

    // Sensor handling
    override fun onSensorChanged(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
                gravityCount++
                for (i in 0..2) sumGravityValues[i] += gravityValues[i]
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magValues, 0, 3)
                magCount++
                for (i in 0..2) sumMagValues[i] += magValues[i]
            }
        }

        // Initialize heading
        if (!isHeadingInitialized && gravityCount >= 25 && magCount >= 25) {
            val avgGravity = FloatArray(3) { i -> sumGravityValues[i] / gravityCount }
            val avgMag = FloatArray(3) { i -> sumMagValues[i] / magCount }

            initialDirection = magneticFieldOrientation.getDirection(
                avgGravity,
                avgMag
            )

            gyroUOrientation = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
            gyroCIntegration = GyroscopeDeltaOrientation(0f)

            isHeadingInitialized = true

            runOnUiThread {
                Toast.makeText(
                    this@GroundTruthTestActivity,
                    "Heading calibrated: ${initialDirection.toInt()}°",
                    Toast.LENGTH_SHORT
                ).show()
            }

            Log.d(TAG, "Heading initialized: $initialDirection degrees")
        }

        if (isTestRunning && isHeadingInitialized) {
            processSensors(event)
        }
    }

    private fun processSensors(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()

        when (event.sensor.type) {
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
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magDirection = magneticFieldOrientation.getDirection(
                    gravityValues,
                    magValues
                )
                currentHeading = ExtraFunctions.calcCompassDirection(magDirection, eulerDirection)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val norm = ExtraFunctions.calcNorm(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )

                if (dynamicStepCounter!!.findStep(norm)) {
                    processStep()
                }
            }
        }
    }

    private fun processStep() {
        stepsTaken++
        distanceTraveled += strideLength

        // Accumulate directly in local XY
        val headingRad = ExtraFunctions.compassToBearing(currentHeading).toDouble()
        val headingDeg = Math.toDegrees(headingRad)
        currentDR = LocalCoordinateSystem.move(currentDR, strideLength, headingDeg)
        updateCurrentPositionDisplay()

        runOnUiThread {
            stepsText!!.text = "Steps: $stepsTaken"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun startSensorListeners() {
        sensorManager!!.registerListener(
            this,
            sensorLinearAcceleration,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this,
            sensorMagneticField,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this,
            sensorGyroscope,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        sensorManager!!.registerListener(
            this,
            sensorGravity,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
    }

    private fun stopSensorListeners() {
        sensorManager?.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun startGPSTracking() {
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

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            500
        ).apply {
            setMinUpdateIntervalMillis(200)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopGPSTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onPause() {
        super.onPause()
        if (!isTestRunning) {
            stopSensorListeners()
            stopGPSTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isTestRunning) {
            startSensorListeners()
            startGPSTracking()
        } else {
            startGPSTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorListeners()
        stopGPSTracking()
        sensorThread?.quitSafely()
        sensorThread = null
    }
}