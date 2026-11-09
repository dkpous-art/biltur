
package dk.example.biltur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WalkStorage(context: Context) {

    private val prefs =
        context.getSharedPreferences("walks", Context.MODE_PRIVATE)

    fun load(): List<Walk> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val json = JSONArray(raw)

        return buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)

                add(
                    Walk(
                        date = item.getString("date"),
                        distanceKm = item.getDouble("distanceKm"),
                        durationSeconds = item.getLong("durationSeconds"),

                        startTimeMs = item.optLong("startTimeMs", 0L),
                        endTimeMs = item.optLong("endTimeMs", 0L),
                        startAddress = item.optString("startAddress", ""),
                        endAddress = item.optString("endAddress", ""),

                        startLatitude = if (
                            item.has("startLatitude") &&
                            !item.isNull("startLatitude")
                        ) {
                            item.getDouble("startLatitude")
                        } else null,

                        startLongitude = if (
                            item.has("startLongitude") &&
                            !item.isNull("startLongitude")
                        ) {
                            item.getDouble("startLongitude")
                        } else null,

                        endLatitude = if (
                            item.has("endLatitude") &&
                            !item.isNull("endLatitude")
                        ) {
                            item.getDouble("endLatitude")
                        } else null,

                        endLongitude = if (
                            item.has("endLongitude") &&
                            !item.isNull("endLongitude")
                        ) {
                            item.getDouble("endLongitude")
                        } else null,

                        maxSpeedKmh = item.optDouble("maxSpeedKmh", 0.0)
                    )
                )
            }
        }.reversed()
    }

    fun add(walk: Walk) {
        val json = JSONArray()

        load().reversed().forEach { old ->
            json.put(toJson(old))
        }

        json.put(toJson(walk))

        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun deleteAt(index: Int) {
        val walks = load().toMutableList()

        if (index !in walks.indices) return

        walks.removeAt(index)

        val json = JSONArray()

        walks.reversed().forEach { walk ->
            json.put(toJson(walk))
        }

        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun toJson(walk: Walk) = JSONObject().apply {
        put("date", walk.date)
        put("distanceKm", walk.distanceKm)
        put("durationSeconds", walk.durationSeconds)

        put("startTimeMs", walk.startTimeMs)
        put("endTimeMs", walk.endTimeMs)
        put("startAddress", walk.startAddress)
        put("endAddress", walk.endAddress)

        put("startLatitude", walk.startLatitude ?: JSONObject.NULL)
        put("startLongitude", walk.startLongitude ?: JSONObject.NULL)
        put("endLatitude", walk.endLatitude ?: JSONObject.NULL)
        put("endLongitude", walk.endLongitude ?: JSONObject.NULL)

        put("maxSpeedKmh", walk.maxSpeedKmh)
    }

    companion object {
        private const val KEY = "walk_list"
    }
}
