package com.example.fypdeadreckoning.helpers.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

// Inspired by https://developer.android.com/develop/sensors-and-location/location/geofencing
// and https://github.com/android/platform-samples
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "GeofenceBroadcastReceiver"

    // Valid states
    companion object {
        const val GEOFENCE_STATUS_CHANGED = "com.example.fypdeadreckoning.GEOFENCE_STATUS_CHANGED"
        const val EXTRA_GEOFENCE_ID = "geofence_id"
        const val EXTRA_TRANSITION_TYPE = "transition_type"
        const val EXTRA_IS_INSIDE = "is_inside"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e(TAG, "onReceive: $errorMessage")
            return
        }

        // Handle the transition
        val geofenceTransition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences

        // Catch nulls
        if (triggeringGeofences == null || triggeringGeofences.isEmpty()) {
            Log.w(TAG, "No triggering geofences")
            return
        }

        Log.d(TAG, "Transition: ${getTransitionString(geofenceTransition)}, Count: ${triggeringGeofences.size}")

        // Handle each triggered geofence
        triggeringGeofences.forEach { geofence ->
            val geofenceId = geofence.requestId

            when (geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    Log.d(TAG, "Entered geofence: $geofenceId")
                    GeofenceStatus.entered(geofenceId)
                }
                // You are already in a Geofence
                Geofence.GEOFENCE_TRANSITION_DWELL -> {
                    Log.d(TAG, "Dwelling in geofence: $geofenceId")
                    GeofenceStatus.entered(geofenceId)
                }
                // You exit a Geofence
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    Log.d(TAG, "Exited geofence: $geofenceId")
                    GeofenceStatus.exited(geofenceId)
                }
                else -> {
                    Log.w(TAG, "Unknown transition type: $geofenceTransition")
                }
            }
        }

    }

    private fun getTransitionString(transitionType: Int): String {
        return when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN"
        }
    }

}