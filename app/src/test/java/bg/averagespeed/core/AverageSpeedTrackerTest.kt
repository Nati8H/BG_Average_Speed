package bg.averagespeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AverageSpeedTrackerTest {

    // Права отсечка на изток по паралел 42.5; ~10 км.
    private val lat = 42.5
    private val lonA = 24.0
    private val metersPerDegLon = Geo.distanceM(lat, 0.0, lat, 1.0)
    private val lonB = lonA + 10_000 / metersPerDegLon

    private val section = Section(
        id = "t", road = "Тест", fromName = "А", toName = "Б",
        startLat = lat, startLon = lonA, endLat = lat, endLon = lonB,
        limitKmh = 120, lengthM = 10_000.0,
    )

    /** Движение по права от lonFrom до lonTo с постоянна скорост, точка на секунда. */
    private fun drive(tracker: AverageSpeedTracker, lonFrom: Double, lonTo: Double, kmh: Double, startMs: Long = 0L): List<TrackerEvent> {
        val mps = kmh / 3.6
        val totalM = Geo.distanceM(lat, lonFrom, lat, lonTo)
        val seconds = (totalM / mps).toInt()
        val events = mutableListOf<TrackerEvent>()
        for (i in 0..seconds) {
            val f = i.toDouble() / seconds
            val lon = lonFrom + (lonTo - lonFrom) * f
            events += tracker.onFix(Fix(lat, lon, startMs + i * 1000L, mps, 5.0))
        }
        return events
    }

    private fun lonOffset(meters: Double) = meters / metersPerDegLon

    @Test
    fun detectsSectionAndComputesAverage() {
        val tracker = AverageSpeedTracker(listOf(section))
        val events = drive(tracker, lonA - lonOffset(2000.0), lonB + lonOffset(2000.0), 100.0)

        assertTrue(events.first() is TrackerEvent.Entered)
        val finished = events.filterIsInstance<TrackerEvent.Finished>().single().result
        assertEquals(100.0, finished.avgKmh, 1.5)
        assertEquals(false, finished.overLimit)
        assertNull(tracker.snapshot())
    }

    @Test
    fun reverseDirectionIsDetected() {
        val tracker = AverageSpeedTracker(listOf(section))
        val events = drive(tracker, lonB + lonOffset(1000.0), lonA - lonOffset(1000.0), 140.0)
        val entered = events.filterIsInstance<TrackerEvent.Entered>().single()
        assertEquals("Б → А", entered.active.title)
        val result = events.filterIsInstance<TrackerEvent.Finished>().single().result
        assertEquals(140.0, result.avgKmh, 2.0)
        assertTrue(result.overLimit)
    }

    @Test
    fun oneWaySectionIgnoresOppositeDirection() {
        val tracker = AverageSpeedTracker(listOf(section.copy(bidirectional = false)))
        val events = drive(tracker, lonB + lonOffset(1000.0), lonA - lonOffset(1000.0), 100.0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun averageSoFarAndAllowedSpeedForRest() {
        val tracker = AverageSpeedTracker(listOf(section))
        // 5 км с 150 км/ч от преди началото до средата.
        drive(tracker, lonA - lonOffset(1000.0), lonA + lonOffset(5000.0), 150.0)
        val active = tracker.snapshot()
        assertNotNull(active)
        active!!
        assertEquals(150.0, active.avgKmh!!, 3.0)
        assertTrue(active.overLimit)
        // Разрешено време общо: 10 км / 120 = 300 с; изминали ~120 с; остават 5 км за ~180 с -> ~100 км/ч.
        assertEquals(100.0, active.maxAllowedKmhForRest!!, 3.0)
    }

    @Test
    fun consecutiveSectionsAreChained() {
        val lonC = lonB + lonOffset(8000.0)
        val second = section.copy(id = "t2", fromName = "Б", toName = "В", startLon = lonB, endLon = lonC, lengthM = 8000.0)
        val tracker = AverageSpeedTracker(listOf(section, second))
        val events = drive(tracker, lonA - lonOffset(1000.0), lonC + lonOffset(1000.0), 110.0)
        val finished = events.filterIsInstance<TrackerEvent.Finished>().map { it.result.title }
        assertEquals(listOf("А → Б", "Б → В"), finished)
    }

    @Test
    fun falseParallelSectionIsDroppedAndRealOneFinishes() {
        // Отсечка със същото начало, но крайна точка на ~8 км на североизток – колата кара на изток.
        val metersPerDegLat = Geo.distanceM(0.0, 0.0, 1.0, 0.0)
        val other = section.copy(
            id = "other", fromName = "А", toName = "Х",
            endLat = lat + 6_000 / metersPerDegLat, endLon = lonA + lonOffset(5_000.0), lengthM = 8_000.0,
        )
        val tracker = AverageSpeedTracker(listOf(section, other))
        val events = drive(tracker, lonA - lonOffset(1000.0), lonB + lonOffset(1000.0), 100.0)
        assertEquals(2, events.filterIsInstance<TrackerEvent.Entered>().size)
        assertEquals(listOf("А → Х"), events.filterIsInstance<TrackerEvent.Cancelled>().map { it.title })
        val finished = events.filterIsInstance<TrackerEvent.Finished>().single().result
        assertEquals("А → Б", finished.title)
        assertEquals(100.0, finished.avgKmh, 1.5)
    }

    @Test
    fun approximateSectionUsesTravelledDistance() {
        val approx = section.copy(approximate = true, lengthM = 12_000.0)
        val tracker = AverageSpeedTracker(listOf(approx))
        val events = drive(tracker, lonA - lonOffset(2000.0), lonB + lonOffset(2000.0), 100.0)
        val result = events.filterIsInstance<TrackerEvent.Finished>().single().result
        // Официалната дължина (12 км) не се ползва – средната е реалната скорост.
        assertEquals(100.0, result.avgKmh, 1.5)
    }

    @Test
    fun bundledSectionsMatchOfficialList() {
        val json = java.io.File("src/main/assets/sections.json").takeIf { it.exists() }
            ?: java.io.File("app/src/main/assets/sections.json")
        val sections = SectionJson.parse(json.readText(), userDefined = false)
        assertEquals(40, sections.size)
        assertEquals(79, sections.sumOf { it.directions().size })
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        for (s in sections) {
            val straight = Geo.distanceM(s.startLat, s.startLon, s.endLat, s.endLon)
            // Приблизителните точки трябва да са съобразени с официалната дължина.
            assertTrue("${s.fromName}-${s.toName}: $straight vs ${s.lengthM}", straight < s.lengthM!! * 1.5 + 2_000)
        }
    }

    @Test
    fun manualMeasurement() {
        val tracker = AverageSpeedTracker(emptyList())
        tracker.onFix(Fix(lat, lonA, 0L, 25.0, 5.0))
        tracker.startManual(90)
        drive(tracker, lonA, lonA + lonOffset(3000.0), 90.0, startMs = 1000L)
        val result = tracker.stop()
        assertNotNull(result)
        assertEquals(90.0, result!!.avgKmh, 3.0)
    }

    @Test
    fun jsonRoundTrip() {
        val json = SectionJson.toJson(listOf(section, section.copy(id = "x", lengthM = null, approximate = true)))
        val parsed = SectionJson.parse(json, userDefined = true)
        assertEquals(2, parsed.size)
        assertEquals(section.copy(userDefined = true), parsed[0])
        assertNull(parsed[1].lengthM)
        assertTrue(parsed[1].approximate)
    }
}
