package com.example.fypdeadreckoning.ui.home

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.activity.MainActivity
import com.example.fypdeadreckoning.databinding.FragmentHomeBinding
import com.example.fypdeadreckoning.helpers.extra.AnalyticsManager
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import com.example.fypdeadreckoning.helpers.location.GeofenceStatus
import com.example.fypdeadreckoning.ui.views.MapView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.graphics.toColorInt

class HomeFragment : Fragment() {
    private var TAG = "HomeFragment"
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by activityViewModels()

    private var isInsideGeofence = false
    private var isMapLoaded = false

    // TODO make dynamic
    private val WGB_BUILDING_ID = 1
    private val WGB_FLOOR_NUMBER = 0

    // TODO pull from uncertainty model
    private val MAX_UNCERTAINTY = 15.0f

    // Stats panel views
    private var uncertaintyValueText: TextView? = null
    private var confidenceText: TextView? = null
    private var uncertaintyProgressBar: LinearProgressIndicator? = null

    // Analytics
    private var analyticsOverlay: View? = null
    private var analyticsToggleButton: ImageButton? = null

    // Pins
    private var pinModeButton: ImageButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("DefaultLocale", "SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind stats panel views
        uncertaintyValueText = binding.root.findViewById(R.id.uncertaintyValueText)
        confidenceText = binding.root.findViewById(R.id.confidenceText)
        uncertaintyProgressBar = binding.root.findViewById(R.id.uncertaintyProgressBar)

        // Set up MapView callback
        binding.mapView.setOnLocationUpdateListener(object : MapView.OnLocationUpdateListener {
            override fun onLocationManuallyUpdated(latitude: Float, longitude: Float) {
                // Update ViewModel
                viewModel.updateUserLocation(latitude, longitude)
                (activity as? MainActivity)?.resetUncertainty()
                Toast.makeText(requireContext(), "Location updated manually", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Uncertainty reset to ${viewModel.uncertaintyRadius.value}m")
            }
        })

        // Collect display settings
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SettingsManager.showUncertaintyRadius.collect { show ->
                        binding.mapView.showUncertaintyRadius = show
                        binding.mapView.invalidate()
                    }
                }
                launch {
                    SettingsManager.userDotColor.collect { color ->
                        binding.mapView.setUserDotColor(color)
                    }
                }
                launch {
                    SettingsManager.uncertaintyCircleColor.collect { color ->
                        binding.mapView.setUncertaintyCircleColor(color)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GeofenceStatus.isInside.collect { isInside ->
                    val wasInside = isInsideGeofence
                    isInsideGeofence = isInside

                    if (isInside && !wasInside) {
                        binding.root.findViewById<View>(R.id.loadMapButton)?.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            "You're near the building - tap Load Map to begin",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (!isInside && wasInside) {
                        binding.root.findViewById<View>(R.id.loadMapButton)?.isEnabled = false
                        updateMapVisibility()
                    }

                    Log.d("UI", "Geofence status updated: $isInside")
                }
            }
        }

        // Analytics overlay setup
        analyticsOverlay = binding.root.findViewById(R.id.analyticsOverlay)
        analyticsToggleButton = binding.root.findViewById(R.id.analyticsToggleButton)

        analyticsToggleButton?.setOnClickListener {
            val analyticsEnabled = !AnalyticsManager.enabled.value
            AnalyticsManager.setEnabled(analyticsEnabled)
        }

        // Check analyticsEnabled to display/hide
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AnalyticsManager.enabled.collect { enabled ->
                        analyticsOverlay?.visibility = if (enabled) View.VISIBLE else View.GONE
                        // Tint the button to indicate state
                        analyticsToggleButton?.alpha = if (enabled) 1.0f else 0.5f
                    }
                }

                // Observe the analytics snapshot and bind to TextViews
                launch {
                    AnalyticsManager.snapshot.collect { snap ->
                        if (!AnalyticsManager.enabled.value) return@collect

                        binding.root.findViewById<TextView>(R.id.analyticsDRPosition)?.text =
                            String.format("DR: %.6f, %.6f", snap.drPosition.lat, snap.drPosition.lon)

                        binding.root.findViewById<TextView>(R.id.analyticsHeading)?.text =
                            String.format(
                                "Heading: M%.0f° G%.0f° M+G%.0f°",
                                Math.toDegrees(snap.magHeading),
                                Math.toDegrees(snap.gyroHeading),
                                Math.toDegrees(snap.combinedHeading)
                            )

                        binding.root.findViewById<TextView>(R.id.analyticsSteps)?.text =
                            "Steps: ${snap.totalSteps}"

                        binding.root.findViewById<TextView>(R.id.analyticsGPSConfidence)?.text =
                            String.format(
                                "GPS: %.2f (Acc%.2f Cn0%.2f San%.2f Stal%.2f)",
                                snap.gpsScore,
                                snap.gpsReportedAccuracyScore,
                                snap.gpsCn0Score,
                                snap.gpsSanityScore,
                                snap.gpsStalenessScore
                            )

                        binding.root.findViewById<TextView>(R.id.analyticsSatellites)?.text =
                            String.format("Sats: %d  Cn0: %.1f", snap.satelliteCount, snap.averageCn0DbHz)

                        binding.root.findViewById<TextView>(R.id.analyticsPeers)?.text =
                            "Peers: ${snap.peerCount}"

                        binding.root.findViewById<TextView>(R.id.analyticsPeersDetail)?.text =
                            " trust=${snap.trustedPeerCount}" +
                                    " far=${snap.farPeerCount}"

                        binding.root.findViewById<TextView>(R.id.analyticsPowerMode)?.text =
                            "Mode: ${snap.powerMode.name}"
                    }
                }
            }
        }

        pinModeButton = binding.root.findViewById(R.id.pinButton)
        pinModeButton?.setOnClickListener { viewModel.togglePinMode()}

        binding.mapView.setOnPinActionListener(object : MapView.OnPinActionListener {
            override fun onPinPlaced(pixelX: Float, pixelY: Float) {
                viewModel.addPin(pixelX, pixelY)
            }
            override fun onPinTapped(pinId: Long) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Pin")
                    .setMessage("Delete this pin?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deletePin(pinId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        viewModel.loadBuildingData(WGB_BUILDING_ID, WGB_FLOOR_NUMBER)

        setupButtons()
        observeViewModel()
    }

    private fun setupButtons() {
        binding.root.findViewById<View>(R.id.loadMapButton)?.setOnClickListener {
            // TODO uncomment this to ignore geofencing
            // loadMap()
            if (isInsideGeofence) {
                loadMap()
            } else {
                Toast.makeText(
                    requireContext(),
                    "You must be inside the WGB to load the map",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Start tracking button
        binding.root.findViewById<View>(R.id.startTrackingButton)?.setOnClickListener {
            if (isMapLoaded) {
                (activity as? MainActivity)?.startTracking()
                viewModel.setTrackingActive(true)
                Toast.makeText(requireContext(), "Tracking started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Please load the map first", Toast.LENGTH_SHORT).show()
            }
        }

        // Stop tracking button
        binding.root.findViewById<View>(R.id.stopTrackingButton)?.setOnClickListener {
            (activity as? MainActivity)?.stopTracking()
            viewModel.setTrackingActive(false)
            Toast.makeText(requireContext(), "Tracking stopped", Toast.LENGTH_SHORT).show()
        }

        // Go up button
        binding.root.findViewById<View>(R.id.upButton)?.setOnClickListener {
            viewModel.goUpAFloor()
            Toast.makeText(requireContext(), "Went up a floor", Toast.LENGTH_SHORT).show()
        }

        // Go down button
        binding.root.findViewById<View>(R.id.downButton)?.setOnClickListener {
            viewModel.goDownAFloor()
            Toast.makeText(requireContext(), "Went down a floor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMapVisibility() {
        // Show or hide map based on geofence status
        if (!isInsideGeofence && isMapLoaded) {
            // Hide map if user leaves geofence
            binding.mapView.visibility = View.GONE
            isMapLoaded = false
            Toast.makeText(requireContext(), "Map hidden - you left the building", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMap() {
        if (isMapLoaded) {
            Toast.makeText(requireContext(), "Map already loaded", Toast.LENGTH_SHORT).show()
            return
        }

        val floor = viewModel.getCurrentFloor()
        val building = viewModel.getCurrentBuilding()
        if (floor == null || building == null) {
            Log.e(TAG, "Floor data not available yet, retry after loading")
            viewModel.loadBuildingData(WGB_BUILDING_ID, WGB_FLOOR_NUMBER)
            Toast.makeText(requireContext(), "Loading building data, try again", Toast.LENGTH_SHORT).show()
            return
        }
            setupMap(
                floor.localImagePath,
                floor.mapMetresX,
                floor.mapMetresY,
                floor.topLeftLat,
                floor.topLeftLon,
                floor.topRightLat,
                floor.topRightLon,
                floor.bottomLeftLat,
                floor.bottomLeftLon,
                floor.bottomRightLat,
                floor.bottomRightLon,
                building.startingPositionPixelX,
                building.startingPositionPixelY
            )
            binding.mapView.visibility = View.VISIBLE
            isMapLoaded = true
            Toast.makeText(requireContext(), "Map loaded successfully", Toast.LENGTH_SHORT).show()
    }

    private fun setupMap(
        localImagePath: String,
        mapMetresX: Double,
        mapMetresY: Double,
        topLeftLat: Double,
        topLeftLon: Double,
        topRightLat: Double,
        topRightLon: Double,
        bottomLeftLat: Double,
        bottomLeftLon: Double,
        bottomRightLat: Double,
        bottomRightLon: Double,
        startingPositionPixelX: Float,
        startingPositionPixelY: Float
    ) {
        Log.d(TAG, "------ SETUP MAP ------")
        Log.d(TAG, "TopLeft: ($topLeftLat, $topLeftLon)")
        Log.d(TAG, "TopRight: ($topRightLat, $topRightLon)")
        Log.d(TAG, "BottomLeft: ($bottomLeftLat, $bottomLeftLon)")
        Log.d(TAG, "BottomRight: ($bottomRightLat, $bottomRightLon)")
        // Image may be too large, may need a lower resolution version
        var mapBitmap = loadBitmapFromPath(localImagePath)
        if (mapBitmap == null) {
            mapBitmap = loadBitmapFromAssets(localImagePath)
        }

        if (mapBitmap == null) {
            Toast.makeText(requireContext(), "Failed to load map image", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.loadMapBitmap(mapBitmap)

        // Set map metadata with all 4 corners for rotation support
        binding.mapView.setMapMetadataFromCorners(
            topLeftLat = topLeftLat.toFloat(),
            topLeftLon = topLeftLon.toFloat(),
            topRightLat = topRightLat.toFloat(),
            topRightLon = topRightLon.toFloat(),
            bottomLeftLat = bottomLeftLat.toFloat(),
            bottomLeftLon = bottomLeftLon.toFloat(),
            bottomRightLat = bottomRightLat.toFloat(),
            bottomRightLon = bottomRightLon.toFloat()
        )

        binding.mapView.setMapDimensions(
            widthMetres = mapMetresX.toFloat(),
            heightMetres = mapMetresY.toFloat()
        )

        Log.d(TAG, "Map setup done with rotation support")
    }

    private fun loadBitmapFromPath(path: String): android.graphics.Bitmap? {
        return try {
            val file = File(path)
            if (file.exists()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(path, options)

                val (height, width) = resources.displayMetrics.run { heightPixels to widthPixels }
                options.inSampleSize = calculateInSampleSize(options, width, height)
                options.inJustDecodeBounds = false

                BitmapFactory.decodeFile(path, options)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error loading bitmap from path: ${e.message}")
            null
        }
    }

    private fun loadBitmapFromAssets(assetName: String): android.graphics.Bitmap? {
        // Load from assets
        return try {
            val assetManager = requireContext().assets

            // Calculate sample size
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            assetManager.open(assetName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Calculate inSampleSize
            val (height, width) = resources.displayMetrics.run { heightPixels to widthPixels }
            options.inSampleSize = calculateInSampleSize(options, width, height)

            // Decode with inSampleSize set
            options.inJustDecodeBounds = false
            assetManager.open(assetName).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error loading bitmap from assets: ${e.message}")
            null
        }

    }

    // Mirrors UncertaintyModel.getRecommendedAction()
    // TODO Pull from UncertaintyModel
    private fun confidenceLabelFromRadius(radiusMetres: Float): String {
        return when {
            radiusMetres <= 2.0f  -> "High confidence"
            radiusMetres <= 5.0f  -> "Good confidence"
            radiusMetres <= 10.0f -> "OK - consider override"
            else                 -> "Low - override recommended"
        }
    }

    private fun observeViewModel() {
        viewModel.mapBitmap.observe(viewLifecycleOwner) { bitmap ->
            binding.mapView.setMapBitmap(bitmap)

            val existingLocation = viewModel.getCurrentUserLocation()
            if (existingLocation != null) {
                // If user has a position, redraw just redraw them on the new floor's map
                binding.mapView.updateUserLocation(existingLocation.x, existingLocation.y)
            } else {
                // First load of map, use starting position
                val building = viewModel.getCurrentBuilding()
                if (building != null) {
                    val latLon = binding.mapView.setInitialUserPositionFromPixels(
                        building.startingPositionPixelX,
                        building.startingPositionPixelY
                    )
                    viewModel.updateUserLocation(
                        latLon.x,
                        latLon.y
                    )
                }
            }
        }

        // Fires automatically when location changes
        viewModel.userLocation.observe(viewLifecycleOwner) { location ->
            // Draw to map immediately
            binding.mapView.updateUserLocation(location.x, location.y)
        }

        viewModel.gpsLocation.observe(viewLifecycleOwner) { location ->
            Log.d("HomeFragment", "GPS location: (${location.x}, ${location.y})")
        }

        viewModel.uncertaintyRadius.observe(viewLifecycleOwner) { radiusMetres ->
            binding.mapView.setUncertaintyRadius(radiusMetres)

            // Update stats panel
            uncertaintyValueText?.text = String.format("%.2f m", radiusMetres)
            confidenceText?.text = confidenceLabelFromRadius(radiusMetres)

            val progressPercent = ((radiusMetres / MAX_UNCERTAINTY) * 100)
                .coerceIn(0f, 100f)
                .toInt()
            // Set progress (0–100)
            uncertaintyProgressBar?.setProgressCompat(progressPercent, true)  // true = animate

            // Change colour based on severity
            // TODO pull colors from colors.xml
            val color = when {
                radiusMetres <= 5f  -> "#4CAF50".toColorInt()      // green
                radiusMetres <= 10f -> "#FFA726".toColorInt()      // amber
                else                -> "#FF5555".toColorInt()      // red
            }
            uncertaintyProgressBar?.setIndicatorColor(color)

            if (radiusMetres > 0f) {
                Log.d("HomeFragment", "Uncertainty radius: ${String.format("%.2f", radiusMetres)}m")
            }
        }

        // Observe floor data to setup map
        viewModel.currentFloor.observe(viewLifecycleOwner) { floor ->
            val building = viewModel.getCurrentBuilding()
            if (building != null && isMapLoaded) {
                setupMap(
                    floor.localImagePath,
                    floor.mapMetresX,
                    floor.mapMetresY,
                    floor.topLeftLat,
                    floor.topLeftLon,
                    floor.topRightLat,
                    floor.topRightLon,
                    floor.bottomLeftLat,
                    floor.bottomLeftLon,
                    floor.bottomRightLat,
                    floor.bottomRightLon,
                    building.startingPositionPixelX,
                    building.startingPositionPixelY
                )
            }

        }

        viewModel.currentBuilding.observe(viewLifecycleOwner) { building ->
            Log.d("HomeFragment", "Loaded building: ${building.name}")
        }

        // Observe tracking state
        viewModel.isTrackingActive.observe(viewLifecycleOwner) { isActive ->
            // Update UI based on tracking state
            binding.root.findViewById<View>(R.id.startTrackingButton)?.isEnabled = !isActive
            binding.root.findViewById<View>(R.id.stopTrackingButton)?.isEnabled = isActive
        }

        // Observe geofence status to toggle views
        viewModel.isInsideGeofence.observe(viewLifecycleOwner) { isInside ->
            // If they leave the geofence, force the map to close
            if (!isInside && isMapLoaded) {
                binding.mapView.visibility = View.GONE
                isMapLoaded = false
            }
        }

        // Observe pin mode
        viewModel.pinModeActive.observe(viewLifecycleOwner) { isActive ->
            binding.mapView.pinModeEnabled = isActive
            pinModeButton?.alpha = if (isActive) 1.0f else 0.5f
            val pinMessage = if (isActive) "Pin mode active" else "Pin mode deactivated"
            Toast.makeText(requireContext(), pinMessage, Toast.LENGTH_SHORT).show()
        }

        viewModel.pins.observe(viewLifecycleOwner) { pinList ->
            binding.mapView.setPins(pinList)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // From Android documentation https://developer.android.com/topic/performance/graphics/load-bitmap
    // Downscales image to fit into memory
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
