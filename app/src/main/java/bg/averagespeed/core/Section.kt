package bg.averagespeed.core

/**
 * Отсечка за средна скорост между две камери.
 * [lengthM] е официалната/измерената дължина; ако липсва, се оценява от GPS пътя.
 * [approximate] = координатите са приблизителни (затова и по-голям радиус на засичане).
 */
data class Section(
    val id: String,
    val road: String,
    val fromName: String,
    val toName: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val limitKmh: Int,
    val lengthM: Double? = null,
    val bidirectional: Boolean = true,
    val approximate: Boolean = false,
    val userDefined: Boolean = false,
    /** Радиус на засичане около камерите; по подразбиране според точността на координатите. */
    val radiusM: Double? = null,
) {
    val triggerRadiusM: Double get() = radiusM ?: if (approximate) 1200.0 else 200.0

    fun directions(): List<DirectedSection> =
        if (bidirectional) listOf(DirectedSection(this, false), DirectedSection(this, true))
        else listOf(DirectedSection(this, false))
}

/** Отсечка в конкретна посока на движение. */
data class DirectedSection(val section: Section, val reversed: Boolean) {
    val key: String = section.id + if (reversed) "#r" else "#f"
    val entryLat get() = if (reversed) section.endLat else section.startLat
    val entryLon get() = if (reversed) section.endLon else section.startLon
    val exitLat get() = if (reversed) section.startLat else section.endLat
    val exitLon get() = if (reversed) section.startLon else section.endLon
    val title: String
        get() = if (reversed) "${section.toName} → ${section.fromName}" else "${section.fromName} → ${section.toName}"
    val straightM: Double get() = Geo.distanceM(entryLat, entryLon, exitLat, exitLon)
    val radiusM: Double get() = section.triggerRadiusM

    /** Дължина за изчисленията: официалната или оценка по права линия с коефициент за завоите. */
    val lengthM: Double get() = section.lengthM ?: (straightM * 1.1)
    val lengthIsEstimate: Boolean get() = section.lengthM == null
}
