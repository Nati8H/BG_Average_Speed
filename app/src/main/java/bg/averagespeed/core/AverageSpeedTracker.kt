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
    /** Дължина на отсечката (null при ръчно измерване без известна дължина). */
    val sectionLengthM: Double?,
    val lengthIsEstimate: Boolean,
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
 * Излизане: аналогично – най-близкото преминаване покрай крайната камера.
 */
class AverageSpeedTracker(sections: List<Section> = emptyList()) {

    private class Candidate(
        var minDist: Double,
        var minFix: Fix,
        var travelledSinceMin: Double,
        var distToExitAtMin: Double,
    )

    private class ExitMin(val minDist: Double, val timeMs: Long, val travelledAtMin: Double)

    private class Active(
        val directed: DirectedSection?,
        val title: String,
        val road: String,
        val limitKmh: Int?,
        var entryTimeMs: Long?,
        var travelledM: Double,
        var exitMin: ExitMin? = null,
    )

    private var directed: List<DirectedSection> = emptyList()
    private val candidates = HashMap<String, Candidate>()
    private var active: Active? = null

    /** Засечено начало на следваща отсечка, докато предишната още не е приключила. */
    private var pendingEntry: Pair<DirectedSection, Candidate>? = null

    var lastFix: Fix? = null
        private set
    var lastResult: SectionResult? = null
        private set

    init {
        setSections(sections)
    }

    fun setSections(sections: List<Section>) {
        directed = sections.flatMap { it.directions() }
        candidates.clear()
        pendingEntry = null
    }

    /** Нулира текущото измерване и последната точка (при спиране на следенето). */
    fun reset() {
        active = null
        pendingEntry = null
        candidates.clear()
        lastFix = null
    }

    val isActive: Boolean get() = active != null
    val isManual: Boolean get() = active?.let { it.directed == null } ?: false

    fun onFix(fix: Fix): List<TrackerEvent> {
        val events = mutableListOf<TrackerEvent>()
        if (fix.accuracyM != null && fix.accuracyM > MAX_ACCURACY_M) return events

        val prev = lastFix
        val stationary = fix.speedMps != null && fix.speedMps < STATIONARY_MPS
        val step = if (prev == null || stationary) 0.0 else Geo.distanceM(prev, fix)
        lastFix = fix

        active?.let { a -> updateActive(a, fix, step, events) }
        updateCandidates(fix, step, events)
        return events
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
        val exitMin = a.exitMin
        if (dExit <= d.radiusM) {
            if (exitMin == null || dExit < exitMin.minDist) {
                a.exitMin = ExitMin(dExit, fix.timeMs, a.travelledM)
            }
        } else if (exitMin != null) {
            val lengthM = if (d.lengthIsEstimate) exitMin.travelledAtMin else d.lengthM
            val result = SectionResult(a.title, a.road, a.limitKmh, lengthM, exitMin.timeMs - entry, manual = false)
            lastResult = result
            active = null
            events += TrackerEvent.Finished(result)
            pendingEntry?.let { (pd, pc) ->
                pendingEntry = null
                pc.travelledSinceMin += step
                if (fix.timeMs - pc.minFix.timeMs < PENDING_MAX_AGE_MS) enter(pd, pc, fix, events)
            }
        } else {
            val elapsedMs = fix.timeMs - entry
            val expectedMs = d.lengthM / (d.section.limitKmh / 3.6) * 1000
            val timeout = max(expectedMs * 4, 30 * 60_000.0)
            if (dExit > d.straightM + CANCEL_MARGIN_M || elapsedMs > timeout) {
                active = null
                pendingEntry = null
                events += TrackerEvent.Cancelled(a.title)
            }
        }
    }

    private fun updateCandidates(fix: Fix, step: Double, events: MutableList<TrackerEvent>) {
        pendingEntry?.second?.let { it.travelledSinceMin += step }
        for (d in directed) {
            val dEntry = Geo.distanceM(fix.lat, fix.lon, d.entryLat, d.entryLon)
            val c = candidates[d.key]
            if (dEntry <= d.radiusM) {
                if (c == null) {
                    candidates[d.key] = Candidate(dEntry, fix, 0.0, distToExit(fix, d))
                } else {
                    c.travelledSinceMin += step
                    if (dEntry < c.minDist) {
                        c.minDist = dEntry
                        c.minFix = fix
                        c.travelledSinceMin = 0.0
                        c.distToExitAtMin = distToExit(fix, d)
                    }
                }
            } else if (c != null) {
                candidates.remove(d.key)
                c.travelledSinceMin += step
                val movingTowardsExit = distToExit(fix, d) < c.distToExitAtMin - DIRECTION_THRESHOLD_M
                if (movingTowardsExit) {
                    val current = active
                    if (current == null) {
                        enter(d, c, fix, events)
                    } else if (current.directed != null && current.directed.key != d.key) {
                        pendingEntry = d to c
                    }
                }
            }
        }
    }

    private fun enter(d: DirectedSection, c: Candidate, fix: Fix, events: MutableList<TrackerEvent>) {
        val a = Active(
            directed = d,
            title = d.title,
            road = d.section.road,
            limitKmh = d.section.limitKmh,
            entryTimeMs = c.minFix.timeMs,
            travelledM = c.travelledSinceMin,
        )
        active = a
        events += TrackerEvent.Entered(snapshotOf(a, fix.timeMs))
    }

    private fun distToExit(fix: Fix, d: DirectedSection) = Geo.distanceM(fix.lat, fix.lon, d.exitLat, d.exitLon)

    /** Ръчно стартиране (напр. за отсечка, която я няма в списъка). */
    fun startManual(limitKmh: Int?) {
        active = Active(
            directed = null,
            title = "Ръчно измерване",
            road = "",
            limitKmh = limitKmh,
            entryTimeMs = lastFix?.timeMs,
            travelledM = 0.0,
        )
    }

    /** Спира текущото измерване (ръчно или автоматично). */
    fun stop(): SectionResult? {
        val a = active ?: return null
        active = null
        pendingEntry = null
        val entry = a.entryTimeMs ?: return null
        val now = lastFix?.timeMs ?: return null
        if (a.directed != null) return null // прекъснато автоматично измерване – без резултат
        val result = SectionResult(a.title, a.road, a.limitKmh, a.travelledM, now - entry, manual = true)
        lastResult = result
        return result
    }

    fun snapshot(): ActiveSection? {
        val a = active ?: return null
        return snapshotOf(a, lastFix?.timeMs ?: a.entryTimeMs ?: 0L)
    }

    private fun snapshotOf(a: Active, nowMs: Long): ActiveSection {
        val entry = a.entryTimeMs ?: nowMs
        return ActiveSection(
            title = a.title,
            road = a.road,
            limitKmh = a.limitKmh,
            manual = a.directed == null,
            elapsedMs = max(0L, nowMs - entry),
            travelledM = a.travelledM,
            sectionLengthM = a.directed?.lengthM,
            lengthIsEstimate = a.directed?.lengthIsEstimate ?: false,
        )
    }

    /** Най-близкото начало на отсечка спрямо текущата позиция. */
    fun nearest(fix: Fix): Pair<DirectedSection, Double>? =
        directed.map { it to Geo.distanceM(fix.lat, fix.lon, it.entryLat, it.entryLon) }.minByOrNull { it.second }

    companion object {
        const val MAX_ACCURACY_M = 60.0
        const val STATIONARY_MPS = 0.8
        const val DIRECTION_THRESHOLD_M = 30.0
        const val CANCEL_MARGIN_M = 3_000.0
        const val PENDING_MAX_AGE_MS = 5 * 60_000L
    }
}
