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
     * 중복/삭제 잔존 기록 제거.
     * 삼성 헬스가 같은 운동을 2개로 기록하거나, 삼성헬스에서 지워도 Health Connect에
     * 남는 경우가 있어, 같은 종류 + 같은 시작 시각(분 단위)이면 하나로 합친다.
     */
    private fun dedupe(list: List<WorkoutRecord>): List<WorkoutRecord> {
        // 1) 같은 종류 + 같은 시작(분) 완전 중복 → 가장 긴 것 하나만
        val merged = list.groupBy { it.type to (it.start.epochSecond / 60) }
            .map { (_, group) -> group.maxByOrNull { it.durationSec } ?: group.first() }

        // 2) 다른 기록 2개 이상을 시간상 통째로 감싸는 '합산 컨테이너'만 제거
        //    (예: 달리기+자전거+달리기를 묶은 8.29km "달리기" 합산본)
        //    1개만 겹치는 건 진짜 운동일 수 있으니 남긴다.
        return merged.filter { a ->
            val contained = merged.count { b ->
                b !== a &&
                    !a.start.isAfter(b.start) && !a.end.isBefore(b.end) && // a가 b를 시간 포함
                    a.durationSec > b.durationSec
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
        val durationSec = Duration.between(session.startTime, session.endTime).seconds

        // 목록/캘린더용 가벼운 집계 (무거운 구간 페이스는 상세 열 때 따로 계산)
        var distance = 0.0; var avgHr: Int? = null; var maxHr: Int? = null
        try {
            val core = client.aggregate(AggregateRequest(
                setOf(DistanceRecord.DISTANCE_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), filter))
            distance = core[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
            avgHr = core[HeartRateRecord.BPM_AVG]?.toInt()
            maxHr = core[HeartRateRecord.BPM_MAX]?.toInt()
        } catch (_: Exception) {}

        var calories: Double? = null; var elevation: Double? = null; var steps: Long? = null
        try {
            val opt = client.aggregate(AggregateRequest(setOf(
                TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                ElevationGainedRecord.ELEVATION_GAINED_TOTAL, StepsRecord.COUNT_TOTAL), filter))
            calories = opt[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                ?: opt[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            elevation = opt[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters
            steps = opt[StepsRecord.COUNT_TOTAL]
        } catch (_: Exception) {}

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
            splits = emptyList(),
            source = sourceLabel(session),
            fromWatch = isWatch(session),
        )
    }

    /** 상세 화면에서만 구간 페이스 계산 (무거워서 지연 로딩) */
    suspend fun splitsFor(w: WorkoutRecord): List<Split> {
        if (w.type != ExerciseType.RUNNING) return emptyList()
        val filter = TimeRangeFilter.between(w.start, w.end)
        return computeSplits(filter, readHrSamples(filter))
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
    ): List<Split> {
        val samples = try {
            client.readRecords(ReadRecordsRequest(SpeedRecord::class, filter, pageSize = 5000))
                .records.flatMap { it.samples }.sortedBy { it.time }
        } catch (e: Exception) { emptyList() }
        if (samples.size < 2) return emptyList()

        val t0 = samples.first().time
        fun secOf(t: Instant) = Duration.between(t0, t).toMillis() / 1000.0
        fun instantAt(sec: Double) = t0.plusMillis((sec * 1000).toLong())

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
            val stepDist = v * dt
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
        return splits
    }

    private fun avgHrBetween(samples: List<Pair<Instant, Int>>, start: Instant, end: Instant): Int? {
        val inRange = samples.filter { !it.first.isBefore(start) && !it.first.isAfter(end) }
        return if (inRange.isEmpty()) null else inRange.map { it.second }.average().toInt()
    }
}
