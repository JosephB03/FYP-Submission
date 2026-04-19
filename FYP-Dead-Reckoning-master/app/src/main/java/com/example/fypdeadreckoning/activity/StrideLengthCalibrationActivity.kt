package com.example.fypdeadreckoning.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.steps.DynamicStepCounter
import com.example.fypdeadreckoning.helpers.dialog.StepCalibrationDialogFragment
import com.example.fypdeadreckoning.interfaces.OnPreferredStepCounterListener
import java.lang.String
import java.util.Locale
import kotlin.Array
import kotlin.Double
import kotlin.Float
import kotlin.Int
import kotlin.arrayOfNulls
import kotlin.text.format

class StrideLengthCalibrationActivity : AppCompatActivity(), SensorEventListener, OnPreferredStepCounterListener {
    companion object {
        const val TAG = "StrideLengthActivity"
        const val PERMISSION_REQUEST_CODE = 101
        const val STARTING_SENSITIVITY = 0.75
        const val ENDING_SENSITIVITY = 1.5
        const val SENSITIVITY_INCREMENT = 0.05
    }
    private var textAndroidSteps: TextView? = null
    private var inputDistance: EditText? = null
    private var textLinearAcceleration: TextView? = null

    private var buttonStartCalibration: Button? = null
    private var buttonStopCalibration: Button? = null
    private var buttonSetStrideLength: Button? = null

    private var linearAcceleration: Sensor? = null
    private var androidStepCounter: Sensor? = null
    private var sensorManager: SensorManager? = null

    private val arraySize: Int = ((ENDING_SENSITIVITY-STARTING_SENSITIVITY)/SENSITIVITY_INCREMENT).toInt()
    private var dynamicStepCounters: Array<DynamicStepCounter> = Array(arraySize) { DynamicStepCounter() }

    // Step counter variables
    private var androidStepCount = 0
    private var androidStepCountTotal = 0
    private var androidStepCountInitial = 0
    private var stepCount = 0
    private var preferredStepCounterIndex = 0

    private var wasRunning = false

    @SuppressLint("UnsafeIntentLaunch")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stride_calibration)

        var sensitivity = STARTING_SENSITIVITY
        for (i in dynamicStepCounters.indices) {
            dynamicStepCounters[i] = DynamicStepCounter(sensitivity)
            sensitivity += SENSITIVITY_INCREMENT
        }

        //defining views
        textAndroidSteps = findViewById(R.id.textCalibrateSteps)
        inputDistance = findViewById(R.id.inputDistance)
        textLinearAcceleration = findViewById(R.id.calibrateLinearAcceleration)

        buttonStartCalibration = findViewById(R.id.startButton)
        buttonStopCalibration = findViewById(R.id.stopButton)
        buttonSetStrideLength = findViewById(R.id.setStrideButton)

        //defining sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        linearAcceleration = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        androidStepCounter = sensorManager!!.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        //activate sensors when start button is pressed
        buttonStartCalibration!!.setOnClickListener {
            if (checkActivityRecognitionPermission()) {
                startSensorListeners()
            } else {
                requestActivityRecognitionPermission()
            }
        }

        //deactivate sensors when stop button is pressed and open step_counters dialog
        buttonStopCalibration!!.setOnClickListener {
            sensorManager!!.unregisterListener(this@StrideLengthCalibrationActivity)

            val stepCounts = arrayOfNulls<String>(dynamicStepCounters.size)
            for (i in stepCounts.indices) stepCounts[i] = String.format(
                Locale.UK,
                "Sensitivity: %.2f :: Step Count: %d",
                dynamicStepCounters[i].sensitivity,
                dynamicStepCounters[i].stepCount
            ) as String?


            // Creating dialog, setting the stepCounts list, and setting a handler
            val stepCalibrationDialogFragment = StepCalibrationDialogFragment()
            stepCalibrationDialogFragment.setOnPreferredStepCounterListener(this@StrideLengthCalibrationActivity)
            stepCalibrationDialogFragment.setStepList(stepCounts)
            stepCalibrationDialogFragment.show(supportFragmentManager, "step_counters")

            buttonStartCalibration!!.isEnabled = true
            buttonSetStrideLength!!.isEnabled = true
            buttonStopCalibration!!.isEnabled = false

            wasRunning = false
        }

        // When the button is pressed, determine the strideLength
        // TODO: Add a clear functionality
        buttonSetStrideLength!!.setOnClickListener {
            if (stepCount <= 0) {
                Toast.makeText(application, "Take a few steps first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val strideLength: Double = inputDistance!!.text.toString().toInt().toDouble() / stepCount
            Log.d(TAG, "Steps taken:: $stepCount")
            Log.d(TAG, "Stride length: $strideLength")

            val strideLengthStr = kotlin.String.format(Locale.UK, "%.2f", strideLength)
            Toast.makeText(
                applicationContext,
                "Stride length set: $strideLengthStr m/step",
                Toast.LENGTH_SHORT
            ).show()

            // Returns the stride_length and step_counter_sensitivity info to the calling activity
            val myIntent = intent
            myIntent.putExtra("stride_length", strideLength)
            myIntent.putExtra(
                "step_counter_sensitivity",
                dynamicStepCounters[preferredStepCounterIndex].sensitivity
            )
            setResult(RESULT_OK, myIntent)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        sensorManager!!.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()

        if (wasRunning) {
            sensorManager!!.registerListener(
                this@StrideLengthCalibrationActivity,
                linearAcceleration,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@StrideLengthCalibrationActivity,
                androidStepCounter,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            buttonStartCalibration!!.isEnabled = false
            buttonSetStrideLength!!.isEnabled = false
            buttonStopCalibration!!.isEnabled = true
        } else {
            buttonStartCalibration!!.isEnabled = true
            buttonSetStrideLength!!.isEnabled = true
            buttonStopCalibration!!.isEnabled = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (androidStepCountInitial == 0) {
                // This is the total count since reboot. Save it as the starting point.
                androidStepCountInitial = event.values[0].toInt()
            }
            androidStepCountTotal = event.values[0].toInt()
            androidStepCount = androidStepCountTotal - androidStepCountInitial
            textAndroidSteps!!.text = androidStepCount.toString()
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val norm: Float = ExtraFunctions.calcNorm(
                event.values[0],
                        event.values[1],
                        event.values[2]
            )

            val linearAcceleration = event.values[2].toString()
            textLinearAcceleration!!.text = if (linearAcceleration.length <= 5) linearAcceleration else linearAcceleration.substring(0, 5)

            for (dynamicStepCounter in dynamicStepCounters) dynamicStepCounter.findStep(norm)
        }
    }

    private fun startSensorListeners() {
        sensorManager!!.registerListener(
            this@StrideLengthCalibrationActivity,
            linearAcceleration,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager!!.registerListener(
            this@StrideLengthCalibrationActivity,
            androidStepCounter,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        buttonStartCalibration!!.isEnabled = false
        buttonSetStrideLength!!.isEnabled = false
        buttonStopCalibration!!.isEnabled = true

        wasRunning = true
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

    // Handle the result of the activity permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out kotlin.String>, grantResults: IntArray) {
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

    override fun onPreferredStepCounter(preferredStepCounterIndex: Int) {
        this.preferredStepCounterIndex = preferredStepCounterIndex
        this.stepCount = dynamicStepCounters[preferredStepCounterIndex].stepCount
        this.textAndroidSteps!!.text = this.stepCount.toString()
    }
}