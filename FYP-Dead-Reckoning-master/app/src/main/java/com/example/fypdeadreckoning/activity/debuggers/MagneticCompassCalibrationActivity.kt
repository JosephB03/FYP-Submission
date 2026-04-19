package com.example.fypdeadreckoning.activity.debuggers

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.bias.MagneticFieldBias
import java.util.Locale

class MagneticCompassCalibrationActivity: AppCompatActivity(), SensorEventListener {

    private var TAG = "MagneticCompassCalibrationActivity"

    private var instructionText: TextView? = null
    private var statusText: TextView? = null
    private var sampleCountText: TextView? = null
    private var startCalibrationButton: Button? = null
    private var stopCalibrationButton: Button? = null

    private var sensorMagneticField: Sensor? = null
    private var sensorManager: SensorManager? = null

    private var magneticFieldBias: MagneticFieldBias? = null

    private var sampleCount = 0
    private var isCalibrating = false
    private var startTime: Long = 0

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mag_calibration)

        // Initialize views
        instructionText = findViewById(R.id.instructionText)
        statusText = findViewById(R.id.statusText)
        sampleCountText = findViewById(R.id.sampleCountText)

        startCalibrationButton = findViewById(R.id.startButton)
        stopCalibrationButton = findViewById(R.id.stopButton)

        // Initialize sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorMagneticField = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

        // Check sensor availability
        if (sensorMagneticField == null) {
            Toast.makeText(this, "Magnetometer not available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize bias calculator
        magneticFieldBias = MagneticFieldBias()
        instructionText?.text = "Move your phone in figure-8 patterns to calibrate the compass.\n\n Recommended: 200+ samples for good calibration."

        // Starts calibration by initializing sensor
        startCalibrationButton?.setOnClickListener {
            sensorManager?.registerListener(
                this,
                sensorMagneticField,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            isCalibrating = true
            sampleCount = 0
            startTime = System.currentTimeMillis() // take start time to calculate elapsed time

            startCalibrationButton?.isEnabled = false
            stopCalibrationButton?.isEnabled = true

            statusText?.text = "Calibrating... Move phone in figure-8s"
            Toast.makeText(this, "Calibration started", Toast.LENGTH_SHORT).show()
        }

        // Finishes calibration manually
        stopCalibrationButton?.setOnClickListener {
            sensorManager?.unregisterListener(this)
            isCalibrating = false

            // Check if enough samples were collected
            if (sampleCount < 100) {
                Toast.makeText(
                    this, "Warning: Low sample count. Recommended 200+ samples.", Toast.LENGTH_LONG).show()
            }

            val bias = magneticFieldBias?.getBias()

            // Checks if bias is valid
            if (bias != null && bias.size >= 3) {
                Log.d(TAG, "Mag Bias X: ${bias[0]}")
                Log.d(TAG, "Mag Bias Y: ${bias[1]}")
                Log.d(TAG, "Mag Bias Z: ${bias[2]}")
                Log.d(TAG, "Magnetic Field Strength: ${bias[3]}")

                val resultIntent = Intent()
                resultIntent.putExtra("mag_bias_x", bias[0].toDouble())
                resultIntent.putExtra("mag_bias_y", bias[1].toDouble())
                resultIntent.putExtra("mag_bias_z", bias[2].toDouble())
                setResult(RESULT_OK, resultIntent)

                Toast.makeText(
                    this,
                    String.format(Locale.UK, "Calibration complete! Samples: %d", sampleCount),
                    Toast.LENGTH_SHORT
                ).show()

                finish()
            } else {
                Toast.makeText(this, "Calibration failed - not enough data", Toast.LENGTH_LONG).show()
                startCalibrationButton?.isEnabled = true
                stopCalibrationButton?.isEnabled = false
            }
        }

        stopCalibrationButton?.isEnabled = false
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // Not needed
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event!!.sensor.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED && isCalibrating) {
            magneticFieldBias!!.calcBias(event.values)
            sampleCount++

            // Update UI every 10 samples to avoid too many updates
            if (sampleCount % 10 == 0) {
                sampleCountText?.text = "Samples collected: $sampleCount"

                val elapsedTime = (System.currentTimeMillis() - startTime) / 1000
                statusText?.text = String.format(
                    Locale.UK,
                    "Calibrating... (%d samples, %d seconds)",
                    sampleCount,
                    elapsedTime
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        sensorManager!!.unregisterListener(this)
    }
}
