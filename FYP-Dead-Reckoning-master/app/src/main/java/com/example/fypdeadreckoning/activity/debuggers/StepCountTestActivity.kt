package com.example.fypdeadreckoning.activity.debuggers

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.steps.DynamicStepCounter
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions

class StepCountTestActivity : Activity(), SensorEventListener {
    private val TAG = "StepCountTestActivity"
    private val PERMISSION_REQUEST_CODE = 101

    // Buttons
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var clearButton: Button? = null
    // TextViews
    // private var textDynamicCounter: TextView? = null
    private var textAndroidCounter: TextView? = null
    private var textAcceleration: TextView? = null

    private var textDynamicCounter1: TextView? = null
    private var textDynamicCounter2: TextView? = null
    private var textDynamicCounter3: TextView? = null
    private var textDynamicCounter4: TextView? = null
    private var textDynamicCounter5: TextView? = null

    // Sensors
    private var sensorAccelerometer: Sensor? = null
    private var sensorLinearAcceleration: Sensor? = null
    private var sensorStepCounter: Sensor? = null
    private var sensorManager: SensorManager? = null

    // Step counter variables
    private var androidStepCount = 0
    private var androidStepCountTotal = 0
    private var androidStepCountInitial = 0
    private lateinit var dynamicStepCounters: Array<DynamicStepCounter>

    // Movement recognition
    private var wasRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_steps)

        // Initialise Views
        startButton = findViewById<View?>(R.id.startButton) as Button
        stopButton = findViewById<View?>(R.id.stopButton) as Button
        clearButton = findViewById<View?>(R.id.clearButton) as Button
        // textDynamicCounter = findViewById<View?>(R.id.textDynamicStepCount) as TextView
        textAndroidCounter = findViewById<View?>(R.id.textAndroidStepCount) as TextView
        textAcceleration = findViewById<View?>(R.id.textAcceleration) as TextView

        textDynamicCounter1 = findViewById<View?>(R.id.textDynamicStepCount1) as TextView
        textDynamicCounter2 = findViewById<View?>(R.id.textDynamicStepCount2) as TextView
        textDynamicCounter3 = findViewById<View?>(R.id.textDynamicStepCount3) as TextView
        textDynamicCounter4 = findViewById<View?>(R.id.textDynamicStepCount4) as TextView
        textDynamicCounter5 = findViewById<View?>(R.id.textDynamicStepCount5) as TextView

        // Initialise sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorAccelerometer = sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorLinearAcceleration = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorStepCounter = sensorManager!!.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        // Set array of dynamic step counters with various sensitivity
        dynamicStepCounters = arrayOf(
            DynamicStepCounter(0.80),
            DynamicStepCounter(0.85),
            DynamicStepCounter(0.875),
            DynamicStepCounter(0.90),
            DynamicStepCounter(0.95)
        )

        clearCounters()

        // Check permissions and start the sensors
        startButton!!.setOnClickListener {
            if (checkActivityRecognitionPermission()) {
                startSensorListeners()
            } else {
                requestActivityRecognitionPermission()
            }
        }

        // Deactivates the sensors
        stopButton!!.setOnClickListener {
            sensorManager!!.unregisterListener(this@StepCountTestActivity, sensorAccelerometer)
            sensorManager!!.unregisterListener(this@StepCountTestActivity, sensorLinearAcceleration)
            sensorManager!!.unregisterListener(this@StepCountTestActivity, sensorStepCounter)

            startButton!!.isEnabled = true
            stopButton!!.isEnabled = false

            wasRunning = false
        }

        clearButton?.setOnClickListener { clearCounters() }

    }

    // Necessary for Kotlin, but not used
    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // Not yet implemented
    }

    // This only works as long as the sensor is registered
    // Sensors not registered by default
    override fun onSensorChanged(event: SensorEvent) {
        // Event is either a step, or acceleration (which approximates to steps)
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (androidStepCountInitial == 0) {
                // This is the total count since reboot. Save it as the starting point.
                androidStepCountInitial = event.values[0].toInt()
            }
            androidStepCountTotal = event.values[0].toInt()
            androidStepCount = androidStepCountTotal - androidStepCountInitial
            textAndroidCounter!!.text = androidStepCount.toString()
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            // TODO implement checks for vertical acceleration
            // Calculate norm of acceleration
            val norm: Float = ExtraFunctions.calcNorm(
                event.values[0],
                        event.values[1],
                        event.values[2]
            )

            // Update acceleration
            textAcceleration!!.text = norm.toString().substring(0, 3) + " m/s^2"

            // Using separate thread for step counters to minimize load on UI Thread
            Thread {

                for (dynamicStepCounter in dynamicStepCounters) dynamicStepCounter.findStep(
                    norm
                )

                runOnUiThread {
                    // textDynamicCounter?.text = dynamicStepCounters[0].stepCount.toString()
                    textDynamicCounter1?.text = dynamicStepCounters[0].stepCount.toString()
                    textDynamicCounter2?.text = dynamicStepCounters[1].stepCount.toString()
                    textDynamicCounter3?.text = dynamicStepCounters[2].stepCount.toString()
                    textDynamicCounter4?.text = dynamicStepCounters[3].stepCount.toString()
                    textDynamicCounter5?.text = dynamicStepCounters[4].stepCount.toString()
                }
            }.start()
        }
    }

    // Re-register sensors
    override fun onResume() {
        super.onResume()

        if (wasRunning) {
            sensorManager!!.registerListener(
                this@StepCountTestActivity,
                sensorAccelerometer,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@StepCountTestActivity,
                sensorStepCounter,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            startButton!!.isEnabled = false
            stopButton!!.isEnabled = true
        } else {
            startButton!!.isEnabled = true
            stopButton!!.isEnabled = false
        }
    }

    // Unregister listeners
    override fun onStop() {
        super.onStop()
        sensorManager!!.unregisterListener(this)
    }

    // Set counters to 0
    fun clearCounters() {
        // textDynamicCounter!!.text = "0"
        textAndroidCounter!!.text = "0"
        textAcceleration!!.text = "0.00 m/s^2"

        textDynamicCounter1!!.text = "0"
        textDynamicCounter2!!.text = "0"
        textDynamicCounter3!!.text = "0"
        textDynamicCounter4!!.text = "0"
        textDynamicCounter5!!.text = "0"

        androidStepCount = 0
        androidStepCountTotal = 0
        androidStepCountInitial = 0

        for (dynamicStepCounter in dynamicStepCounters) {
            dynamicStepCounter.clearStepCount()
        }
    }

    // Check if activity tracking permission is granted
    private fun checkActivityRecognitionPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Request activity tracking permission
    private fun requestActivityRecognitionPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
            PERMISSION_REQUEST_CODE
        )
    }

    // Registers listeners on start
    private fun startSensorListeners() {
        sensorManager!!.registerListener(
            this@StepCountTestActivity,
            sensorAccelerometer,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager!!.registerListener(
            this@StepCountTestActivity,
            sensorLinearAcceleration,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager!!.registerListener(
            this@StepCountTestActivity,
            sensorStepCounter,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager!!.registerListener(
            this@StepCountTestActivity,
            sensorStepCounter,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        startButton!!.isEnabled = false
        stopButton!!.isEnabled = true
        wasRunning = true
    }

    // Handle the result of the activity permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start the sensors
                startSensorListeners()
            } else {
                // Permission denied
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}