package com.example.fypdeadreckoning.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.fypdeadreckoning.helpers.dataClasses.MapData
import com.example.fypdeadreckoning.helpers.dataClasses.Pin
import com.example.fypdeadreckoning.helpers.storage.DatabaseProvider
import com.example.fypdeadreckoning.helpers.storage.Building
import com.example.fypdeadreckoning.helpers.storage.Floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    // LiveData for user's DR location
    private val _userLocation = MutableLiveData<PointF>()
    val userLocation: LiveData<PointF> = _userLocation

    // LiveData for user's GPS location
    private val _gpsLocation = MutableLiveData<PointF>()
    val gpsLocation: LiveData<PointF> = _gpsLocation

    // LiveData for map data
    private val _mapData = MutableLiveData<MapData>()
    val mapData: LiveData<MapData> = _mapData

    // LiveData for the bitmap
    private val _mapBitmap = MutableLiveData<Bitmap>()
    val mapBitmap: LiveData<Bitmap> = _mapBitmap

    private val _isInsideGeofence = MutableLiveData(false)
    val isInsideGeofence: LiveData<Boolean> = _isInsideGeofence

    private val _uncertaintyRadius = MutableLiveData(0f)
    val uncertaintyRadius: LiveData<Float> = _uncertaintyRadius

    private val _uncertaintyResetEvent = MutableLiveData<Float?>()
    val uncertaintyResetEvent: LiveData<Float?> = _uncertaintyResetEvent

    // Database handling
    private val database = DatabaseProvider.getDatabase(application)
    private val buildingDao = database.buildingDao()
    private val floorDao = database.floorDao()

    private val _currentBuilding = MutableLiveData<Building>()
    val currentBuilding: LiveData<Building> = _currentBuilding

    private val _currentFloor = MutableLiveData<Floor>()
    val currentFloor: LiveData<Floor> = _currentFloor

    // Tracking state
    private val _isTrackingActive = MutableLiveData(false)
    val isTrackingActive: LiveData<Boolean> = _isTrackingActive

    // Map Pins
    private val _pins = MutableLiveData<List<Pin>>(emptyList())
    val pins: LiveData<List<Pin>> = _pins
    private val _pinModeActive = MutableLiveData(false)
    val pinModeActive: LiveData<Boolean> = _pinModeActive
    private var nextPinId = 0L

    fun loadBuildingData(buildingId: Int, floorNumber: Int = 0) {
        // Automatically destroys itself when leaving ViewModel's scope
        viewModelScope.launch(Dispatchers.IO) {
            val building = buildingDao.getAllBuildings().find { it.buildingId == buildingId }
            val floor = floorDao.getFloorsForBuilding(buildingId)
                .find { it.floorNumber == floorNumber }

            building?.let { _currentBuilding.postValue(it) }
            floor?.let { _currentFloor.postValue(it) }
        }
    }

    fun getCurrentUserLocation(): PointF? {
        return _userLocation.value
    }

    fun updateUserLocation(lat: Float, lon: Float) {
        _userLocation.postValue(PointF(lat, lon))
    }

    fun updateGPSLocation(lat: Float, lon: Float) {
        _gpsLocation.postValue(PointF(lat, lon))
    }

    fun updateUncertaintyRadius(radiusMetres: Float) {
        _uncertaintyRadius.postValue(radiusMetres)
    }

    // TODO find a way to standardise this value across app
    fun resetUncertainty(newUncertainty: Float = 5.0f) {
        _uncertaintyResetEvent.postValue(newUncertainty)
    }

    fun setupMap(scale: Float, originX: Double, originY: Double) {
        _mapData.postValue(MapData(scale, originX, originY))
    }

    fun loadMapBitmap(bitmap: Bitmap?) {
        _mapBitmap.postValue(bitmap)
    }

    fun setTrackingActive(isActive: Boolean) {
        _isTrackingActive.postValue(isActive)
    }

    fun setGeofenceStatus(isInside: Boolean) {
        _isInsideGeofence.postValue(isInside)
    }

    fun getCurrentBuilding(): Building? {
        return _currentBuilding.value
    }

    fun getCurrentFloor(): Floor? {
        return _currentFloor.value
    }

    fun goUpAFloor() {
        val currentFloorNum = _currentFloor.value?.floorNumber ?: return
        val buildingId = _currentBuilding.value?.buildingId ?: return

        // Prevent going above total floors
        val maxFloors = _currentBuilding.value?.totalFloors ?: Int.MAX_VALUE
        if (currentFloorNum < maxFloors - 1) { // Floors indexed at 0
            changeFloor(buildingId, currentFloorNum + 1)
        }
    }

    fun goDownAFloor() {
        val currentFloorNum = _currentFloor.value?.floorNumber ?: return
        val buildingId = _currentBuilding.value?.buildingId ?: return

        changeFloor(buildingId, currentFloorNum - 1)
    }

    private fun changeFloor(buildingId: Int, targetFloorNumber: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val nextFloor = floorDao.getFloorByNumber(buildingId, targetFloorNumber)
            nextFloor?.let {
                _currentFloor.postValue(it)
            }
        }
    }

    fun togglePinMode() {
        _pinModeActive.value = _pinModeActive.value != true
    }

    fun addPin(pixelX: Float, pixelY: Float) {
        val current = _pins.value.orEmpty().toMutableList()
        current.add(Pin(id = nextPinId++, pixelX = pixelX, pixelY = pixelY))
        _pins.value = current
    }

    fun deletePin(pinId: Long) {
        val current = _pins.value.orEmpty().toMutableList()
        current.removeAll { it.id == pinId }
        _pins.value = current
    }

    fun clearPins() {
        _pins.value = emptyList()
        nextPinId = 0L
    }
}