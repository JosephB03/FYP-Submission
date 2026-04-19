package com.example.fypdeadreckoning.helpers.orientation

import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.ejml.simple.*
class MagneticFieldOrientation {

    val mRotationNED: SimpleMatrix = SimpleMatrix(
        arrayOf<DoubleArray?>(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0)
        )
    )

    fun getOrientationMatrix(gValues: FloatArray, mValues: FloatArray): Array<FloatArray?> {

        val mInitDouble: Array<DoubleArray?> =
            ExtraFunctions.vectorToMatrix(ExtraFunctions.floatVectorToDoubleVector(mValues))

        var mMInit: SimpleMatrix? = SimpleMatrix(mInitDouble)
        var mGValues = SimpleMatrix(
            ExtraFunctions.vectorToMatrix(
                ExtraFunctions.floatVectorToDoubleVector(gValues)
            )
        )

        mMInit = mRotationNED.mult(mMInit)
        mGValues = mRotationNED.mult(mGValues)

        val gMValues: Array<FloatArray?> =
            ExtraFunctions.denseMatrixToArray(mGValues.getMatrix())

        // Calculate roll and pitch from gravity
        val gR = atan2(gMValues[1]!![0].toDouble(), gMValues[2]!![0].toDouble())
        val gP = atan2(
            -gMValues[0]!![0].toDouble(),
            gMValues[1]!![0] * sin(gR) + gMValues[2]!![0] * cos(gR)
        )

        // Create the rotation matrix representing the roll and pitch
        val rRP = arrayOf<DoubleArray?>(
            doubleArrayOf(cos(gP), sin(gP) * sin(gR), sin(gP) * cos(gR)),
            doubleArrayOf(0.0, cos(gR), -sin(gR)),
            doubleArrayOf(-sin(gP), cos(gP) * sin(gR), cos(gP) * cos(gR))
        )

        // Convert arrays to matrices to allow for multiplication
        val mRRP = SimpleMatrix(rRP)

        // Rotate magnetic field values in accordance to gravity readings
        val mMRP: SimpleMatrix = mRRP.mult(mMInit)

        //cCalc heading (rads) from rotated magnetic field
        val h = atan2(-mMRP.get(1), mMRP.get(0)) -1.67 * Math.PI / 180.0

        // Rotation matrix representing heading, is negative when moving East of North
        val rH = arrayOf<DoubleArray?>(
            doubleArrayOf(cos(h), -sin(h), 0.0),
            doubleArrayOf(sin(h), cos(h), 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )

        // Calc complete (initial) rotation matrix by multiplying roll/pitch matrix with heading matrix
        val mRH = SimpleMatrix(rH)
        val mR: SimpleMatrix = mRRP.mult(mRH)

        return ExtraFunctions.denseMatrixToArray(mR.getMatrix())
    }

    fun getDirection(gValues: FloatArray, mValues: FloatArray): Float {
        val orientationMatrix = getOrientationMatrix(gValues, mValues)
        return atan2(
            orientationMatrix[1]!![0].toDouble(),
            orientationMatrix[0]!![0].toDouble()
        ).toFloat()
    }
}