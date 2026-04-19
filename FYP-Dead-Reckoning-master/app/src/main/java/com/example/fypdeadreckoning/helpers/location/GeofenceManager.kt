package com.example.fypdeadreckoning.helpers.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fypdeadreckoning.helpers.extra.PowerModeManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GeofenceManager(private val context: Context, private val scope: CoroutineScope) {
    private val TAG = "GeofenceManager"
    private val client = LocationServices.getGeofencingClient(context)
    val geofenceList = mutableMapOf<String, Geofence>()


    companion object {
        const val GEOFENCE_INTENT_ACTION = "com.example.fypdeadreckoning.GEOFENCE_EVENT"
        const val GEOFENCE_REQUEST_CODE = 100
        var DWELL_LOITERING_DELAY_MS = 10_000  // 10 seconds
    }

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        intent.action = GEOFENCE_INTENT_ACTION

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        PendingIntent.getBroadcast(
            context,
            GEOFENCE_REQUEST_CODE,
            intent,
            flags
        )
    }

    // Set Dwell delay
    fun observePowerMode() {
        scope.launch {
            PowerModeManager.mode.collect { mode ->
                DWELL_LOITERING_DELAY_MS = PowerModeManager.Geofence.dwellIntervalMs(mode)
            }
        }
    }

    fun addGeofence(
        key: String,
        latitude: Double,
        longitude: Double,
        radiusInMeters: Float = 100.0f,
    ) {
        val geofence = Geofence.Builder()
            .setRequestId(key)
            .setCircularRegion(latitude, longitude, radiusInMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setLoiteringDelay(DWELL_LOITERING_DELAY_MS)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_DWELL or
                        Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()
        geofenceList[key] = geofence
        Log.d(TAG, "Geofence added: $key at ($latitude, $longitude)")
    }

    fun removeGeofence(key: String) {
        geofenceList.remove(key)
        Log.d(TAG, "Geofence removed: $key")
    }

    @SuppressLint("MissingPermission")
    fun registerGeofence() {
        if (geofenceList.isEmpty()) {
            Log.e(TAG, "No geofences to register")
            return
        }

        val geofenceRequest = GeofencingRequest.Builder().apply {
            setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            addGeofences(geofenceList.values.toList())
        }.build()

        client.addGeofences(geofenceRequest, geofencePendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "registerGeofence: SUCCESS")
            }.addOnFailureListener { exception ->
                Log.d(TAG, "registerGeofence: Failure\n$exception")
            }
    }

    fun deregisterGeofence() {
        client.removeGeofences(geofencePendingIntent)
            .addOnSuccessListener {
                geofenceList.clear()
                Log.d(TAG, "All geofences deregistered successfully")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to deregister geofences", exception)
            }
    }

    fun getAllGeofenceKeys(): Set<String> {
        return geofenceList.keys
    }

}