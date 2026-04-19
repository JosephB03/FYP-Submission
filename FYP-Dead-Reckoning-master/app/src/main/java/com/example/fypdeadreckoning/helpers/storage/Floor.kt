package com.example.fypdeadreckoning.helpers.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Floor(
    @PrimaryKey val floorId: Int,
    val buildingId: Int, // Foreign key
    val floorNumber: Int, // 0 for Ground, -1 for Basement, etc
    val floorName: String?, // e.g., "Ground Floor", "Lobby"
    // val imageUrl: String, // Original server URL
    val localImagePath: String, // Cached local path
    // Dimensions of the map in real metres
    val mapMetresX: Double,
    val mapMetresY: Double,
    // User records the opposite corners' lat/lon in the map
    val topLeftLat: Double,
    val topLeftLon: Double,
    val topRightLat: Double,
    val topRightLon: Double,
    val bottomLeftLat: Double,
    val bottomLeftLon: Double,
    val bottomRightLat: Double,
    val bottomRightLon: Double,
)