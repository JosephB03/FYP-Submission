package com.example.fypdeadreckoning.helpers.extra

import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import org.ejml.data.DMatrixRMaj
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Holds functions to be reused across app
object ExtraFunctions {
    val IDENTITY_MATRIX: Array<FloatArray?> = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f)
    )

    fun nsToSec(time: Long): Float { return time / 1000000000.0f }

    fun factorial(num: Int): Int {
        var factorial = 1
        for (i in 1..num) {
            factorial *= i
        }
        return factorial
    }

    fun addMatrices(a: Array<FloatArray?>, b: Array<FloatArray?>): Array<FloatArray?> {
        val numRows = a.size
        val numColumns = a[0]!!.size

        val c = Array<FloatArray?>(numRows) { FloatArray(numColumns) }

        for (row in 0..<numRows) for (column in 0..<numColumns) c[row]!![column] =
            a[row]!![column] + b[row]!![column]

        //a[][] + b[][] = c[][]
        return c
    }

    fun multiplyMatrices(a: Array<FloatArray?>?, b: Array<FloatArray?>?): Array<FloatArray?> {
        val aNonNull = a!!
        val bNonNull = b!!

        val numRows: Int = aNonNull.size
        val numCols: Int = bNonNull[0]!!.size
        val numElements: Int = bNonNull.size

        val c = Array<FloatArray?>(numRows) { FloatArray(numCols) }

        for (row in 0..<numRows) {
            for (col in 0..<numCols) {
                for (element in 0..<numElements) {
                    // Assert non-nullability for array access
                    c[row]!![col] += aNonNull[row]!![element] * bNonNull[element]!![col]
                }
            }
        }
        // a[][] * b[][] = c
        return c
    }

    fun scaleMatrix(a: Array<FloatArray?>?, scalar: Float): Array<FloatArray?> {
        val numRows = a!!.size
        val numColumns = a[0]!!.size

        val b = Array<FloatArray?>(numRows) { FloatArray(numColumns) }

        for (row in 0..<numRows) for (column in 0..<numColumns) b[row]!![column] =
            a[row]!![column] * scalar

        //a[][] * c = b[][]
        return b
    }

    fun denseMatrixToArray(matrix: DMatrixRMaj): Array<FloatArray?> {
        val array: Array<FloatArray?>? =
            Array(matrix.getNumRows()) { FloatArray(matrix.getNumCols()) }
        for (row in 0..<matrix.getNumRows()) for (col in 0..<matrix.getNumCols()) array!![row]!![col] =
            matrix.get(row, col).toFloat()
        return array!!
    }

    fun vectorToMatrix(array: DoubleArray): Array<DoubleArray?> {
        return arrayOf(
            doubleArrayOf(array[0]),
            doubleArrayOf(array[1]),
            doubleArrayOf(array[2])
        )
    }

    fun floatVectorToDoubleVector(floatValues: FloatArray): DoubleArray {
        val doubleValues = DoubleArray(floatValues.size)
        for (i in floatValues.indices) doubleValues[i] = floatValues[i].toDouble()
        return doubleValues
    }

    // Calculate norm of 3 dimensional vectors
    fun calcNorm(vararg args: Float): Float {
        var sumSq = 0.0f // Declare as Float
        for (arg in args) {
            // Use simple multiplication for squaring, which is fastest
            sumSq += arg * arg
        }
        // Use the Float version of sqrt and the result is already a Float
        return sqrt(sumSq)
    }

    fun radsToDegrees(rads: Double): Float {
        var degrees = if (rads < 0) (2.0 * Math.PI + rads) else rads
        degrees *= (180.0 / Math.PI)
        return degrees.toFloat()
    }

    fun polarAdd(initHeading: Float, deltaHeading: Float): Float {
        val currHeading = initHeading + deltaHeading

        //convert 0 < h < 2pi or -2pi < h < 0 to -pi/2 < h < pi/2
        return if (currHeading < -Math.PI) ((currHeading % Math.PI) + Math.PI).toFloat()
        else if (currHeading > Math.PI) ((currHeading % Math.PI) + -Math.PI).toFloat()
        else currHeading
    }

    fun calcCompassDirection(magneticDirection: Float, gyroDirection: Float): Float {
        // Normalize both inputs to [0, 2π)
        val magNorm = ((magneticDirection % (2.0 * Math.PI).toFloat()) + (2.0 * Math.PI).toFloat()) % (2.0 * Math.PI).toFloat()
        val gyroNorm = ((gyroDirection % (2.0 * Math.PI).toFloat()) + (2.0 * Math.PI).toFloat()) % (2.0 * Math.PI).toFloat()

        // Complementary filter
        val magWeight = SettingsManager.complementaryFilterRatio.value
        var compassDirection = magWeight * magNorm + (1f - magWeight) * gyroNorm

        // Wrap result to [-π, π]
        if (compassDirection > Math.PI.toFloat()) {
            compassDirection -= (2.0 * Math.PI).toFloat()
        }

        return compassDirection
    }

    // Convert compass direction into bearing
    fun compassToBearing(compassHeading: Float): Float {
        // Convert from -π to π range to 0 to 2π range
        var bearing = if (compassHeading < 0) {
            compassHeading + (2.0 * Math.PI).toFloat()
        } else {
            compassHeading
        }

        // Ensure it's in valid range [0, 2π)
        bearing %= (2.0 * Math.PI).toFloat()

        return bearing
    }

    // Find new location given lat, lon, distance, and bearing
    // Takes in degrees and metres
    fun bearingToLocation(currentLatitude: Double, currentLongitude: Double, distanceTravelled: Double, bearing: Float): LatLon {
        val phi1 = Math.toRadians(currentLatitude)      // in radians
        val lambda1 = Math.toRadians(currentLongitude)     // in radians
        val d = distanceTravelled       // in metres
        val r = 6371000.0               // Earth radius in metres

        val bearingRad = bearing.toDouble()

        val phi2 = asin(
            sin(phi1) * cos(d / r) +
                    cos(phi1) * sin(d / r) * cos(bearingRad)  // ← Use bearingRad
        )

        val lambda2 = lambda1 + atan2(
            sin(bearingRad) * sin(d / r) * cos(phi1),  // ← Use bearingRad
            cos(d / r) - sin(phi1) * sin(phi2)
        )

        val newLatDegrees = Math.toDegrees(phi2)
        val newLonDegrees = Math.toDegrees(lambda2)

        return LatLon(newLatDegrees, newLonDegrees)
    }

    // Finds distance between two points on Earth
    // Takes in degrees
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double,): Double {
        val r = 6371e3 // Earth radius in metres

        // Convert to radians
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        val output = r * c

        return output // Distance in metres
    }

    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        val bearing = Math.toDegrees(atan2(y, x))

        return (bearing + 360) % 360
    }
}