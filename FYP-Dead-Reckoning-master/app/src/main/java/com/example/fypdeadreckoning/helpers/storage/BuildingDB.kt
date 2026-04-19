package com.example.fypdeadreckoning.helpers.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Building::class, Floor::class], version = 1)
abstract class BuildingDatabase : RoomDatabase() {
    abstract fun buildingDao(): BuildingDao
    abstract fun floorDao(): FloorDao
}