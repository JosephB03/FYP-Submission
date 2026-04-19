package com.example.fypdeadreckoning.activity.debuggers

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.extra.ExtraFunctions
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeDeltaOrientation
import com.example.fypdeadreckoning.helpers.orientation.GyroscopeEulerOrientation
import com.example.fypdeadreckoning.helpers.orientation.MagneticFieldOrientation

class DirectionTestActivity : Activity(), SensorEventListener {
    private val PERMISSION_REQUEST_CODE = 101
    val EULER_GYROSCOPE_SENSITIVITY: Float = 0.0025f
    val GYROSCOPE_SENSITIVITY: Float = 0f

    // TODO remove one of these after debugging
    private var gyroUOrientation1: GyroscopeEulerOrientation? = null
    private var gyroUOrientation2: GyroscopeEulerOrientation? = null

    private var gyroCIntegration: GyroscopeDeltaOrientation? = null
    private var gyroUIntegration: GyroscopeDeltaOrientation? = null

    private var sensorGyroC: Sensor? = null //gyroscope Android-calibrated
    private var sensorGyroU: Sensor? = null //gyroscope uncalibrated (manually calibrated)
    private var sensorMagU: Sensor? = null //magnetic field uncalibrated
    private var sensorGravity: Sensor? = null
    private var sensorManager: SensorManager? = null

    private var textDirectionCosine: TextView? = null
    private var textGyroU: TextView? = null
    private var textGyroC: TextView? = null
    private var textMagneticField: TextView? = null
    private var textComplimentaryFilter: TextView? = null

    private var startButton: Button? = null
    private var stopButton: Button? = null

    private var gyroDirection = 0.0
    private var gyroDirectionU = 0.0

    //todo: remove two of these after debugging
    private var eulerDirection1: Float = 0.0F
    private var eulerDirection2: Float = 0.0F
    private var eulerDirection3: Float = 0.0F

    private var magDirection: Float = 0.0F

    private lateinit var gravityValues: FloatArray
    private lateinit var magValues: FloatArray

    private var isRunning = false

    private var initialDirection: Float = 0.0F

    var gravityCount: Int = 0
    var magCount: Int = 0
    lateinit var sumGravityValues: FloatArray
    lateinit var sumMagValues: FloatArray


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direction)

        // Initialise variables
        gravityValues = FloatArray(3)
        magValues = FloatArray(3)
        gravityCount = 0.also { magCount = 0 }
        sumGravityValues = FloatArray(3)
        sumMagValues = FloatArray(3)

        gyroCIntegration = GyroscopeDeltaOrientation(
            GYROSCOPE_SENSITIVITY
        )
        gyroUIntegration = GyroscopeDeltaOrientation(
            EULER_GYROSCOPE_SENSITIVITY
        )

        gyroDirection = 0.0
        gyroDirectionU = 0.0
        eulerDirection1 = 0.0F
        eulerDirection2 = 0.0F
        eulerDirection3 = 0.0F
        magDirection = 0.0F

        textDirectionCosine = findViewById<View?>(R.id.textDirectionCosine) as TextView
        textGyroU = findViewById<View?>(R.id.textGyroscopeU) as TextView
        textGyroC = findViewById<View?>(R.id.textGyroscope) as TextView
        textMagneticField = findViewById<View?>(R.id.textMagneticField) as TextView
        textComplimentaryFilter = findViewById<View?>(R.id.textRotationVector) as TextView

        startButton = findViewById<View?>(R.id.buttonStart) as Button
        stopButton = findViewById<View?>(R.id.buttonStop) as Button

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorGyroU = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        sensorGyroC = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorMagU = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorGravity = sensorManager!!.getDefaultSensor(Sensor.TYPE_GRAVITY)

        startButton!!.setOnClickListener {
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGyroU,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGyroC,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorMagU,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGravity,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val magneticFieldOrientation = MagneticFieldOrientation()
            val initialOrientation: Array<FloatArray?>? =
                magneticFieldOrientation.getOrientationMatrix(
                    gravityValues,
                    magValues
                )

            initialDirection =
                magneticFieldOrientation.getDirection(gravityValues, magValues)

            gyroUOrientation1 = GyroscopeEulerOrientation(ExtraFunctions.IDENTITY_MATRIX)
            gyroUOrientation2 = GyroscopeEulerOrientation(initialOrientation!!)

            startButton!!.isEnabled = false
            stopButton!!.isEnabled = true

            isRunning = true
        }

        // Deactivates the sensors
        stopButton!!.setOnClickListener {
            sensorManager!!.unregisterListener(this@DirectionTestActivity, sensorGravity)
            sensorManager!!.unregisterListener(this@DirectionTestActivity, sensorMagU)
            sensorManager!!.unregisterListener(this@DirectionTestActivity, sensorGyroC)
            sensorManager!!.unregisterListener(this@DirectionTestActivity, sensorGyroU)
            startButton!!.isEnabled = true
            stopButton!!.isEnabled = false

        }

    }

    override fun onStop() {
        super.onStop()
        sensorManager!!.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isRunning) {
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGyroU,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGyroC,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorMagU,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            sensorManager!!.registerListener(
                this@DirectionTestActivity,
                sensorGravity,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            startButton!!.isEnabled = false
            stopButton!!.isEnabled = true
        } else {
            startButton!!.isEnabled = true
            stopButton!!.isEnabled = false
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Only necessary for interface
    }


    override fun onSensorChanged(event: SensorEvent) {
        val magneticFieldOrientation = MagneticFieldOrientation()
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            gravityValues = event.values

            gravityCount++
            for (i in 0..2) sumGravityValues[i] += gravityValues[i]
        }

        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magValues = event.values

            magCount++
            for (i in 0..2) sumMagValues[i] += magValues[i]
        }

        if (isRunning) {
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val deltaOrientation: FloatArray =
                    gyroCIntegration!!.calcDeltaOrientation(event.timestamp, event.values)!!
                gyroDirection += deltaOrientation[2].toDouble()

            } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
                val deltaOrientation: FloatArray =
                    gyroUIntegration!!.calcDeltaOrientation(event.timestamp, event.values)!!

                //rotation about z
                gyroDirectionU += deltaOrientation[2].toDouble()
                ExtraFunctions.radsToDegrees(gyroDirectionU)

                //direction cosine matrix
                eulerDirection1 = gyroUOrientation1!!.getDirection(deltaOrientation) //identity
                eulerDirection2 =
                    gyroUOrientation2!!.getDirection(deltaOrientation) //initial orientation
                eulerDirection3 = ExtraFunctions.polarAdd(
                    initialDirection,
                    eulerDirection1
                ) //initial direction + identity direction

                textDirectionCosine!!.text = eulerDirection3.toString()

                (findViewById<View?>(R.id.textView) as TextView).text = "Non-Initialized DCM"
                textGyroU!!.text = eulerDirection1.toString()

                (findViewById<View?>(R.id.textView6) as TextView).text = "Initialized DCM"
                textGyroC!!.text = eulerDirection2.toString()
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                magDirection =
                    magneticFieldOrientation.getDirection(gravityValues, magValues)
                textMagneticField!!.text = magDirection.toString()
            }

            val compassDirection: Float = ExtraFunctions.calcCompassDirection(magDirection, eulerDirection3)
            textComplimentaryFilter!!.text = compassDirection.toString()
        }
    }
}