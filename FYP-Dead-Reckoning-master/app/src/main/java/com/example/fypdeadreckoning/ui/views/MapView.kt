package com.example.fypdeadreckoning.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.toColorInt
import com.example.fypdeadreckoning.helpers.dataClasses.Pin
import kotlin.math.sqrt

/*
* The View that contains the map and draws user location
*/
class MapView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : View(context, attributes, defaultStyleAttribute) {
    private var TAG = "MapView"
    private var mapBitmap: Bitmap? = null   // Holds the image

    // Drawing/brush descriptions

    private val locationPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true  // smooth edges
    }
    private val locationStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val uncertaintyCirclePaint = Paint().apply {
        color = "#4DFF0000".toColorInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val uncertaintyStrokePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val pinPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val pinStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // Uncertainty radius in metres
    private var uncertaintyRadiusMetres: Float = 0f

    // Display settings
    var showUncertaintyRadius: Boolean = true

    // Track if initial position has been set
    private var hasInitialPosition: Boolean = false

    // User location in real-world coordinates
    private var userLatitude: Float = 0f
    private var userLongitude: Float = 0f

    // Map corners in real-world coordinates
    private var topLeftLat: Float = 0f
    private var topLeftLon: Float = 0f
    private var topRightLat: Float = 0f
    private var topRightLon: Float = 0f
    private var bottomLeftLat: Float = 0f
    private var bottomLeftLon: Float = 0f
    private var bottomRightLat: Float = 0f
    private var bottomRightLon: Float = 0f

    // Affine transformation matrices for coordinate conversion
    private val latLonToPixelMatrix = Matrix()  // Transforms lat/lon -> pixel
    private val pixelToLatLonMatrix = Matrix()  // Transforms pixel -> lat/lon

    // Map dimensions in pixels
    private var mapWidthPixels: Float = 0f
    private var mapHeightPixels: Float = 0f

    // Real-world map dimensions in metres (for uncertainty scaling)
    private var mapWidthMetres: Float = 0f
    private var mapHeightMetres: Float = 0f

    private val locationRadius = 15f

    // Zoom and pan variables
    private val displayMatrix = Matrix()       // holds transformation being applied to the bitmap
    private val savedMatrix = Matrix()         // copy of matrix, saved when gesture begins

    // Allowed zoom levels
    private var minScale = 0.5f
    private var maxScale = 4f

    // Active pointer tracking
    private var activePointerId = INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Gesture detectors
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // Asynchronous location updating
    private var locationUpdateListener: OnLocationUpdateListener? = null

    // Pins
    private var pins: List<Pin> = emptyList()
    var pinModeEnabled: Boolean = false
    private var pinActionListener: OnPinActionListener? = null
    private val pinRadius = 12f
    private val pinHitRadius = 30f // screen dp for tap detection

    // Callback interface for location updates
    interface OnLocationUpdateListener {
        fun onLocationManuallyUpdated(latitude: Float, longitude: Float)
    }

    // Callback interface for pin actions
    interface OnPinActionListener {
        fun onPinPlaced(pixelX: Float, pixelY: Float)
        fun onPinTapped(pinId: Long)
    }

    fun setOnLocationUpdateListener(listener: OnLocationUpdateListener) {
        this.locationUpdateListener = listener
    }

    fun setOnPinActionListener(listener: OnPinActionListener) {
        this.pinActionListener = listener
    }

    fun setPins(pins: List<Pin>) {
        this.pins = pins
        invalidate()
    }

    fun setMapBitmap(bitmap: Bitmap) {
        mapBitmap = bitmap
        mapWidthPixels = bitmap.width.toFloat()
        mapHeightPixels = bitmap.height.toFloat()
        calculateTransformationMatrices()
        initializeMatrix()
        invalidate()    // Redraw
    }

    fun setMapMetadataFromCorners(
        topLeftLat: Float,
        topLeftLon: Float,
        topRightLat: Float,
        topRightLon: Float,
        bottomLeftLat: Float,
        bottomLeftLon: Float,
        bottomRightLat: Float,
        bottomRightLon: Float
    ) {
        this.topLeftLat = topLeftLat
        this.topLeftLon = topLeftLon
        this.topRightLat = topRightLat
        this.topRightLon = topRightLon
        this.bottomLeftLat = bottomLeftLat
        this.bottomLeftLon = bottomLeftLon
        this.bottomRightLat = bottomRightLat
        this.bottomRightLon = bottomRightLon
        calculateTransformationMatrices()
        // Calculate pixels per metre
        mapBitmap?.let { bitmap ->
            this.mapWidthPixels = bitmap.width.toFloat()
            this.mapHeightPixels = bitmap.height.toFloat()
        }
    }

    /**
     * Calculate affine transformation between lat/lon and pixels
     * This supports rotated buildings by creating a proper mapping between the two coordinate systems
     */
    private fun calculateTransformationMatrices() {
        Log.d(TAG, "------ COORDINATE MAPPING ------")
        Log.d(TAG, "Top-Left: ($topLeftLat, $topLeftLon) -> (0, 0)")
        Log.d(TAG, "Top-Right: ($topRightLat, $topRightLon) -> ($mapWidthPixels, 0)")
        Log.d(TAG, "Bottom-Left: ($bottomLeftLat, $bottomLeftLon) -> (0, $mapHeightPixels)")
        Log.d(TAG, "Bottom-Right: ($bottomRightLat, $bottomRightLon)")

        // Use 3 real points to calculate the affine transformation
        // Arbitrary choice of points
        val srcPoints = floatArrayOf(
            topLeftLon, topLeftLat,
            topRightLon, topRightLat,
            bottomLeftLon, bottomLeftLat
        )

        // Destination points (the map points)
        val dstPoints = floatArrayOf(
            0f, 0f,                  // Point 0: top left
            mapWidthPixels, 0f,      // Point 1: top right
            0f, mapHeightPixels      // Point 2: bottom left
        )

        // Calculate lat/lon to pixels
        if (!latLonToPixelMatrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 3)) {
            Log.e(TAG, "Failed to calculate lat/lon to pixel transformation matrix")
            return
        }

        // Calculate pixels to lat/lon
        if (!latLonToPixelMatrix.invert(pixelToLatLonMatrix)) {
            Log.e(TAG, "Failed to calculate inverse transformation matrix")
            return
        }

        Log.d(TAG, "Transformation matrices calculated successfully")
    }

    fun setMapDimensions(widthMetres: Float, heightMetres: Float) {
        this.mapWidthMetres = widthMetres
        this.mapHeightMetres = heightMetres
    }

    fun setUncertaintyRadius(radiusMetres: Float) {
        this.uncertaintyRadiusMetres = radiusMetres
        invalidate()  // Redraw to show updated circle
    }

    fun updateUserLocation(latitude: Float, longitude: Float) {
        // Clamp latitude to map bounds
        val minLat = minOf(topLeftLat, topRightLat, bottomLeftLat, bottomRightLat)
        val maxLat = maxOf(topLeftLat, topRightLat, bottomLeftLat, bottomRightLat)
        userLatitude = latitude.coerceIn(minLat, maxLat)

        // Clamp longitude to map bounds
        val minLon = minOf(topLeftLon, topRightLon, bottomLeftLon, bottomRightLon)
        val maxLon = maxOf(topLeftLon, topRightLon, bottomLeftLon, bottomRightLon)
        userLongitude = longitude.coerceIn(minLon, maxLon)

        // Log if location was clamped
        if (userLatitude != latitude || userLongitude != longitude) {
            Log.w(TAG, "Location clamped from ($latitude, $longitude) to ($userLatitude, $userLongitude)")
        } else {
            Log.d(TAG, "User real location updated to: ($latitude, $longitude)")
        }
        // Find pixel location for debugging
        val pixelLocation = latLonToMapPixels(userLatitude, userLongitude)
        val pixelX = pixelLocation.x
        val pixelY = pixelLocation.y
        Log.d(TAG, "User pixel location updated to: ($pixelX, $pixelY)")
        postInvalidate()  // Redraws if called in background
    }

    // Convert latitude/longitude to map pixel coordinates using affine transformation
    private fun latLonToMapPixels(latitude: Float, longitude: Float): PointF {
        val srcPoint = floatArrayOf(longitude, latitude)
        val dstPoint = floatArrayOf(0f, 0f)

        latLonToPixelMatrix.mapPoints(dstPoint, srcPoint)

        return PointF(dstPoint[0], dstPoint[1])
    }

    // Convert pixel coordinates to latitude/longitude using inverse affine transformation
    private fun pixelsToLatLon(pixelX: Float, pixelY: Float): PointF {
        val srcPoint = floatArrayOf(pixelX, pixelY)
        val dstPoint = floatArrayOf(0f, 0f)

        pixelToLatLonMatrix.mapPoints(dstPoint, srcPoint)

        return PointF(dstPoint[1], dstPoint[0])
    }

    private fun mapPixelsToScreen(pixelX: Float, pixelY: Float): PointF {
        val point = floatArrayOf(pixelX, pixelY)
        displayMatrix.mapPoints(point)
        return PointF(point[0], point[1])
    }

    private fun metresToMapPixels(metres: Float): Float {
        // Calculate pixels per metre based on map dimensions
        val pixelsPerMetre = mapWidthPixels / mapWidthMetres
        return metres * pixelsPerMetre
    }

    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    fun setInitialUserPosition(latitude: Float, longitude: Float) {
        updateUserLocation(latitude, longitude)
        Log.d(TAG, "Initial position set to: ($latitude, $longitude)")
        postInvalidate()
    }

    fun setInitialUserPositionFromPixels(pixelX: Float, pixelY: Float): PointF {
        val latLon = pixelsToLatLon(pixelX, pixelY)
        updateUserLocation(latLon.x, latLon.y)
        Log.d(TAG, "Initial position set from pixels ($pixelX, $pixelY) -> lat/lon (${latLon.x}, ${latLon.y})")
        postInvalidate()
        return latLon
    }

    // Finds a scale to fit the entire bitmap in screen view
    private fun initializeMatrix() {
        mapBitmap?.let { bitmap ->
            // Fit bitmap to screen initially
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()

            // Keeps aspect ratio of bitmap
            val scale = minOf(
                viewWidth / bitmapWidth,
                viewHeight / bitmapHeight
            )

            minScale = scale * 0.5f

            displayMatrix.reset()
            displayMatrix.postScale(scale, scale)

            // Centre the bitmap
            val scaledWidth = bitmapWidth * scale
            val scaledHeight = bitmapHeight * scale
            val dx = (viewWidth - scaledWidth) / 2
            val dy = (viewHeight - scaledHeight) / 2
            displayMatrix.postTranslate(dx, dy)
        }
    }

    // Called when view size changes
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initializeMatrix()
    }

    // Called when screen touched
    // https://developer.android.com/develop/ui/views/touch-and-input/gestures/scale#kotlin
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the ScaleGestureDetector inspect all events
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                event.actionIndex.also { pointerIndex ->
                    // Save the matrix state when touch starts
                    savedMatrix.set(displayMatrix)

                    // Remember where we start for dragging
                    lastTouchX = event.getX(pointerIndex)
                    lastTouchY = event.getY(pointerIndex)
                }

                // Save the ID of this pointer for dragging
                activePointerId = event.getPointerId(0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                event.actionIndex.also { pointerIndex ->
                    // Second finger down - prepare for multi-touch
                    lastTouchX = event.getX(pointerIndex)
                    lastTouchY = event.getY(pointerIndex)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Find the index of the active pointer and fetch its position
                event.findPointerIndex(activePointerId).let { pointerIndex ->
                    if (pointerIndex == -1) return@let

                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)

                    // Only drag if we're not scaling
                    if (!scaleGestureDetector.isInProgress) {
                        // Calculate the distance moved
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY

                        // Apply translation
                        displayMatrix.postTranslate(dx, dy)
                        constrainMatrix()

                        invalidate()
                    }

                    // Remember this touch position for the next move event
                    lastTouchX = x
                    lastTouchY = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = INVALID_POINTER_ID
            }

            MotionEvent.ACTION_POINTER_UP -> {
                event.actionIndex.also { pointerIndex ->
                    event.getPointerId(pointerIndex)
                        .takeIf { it == activePointerId }
                        ?.run {
                            // The active pointer going up. Choose a new active pointer
                            val newPointerIndex = if (pointerIndex == 0) 1 else 0
                            lastTouchX = event.getX(newPointerIndex)
                            lastTouchY = event.getY(newPointerIndex)
                            activePointerId = event.getPointerId(newPointerIndex)
                        }
                }
            }
        }

        return true
    }

    // Helper function
    // Obtained via Claude.
    private fun constrainMatrix() {
        // Extracts the current scale from the matrix
        val values = FloatArray(9)
        displayMatrix.getValues(values)

        var scale = values[Matrix.MSCALE_X]
        scale = scale.coerceIn(minScale, maxScale)

        // Update scale if constrained
        if (scale != values[Matrix.MSCALE_X]) {
            displayMatrix.setScale(scale, scale)
        }

        // Recalculate bitmap dimensions
        mapBitmap?.let { bitmap ->
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale

            var dx = values[Matrix.MTRANS_X]
            var dy = values[Matrix.MTRANS_Y]

            // If bitmap wider/narrower than view
            dx = if (scaledWidth > width) {
                dx.coerceIn(width - scaledWidth, 0f)
            } else {
                dx.coerceIn(0f, width - scaledWidth)
            }
            // If bitmap taller/shorter than view
            dy = if (scaledHeight > height) {
                dy.coerceIn(height - scaledHeight, 0f)
            } else {
                dy.coerceIn(0f, height - scaledHeight)
            }

            // Rebuild matrix
            displayMatrix.setValues(floatArrayOf(
                scale, values[Matrix.MSKEW_X], dx,
                values[Matrix.MSKEW_Y], scale, dy,
                values[Matrix.MPERSP_0], values[Matrix.MPERSP_1], values[Matrix.MPERSP_2]
            ))
        }
    }

    // Listener for a scaling gesture
    // https://developer.android.com/reference/android/view/ScaleGestureDetector
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Save the matrix state when scaling begins
            savedMatrix.set(displayMatrix)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val values = FloatArray(9)
            displayMatrix.getValues(values)
            var scale = values[Matrix.MSCALE_X]

            // Apply the scale factor
            scale *= detector.scaleFactor
            scale = scale.coerceIn(minScale, maxScale)

            // Apply new scale while maintaining focus point
            displayMatrix.set(savedMatrix)
            displayMatrix.postScale(
                scale / values[Matrix.MSCALE_X],
                scale / values[Matrix.MSCALE_X],
                detector.focusX,
                detector.focusY
            )

            constrainMatrix()
            invalidate()

            // Update saved matrix for continuous scaling
            savedMatrix.set(displayMatrix)
            return true
        }
    }

    // Update location based on long press
    private fun handleLongPress(lat: Float, lon: Float) {
        locationUpdateListener?.onLocationManuallyUpdated(lat, lon)
        Log.d(TAG, "Long press - requesting location update to ($lat, $lon)")
    }

    // Deal with pins based on single press.
    private fun handleSinglePress(screenX: Float, screenY: Float, bitmapX: Float, bitmapY: Float) {
        // Hit-test existing pins in screen space
        for (pin in pins) {
            val pinScreen = mapPixelsToScreen(pin.pixelX, pin.pixelY)
            val dx = screenX - pinScreen.x
            val dy = screenY - pinScreen.y
            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val allowedHitRadius = pinHitRadius * resources.displayMetrics.density
            if (distance <= allowedHitRadius) {
                Log.d(TAG, "Pin ${pin.id} tapped for deletion")
                pinActionListener?.onPinTapped(pin.id)
                return
            }
        }
        // No existing pin, place new pin
        Log.d(TAG, "Placing new pin at bitmap ($bitmapX, $bitmapY)")
        pinActionListener?.onPinPlaced(bitmapX, bitmapY)
    }

    // Override user location
    // https://developer.android.com/reference/android/view/GestureDetector
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(event: MotionEvent) {
            Log.d(TAG, "Long Press Detected")
            // Must reverse the matrix transformation to get coordinates on the BitMap rather than
            // the View
            val screenX = event.x
            val screenY = event.y

            // Create an array to hold our coordinates

            /**
             * Since the Bitmap is moved around the screen using matrix transformations,
             * we just need to do an inverse of that original matrix to get the coordinates
             * in the original coordinate state.
             **/
            val coords = floatArrayOf(screenX, screenY)
            val inverseMatrix = Matrix()

            // Calculate the inverse of the current map transformation
            if (displayMatrix.invert(inverseMatrix)) {
                // Map screen coordinates to actual bitmap pixel coordinates
                inverseMatrix.mapPoints(coords)
                val bitmapX = coords[0]
                val bitmapY = coords[1]

                // Check if the touch is within the bitmap's pixels
                if (bitmapX in 0f..mapWidthPixels && bitmapY in 0f..mapHeightPixels) {
                    Log.d(TAG, "Long press on Bitmap at Pixel: ($bitmapX, $bitmapY)")
                    val latLon = pixelsToLatLon(bitmapX, bitmapY)
                    handleLongPress(latLon.x, latLon.y)
                }
            }
        }

        // Add pin / open delete dialogue for existing pin
        // Only active when pin mode is enabled
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!pinModeEnabled) return false

            Log.d(TAG, "Single Tap Detected (pin mode)")

            val screenX = event.x
            val screenY = event.y

            val coords = floatArrayOf(screenX, screenY)
            val inverseMatrix = Matrix()

            if (displayMatrix.invert(inverseMatrix)) {
                inverseMatrix.mapPoints(coords)
                val bitmapX = coords[0]
                val bitmapY = coords[1]

                // Check if the touch is within the bitmap's pixels
                if (bitmapX in 0f..mapWidthPixels && bitmapY in 0f..mapHeightPixels) {
                    Log.d(TAG, "Single tap on Bitmap at Pixel: ($bitmapX, $bitmapY)")
                    handleSinglePress(screenX, screenY, bitmapX, bitmapY)
                }
            }

            return true
        }
    }

    // Drawing function
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the map bitmap
        mapBitmap?.let { canvas.drawBitmap(it, displayMatrix, null) }

        // Draw pins
        for (pin in pins) {
            val screenPos = mapPixelsToScreen(pin.pixelX, pin.pixelY)
            canvas.drawCircle(screenPos.x, screenPos.y, pinRadius, pinPaint)
            canvas.drawCircle(screenPos.x, screenPos.y, pinRadius, pinStrokePaint)
        }

        // Convert user location to screen coordinates and draw
        if (userLatitude != 0f || userLongitude != 0f) {
            // Convert lat/lon to map pixels
            val mapPixels = latLonToMapPixels(userLatitude, userLongitude)
            // Convert map pixels to screen coordinates
            val screenPos = mapPixelsToScreen(mapPixels.x, mapPixels.y)

            // Draw uncertainty circle if radius > 0 and enabled
            if (showUncertaintyRadius && uncertaintyRadiusMetres > 0f) {
                // Convert uncertainty radius from metres to map pixels
                val uncertaintyRadiusPixels = metresToMapPixels(uncertaintyRadiusMetres)

                // Scale by current zoom level
                val currentScale = getCurrentScale()
                val screenRadiusPixels = uncertaintyRadiusPixels * currentScale

                // Draw filled circle
                canvas.drawCircle(
                    screenPos.x,
                    screenPos.y,
                    screenRadiusPixels,
                    uncertaintyCirclePaint
                )

                // Draw circle outline
                canvas.drawCircle(
                    screenPos.x,
                    screenPos.y,
                    screenRadiusPixels,
                    uncertaintyStrokePaint
                )
            }

            // Draw user location dot on top of uncertainty circle
            // Draw filled circle
            canvas.drawCircle(screenPos.x, screenPos.y, locationRadius, locationPaint)
            // Draw outline
            canvas.drawCircle(screenPos.x, screenPos.y, locationRadius, locationStrokePaint)
        }
    }

    fun setUserDotColor(color: Int) {
        locationPaint.color = color
        invalidate()
    }

    fun setUncertaintyCircleColor(color: Int) {
        // Fill with 30% alpha
        uncertaintyCirclePaint.color = (color and 0x00FFFFFF) or 0x4D000000
        uncertaintyStrokePaint.color = color
        invalidate()
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
    }
}