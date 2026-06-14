package com.healthinsight.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini(무료) / Claude(유료) 둘 다 지원하는 AI 코치.
 * 이미지(카메라 사진)도 함께 보낼 수 있다.
 */
object AiCoach {

    private val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")
    private const val CLAUDE_MODEL = "claude-sonnet-4-6"

    suspend fun generate(
        provider: String,
        apiKey: String,
        prompt: String,
        imageJpeg: ByteArray? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        // 네트워크 오류면 최대 3번까지 재시도 (연결 끊김 대비)
        repeat(3) { attempt ->
            try {
                val text = if (provider == "claude") callClaude(apiKey, prompt, imageJpeg)
                else callGemini(apiKey, prompt, imageJpeg)
                return@withContext Result.success(text.trim())
            } catch (e: java.io.IOException) {
                lastError = e
                delay(800L * (attempt + 1)) // 0.8s, 1.6s 후 재시도
            } catch (e: Exception) {
                return@withContext Result.failure(RuntimeException(friendlyError(e.message ?: "오류"))) // API 오류(키 등)는 재시도 안 함
            }
        }
        Result.failure(lastError ?: RuntimeException("네트워크 오류로 실패했어요"))
    }

    /** 선수 정보 기반 주간 러닝 프로그램 생성 (JSON 원본 반환) */
    suspend fun generateProgram(provider: String, apiKey: String, profile: String): Result<String> {
        val prompt = buildString {
            appendLine("너는 러닝 코치야. 아래 선수 정보를 바탕으로 '이번 주 러닝 훈련 3회' 프로그램을 설계해.")
            appendLine()
            append(profile)
            appendLine()
            appendLine("반드시 JSON만 출력해 (설명·인사·마크다운·코드펜스 전부 금지).")
            appendLine("형식:")
            appendLine("{\"sessions\":[{\"title\":\"화 - 이지런\",\"focus\":\"유산소 지구력\",\"segments\":[{\"label\":\"워밍업 걷기\",\"durationSec\":300,\"targetPaceSec\":0,\"targetHr\":0},{\"label\":\"이지런\",\"durationSec\":1500,\"targetPaceSec\":390,\"targetHr\":142}]}]}")
            appendLine("규칙: durationSec=초, targetPaceSec=초/km(없으면 0), targetHr=bpm(없으면 0).")
            appendLine("세션 정확히 3개. 각 세션은 워밍업·메인·쿨다운 등 2~5구간. 사용자 레벨·목표·부상·고도보정·심박존을 반영하고 무리한 부하는 금지.")
        }
        return generate(provider, apiKey, prompt, null)
    }

    /** 429/quota 등 원시 오류를 사용자 친화 메시지로 변환 */
    private fun friendlyError(raw: String): String {
        val low = raw.lowercase()
        return if (low.contains("quota") || low.contains("resource_exhausted") || low.contains("exceeded") || low.contains("429")) {
            val sec = Regex("retry in ([0-9.]+)s").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
            val whenTxt = if (sec != null && sec < 120) "약 ${sec}초 뒤" else "분당 한도면 1분쯤 뒤, 일일 한도면 내일(태평양 자정)"
            "무료 사용량(quota)을 다 썼어요 😢 $whenTxt 다시 풀려요.\n잠시 후 다시 누르거나, ⚙️ 설정에서 Claude로 바꿔봐."
        } else raw
    }

    // ---------- Gemini ----------
    private fun callGemini(apiKey: String, prompt: String, image: ByteArray?): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))

        var lastErr = "알 수 없는 오류"
        for (model in GEMINI_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val (code, resp) = httpPost(url, mapOf("Content-Type" to "application/json"), body.toString())
            if (code in 200..299) {
                return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            }
            lastErr = parseError(resp, code)
            if (code != 429) throw RuntimeException(lastErr) // 429면 다음 모델로 폴백
        }
        throw RuntimeException(lastErr)
    }

    // ---------- Claude ----------
    private fun callClaude(apiKey: String, prompt: String, image: ByteArray?): String {
        val content = JSONArray()
        if (image != null) {
            content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", CLAUDE_MODEL).put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val (code, resp) = httpPost(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json",
            ),
            body.toString()
        )
        if (code !in 200..299) throw RuntimeException(parseError(resp, code))
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
    }

    // ---------- 공통 HTTP ----------
    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun parseError(resp: String, code: Int): String = try {
        JSONObject(resp).getJSONObject("error").getString("message")
    } catch (e: Exception) { "HTTP $code" }
}

/** 운동 기록 → 코칭 프롬프트 */
object CoachPrompt {

    /** 러닝 코칭 전문 지식 — 작은 모델도 전문적으로 답하게 주입 */
    private val RUNNING_GUIDE = """
[코칭 지침 — 러닝 (반드시 반영)]
- 경사도(%) = 상승고도(m) ÷ 거리(m) × 100. 누적고도만 보지 말고, 오르막이 어느 구간에 몰렸는지·경사도를 감안해.
- GAP(경사보정페이스): 오르막은 평지보다 느린 게 정상. 대략 +1% 오르막 ≈ 10~20초/km 손해, +2% ≈ 25~40초, +3% ≈ 40~60초, +5%↑는 페이스 욕심 금지.
- 심박 해석: 같은 페이스라도 오르막·선행피로·더위면 심박이 오름. 페이스만 보지 말고 심박과 묶어서 '효율'을 평가해.
- [내 프로필]의 최근 기록과 비교해서(거리·페이스·심박·고도 차이) 왜 이렇게 나왔는지 설명해줘.
- 유산소런이면 "오르막에선 페이스를 버리고 목표 심박을 지켜라"로 코칭. 성공 기준은 페이스가 아니라 심박을 안 터뜨리는 것.
""".trim()


    fun summarize(w: WorkoutRecord): String = buildString {
        appendLine("• 종류: ${w.type.emoji} ${w.type.label}")
        if (w.type.distanceBased && w.distanceMeters > 0) appendLine("• 거리: ${"%.2f".format(w.distanceKm)}km")
        appendLine("• 시간: ${formatDuration(w.durationSec)}")
        if (w.type.distanceBased && w.distanceMeters > 0) appendLine("• 평균 페이스: ${formatPace(w.avgPaceSecPerKm)}")
        if (w.avgHr != null) appendLine("• 평균/최고 심박: ${w.avgHr}/${w.maxHr} bpm")
        if (w.calories != null) appendLine("• 칼로리: ${"%.0f".format(w.calories)}kcal")
        val e = w.elevationGainM
        if (e != null) {
            appendLine("• 고도 상승: ${"%.0f".format(e)}m")
            if (w.distanceMeters > 0) appendLine("• 평균 경사도: ${"%.1f".format(e / w.distanceMeters * 100)}%")
        }
        if (w.splits.isNotEmpty()) {
            append("• 구간별 페이스: ")
            appendLine(w.splits.joinToString(", ") { "${it.label} ${formatPace(it.paceSecPerKm)}" })
        }
    }

    fun build(w: WorkoutRecord, memo: String, profile: String, hasImage: Boolean): String = buildString {
        appendLine("너는 사용자의 친한 운동 코치 친구야. 반말로 친근하고 격려하는 말투로 코칭해줘. 이모지도 적당히.")
        appendLine()
        if (profile.isNotBlank()) { append(profile); appendLine() }
        appendLine("[오늘 운동]")
        append(summarize(w))
        if (memo.isNotBlank()) appendLine("• 메모: $memo")
        val elev = w.elevationGainM
        if (elev != null && elev > 5.0) {
            appendLine("• 참고: 고도 상승 ${"%.0f".format(elev)}m. 오르막 구간이 있으니 페이스를 평가할 때 경사를 감안해서 봐줘.")
        }
        if (hasImage) appendLine("\n첨부한 사진도 함께 분석해줘 (기구/자세/장소/식단 등).")
        if (w.type == ExerciseType.RUNNING) { appendLine(); append(RUNNING_GUIDE) }
        appendLine()
        appendLine("위 [내 프로필]과 [오늘 운동]을 함께 고려해서, 아래 형식으로 (제목 유지):")
        appendLine("### 1. 오늘 평가")
        appendLine("### 2. 다음 훈련 처방")
        when (w.type) {
            ExerciseType.RUNNING -> {
                appendLine("(인터벌/템포런/롱런 중 선택 + 구체적으로)")
                appendLine("### 3. 구간별 목표 페이스")
                appendLine("### 4. 심박수 목표")
            }
            ExerciseType.STRENGTH -> {
                appendLine("(다음 분할/종목·중량·세트·휴식)")
                appendLine("### 3. 자세·폼 팁")
                appendLine("### 4. 강도 가이드")
            }
            else -> {
                appendLine("(다음 목표 거리·시간·강도)")
                appendLine("### 3. 페이스·심박 가이드")
            }
        }
        appendLine("### 5. 준비운동")
        appendLine("### 6. 보조 운동")
        appendLine()
        append("핵심만, 너무 길지 않게.")
    }
}
