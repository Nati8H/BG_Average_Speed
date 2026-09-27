package bg.averagespeed.core

import kotlin.math.max

/** Текущо състояние на измерване в отсечка. */
data class ActiveSection(
    val title: String,
    val road: String,
    val limitKmh: Int?,
    val manual: Boolean,
    val elapsedMs: Long,
    val travelledM: Double,
    /** Дължина на измерваната част от отсечката (null при ръчно измерване). */
    val sectionLengthM: Double?,
    val lengthIsEstimate: Boolean,
    /** Измерването е започнало по средата на отсечката (не от началната камера). */
    val joinedMidway: Boolean = false,
) {
    /** Средна скорост до момента = изминато разстояние / изминало време. */
    val avgKmh: Double?
        get() = if (elapsedMs >= 3_000 && travelledM > 0) travelledM / (elapsedMs / 1000.0) * 3.6 else null

    val remainingM: Double?
        get() = sectionLengthM?.let { max(0.0, it - travelledM) }

    /**
     * Максимална средна скорост за остатъка от отсечката, при която общата средна
     * остава в рамките на ограничението. null ако не може да се изчисли.
     * [Double.POSITIVE_INFINITY] = остатъкът е нула/минимален, няма ограничение;
     * отрицателна стойност = нарушението вече е неизбежно.
     */
    val maxAllowedKmhForRest: Double?
        get() {
            val limit = limitKmh ?: return null
            val length = sectionLengthM ?: return null
            val remaining = remainingM ?: return null
            if (remaining < 1.0) return Double.POSITIVE_INFINITY
            val allowedTotalS = length / (limit / 3.6)
            val allowedRestS = allowedTotalS - elapsedMs / 1000.0
            if (allowedRestS <= 0) return -1.0
            return remaining / allowedRestS * 3.6
        }

    val overLimit: Boolean
        get() {
            val limit = limitKmh ?: return false
            val avg = avgKmh ?: return false
            return avg > limit
        }
}

/** Резултат от приключена отсечка. */
data class SectionResult(
    val title: String,
    val road: String,
    val limitKmh: Int?,
    val lengthM: Double,
    val durationMs: Long,
    val manual: Boolean,
    /** Измерена е само част от отсечката (започнато по средата). */
    val partial: Boolean = false,
) {
    val avgKmh: Double get() = if (durationMs > 0) lengthM / (durationMs / 1000.0) * 3.6 else 0.0
    val overLimit: Boolean get() = limitKmh != null && avgKmh > limitKmh
}

sealed interface TrackerEvent {
    data class Entered(val active: ActiveSection) : TrackerEvent
    data class Finished(val result: SectionResult) : TrackerEvent
    data class Cancelled(val title: String) : TrackerEvent
}

/**
 * Следи GPS точките и автоматично засича влизане/излизане от отсечки.
 *
 * Влизане: колата минава в радиуса около началната камера; моментът на най-близко
 * преминаване се приема за засичане. При излизане от радиуса се проверява, че
 * колата се приближава към крайната камера (правилна посока).
 * Влизане по средата: ако колата е в коридора на отсечката (между двете камери) и за
 * ~250 м се приближава към крайната камера, измерването започва от тази точка.
 * Излизане: най-близкото преминаване покрай крайната камера.
 *
 * Може да има няколко едновременно засечени отсечки (напр. успоредни пътища с обща
 * начална точка при приблизителни координати) – грешните отпадат, когато колата се
 * отдалечи от крайната им точка.
 */
class AverageSpeedTracker(sections: List<Section> = emptyList()) {

    private class Candidate(
        var minDist: Double,
        var minFix: Fix,
        var travelledSinceMin: Double,
        var distToExitAtMin: Double,
    )

    /** Наблюдение за влизане по средата на отсечката. */
    private class MidCandidate(
        val startFix: Fix,
        val startDistToEntry: Double,
        val startDistToExit: Double,
        var travelledM: Double = 0.0,
    )

    private class ExitMin(val minDist: Double, val timeMs: Long, val travelledAtMin: Double)

    private class Active(
        val directed: DirectedSection?,
        val title: String,
        val road: String,
        val limitKmh: Int?,
        var entryTimeMs: Long?,
        var travelledM: Double,
        /** Приблизително колко от отсечката е било преди точката на влизане (при влизане по средата). */
        val offsetM: Double = 0.0,
        val joinedMidway: Boolean = false,
        var exitMin: ExitMin? = null,
        var minDistToExit: Double = Double.MAX_VALUE,
    )

    private var directed: List<DirectedSection> = emptyList()
    private val candidates = HashMap<String, Candidate>()
    private val midCandidates = HashMap<String, MidCandidate>()
    private val recentlyEnded = HashMap<String, Long>()
    private val actives = mutableListOf<Active>()

    var lastFix: Fix? = null
        private set
    var lastResult: SectionResult? = null
        private set

    /** Посока на движение в градуси (0 = север) или null, ако още не е известна. */
    var headingDeg: Double? = null
        private set
    private var headingAnchor: Fix? = null

    init {
        setSections(sections)
    }

    fun setSections(sections: List<Section>) {
        directed = sections.flatMap { it.directions() }
        candidates.clear()
        midCandidates.clear()
        actives.removeAll { it.directed != null }
    }

    /** Нулира текущите измервания и последната точка (при спиране на следенето). */
    fun reset() {
        actives.clear()
        candidates.clear()
        midCandidates.clear()
        recentlyEnded.clear()
        lastFix = null
        headingDeg = null
        headingAnchor = null
    }

    val isActive: Boolean get() = actives.isNotEmpty()

    fun onFix(fix: Fix): List<TrackerEvent> {
        val events = mutableListOf<TrackerEvent>()
        if (fix.accuracyM != null && fix.accuracyM > MAX_ACCURACY_M) return events

        val prev = lastFix
        val stationary = fix.speedMps != null && fix.speedMps < STATIONARY_MPS
        val step = if (prev == null || stationary) 0.0 else Geo.distanceM(prev, fix)
        lastFix = fix
        updateHeading(fix)

        for (a in actives.toList()) updateActive(a, fix, step, events)
        updateCandidates(fix, step, events)
        return events
    }

    private fun updateHeading(fix: Fix) {
        val gpsBearing = fix.bearingDeg
        if (gpsBearing != null && (fix.speedMps ?: 0.0) >= HEADING_MIN_SPEED_MPS) {
            headingDeg = gpsBearing
            headingAnchor = fix
            return
        }
        val anchor = headingAnchor
        if (anchor == null) {
            headingAnchor = fix
        } else if (Geo.distanceM(anchor, fix) >= HEADING_MIN_DISTANCE_M) {
            headingDeg = Geo.bearingDeg(anchor.lat, anchor.lon, fix.lat, fix.lon)
            headingAnchor = fix
        }
    }

    private fun updateActive(a: Active, fix: Fix, step: Double, events: MutableList<TrackerEvent>) {
        val entry = a.entryTimeMs
        if (entry == null) { // ръчен старт преди първа GPS точка
            a.entryTimeMs = fix.timeMs
            return
        }
        a.travelledM += step
        val d = a.directed ?: return

        val dExit = Geo.distanceM(fix.lat, fix.lon, d.exitLat, d.exitLon)
        a.minDistToExit = minOf(a.minDistToExit, dExit)
        val exitMin = a.exitMin
        if (dExit <= d.radiusM) {
            if (exitMin == null || dExit < exitMin.minDist) {
                a.exitMin = ExitMin(dExit, fix.timeMs, a.travelledM)
            }
        } else if (exitMin != null) {
            // При приблизителни координати или влизане по средата засеченият участък не съвпада
            // точно с отсечката, затова средната се смята по реално изминатото разстояние.
            val useTravelled = d.lengthIsEstimate || d.section.approximate || a.joinedMidway
            val lengthM = if (useTravelled) exitMin.travelledAtMin else d.lengthM
            val result = SectionResult(
                a.title, a.road, a.limitKmh, lengthM, exitMin.timeMs - entry,
                manual = false, partial = a.joinedMidway,
            )
            lastResult = result
            end(a, fix)
            events += TrackerEvent.Finished(result)
        } else {
            val elapsedMs = fix.timeMs - entry
            val expectedMs = d.lengthM / (d.section.limitKmh / 3.6) * 1000
            val timeout = max(expectedMs * 4, 30 * 60_000.0)
            val movingAway = dExit > a.minDistToExit + MOVING_AWAY_M
            if (movingAway || dExit > d.straightM + CANCEL_MARGIN_M || elapsedMs > timeout) {
                end(a, fix)
                events += TrackerEvent.Cancelled(a.title)
            }
        }
    }

    private fun end(a: Active, fix: Fix) {
        actives.remove(a)
        a.directed?.let { recentlyEnded[it.key] = fix.timeMs }
    }

    private fun updateCandidates(fix: Fix, step: Double, events: MutableList<TrackerEvent>) {
        for (d in directed) {
            val dEntry = Geo.distanceM(fix.lat, fix.lon, d.entryLat, d.entryLon)
            val dExit = distToExit(fix, d)
            val c = candidates[d.key]
            if (dEntry <= d.radiusM) {
                midCandidates.remove(d.key)
                if (c == null) {
                    candidates[d.key] = Candidate(dEntry, fix, 0.0, dExit)
                } else {
                    c.travelledSinceMin += step
                    if (dEntry < c.minDist) {
                        c.minDist = dEntry
                        c.minFix = fix
                        c.travelledSinceMin = 0.0
                        c.distToExitAtMin = dExit
                    }
                }
                continue
            }
            if (c != null) {
                candidates.remove(d.key)
                c.travelledSinceMin += step
                val movingTowardsExit = dExit < c.distToExitAtMin - DIRECTION_THRESHOLD_M
                if (movingTowardsExit && !isActive(d)) {
                    start(d, c.minFix.timeMs, c.travelledSinceMin, 0.0, joinedMidway = false, fix, events)
                }
                continue
            }
            updateMidCandidate(d, fix, dEntry, dExit, step, events)
        }
    }

    private fun updateMidCandidate(
        d: DirectedSection, fix: Fix, dEntry: Double, dExit: Double, step: Double, events: MutableList<TrackerEvent>,
    ) {
        val endedAt = recentlyEnded[d.key]
        val cooldown = endedAt != null && fix.timeMs - endedAt < MID_JOIN_COOLDOWN_MS
        val inCorridor = dExit > d.radiusM && dEntry + dExit <= d.lengthM * 1.1 + 2 * d.radiusM
        if (!inCorridor || cooldown || isActive(d)) {
            midCandidates.remove(d.key)
            return
        }
        val m = midCandidates[d.key]
        if (m == null) {
            midCandidates[d.key] = MidCandidate(fix, dEntry, dExit)
            return
        }
        m.travelledM += step
        if (m.travelledM < MID_JOIN_DISTANCE_M) return

        val towardsExit = m.startDistToExit - dExit
        val awayFromEntry = dEntry - m.startDistToEntry
        if (towardsExit >= m.travelledM * 0.5 && awayFromEntry >= m.travelledM * 0.3) {
            midCandidates.remove(d.key)
            // Къде по отсечката е била точката на влизане – пропорционално на разстоянията до камерите.
            val fraction = m.startDistToEntry / (m.startDistToEntry + m.startDistToExit)
            val offsetM = fraction * d.lengthM
            start(d, m.startFix.timeMs, m.travelledM, offsetM, joinedMidway = true, fix, events)
        } else {
            // Не се движим към края – започваме наблюдението наново от текущата точка.
            midCandidates[d.key] = MidCandidate(fix, dEntry, dExit)
        }
    }

    private fun isActive(d: DirectedSection) = actives.any { it.directed?.key == d.key }

    private fun start(
        d: DirectedSection, entryTimeMs: Long, travelledM: Double, offsetM: Double, joinedMidway: Boolean,
        fix: Fix, events: MutableList<TrackerEvent>,
    ) {
        val a = Active(
            directed = d,
            title = d.title,
            road = d.section.road,
            limitKmh = d.section.limitKmh,
            entryTimeMs = entryTimeMs,
            travelledM = travelledM,
            offsetM = offsetM,
            joinedMidway = joinedMidway,
        )
        actives += a
        events += TrackerEvent.Entered(snapshotOf(a, fix.timeMs))
    }

    private fun distToExit(fix: Fix, d: DirectedSection) = Geo.distanceM(fix.lat, fix.lon, d.exitLat, d.exitLon)

    /** Ръчно стартиране (напр. за отсечка, която я няма в списъка). */
    fun startManual(limitKmh: Int?) {
        actives.removeAll { it.directed == null }
        actives += Active(
            directed = null,
            title = "Ръчно измерване",
            road = "",
            limitKmh = limitKmh,
            entryTimeMs = lastFix?.timeMs,
            travelledM = 0.0,
        )
    }

    /**
     * Спира текущите измервания. Връща резултат само за ръчно измерване;
     * прекъснатите автоматични измервания нямат резултат.
     */
    fun stop(): SectionResult? {
        val manual = actives.firstOrNull { it.directed == null }
        val now = lastFix?.timeMs
        if (now != null) actives.forEach { a -> a.directed?.let { recentlyEnded[it.key] = now } }
        actives.clear()
        val a = manual ?: return null
        val entry = a.entryTimeMs ?: return null
        if (now == null) return null
        val result = SectionResult(a.title, a.road, a.limitKmh, a.travelledM, now - entry, manual = true)
        lastResult = result
        return result
    }

    /** Основното текущо измерване: ръчното, ако има, иначе от началната камера, иначе последно засеченото. */
    fun snapshot(): ActiveSection? {
        val a = actives.firstOrNull { it.directed == null }
            ?: actives.lastOrNull { !it.joinedMidway }
            ?: actives.lastOrNull()
            ?: return null
        return snapshotOf(a, lastFix?.timeMs ?: a.entryTimeMs ?: 0L)
    }

    private fun snapshotOf(a: Active, nowMs: Long): ActiveSection {
        val entry = a.entryTimeMs ?: nowMs
        val d = a.directed
        return ActiveSection(
            title = a.title,
            road = a.road,
            limitKmh = a.limitKmh,
            manual = d == null,
            elapsedMs = max(0L, nowMs - entry),
            travelledM = a.travelledM,
            sectionLengthM = d?.let { max(0.0, it.lengthM - a.offsetM) },
            lengthIsEstimate = d != null && (d.lengthIsEstimate || d.section.approximate || a.joinedMidway),
            joinedMidway = a.joinedMidway,
        )
    }

    /** Най-близкото начало на отсечка спрямо текущата позиция (без значение от посоката). */
    fun nearest(fix: Fix): Pair<DirectedSection, Double>? =
        directed.map { it to Geo.distanceM(fix.lat, fix.lon, it.entryLat, it.entryLon) }.minByOrNull { it.second }

    /**
     * Най-близката отсечка пред колата в посоката на движение: началото ѝ е напред
     * и самата отсечка води в същата посока. Без известна посока – най-близката изобщо.
     */
    fun nearestAhead(fix: Fix): Pair<DirectedSection, Double>? {
        val heading = headingDeg ?: return nearest(fix)
        return directed
            .map { it to Geo.distanceM(fix.lat, fix.lon, it.entryLat, it.entryLon) }
            .filter { (d, dist) ->
                val toEntry = Geo.bearingDeg(fix.lat, fix.lon, d.entryLat, d.entryLon)
                val sectionDir = Geo.bearingDeg(d.entryLat, d.entryLon, d.exitLat, d.exitLon)
                val entryAhead = dist <= d.radiusM || Geo.angleDiff(heading, toEntry) <= AHEAD_MAX_ANGLE
                entryAhead && Geo.angleDiff(heading, sectionDir) <= SAME_DIRECTION_MAX_ANGLE
            }
            .minByOrNull { it.second }
    }

    companion object {
        const val MAX_ACCURACY_M = 60.0
        const val STATIONARY_MPS = 0.8
        const val DIRECTION_THRESHOLD_M = 30.0
        const val CANCEL_MARGIN_M = 3_000.0
        const val MOVING_AWAY_M = 2_000.0
        const val MID_JOIN_DISTANCE_M = 250.0
        const val MID_JOIN_COOLDOWN_MS = 3 * 60_000L
        const val HEADING_MIN_SPEED_MPS = 3.0
        const val HEADING_MIN_DISTANCE_M = 25.0
        const val AHEAD_MAX_ANGLE = 70.0
        const val SAME_DIRECTION_MAX_ANGLE = 100.0
    }
}
