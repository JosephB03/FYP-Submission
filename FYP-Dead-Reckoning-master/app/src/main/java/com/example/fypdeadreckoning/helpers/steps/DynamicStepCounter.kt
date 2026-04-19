package com.example.fypdeadreckoning.helpers.steps

import android.util.Log
import com.example.fypdeadreckoning.helpers.extra.SettingsManager

class DynamicStepCounter() {

    var stepCount: Int = 0
        private set
    var sensitivity: Double = 1.0
        private set
    private var upperThreshold = 10.8
    private var lowerThreshold = 8.8

    private var firstRun = true
    private var peakFound = false

    private var avgAcc: Double = 0.0

    private var lastStepTime = System.currentTimeMillis()

    companion object {
        const val TAG = "DynamicStepCounter"
        const val TIME_BETWEEN_STEPS_MS = 250L
    }

    // Set the default values for sensitivity
    constructor(sensitivity: Double) : this() {
        this.sensitivity = sensitivity
    }

    // Checks peaks in acceleration
    fun findStep(acc: Float): Boolean {
        // Always update thresholds so the EMA tracks the signal continuously
        updateThresholds(acc)

        // Finds a peak above the upperThreshold
        if (acc > upperThreshold) {
            if (!peakFound) {
                val currentTime = System.currentTimeMillis()

                // Apply debounce to the counting decision
                if (currentTime - lastStepTime < TIME_BETWEEN_STEPS_MS) {
                    Log.d(TAG, "Step detected too soon, debouncing")
                    return false
                }

                lastStepTime = currentTime
                stepCount++
                peakFound = true
                return true
            }
        } // No longer within peak
        else if (acc < lowerThreshold) {
            if (peakFound) {
                peakFound = false
            }
        }

        return false
    }

    // Dynamically updates sensitivity
    private fun updateThresholds(acc: Float) {
        if (firstRun) {
            avgAcc = acc.toDouble()
            firstRun = false
        } else {
            // Exponential moving average calc
            val alpha = SettingsManager.stepCounterAlpha.value
            avgAcc = alpha * acc + (1.0 - alpha) * avgAcc
        }

        upperThreshold = avgAcc + sensitivity
        lowerThreshold = avgAcc - sensitivity
    }

    fun clearStepCount() {
        stepCount = 0
    }
}