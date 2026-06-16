package com.healthinsight.app

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant

/** 지원하는 운동 종류 */
enum class ExerciseType(
    val label: String,
    val emoji: String,
    val distanceBased: Boolean,
    val hcTypes: Set<Int>,
) {
    RUNNING("달리기", "🏃", true, setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    )),
    STRENGTH("헬스·근력", "🏋️", false, setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    )),
    WALKING("걷기", "🚶", true, setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    )),
    CYCLING("자전거", "🚴", true, setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    )),
    HIKING("등산", "🥾", true, setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    ));

    companion object {
        /** Health Connect 운동 타입 int → ExerciseType */
        fun fromHc(hc: Int): ExerciseType? = entries.firstOrNull { hc in it.hcTypes }
        val ALL_HC: Set<Int> = entries.flatMap { it.hcTypes }.toSet()
    }
}

/** 구간 기록 (0.5km 단위) */
data class Split(
    val distanceKm: Double,   // 누적 거리 (0.5, 1.0, 1.5 ...)
    val paceSecPerKm: Int,    // 해당 구간의 km당 페이스
    val avgHr: Int?,
) {
    val label: String
        get() = if (distanceKm % 1.0 == 0.0) "${distanceKm.toInt()}km" else "%.1fkm".format(distanceKm)
}

/** 운동 한 번의 기록 */
data class WorkoutRecord(
    val type: ExerciseType,
    val start: Instant,
    val end: Instant,
    val durationSec: Long,
    val distanceMeters: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val calories: Double?,
    val elevationGainM: Double?,
    val steps: Long?,
    val maxSpeedMps: Double?,   // 최고 속도 (m/s)
    val splits: List<Split>,
    val source: String = "",   // 데이터 출처 (앱·기기)
    val fromWatch: Boolean = false,  // 워치에서 기록됐는지
) {
    /** 코칭/메모 저장 키 — 세션 시작 시각(ms) */
    val id: Long get() = start.toEpochMilli()
    val distanceKm: Double get() = distanceMeters / 1000.0
    val avgPaceSecPerKm: Int
        get() = if (distanceMeters > 0) (durationSec / (distanceMeters / 1000.0)).toInt() else 0
}

/** 달리기 목표 레벨 */
enum class Level(val label: String, val goal: String) {
    LV1("Lv.1", "5km 완주"),
    LV2("Lv.2", "5km 36분 이내"),
    LV3("Lv.3", "10km 50분 이내"),
}

data class LevelStatus(
    val current: Level,
    val achievedTop: Boolean,
    val best5kSec: Int?,
    val best10kSec: Int?,
) {
    fun nextGoalText(): String = when {
        achievedTop -> "🏆 모든 레벨 달성! 자기 기록 경신에 도전하세요."
        current == Level.LV2 -> best10kSec?.let {
            "다음 목표 Lv.3: 10km 50분 이내 (현재 최고 ${formatDuration(it.toLong())})"
        } ?: "다음 목표 Lv.3: 10km를 50분 이내에 완주하기"
        current == Level.LV1 -> "다음 목표 Lv.2: 5km 36분 이내"
        else -> "다음 목표 Lv.1: 5km 완주"
    }
}

/** 달리기 기록들로 레벨 계산 */
fun computeRunningLevel(runs: List<WorkoutRecord>): LevelStatus {
    val best5k = runs.filter { it.distanceKm >= 4.7 && it.distanceKm < 8.0 }
        .minOfOrNull { it.durationSec.toInt() }
    val best10k = runs.filter { it.distanceKm >= 9.5 }
        .minOfOrNull { it.durationSec.toInt() }
    val ranAny5k = runs.any { it.distanceKm >= 4.7 }
    val ranAny10k = runs.any { it.distanceKm >= 9.5 }

    val lv3 = best10k != null && best10k <= 3000
    val lv2 = best5k != null && best5k <= 2160
    val lv1 = ranAny5k || ranAny10k
    val current = when {
        lv3 -> Level.LV3
        lv2 -> Level.LV2
        else -> Level.LV1
    }
    return LevelStatus(current, lv3, best5k, best10k)
}

/** 코스 난이도 — 거리당 상승고도(m/km)로 평지~가파름 등급 */
data class CourseDifficulty(val label: String, val emoji: String, val climbPerKm: Int)

fun courseDifficulty(elevationGainM: Double?, distanceMeters: Double): CourseDifficulty? {
    if (elevationGainM == null || elevationGainM < 0 || distanceMeters < 500) return null
    val perKm = (elevationGainM / (distanceMeters / 1000.0)).toInt()
    return when {
        perKm < 5 -> CourseDifficulty("평지", "🟢", perKm)
        perKm < 15 -> CourseDifficulty("완만", "🟡", perKm)
        perKm < 30 -> CourseDifficulty("언덕", "🟠", perKm)
        else -> CourseDifficulty("가파름", "🔴", perKm)
    }
}

fun formatPace(secPerKm: Int): String {
    if (secPerKm <= 0) return "-"
    return "%d:%02d/km".format(secPerKm / 60, secPerKm % 60)
}

fun formatDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
