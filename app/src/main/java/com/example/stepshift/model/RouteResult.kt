package com.example.stepshift.model

/**
 * Parsed route geometry and metadata from OSRM or fallback generator.
 */
data class RouteResult(
    val points: List<GeoPoint>,
    val totalDistanceMeters: Double,
    val estimatedDurationSeconds: Double,
    val startAddress: String = "起点",
    val endAddress: String = "终点",
    val isFallbackDirect: Boolean = false
) {
    fun formatDistanceKm(): String {
        return "%.2f km".format(totalDistanceMeters / 1000.0)
    }

    fun formatDuration(): String {
        val mins = (estimatedDurationSeconds / 60).toInt()
        val hours = mins / 60
        val remainMins = mins % 60
        return if (hours > 0) {
            "${hours}小时${remainMins}分钟"
        } else {
            "${remainMins}分钟"
        }
    }
}
