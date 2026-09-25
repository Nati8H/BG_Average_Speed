package bg.averagespeed.core

import org.json.JSONArray
import org.json.JSONObject

/** Сериализация на отсечките в/от JSON (формат на assets/sections.json). */
object SectionJson {

    fun parse(json: String, userDefined: Boolean): List<Section> {
        val trimmed = json.trim()
        val array = if (trimmed.startsWith("{")) JSONObject(trimmed).getJSONArray("sections") else JSONArray(trimmed)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val start = o.getJSONArray("start")
            val end = o.getJSONArray("end")
            Section(
                id = o.optString("id").ifBlank { "s$i-${start.getDouble(0)}-${start.getDouble(1)}" },
                road = o.optString("road"),
                fromName = o.getString("from"),
                toName = o.getString("to"),
                startLat = start.getDouble(0),
                startLon = start.getDouble(1),
                endLat = end.getDouble(0),
                endLon = end.getDouble(1),
                limitKmh = o.getInt("limit"),
                lengthM = if (o.has("lengthKm") && !o.isNull("lengthKm")) o.getDouble("lengthKm") * 1000 else null,
                bidirectional = o.optBoolean("bidirectional", true),
                approximate = o.optBoolean("approximate", false),
                userDefined = userDefined,
                radiusM = if (o.has("radiusM") && !o.isNull("radiusM")) o.getDouble("radiusM") else null,
            )
        }
    }

    fun toJson(sections: List<Section>): String {
        val array = JSONArray()
        sections.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("road", s.road)
                    .put("from", s.fromName)
                    .put("to", s.toName)
                    .put("start", JSONArray().put(s.startLat).put(s.startLon))
                    .put("end", JSONArray().put(s.endLat).put(s.endLon))
                    .put("limit", s.limitKmh)
                    .put("lengthKm", s.lengthM?.let { it / 1000.0 } ?: JSONObject.NULL)
                    .put("bidirectional", s.bidirectional)
                    .put("approximate", s.approximate)
                    .put("radiusM", s.radiusM ?: JSONObject.NULL)
            )
        }
        return JSONObject().put("sections", array).toString(2)
    }
}
