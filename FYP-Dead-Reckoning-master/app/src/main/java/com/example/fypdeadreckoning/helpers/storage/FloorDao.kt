package com.example.fypdeadreckoning.helpers.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FloorDao {
    @Query("SELECT * FROM Floor")
    fun getAllFloors(): List<Floor>

    @Query("SELECT * FROM Floor WHERE buildingId = :buildingId ORDER BY floorNumber")
    fun getFloorsForBuilding(buildingId: Int): List<Floor>

    @Query("SELECT * FROM Floor WHERE buildingId = :buildingId AND floorNumber = :floorNumber LIMIT 1")
    suspend fun getFloorByNumber(buildingId: Int, floorNumber: Int): Floor?

    @Insert
    suspend fun insert(floor: Floor)
}