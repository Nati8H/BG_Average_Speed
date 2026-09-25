package bg.averagespeed.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Една GPS точка. [timeMs] е монотонно време (напр. elapsedRealtime), не часовник. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Разстояние по дъга на голям кръг (haversine) в метри. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: Fix, b: Fix) = distanceM(a.lat, a.lon, b.lat, b.lon)
}
