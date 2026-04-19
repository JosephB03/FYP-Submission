package com.example.fypdeadreckoning.helpers.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 2D point in local coordinate system (metres)
data class Point2D(val x: Double, val y: Double) {
    override fun toString(): String = "($x, $y)"
}

// Helper class for converting between lat/lon and local XY coordinates
object LocalCoordinateSystem {

    /**
     * Convert from lat/lon to local XY coordinates (metres from reference point)
     * East = +X, North = +Y
     */
    fun latLonToLocalXY(
        refLat: Double, refLon: Double,
        lat: Double, lon: Double
    ): Point2D {
        val R = 6371000.0
        val lat1 = Math.toRadians(refLat)
        val lat2 = Math.toRadians(lat)
        val dLat = Math.toRadians(lat - refLat)
        val dLon = Math.toRadians(lon - refLon)

        val x = dLon * R * cos((lat1 + lat2) / 2)
        val y = dLat * R

        return Point2D(x, y)
    }

    // Euclidean distance between two points
    fun distance(p1: Point2D, p2: Point2D): Double {
        return sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
    }

    // Bearing from p1 to p2 in degrees (0 = North, 90 = East)
    fun bearing(p1: Point2D, p2: Point2D): Double {
        val angle = atan2(p2.x - p1.x, p2.y - p1.y)
        return (Math.toDegrees(angle) + 360) % 360
    }

    // Move in metres along a compass heading
    fun move(from: Point2D, distanceMetres: Double, headingDegrees: Double): Point2D {
        val headingRad = Math.toRadians(headingDegrees)
        return Point2D(
            from.x + distanceMetres * sin(headingRad),
            from.y + distanceMetres * cos(headingRad)
        )
    }
}