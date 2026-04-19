package com.example.fypdeadreckoning.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.example.fypdeadreckoning.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.fypdeadreckoning.helpers.storage.dataStore
import com.example.fypdeadreckoning.helpers.storage.CalibrationKeys

class SetUpActivity: AppCompatActivity() {
    private var TAG = "SetUpActivity"

    private val ACTIVITY_PERMISSION_REQUEST_CODE = 101
    private val LOCATION_PERMISSION_REQUEST_CODE = 102
    private val BLE_PERMISSION_REQUEST_CODE = 103

    private var beginText: TextView? = null
    private var strideButton: Button? = null
    private var beginButton: Button? = null

    private val defaultStrideLength = 0.75
    private val defaultSensitivity = 2.5

    private val strideLengthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                val strideLength = data.getDoubleExtra("stride_length", defaultStrideLength) // average stride length for a person
                val stepCounterSensitivity = data.getDoubleExtra("step_counter_sensitivity", defaultSensitivity) // best sensitivity in my testing

                Log.d(TAG, "Stride length: $strideLength")
                Log.d(TAG, "Preferred step sensitivity: $stepCounterSensitivity")

                saveStepCalibration(strideLength, stepCounterSensitivity)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted, proceeding to MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            val denied = permissions.filter { !it.value }.keys
            Log.d(TAG, "Denied permissions: $denied")
            Toast.makeText(this, "All permissions are required to proceed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        beginText = findViewById<View?>(R.id.beginText) as TextView
        strideButton = findViewById<View?>(R.id.strideButton) as Button
        beginButton = findViewById<View?>(R.id.beginButton) as Button

        strideButton!!.setOnClickListener {
            Log.d(TAG, "Stride calibration button pressed.")
            val intent = Intent(this@SetUpActivity, StrideLengthCalibrationActivity::class.java)
            strideLengthLauncher.launch(intent)
        }

        beginButton!!.setOnClickListener {
            Log.d(TAG, "Begin button pressed.")

            lifecycleScope.launch {
                // Read all calibration values
                val preferences = dataStore.data.first()
                val strideLength = preferences[CalibrationKeys.STRIDE_LENGTH_KEY] ?: -1.0
                val sensitivity = preferences[CalibrationKeys.STEP_COUNTER_SENSITIVITY_KEY] ?: -1.0

                Log.d(TAG, "Retrieved stride length: $strideLength")
                // Check if all are calibrated
                if (strideLength == -1.0 || sensitivity == -1.0) {
                    Toast.makeText(this@SetUpActivity, "Please calibrate primary sensors", Toast.LENGTH_SHORT).show()
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACTIVITY_RECOGNITION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }

    private fun saveStepCalibration(strideLength: Double, sensitivity: Double) {
        lifecycleScope.launch {
            dataStore.edit { preferences ->
                preferences[CalibrationKeys.STRIDE_LENGTH_KEY] = strideLength
                preferences[CalibrationKeys.STEP_COUNTER_SENSITIVITY_KEY] = sensitivity
            }
            Log.d(TAG, "Stride length and sensitivity saved to DataStore")
        }
    }

}