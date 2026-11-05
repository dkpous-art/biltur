
package dk.example.biltur

data class Walk(
    val date: String,
    val distanceKm: Double,
    val durationSeconds: Long,

    // Nye oplysninger om turen
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val startAddress: String = "",
    val endAddress: String = "",
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null
) {
    val averageKmh: Double
        get() = if (durationSeconds > 0) {
            distanceKm / (durationSeconds / 3600.0)
        } else {
            0.0
        }
}
