package com.healthinsight.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Health Connect에서 운동 세션을 종류별로 읽어온다. */
class WorkoutRepository(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        /** 요청하는 전체 권한 (있으면 데이터가 더 풍부해짐) */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
        )

        /** 연결 성공으로 간주하는 최소 핵심 권한 (운동·심박·거리·속도·걸음) */
        val REQUIRED: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
    }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED)

    /** 특정 종류의 최근 운동 목록 (최신순) */
    suspend fun recentWorkouts(type: ExerciseType, days: Long = 120): List<WorkoutRecord> {
        return dedupe(readSessions(days)
            .filter { it.exerciseType in type.hcTypes }
            .map { toRecord(it, type) })
    }

    /** 특정 종류의 가장 최근 운동 */
    suspend fun latestOf(type: ExerciseType): WorkoutRecord? =
        recentWorkouts(type).maxByOrNull { it.end }

    /** 지원하는 모든 종류의 최근 운동 (캘린더/리스트용) */
    suspend fun allWorkouts(days: Long = 120): List<WorkoutRecord> {
        return dedupe(readSessions(days).mapNotNull { s ->
            val t = ExerciseType.fromHc(s.exerciseType) ?: return@mapNotNull null
            toRecord(s, t)
        })
    }

    /**
     * 중복 제거 — 삼성헬스 우선.
     * 삼성헬스 + 구글핏이 같은 운동을 헬스커넥트에 이중 기록하므로,
     * (1) 같은 분 중복은 삼성>심박>길이 순으로 하나만,
     * (2) 다른 소스의 거의 동일 기록은 삼성 것을 남기고 제거,
     * (3) 여러 운동을 감싼 합산 컨테이너(보통 구글핏) 제거. 삼성 기록은 항상 보호.
     */
    private fun dedupe(list: List<WorkoutRecord>): List<WorkoutRecord> {
        fun pri(w: WorkoutRecord): Int = when {
            w.source.contains("삼성") || w.fromWatch -> 3   // 삼성헬스/워치 최우선
            w.avgHr != null -> 2                            // 심박 있는 기록
            else -> 1
        }
        // 1) 같은 종류 + 같은 시작(분): 삼성>심박>길이 우선 하나만
        val merged = list.groupBy { it.type to (it.start.epochSecond / 60) }
            .map { (_, g) -> g.maxWithOrNull(compareBy({ pri(it) }, { it.durationSec })) ?: g.first() }

        // 2) 다른 소스의 거의 동일 기록 제거 (삼성 우선 유지)
        val crossDeduped = merged.filter { a ->
            if (pri(a) >= 3) return@filter true
            val hasSamsungDup = merged.any { b ->
                b !== a && pri(b) >= 3 && b.type == a.type &&
                    kotlin.math.abs(b.start.epochSecond - a.start.epochSecond) <= 90 &&
                    kotlin.math.abs(b.durationSec - a.durationSec) <= maxOf(60L, a.durationSec / 4)
            }
            !hasSamsungDup
        }

        // 3) 다른 기록 2개 이상을 통째로 감싸는 합산 컨테이너 제거 (삼성 기록은 보호)
        return crossDeduped.filter { a ->
            if (pri(a) >= 3) return@filter true
            val contained = crossDeduped.count { b ->
                b !== a && !a.start.isAfter(b.start) && !a.end.isBefore(b.end) && a.durationSec > b.durationSec
            }
            contained < 2
        }.sortedByDescending { it.start }
    }

    /** 지원 종류 전체에서 가장 최근 운동 (자동 감지용) */
    suspend fun latestAny(days: Long = 30): WorkoutRecord? {
        val session = readSessions(days)
            .filter { it.exerciseType in ExerciseType.ALL_HC }
            .maxByOrNull { it.endTime } ?: return null
        val type = ExerciseType.fromHc(session.exerciseType) ?: return null
        return toRecord(session, type)
    }

    private suspend fun readSessions(days: Long): List<ExerciseSessionRecord> {
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)
        return client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = false,
                pageSize = 1000,
            )
        ).records
    }

    private suspend fun toRecord(session: ExerciseSessionRecord, type: ExerciseType): WorkoutRecord {
        val filter = TimeRangeFilter.between(session.startTime, session.endTime)
        // 총 경과시간(출발~도착). 삼성헬스의 '활동시간'(일시정지 제외)은 Health Connect로
        // 일관되게 넘어오지 않아 재현 불가 → 일관성을 위해 총 경과시간 사용.
        val durationSec = Duration.between(session.startTime, session.endTime).seconds

        // 집계 전략: 먼저 '이 운동을 기록한 앱' 기준(다중 소스 이중 합산 방지),
        // 그 앱에 핵심 값이 없으면 전체 소스로 보충(거리·고도가 다른 앱에만 있는 경우).
        val pkg = session.metadata.dataOrigin.packageName
        val originFilter = if (pkg.isNotBlank()) setOf(DataOrigin(pkg)) else emptySet()
        val metrics = setOf(
            DistanceRecord.DISTANCE_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
            ElevationGainedRecord.ELEVATION_GAINED_TOTAL, StepsRecord.COUNT_TOTAL, SpeedRecord.SPEED_MAX,
        )
        val own = try { client.aggregate(AggregateRequest(metrics, filter, dataOriginFilter = originFilter)) } catch (_: Exception) { null }
        val needAll = own == null ||
            (own[DistanceRecord.DISTANCE_TOTAL] == null && own[HeartRateRecord.BPM_AVG] == null &&
                own[ElevationGainedRecord.ELEVATION_GAINED_TOTAL] == null)
        val all = if (needAll && originFilter.isNotEmpty())
            try { client.aggregate(AggregateRequest(metrics, filter)) } catch (_: Exception) { null } else null

        val distance = (own?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters?.takeIf { it > 0 })
            ?: (all?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: 0.0)
        val avgHr = (own?.get(HeartRateRecord.BPM_AVG) ?: all?.get(HeartRateRecord.BPM_AVG))?.toInt()
        val maxHr = (own?.get(HeartRateRecord.BPM_MAX) ?: all?.get(HeartRateRecord.BPM_MAX))?.toInt()
        val calories = own?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories
            ?: own?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories
            ?: all?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories
            ?: all?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories
        val elevation = own?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters
            ?: all?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters
        val steps = own?.get(StepsRecord.COUNT_TOTAL) ?: all?.get(StepsRecord.COUNT_TOTAL)
        val maxSpeed = own?.get(SpeedRecord.SPEED_MAX)?.inMetersPerSecond ?: all?.get(SpeedRecord.SPEED_MAX)?.inMetersPerSecond

        return WorkoutRecord(
            type = type,
            start = session.startTime,
            end = session.endTime,
            durationSec = durationSec,
            distanceMeters = distance,
            avgHr = avgHr,
            maxHr = maxHr,
            calories = calories,
            elevationGainM = elevation,
            steps = steps,
            maxSpeedMps = maxSpeed,
            splits = emptyList(),
            source = sourceLabel(session),
            fromWatch = isWatch(session),
        )
    }

    /** 상세 화면에서만 구간 페이스 계산 (무거워서 지연 로딩) */
    suspend fun splitsFor(w: WorkoutRecord): List<Split> {
        if (w.type != ExerciseType.RUNNING) return emptyList()
        val filter = TimeRangeFilter.between(w.start, w.end)
        return computeSplits(filter, readHrSamples(filter), w.distanceMeters)
    }

    /** 데이터 출처(앱·기기) 사람이 읽는 라벨 */
    private fun sourceLabel(session: ExerciseSessionRecord): String {
        val pkg = session.metadata.dataOrigin.packageName
        val app = when {
            pkg.contains("shealth") -> "삼성 헬스"
            pkg.contains("healthdata") -> "Health Connect"
            pkg.contains("fitness") -> "Google Fit"
            pkg.isBlank() -> "알 수 없음"
            else -> pkg.substringAfterLast('.')
        }
        val device = session.metadata.device?.model?.takeIf { it.isNotBlank() }
        return if (device != null) "$app · $device" else app
    }

    /** 워치에서 기록된 데이터인지 (기기 타입 또는 모델명으로 판별) */
    private fun isWatch(session: ExerciseSessionRecord): Boolean {
        val device = session.metadata.device ?: return false
        val model = device.model ?: ""
        return device.type == Device.TYPE_WATCH ||
            model.startsWith("SM-R", ignoreCase = true) ||   // 갤럭시 워치 모델
            model.contains("Watch", ignoreCase = true)
    }

    private suspend fun readHrSamples(filter: TimeRangeFilter): List<Pair<Instant, Int>> = try {
        client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter, pageSize = 5000))
            .records.flatMap { rec -> rec.samples.map { it.time to it.beatsPerMinute.toInt() } }
            .sortedBy { it.first }
    } catch (e: Exception) { emptyList() }

    private suspend fun computeSplits(
        filter: TimeRangeFilter,
        hrSamples: List<Pair<Instant, Int>>,
        totalDistanceMeters: Double,
    ): List<Split> {
        val samples = try {
            client.readRecords(ReadRecordsRequest(SpeedRecord::class, filter, pageSize = 5000))
                .records.flatMap { it.samples }.sortedBy { it.time }
        } catch (e: Exception) { emptyList() }
        if (samples.size < 2) return emptyList()

        val t0 = samples.first().time
        fun secOf(t: Instant) = Duration.between(t0, t).toMillis() / 1000.0
        fun instantAt(sec: Double) = t0.plusMillis((sec * 1000).toLong())

        // 1차: 속도 적분 총거리 → 실제 총거리에 맞춰 보정(스케일)
        var rawTotal = 0.0
        for (i in 1 until samples.size) {
            val dt = secOf(samples[i].time) - secOf(samples[i - 1].time)
            if (dt <= 0) continue
            val v = (samples[i - 1].speed.inMetersPerSecond + samples[i].speed.inMetersPerSecond) / 2.0
            if (v > 0) rawTotal += v * dt
        }
        val scale = if (rawTotal > 0 && totalDistanceMeters > 0) totalDistanceMeters / rawTotal else 1.0

        val segMeters = 500.0 // 0.5km 구간
        val splits = mutableListOf<Split>()
        var cum = 0.0
        var idx = 1
        var segStartSec = 0.0
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]; val cur = samples[i]
            val pSec = secOf(prev.time); val cSec = secOf(cur.time)
            val dt = cSec - pSec
            if (dt <= 0) continue
            val v = (prev.speed.inMetersPerSecond + cur.speed.inMetersPerSecond) / 2.0
            val stepDist = v * dt * scale
            if (stepDist <= 0) continue
            val prevCum = cum
            cum += stepDist
            // 한 스텝(샘플 간격)이 여러 경계를 지날 수 있으니 while + 보간으로 정확히
            while (cum >= idx * segMeters) {
                val boundary = idx * segMeters
                val frac = ((boundary - prevCum) / stepDist).coerceIn(0.0, 1.0)
                val tBoundary = pSec + dt * frac // 경계 통과 시각을 선형 보간
                val segSec = tBoundary - segStartSec
                val paceSecPerKm = (segSec / (segMeters / 1000.0)).toInt()
                val hr = avgHrBetween(hrSamples, instantAt(segStartSec), instantAt(tBoundary))
                if (paceSecPerKm in 120..1200) splits.add(Split(idx * segMeters / 1000.0, paceSecPerKm, hr))
                segStartSec = tBoundary
                idx++
            }
        }
        // 마지막 자투리 구간(예: 9.5km 이후 남은 0.5km)도 표시
        val lastBoundary = (idx - 1) * segMeters
        val leftover = cum - lastBoundary
        val lastSec = secOf(samples.last().time)
        if (leftover >= 50.0 && lastSec > segStartSec) {
            val segSec = lastSec - segStartSec
            val paceSecPerKm = (segSec / (leftover / 1000.0)).toInt()
            val hr = avgHrBetween(hrSamples, instantAt(segStartSec), instantAt(lastSec))
            if (paceSecPerKm in 120..1200) splits.add(Split(cum / 1000.0, paceSecPerKm, hr))
        }
        return splits
    }

    private fun avgHrBetween(samples: List<Pair<Instant, Int>>, start: Instant, end: Instant): Int? {
        val inRange = samples.filter { !it.first.isBefore(start) && !it.first.isAfter(end) }
        return if (inRange.isEmpty()) null else inRange.map { it.second }.average().toInt()
    }
}
