package com.example.fypdeadreckoning.helpers.orientation

import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import kotlin.math.abs

class GyroscopeDeltaOrientation {
    private var isFirstRun = true
    private var sensitivity = 0.0025f
    private var lastTimestamp = 0f

    constructor (sensitivity: Float) {
        this.sensitivity = sensitivity
    }

    fun calcDeltaOrientation(timestamp: Long, rawGyroValues: FloatArray): FloatArray? {
        //get the first timestamp
        if (isFirstRun) {
            isFirstRun = false
            lastTimestamp = ExtraFunctions.nsToSec(timestamp)
            return FloatArray(3)
        }

        val filteredValues = filterGyroValues(rawGyroValues)

        //return deltaOrientation[]
        return integrateValues(timestamp, filteredValues)
    }

    private fun filterGyroValues(gyroValues: FloatArray): FloatArray {
        //ignoring the last 3 values of TYPE_UNCALIBRATED_GYROSCOPE, since the are only the Android-calculated biases
        val filtered = FloatArray(3)
        val currentSensitivity = SettingsManager.gyroSensitivity.value

        for (i in 0..2) {
            filtered[i] = if (abs(gyroValues[i]) > currentSensitivity) gyroValues[i] else 0f
        }

        return filtered
    }

    private fun integrateValues(timestamp: Long, gyroValues: FloatArray): FloatArray {
        val currentTime: Float = ExtraFunctions.nsToSec(timestamp)
        val deltaTime = currentTime - lastTimestamp

        val deltaOrientation = FloatArray(3)

        // integrating angular velocity with respect to time
        for (i in 0..2) deltaOrientation[i] = gyroValues[i] * deltaTime

        lastTimestamp = currentTime

        return deltaOrientation
    }
}