package com.example.fypdeadreckoning.helpers.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// A singleton to store the state of our Geofences. Necessary to bridge the GeofenceBroadcastReceiver
// and the GeofenceManager
// TODO integrate for multiple geofences
object GeofenceStatus {
    private val _insideGeofences = MutableStateFlow<Set<String>>(emptySet())

    // True if inside a geofence
    val isInside: StateFlow<Boolean> = MutableStateFlow(false)

    fun entered(geofenceId: String) {
        _insideGeofences.value += geofenceId
        (isInside as MutableStateFlow).value = _insideGeofences.value.isNotEmpty()
    }

    fun exited(geofenceId: String) {
        _insideGeofences.value -= geofenceId
        (isInside as MutableStateFlow).value = _insideGeofences.value.isNotEmpty()
    }
}