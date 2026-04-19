package com.example.fypdeadreckoning.helpers.orientation

import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import kotlin.math.atan2
import kotlin.math.pow

class GyroscopeEulerOrientation {
    private var C: Array<FloatArray?>

    init {
        C = ExtraFunctions.IDENTITY_MATRIX.clone()
    }

    constructor (initialOrientation: Array<FloatArray?>) {
        C = initialOrientation.clone()
    }

    fun getOrientationMatrix(gyroValues: FloatArray): Array<FloatArray?> {
        val wX = gyroValues[1]
        val wY = gyroValues[0]
        val wZ = -gyroValues[2]

        val A = calcMatrixA(wX, wY, wZ)

        calcMatrixC(A)

        return C.clone()
    }

    fun getDirection(gyroValue: FloatArray): Float {
        getOrientationMatrix(gyroValue)
        return (atan2(C[1]!![0].toDouble(), C[0]!![0].toDouble())).toFloat()
    }

    private fun calcMatrixA(wX: Float, wY: Float, wZ: Float): Array<FloatArray?> {
        var a: Array<FloatArray?>?

        // Skew symmetric matrix
        var b: Array<FloatArray?>? = calcMatrixB(wX, wY, wZ)
        var bSquare: Array<FloatArray?>? = ExtraFunctions.multiplyMatrices(b, b)

        val norm = ExtraFunctions.calcNorm(wX, wY, wZ)
        val bScaleFactor = calcBScaleFactor(norm)
        val bSquareScaleFactor = calcBSqScaleFactor(norm)

        b = ExtraFunctions.scaleMatrix(b, bScaleFactor)
        bSquare = ExtraFunctions.scaleMatrix(bSquare, bSquareScaleFactor)

        a = ExtraFunctions.addMatrices(b, bSquare)
        a = ExtraFunctions.addMatrices(a, ExtraFunctions.IDENTITY_MATRIX)

        return a
    }

    private fun calcMatrixB(wX: Float, wY: Float, wZ: Float): Array<FloatArray?> {
        return (arrayOf(
            floatArrayOf(0f, wZ, -wY),
            floatArrayOf(-wZ, 0f, wX),
            floatArrayOf(wY, -wX, 0f)
        ))
    }

    //(sin σ) / σ ≈ 1 - (σ^2 / 3!) + (σ^4 / 5!)
    private fun calcBScaleFactor(sigma: Float): Float {
        //return (float) ((1 - Math.cos(sigma)) / Math.pow(sigma, 2));
        val sigmaSqOverThreeFactorial: Float =
            sigma.toDouble().pow(2.0).toFloat() / ExtraFunctions.factorial(3)
        val sigmaToForthOverFiveFactorial: Float =
            sigma.toDouble().pow(4.0).toFloat() / ExtraFunctions.factorial(5)
        return (1.0 - sigmaSqOverThreeFactorial + sigmaToForthOverFiveFactorial).toFloat()
    }

    //(1 - cos σ) / σ^2 ≈ (1/2) - (σ^2 / 4!) + (σ^4 / 6!)
    private fun calcBSqScaleFactor(sigma: Float): Float {
        //return (float) (Math.sin(sigma) / sigma);
        val sigmaSqOverFourFactorial: Float =
            sigma.toDouble().pow(2.0).toFloat() / ExtraFunctions.factorial(4)
        val sigmaToForthOverSixFactorial: Float =
            sigma.toDouble().pow(4.0).toFloat() / ExtraFunctions.factorial(6)
        return (0.5 - sigmaSqOverFourFactorial + sigmaToForthOverSixFactorial).toFloat()
    }

    //calculate the new DCM depending on the change in orientation
    private fun calcMatrixC(A: Array<FloatArray?>?) {
        C = ExtraFunctions.multiplyMatrices(C, A)
    }
}