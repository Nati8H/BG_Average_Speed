package bg.averagespeed.service

import android.content.Context
import bg.averagespeed.core.ActiveSection
import bg.averagespeed.core.AverageSpeedTracker
import bg.averagespeed.core.Fix
import bg.averagespeed.core.Geo
import bg.averagespeed.core.Section
import bg.averagespeed.core.SectionRepository
import bg.averagespeed.core.SectionResult
import bg.averagespeed.core.TrackerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class NearestInfo(val title: String, val road: String, val distanceM: Double, val limitKmh: Int)

/** Записване на нова отсечка по време на шофиране: от маркер „начало" до маркер „край". */
data class RecordingInfo(val startLat: Double, val startLon: Double, val lengthM: Double, val startTimeMs: Long)

data class UiState(
    val tracking: Boolean = false,
    val hasFix: Boolean = false,
    val accuracyM: Double? = null,
    val currentKmh: Double? = null,
    val active: ActiveSection? = null,
    val nearest: NearestInfo? = null,
    val lastResult: SectionResult? = null,
    val recording: RecordingInfo? = null,
    val sections: List<Section> = emptyList(),
)

/**
 * Обща точка между услугата (GPS) и екрана. Всички извиквания са от главната нишка.
 */
object TrackingHub {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val tracker = AverageSpeedTracker()
    private var repo: SectionRepository? = null
    private var recording: RecordingInfo? = null
    private var lastRecordFix: Fix? = null

    fun init(context: Context) {
        if (repo != null) return
        repo = SectionRepository(context.applicationContext)
        reloadSections()
    }

    fun repository(): SectionRepository = repo ?: error("TrackingHub.init не е извикан")

    fun reloadSections() {
        val sections = repository().all()
        tracker.setSections(sections)
        _state.value = _state.value.copy(sections = sections)
    }

    fun setTracking(tracking: Boolean) {
        if (!tracking) {
            tracker.reset()
            recording = null
            lastRecordFix = null
            publish(null)
        }
        _state.value = _state.value.copy(tracking = tracking)
    }

    fun onFix(fix: Fix): List<TrackerEvent> {
        val events = tracker.onFix(fix)
        recording?.let { r ->
            val prev = lastRecordFix
            val accurate = fix.accuracyM == null || fix.accuracyM <= AverageSpeedTracker.MAX_ACCURACY_M
            if (accurate) {
                val moving = fix.speedMps == null || fix.speedMps >= AverageSpeedTracker.STATIONARY_MPS
                val step = if (prev != null && moving) Geo.distanceM(prev, fix) else 0.0
                recording = r.copy(lengthM = r.lengthM + step)
                lastRecordFix = fix
            }
        }
        publish(fix)
        return events
    }

    fun startManual(limitKmh: Int?) {
        tracker.startManual(limitKmh)
        publish(tracker.lastFix)
    }

    fun stopActive(): SectionResult? {
        val result = tracker.stop()
        publish(tracker.lastFix)
        return result
    }

    /** Маркира началото на нова отсечка на текущата позиция. false ако няма GPS. */
    fun startRecording(): Boolean {
        val fix = tracker.lastFix ?: return false
        recording = RecordingInfo(fix.lat, fix.lon, 0.0, fix.timeMs)
        lastRecordFix = fix
        publish(fix)
        return true
    }

    fun cancelRecording() {
        recording = null
        lastRecordFix = null
        publish(tracker.lastFix)
    }

    /** Приключва записа и запазва отсечката. Връща null ако няма GPS или записът е твърде кратък. */
    fun finishRecording(road: String, fromName: String, toName: String, limitKmh: Int): Section? {
        val r = recording ?: return null
        val end = lastRecordFix ?: return null
        if (r.lengthM < 200) return null
        val section = Section(
            id = "user-" + UUID.randomUUID().toString().take(8),
            road = road,
            fromName = fromName,
            toName = toName,
            startLat = r.startLat,
            startLon = r.startLon,
            endLat = end.lat,
            endLon = end.lon,
            limitKmh = limitKmh,
            lengthM = r.lengthM,
            bidirectional = true,
            approximate = false,
            userDefined = true,
        )
        repository().addUser(section)
        recording = null
        lastRecordFix = null
        reloadSections()
        publish(tracker.lastFix)
        return section
    }

    private fun publish(fix: Fix?) {
        val nearest = fix?.let { tracker.nearest(it) }?.let { (d, dist) ->
            NearestInfo(d.title, d.section.road, dist, d.section.limitKmh)
        }
        _state.value = _state.value.copy(
            hasFix = fix != null,
            accuracyM = fix?.accuracyM,
            currentKmh = fix?.speedMps?.let { it * 3.6 },
            active = tracker.snapshot(),
            nearest = nearest,
            lastResult = tracker.lastResult,
            recording = recording,
        )
    }
}
