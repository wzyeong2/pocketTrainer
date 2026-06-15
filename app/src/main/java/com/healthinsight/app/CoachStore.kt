package com.healthinsight.app

import android.content.Context

/** API 키·코칭 기록·메모를 기기에 저장 */
class CoachStore(context: Context) {
    private val prefs = context.getSharedPreferences("running_coach", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("provider", v).apply()

    var geminiKey: String
        get() = prefs.getString("key_gemini", "") ?: ""
        set(v) = prefs.edit().putString("key_gemini", v).apply()

    var claudeKey: String
        get() = prefs.getString("key_claude", "") ?: ""
        set(v) = prefs.edit().putString("key_claude", v).apply()

    fun keyFor(p: String): String = if (p == "claude") claudeKey else geminiKey

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

    /** 운동별 코칭 결과 */
    fun getCoaching(id: Long): String? = prefs.getString("coach_$id", null)
    fun setCoaching(id: Long, text: String) = prefs.edit().putString("coach_$id", text).apply()

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

    /** 앱을 마지막으로 연 날짜 — '오늘 첫 방문' 안내용 (fetch 여부와 무관) */
    var lastSeenDate: String
        get() = prefs.getString("last_seen_date", "") ?: ""
        set(v) = prefs.edit().putString("last_seen_date", v).apply()

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
