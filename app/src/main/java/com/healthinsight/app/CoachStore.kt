package com.healthinsight.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 저장된 코칭 한 건 (운동별/하루/이번달/라이브 공통) */
data class CoachLog(val time: Long, val kind: String, val title: String, val text: String)

/** AI 호출 1건의 사용 내역 (언제·무엇·제공사·예상비용USD) */
data class UsageLog(val time: Long, val kind: String, val provider: String, val costUsd: Double)

/** 라이브 코치 세션 상세 (폰 기압계 고도·경사 + 심박·타임라인). 삼성 기록과 합쳐 코칭에 사용 */
data class LiveSession(
    val start: Long, val end: Long, val distM: Double, val elevGainM: Double, val elevLossM: Double,
    val hrMax: Int, val gradeMax: Int, val timeline: List<String>,
    val track: List<Pair<Double, Double>> = emptyList(),  // GPS 경로(위도,경도) — 지도 고도 보정용
) {
    val durationSec: Long get() = (end - start) / 1000
    /** 같은 러닝인지 (시간대 겹침) */
    fun overlaps(s: Long, e: Long): Boolean = start < e && s < end
}

/** API 키·코칭 기록·메모를 기기에 저장 */
class CoachStore(context: Context) {
    private val prefs = context.getSharedPreferences("running_coach", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("provider", v).apply()

    /** 제공사별 API 키 (확장성: 제공사 id로 key_<id>에 저장) */
    fun keyFor(provider: String): String = prefs.getString("key_$provider", "") ?: ""
    fun setKey(provider: String, value: String) = prefs.edit().putString("key_$provider", value).apply()

    /** 오늘 AI 코칭 호출 횟수 (무료 quota 감 잡기용, 자정 리셋) */
    fun usageToday(): Int {
        val today = java.time.LocalDate.now().toString()
        return if (prefs.getString("ai_usage_date", "") == today) prefs.getInt("ai_usage_count", 0) else 0
    }

    fun recordAiCall() {
        val today = java.time.LocalDate.now().toString()
        val cur = if (prefs.getString("ai_usage_date", "") == today) prefs.getInt("ai_usage_count", 0) else 0
        prefs.edit().putString("ai_usage_date", today).putInt("ai_usage_count", cur + 1).apply()
    }

    /** 사용자가 직접 적는 선수 프로필/목표/부상 메모 (코칭에 항상 참고) */
    var athleteProfile: String
        get() = prefs.getString("athlete_profile", "") ?: ""
        set(v) = prefs.edit().putString("athlete_profile", v).apply()

    /** AI가 만든 주간 프로그램 (원본 JSON) + 생성일 */
    var programJson: String
        get() = prefs.getString("program_json", "") ?: ""
        set(v) = prefs.edit().putString("program_json", v).apply()
    var programDate: String
        get() = prefs.getString("program_date", "") ?: ""
        set(v) = prefs.edit().putString("program_date", v).apply()

    /** 지난 프로그램 세션 실제 결과 (다음 프로그램 생성에 반영) */
    var lastProgramResult: String
        get() = prefs.getString("last_program_result", "") ?: ""
        set(v) = prefs.edit().putString("last_program_result", v).apply()

    /** 백필 분석 요약 (과거 기록으로 만든 러너 특성/추세 — 코칭·챗에 항상 참고) */
    var athleteModel: String
        get() = prefs.getString("athlete_model", "") ?: ""
        set(v) = prefs.edit().putString("athlete_model", v).apply()
    /** 지금까지 백필한 개월 수 (최근부터 거꾸로) */
    var backfillMonths: Int
        get() = prefs.getInt("backfill_months", 0)
        set(v) = prefs.edit().putInt("backfill_months", v).apply()

    /** 코치챗 대화 (role=user/assistant) */
    fun chatMessages(): List<Pair<String, String>> {
        val s = prefs.getString("chat_thread", "") ?: ""
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); o.getString("r") to o.getString("c")
            }
        } catch (e: Exception) { emptyList() }
    }
    fun addChatMessage(role: String, content: String) {
        val cur = chatMessages().toMutableList()
        cur.add(role to content)
        val trimmed = cur.takeLast(40) // 비용 관리: 최근 40개만 유지
        val arr = JSONArray()
        trimmed.forEach { (r, c) -> arr.put(JSONObject().put("r", r).put("c", c)) }
        prefs.edit().putString("chat_thread", arr.toString()).apply()
    }
    fun clearChat() = prefs.edit().remove("chat_thread").apply()

    /** AI 사용 내역 (최신순, 최대 300건) */
    fun usageLogs(): List<UsageLog> {
        val s = prefs.getString("usage_log", "") ?: ""
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UsageLog(o.getLong("t"), o.getString("k"), o.optString("p", ""), o.getDouble("c"))
            }.sortedByDescending { it.time }
        } catch (e: Exception) { emptyList() }
    }
    fun addUsage(kind: String, provider: String, costUsd: Double) {
        val cur = usageLogs().toMutableList()
        cur.add(0, UsageLog(System.currentTimeMillis(), kind, provider, costUsd))
        val arr = JSONArray()
        cur.take(300).forEach { arr.put(JSONObject().put("t", it.time).put("k", it.kind).put("p", it.provider).put("c", it.costUsd)) }
        prefs.edit().putString("usage_log", arr.toString()).apply()
    }

    /** 운동별 코칭 결과 */
    fun getCoaching(id: Long): String? = prefs.getString("coach_$id", null)
    fun setCoaching(id: Long, text: String) = prefs.edit().putString("coach_$id", text).apply()

    /** 모든 코칭 히스토리 (운동별·하루·이번달·라이브 통합, 최신순, 최대 50개) */
    fun coachingLogs(): List<CoachLog> {
        val s = prefs.getString("coach_logs", "") ?: ""
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoachLog(o.getLong("t"), o.getString("k"), o.getString("ti"), o.getString("tx"))
            }.sortedByDescending { it.time }
        } catch (e: Exception) { emptyList() }
    }

    fun addCoachingLog(time: Long, kind: String, title: String, text: String) {
        val cur = coachingLogs().toMutableList()
        cur.add(0, CoachLog(time, kind, title, text))
        val trimmed = cur.take(50)
        val arr = JSONArray()
        trimmed.forEach { l ->
            arr.put(JSONObject().put("t", l.time).put("k", l.kind).put("ti", l.title).put("tx", l.text))
        }
        prefs.edit().putString("coach_logs", arr.toString()).apply()
    }

    /** 운동별 메모 */
    fun getMemo(id: Long): String = prefs.getString("memo_$id", "") ?: ""
    fun setMemo(id: Long, text: String) = prefs.edit().putString("memo_$id", text).apply()

    /** 자동 감지 알림 중복 방지 */
    var lastNotifiedRunEndMillis: Long
        get() = prefs.getLong("last_notified_end", 0L)
        set(v) = prefs.edit().putLong("last_notified_end", v).apply()

    /** 개인정보 처리방침 동의 여부 */
    var consentAccepted: Boolean
        get() = prefs.getBoolean("consent", false)
        set(v) = prefs.edit().putBoolean("consent", v).apply()

    /** 스플래시를 마지막으로 띄운 날짜(YYYY-MM-DD) — 1일 1회용 */
    var lastSplashDate: String
        get() = prefs.getString("splash_date", "") ?: ""
        set(v) = prefs.edit().putString("splash_date", v).apply()

    /** 운동 목록 로컬 캐시 + 마지막 fetch 날짜 */
    var workoutsCache: String
        get() = prefs.getString("workouts_cache", "") ?: ""
        set(v) = prefs.edit().putString("workouts_cache", v).apply()
    var lastFetchDate: String
        get() = prefs.getString("last_fetch_date", "") ?: ""
        set(v) = prefs.edit().putString("last_fetch_date", v).apply()

    /** 라이브 코치로 직접 뛴 기록 (Health Connect와 별개로 로컬 저장, 사용자가 숨길 수 있음) */
    var liveRunsJson: String
        get() = prefs.getString("live_runs", "") ?: ""
        set(v) = prefs.edit().putString("live_runs", v).apply()

    /** 라이브 코치 세션 상세 (고도·경사·심박 타임라인) */
    fun liveSessions(): List<LiveSession> {
        val s = prefs.getString("live_sessions", "") ?: ""
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tl = o.optJSONArray("tl")
                val timeline = if (tl != null) (0 until tl.length()).map { tl.getString(it) } else emptyList()
                val rt = o.optJSONArray("rt")
                val track = if (rt != null) (0 until rt.length()).map {
                    val p = rt.getJSONArray(it); p.getDouble(0) to p.getDouble(1)
                } else emptyList()
                LiveSession(o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.getDouble("el"), o.optDouble("lo", 0.0),
                    o.getInt("hr"), o.getInt("g"), timeline, track)
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addLiveSession(ls: LiveSession) {
        val cur = liveSessions().toMutableList()
        cur.add(ls)
        val arr = JSONArray()
        cur.takeLast(50).forEach { l ->
            val rt = JSONArray(); l.track.forEach { rt.put(JSONArray().put(it.first).put(it.second)) }
            arr.put(JSONObject().put("s", l.start).put("e", l.end).put("d", l.distM).put("el", l.elevGainM).put("lo", l.elevLossM)
                .put("hr", l.hrMax).put("g", l.gradeMax).put("tl", JSONArray(l.timeline)).put("rt", rt))
        }
        prefs.edit().putString("live_sessions", arr.toString()).apply()
    }

    /** 주어진 시간대(운동)와 겹치는 라이브 세션 (코칭 합치기용) */
    fun liveSessionFor(start: Long, end: Long): LiveSession? = liveSessions().firstOrNull { it.overlaps(start, end) }

    /** 지도 고도(DEM) 계산 결과 캐시 — 세션 시작시각 기준 (총오르막, 총내리막). 없으면 null */
    fun demFor(start: Long): Pair<Double, Double>? {
        val g = prefs.getFloat("dem_g_$start", -1f)
        return if (g >= 0f) g.toDouble() to prefs.getFloat("dem_l_$start", 0f).toDouble() else null
    }
    fun setDem(start: Long, gain: Double, loss: Double) =
        prefs.edit().putFloat("dem_g_$start", gain.toFloat()).putFloat("dem_l_$start", loss.toFloat()).apply()

    /** 앱을 마지막으로 연 날짜 — '오늘 첫 방문' 안내용 (fetch 여부와 무관) */
    var lastSeenDate: String
        get() = prefs.getString("last_seen_date", "") ?: ""
        set(v) = prefs.edit().putString("last_seen_date", v).apply()

    /** 알고 있는 최고 기록(초) — 갱신 감지해서 빵빠레 띄우기용. 0=아직 없음 */
    var bestKnown5kSec: Int
        get() = prefs.getInt("best_known_5k", 0)
        set(v) = prefs.edit().putInt("best_known_5k", v).apply()
    var bestKnown10kSec: Int
        get() = prefs.getInt("best_known_10k", 0)
        set(v) = prefs.edit().putInt("best_known_10k", v).apply()

    // ----- 숨긴 운동 (중복/원치 않는 기록을 앱에서 안 보이게) -----
    fun hiddenIds(): Set<Long> =
        (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    fun hide(id: Long) {
        val cur = hiddenIds().map { it.toString() }.toMutableSet()
        cur.add(id.toString())
        prefs.edit().putStringSet("hidden", cur).apply()
    }

    fun unhide(id: Long) {
        val cur = hiddenIds().map { it.toString() }.toMutableSet()
        cur.remove(id.toString())
        prefs.edit().putStringSet("hidden", cur).apply()
    }

    // ----- "둘 다 유지"로 처리한 중복쌍 (다시 안 물어보게) -----
    fun resolvedPairs(): Set<String> =
        (prefs.getStringSet("resolved", emptySet()) ?: emptySet()).toSet()

    fun addResolved(key: String) {
        val cur = resolvedPairs().toMutableSet()
        cur.add(key)
        prefs.edit().putStringSet("resolved", cur).apply()
    }
}
