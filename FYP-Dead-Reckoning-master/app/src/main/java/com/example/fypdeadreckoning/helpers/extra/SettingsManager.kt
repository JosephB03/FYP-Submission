package com.example.fypdeadreckoning.helpers.extra

import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central source of truth for user-configurable settings.
 * Runtime only — resets on app restart.
 */
object SettingsManager {
    // Display Settings

    // Confidence level: 0.68 or 0.95
    private val _confidenceLevel = MutableStateFlow(0.68)
    val confidenceLevel: StateFlow<Double> = _confidenceLevel.asStateFlow()

    fun setConfidenceLevel(level: Double) { _confidenceLevel.value = level }

    // Show uncertainty radius on map
    private val _showUncertaintyRadius = MutableStateFlow(true)
    val showUncertaintyRadius: StateFlow<Boolean> = _showUncertaintyRadius.asStateFlow()

    fun setShowUncertaintyRadius(show: Boolean) { _showUncertaintyRadius.value = show }

    // User dot color
    private val _userDotColor = MutableStateFlow(Color.BLUE)
    val userDotColor: StateFlow<Int> = _userDotColor.asStateFlow()

    fun setUserDotColor(color: Int) { _userDotColor.value = color }

    // Uncertainty circle color
    private val _uncertaintyCircleColor = MutableStateFlow(Color.RED)
    val uncertaintyCircleColor: StateFlow<Int> = _uncertaintyCircleColor.asStateFlow()

    fun setUncertaintyCircleColor(color: Int) { _uncertaintyCircleColor.value = color }

    // Augmentation Settings

    // GPS augmentation enabled
    private val _gpsAugmentationEnabled = MutableStateFlow(true)
    val gpsAugmentationEnabled: StateFlow<Boolean> = _gpsAugmentationEnabled.asStateFlow()

    fun setGpsAugmentationEnabled(enabled: Boolean) { _gpsAugmentationEnabled.value = enabled }

    // BLE peer augmentation enabled
    private val _bleAugmentationEnabled = MutableStateFlow(true)
    val bleAugmentationEnabled: StateFlow<Boolean> = _bleAugmentationEnabled.asStateFlow()

    fun setBleAugmentationEnabled(enabled: Boolean) { _bleAugmentationEnabled.value = enabled }

    // Sensor & Algorithm Settings

    // Complementary filter ratio (mag weight). Gyro weight = 1 - this.
    private val _complementaryFilterRatio = MutableStateFlow(0.02f)
    val complementaryFilterRatio: StateFlow<Float> = _complementaryFilterRatio.asStateFlow()

    fun setComplementaryFilterRatio(ratio: Float) { _complementaryFilterRatio.value = ratio }

    // Use Android's built-in TYPE_STEP_COUNTER instead of DynamicStepCounter
    private val _useAndroidStepCounter = MutableStateFlow(false)
    val useAndroidStepCounter: StateFlow<Boolean> = _useAndroidStepCounter.asStateFlow()

    fun setUseAndroidStepCounter(use: Boolean) { _useAndroidStepCounter.value = use }

    // Gyroscope sensitivity threshold
    private val _gyroSensitivity = MutableStateFlow(0.0025f)
    val gyroSensitivity: StateFlow<Float> = _gyroSensitivity.asStateFlow()

    fun setGyroSensitivity(sensitivity: Float) { _gyroSensitivity.value = sensitivity }

    // Step counter EMA alpha
    private val _stepCounterAlpha = MutableStateFlow(0.15)
    val stepCounterAlpha: StateFlow<Double> = _stepCounterAlpha.asStateFlow()

    fun setStepCounterAlpha(alpha: Double) { _stepCounterAlpha.value = alpha }

    // GPS nudge factor
    private val _gpsNudgeFactor = MutableStateFlow(0.30f)
    val gpsNudgeFactor: StateFlow<Float> = _gpsNudgeFactor.asStateFlow()

    fun setGpsNudgeFactor(factor: Float) { _gpsNudgeFactor.value = factor }

    // BLE nudge factor
    private val _bleNudgeFactor = MutableStateFlow(0.20f)
    val bleNudgeFactor: StateFlow<Float> = _bleNudgeFactor.asStateFlow()

    fun setBleNudgeFactor(factor: Float) { _bleNudgeFactor.value = factor }
}
