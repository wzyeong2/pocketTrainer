package com.healthinsight.app

import org.json.JSONObject

/** 프로그램 한 구간 (예: 워밍업 5분, 이지런 25분 @5:30/142bpm) */
data class ProgramSegment(
    val label: String,
    val durationSec: Int,
    val targetPaceSec: Int,   // 초/km, 0이면 목표 없음
    val targetHr: Int,        // bpm, 0이면 목표 없음
)

/** 프로그램 한 세션(=하루 훈련) */
data class ProgramSession(
    val title: String,        // 예: "화 - 이지런"
    val focus: String,        // 예: "유산소 지구력"
    val segments: List<ProgramSegment>,
) {
    val totalSec: Int get() = segments.sumOf { it.durationSec }
}

/** AI가 만든 주간 프로그램 (JSON 문자열 ↔ 세션 리스트) */
object ProgramParser {
    /** AI 응답(JSON, 코드펜스 섞여도 OK)에서 세션 리스트 파싱. 실패시 빈 리스트. */
    fun parse(raw: String): List<ProgramSession> {
        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val obj = JSONObject(raw.substring(start, end + 1))
            val arr = obj.optJSONArray("sessions") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val so = arr.getJSONObject(i)
                val segArr = so.optJSONArray("segments")
                val segs = if (segArr != null) (0 until segArr.length()).map { j ->
                    val sg = segArr.getJSONObject(j)
                    ProgramSegment(
                        label = sg.optString("label", "구간 ${j + 1}"),
                        durationSec = sg.optInt("durationSec", 0),
                        targetPaceSec = sg.optInt("targetPaceSec", 0),
                        targetHr = sg.optInt("targetHr", 0),
                    )
                } else emptyList()
                ProgramSession(
                    title = so.optString("title", "세션 ${i + 1}"),
                    focus = so.optString("focus", ""),
                    segments = segs,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
