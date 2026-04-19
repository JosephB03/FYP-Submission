package com.example.fypdeadreckoning.helpers.bias

import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import org.ejml.dense.row.CommonOps_DDRM
import org.ejml.simple.SimpleMatrix
import kotlin.math.pow
import kotlin.math.sqrt

class MagneticFieldBias {
    /*
        From Pg. 14 of "Calibrating an eCompass in the Presence of Hard and Soft-iron Interference, Rev. 3"

        Example:
        float[][] data = {{167.4f, -242.4f, 91.7f},
                          {140.3f, -221.9f, 86.8f},
                          {152.4f, -230.4f, -0.6f},
                          {180.3f, -270.6f, 71.0f},
                          {190.9f, -212.4f, 62.7f},
                          {192.9f, -242.4f, 17.1f}};

        The above data will produce the following results:

        XTX
            139883.508  -196159.625  52207.910  831.300
            -196159.625  279419.645  -73880.837  -1177.700
            52207.910  -73880.837  24915.780  311.600
            831.300  -1177.700  311.600   5.000

        XTY
            74583526.000
            -105754734.000
            28544634.836
            444218.930

        XTX_Inverse
            0.001   0.000  -0.000  -0.078
            0.000   0.001   0.000   0.103
            -0.000   0.000   0.000   0.004
            -0.078   0.103   0.004  37.372

        B
            311.717
            -478.286
            91.525
            -81341.651
        */
    private var XTX: Array<DoubleArray?> //X-Transposed * X is a 4x4 matrix
    private var XTY: Array<DoubleArray?> //X_Transposed * Y is a 4x1 vector (stored in a matrix for easier manipulation)

    var firstRun: Boolean = false

    var reserveX: Float = 0f
    var reserveY: Float = 0f
    var reserveZ: Float = 0f

    init {
        firstRun = true
        XTX = Array<DoubleArray?>(4) { DoubleArray(4) }
        XTY = Array<DoubleArray?>(4) { DoubleArray(1) }
    }

    fun calcBias(rawMagneticValues: FloatArray) {
        val x: Float
        val y: Float
        val z: Float

        //TODO: figure out if reserve values are needed
        //the bias is calculated by n-l values instead of n values (according to the paper)
        //so the following if keeps the latest set of values n reserve
        if (firstRun) {
            reserveX = rawMagneticValues[0]
            reserveY = rawMagneticValues[1]
            reserveZ = rawMagneticValues[2]

            firstRun = false
            return
        } else {
            x = reserveX
            y = reserveY
            z = reserveZ

            reserveX = rawMagneticValues[0]
            reserveY = rawMagneticValues[1]
            reserveZ = rawMagneticValues[2]
        }

        //calculating magnetic field bias
        XTX = arrayOf(
            doubleArrayOf(
                XTX[0]!![0] + x * x,
                XTX[0]!![1] + x * y,
                XTX[0]!![2] + x * z,
                XTX[0]!![3] + x
            ),
            doubleArrayOf(
                XTX[1]!![0] + x * y,
                XTX[1]!![1] + y * y,
                XTX[1]!![2] + y * z,
                XTX[1]!![3] + y
            ),
            doubleArrayOf(
                XTX[2]!![0] + x * z,
                XTX[2]!![1] + y * z,
                XTX[2]!![2] + z * z,
                XTX[2]!![3] + z
            ),
            doubleArrayOf(XTX[3]!![0] + x, XTX[3]!![1] + y, XTX[3]!![2] + z, XTX[3]!![3] + 1)
        )

        XTY = arrayOf(
            doubleArrayOf(XTY[0]!![0] + x * (x * x + y * y + z * z)),
            doubleArrayOf(XTY[1]!![0] + y * (x * x + y * y + z * z)),
            doubleArrayOf(XTY[2]!![0] + z * (x * x + y * y + z * z)),
            doubleArrayOf(XTY[3]!![0] + (x * x + y * y + z * z))
        )
    }

    fun getBias(): FloatArray? {
        val M_XTX = SimpleMatrix(XTX)
        val M_XTY = SimpleMatrix(XTY)

        val M_XTX_Inverse = SimpleMatrix(Array<DoubleArray?>(4) { DoubleArray(4) })
        CommonOps_DDRM.invert(M_XTX.getMatrix(), M_XTX_Inverse.getMatrix())

        val M_B: SimpleMatrix = M_XTX_Inverse.mult(M_XTY)

        val b: Array<FloatArray?> = ExtraFunctions.denseMatrixToArray(M_B.getMatrix())

        val xBias = b[0]!![0] / 2.0f
        val yBias = b[1]!![0] / 2.0f
        val zBias = b[2]!![0] / 2.0f
        val magneticFieldStrength = sqrt(
            b[3]!![0] + xBias.toDouble().pow(2.0) + yBias.toDouble().pow(2.0) + zBias.toDouble()
                .pow(2.0)
        ).toFloat()

        return floatArrayOf(xBias, yBias, zBias, magneticFieldStrength)
    }

}