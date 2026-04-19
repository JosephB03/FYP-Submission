package com.example.fypdeadreckoning.activity.debuggers

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.bias.GyroscopeBias
import java.util.Locale

class GyroscopeCalibrationActivity : AppCompatActivity(), SensorEventListener {

    private val TAG = "GyroCalibration"

    private val WAIT_SAMPLES = 50  // Wait for this many samples before starting calibration
    private val CALIBRATION_SAMPLES = 300  // Number of samples to collect

    private var instructionText: TextView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var startButton: Button? = null

    private var sensorGyroscope: Sensor? = null
    private var sensorManager: SensorManager? = null

    private var gyroscopeBias: GyroscopeBias? = null

    private var sampleCount = 0
    private var isCalibrating = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gyro_calibration)

        // Initialize views
        instructionText = findViewById(R.id.instructionText)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        startButton = findViewById(R.id.startButton)

        // Initialize sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorGyroscope = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)

        // Check sensor availability
        if (sensorGyroscope == null) {
            Toast.makeText(this, "Gyroscope not available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize bias calculator
        gyroscopeBias = GyroscopeBias(CALIBRATION_SAMPLES)

        instructionText?.text = "Place your phone on a flat, stable surface.\n\n Do not touch or move the phone during calibration.\n\n Calibration takes about 10 seconds."

        progressBar?.max = CALIBRATION_SAMPLES
        progressBar?.progress = 0

        startButton?.setOnClickListener {
            startButton?.isEnabled = false
            statusText?.text = "Preparing... Keep phone still!"

            // Wait 2 seconds before starting calibration and loop
            Handler(Looper.getMainLooper()).postDelayed({
                sensorManager?.registerListener(
                    this,
                    sensorGyroscope,
                    SensorManager.SENSOR_DELAY_FASTEST
                )
                isCalibrating = true
                sampleCount = 0
                statusText?.text = "Calibrating... Do not move phone!"
            }, 2000)
        }
    }

    // Runs once calibration is complete
    private fun completeCalibration() {
        sensorManager?.unregisterListener(this)
        isCalibrating = false

        val bias = gyroscopeBias?.getBias()

        // Check if bias is valid
        if (bias != null && bias.size >= 3) {
            Log.d(TAG, "Gyro Bias X: ${bias[0]}")
            Log.d(TAG, "Gyro Bias Y: ${bias[1]}")
            Log.d(TAG, "Gyro Bias Z: ${bias[2]}")

            val resultIntent = Intent()
            resultIntent.putExtra("gyro_bias_x", bias[0].toDouble())
            resultIntent.putExtra("gyro_bias_y", bias[1].toDouble())
            resultIntent.putExtra("gyro_bias_z", bias[2].toDouble())
            setResult(RESULT_OK, resultIntent)

            statusText?.text = "Calibration complete!"

            Toast.makeText(this, "Gyroscope calibration successful!",Toast.LENGTH_SHORT).show()

            // Delay UI update before finishing
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1000)
        } else {
            Toast.makeText(this, "Calibration failed", Toast.LENGTH_LONG).show()
            startButton?.isEnabled = true
            statusText?.text = "Calibration failed. Try again."
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED && isCalibrating) {

            // Skip initial samples to let sensor stabilize
            if (sampleCount < WAIT_SAMPLES) {
                sampleCount++
                return
            }

            val biasCalculated = gyroscopeBias?.calcBias(event.values) ?: false

            if (biasCalculated) {
                completeCalibration()
            } else {
                val adjustedCount = sampleCount - WAIT_SAMPLES
                progressBar?.progress = adjustedCount

                if (adjustedCount % 30 == 0) {  // Update every 30 samples
                    val percentage = (adjustedCount * 100) / CALIBRATION_SAMPLES
                    statusText?.text = String.format(
                        Locale.UK,
                        "Calibrating... %d%%",
                        percentage
                    )
                }

                sampleCount++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    override fun onStop() {
        super.onStop()
        sensorManager?.unregisterListener(this)
    }
}