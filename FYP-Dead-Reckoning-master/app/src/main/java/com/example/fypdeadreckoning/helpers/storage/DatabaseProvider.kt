package com.example.fypdeadreckoning.helpers.storage

import android.content.Context
import androidx.room.Room

// https://www.geeksforgeeks.org/kotlin/how-to-use-singleton-pattern-for-room-database-in-android/

// A Database singleton. This is the centre point of all database access
object DatabaseProvider {
    @Volatile
    private var INSTANCE: BuildingDatabase? = null

    fun getDatabase(context: Context): BuildingDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                BuildingDatabase::class.java,
                "building_database"
            ).build()
            INSTANCE = instance
            instance
        }
    }
}