package com.example.fypdeadreckoning.helpers.extra

import kotlin.math.sqrt

class UncertaintyModel(
    // TODO Configure after testing
    // Average error per step in metres
    private var errorPerStep: Double = 0.20,

    // Initial uncertainty metres (5m from GPS)
    private val initialUncertainty: Double = 5.0,

    // Maximum uncertainty radius until reset suggested (arbitrary value)
    private val maxUncertainty: Double = 15.0,

    // Confidence level (68 or 95)
    confidenceLevel: Double = 0.68
) {

    // Current uncertainty radius in meters
    private var currentUncertainty: Double = initialUncertainty
    private var currentBaseUncertainty: Double = initialUncertainty

    private val confidenceMultiplier: Double = when {
        confidenceLevel >= 0.95 -> 1.96
        confidenceLevel >= 0.68 -> 1.0
        else -> 1.0
    }

    // Total steps taken since last reset
    private var stepCount: Int = 0


    // Called when MainActivity detects a step
    fun onStepDetected() {
        stepCount++

        // Error grows with square root of steps (accounting for random walking)
        currentUncertainty = (currentBaseUncertainty + (errorPerStep * confidenceMultiplier * sqrt(stepCount.toDouble())))
            .coerceAtMost(maxUncertainty)   // Cap at maximum
    }

    // Reset on override. Reports if we were at max uncertainty
    fun resetUncertainty(newUncertainty: Double = initialUncertainty): Boolean {
        val wasAtMax = isMaxUncertainty()
        currentBaseUncertainty = newUncertainty.coerceIn(0.0, maxUncertainty)
        currentUncertainty = currentBaseUncertainty
        stepCount = 0
        return wasAtMax
    }

    // Getters & Setters
    fun getUncertaintyRadius(): Double {
        return currentUncertainty
    }

    fun getErrorPerStep(): Double {
        return errorPerStep
    }

    // More uncertainty = less confidence
    // Grows by square root
    fun getConfidenceLevel(): Double {
        val growthRange = maxUncertainty - currentBaseUncertainty
        val growth = (currentUncertainty - currentBaseUncertainty).coerceAtLeast(0.0)
        // Invert the sqrt so confidence is inverse to uncertainty
        val normalised = (growth / growthRange).coerceIn(0.0, 1.0)
        return (1.0 - (normalised * normalised)).coerceIn(0.0, 1.0)
    }

    fun getStepsSinceReset(): Int {
        return stepCount
    }

    fun setUncertainty(uncertaintyMeters: Double) {
        currentUncertainty = uncertaintyMeters.coerceIn(0.0, maxUncertainty)
    }

    fun reduceUncertainty(reductionMetres: Double) {
        if (reductionMetres <= 0.0) return
        currentUncertainty = (currentUncertainty - reductionMetres)
            .coerceIn(initialUncertainty, maxUncertainty)
    }

    fun isMaxUncertainty(): Boolean {
        return currentUncertainty >= maxUncertainty
    }

    fun getRecommendedAction(): String {
        return when {
            currentUncertainty < 2.0 -> "High confidence"
            currentUncertainty < 5.0 -> "Good confidence"
            currentUncertainty < 10.0 -> "Ok confidence - consider override"
            else -> "Low confidence - override recommending"
        }
    }

    override fun toString(): String {
        val confidence = getConfidenceLevel() * 100
        return "Uncertainty: $currentUncertainty ($confidence confidence, $stepCount steps)"
    }
}