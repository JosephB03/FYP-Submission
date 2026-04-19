package com.example.fypdeadreckoning.helpers.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// Extension property - accessible from any Context
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calibration_settings")

// Define all calibration keys in one place
object CalibrationKeys {
    // Step calibration
    val STRIDE_LENGTH_KEY = doublePreferencesKey("stride_length")
    val STEP_COUNTER_SENSITIVITY_KEY = doublePreferencesKey("step_counter_sensitivity")
}