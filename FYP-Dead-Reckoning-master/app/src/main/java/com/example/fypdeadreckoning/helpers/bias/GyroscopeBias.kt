package com.example.fypdeadreckoning.helpers.bias

class GyroscopeBias {
    private var runCount = 0
    private var trials = 0
    private var gyroBias: FloatArray

     init {
        runCount = 0
        gyroBias = FloatArray(3)
    }

    constructor(trials: Int) {
        this.trials = trials
    }

    fun calcBias(rawGyroValues: FloatArray): Boolean {
        runCount++

        if (runCount >= trials) return true

        if (runCount == 1) {
            gyroBias[0] = rawGyroValues[0]
            gyroBias[1] = rawGyroValues[1]
            gyroBias[2] = rawGyroValues[2]
            return false
        }

        //averaging bias for the first few hundred data points
        //movingAverage = movingAverage * ((n-1)/n) + newValue * (1/n)
        val n = runCount.toFloat()
        gyroBias[0] = gyroBias[0] * ((n - 1) / n) + rawGyroValues[0] * (1 / n)
        gyroBias[1] = gyroBias[1] * ((n - 1) / n) + rawGyroValues[1] * (1 / n)
        gyroBias[2] = gyroBias[2] * ((n - 1) / n) + rawGyroValues[2] * (1 / n)

        return false
    }

    fun getBias(): FloatArray? {
        return gyroBias.clone()
    }

}