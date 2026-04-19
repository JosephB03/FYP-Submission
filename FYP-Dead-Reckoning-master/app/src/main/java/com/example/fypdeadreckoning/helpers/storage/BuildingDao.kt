package com.example.fypdeadreckoning.helpers.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BuildingDao {
    @Query("SELECT * FROM Building")
    fun getAllBuildings(): List<Building>

    @Insert
    suspend fun insert(building: Building)
}