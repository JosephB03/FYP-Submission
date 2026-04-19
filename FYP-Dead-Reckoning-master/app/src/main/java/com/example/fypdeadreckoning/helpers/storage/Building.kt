package com.example.fypdeadreckoning.helpers.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Building(
    @PrimaryKey val buildingId: Int,
    val name: String,
    val address: String?,
    val totalFloors: Int,
    val geofenceX: Double,
    val geofenceY: Double,
    val geofenceRadius: Double,
    val startingPositionPixelX: Float,
    val startingPositionPixelY: Float,
    val startingPositionImage: String?
)