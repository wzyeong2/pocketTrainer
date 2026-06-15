package com.healthinsight.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** 운동 목록을 로컬에 저장/복원 (켤 때마다 다시 안 읽도록 캐시) */
object WorkoutCache {

    fun toJson(list: List<WorkoutRecord>): String {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().apply {
                put("type", w.type.name)
                put("start", w.start.toEpochMilli())
                put("end", w.end.toEpochMilli())
                put("dur", w.durationSec)
                put("dist", w.distanceMeters)
                put("avgHr", w.avgHr ?: -1)
                put("maxHr", w.maxHr ?: -1)
                put("cal", w.calories ?: -1.0)
                put("elev", w.elevationGainM ?: -1.0)
                put("steps", w.steps ?: -1L)
                put("maxSpd", w.maxSpeedMps ?: -1.0)
                put("src", w.source)
                put("watch", w.fromWatch)
            })
        }
        return arr.toString()
    }

    fun fromJson(s: String): List<WorkoutRecord> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val type = ExerciseType.entries.firstOrNull { it.name == o.getString("type") } ?: return@mapNotNull null
                WorkoutRecord(
                    type = type,
                    start = Instant.ofEpochMilli(o.getLong("start")),
                    end = Instant.ofEpochMilli(o.getLong("end")),
                    durationSec = o.getLong("dur"),
                    distanceMeters = o.getDouble("dist"),
                    avgHr = o.getInt("avgHr").takeIf { it >= 0 },
                    maxHr = o.getInt("maxHr").takeIf { it >= 0 },
                    calories = o.getDouble("cal").takeIf { it >= 0 },
                    elevationGainM = o.getDouble("elev").takeIf { it >= 0 },
                    steps = o.getLong("steps").takeIf { it >= 0 },
                    maxSpeedMps = o.optDouble("maxSpd", -1.0).takeIf { it >= 0 },
                    splits = emptyList(),
                    source = o.optString("src", ""),
                    fromWatch = o.optBoolean("watch", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
