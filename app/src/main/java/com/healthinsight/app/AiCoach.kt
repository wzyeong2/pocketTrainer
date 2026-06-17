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
    private const val OPENAI_MODEL = "gpt-4o"

    /** 지원 AI 제공사 — 여기에 한 줄 추가 + when 분기만 추가하면 새 제공사가 붙는다(확장성) */
    data class Provider(val id: String, val label: String, val keyUrl: String)
    val PROVIDERS = listOf(
        Provider("gemini", "Gemini (무료)", "aistudio.google.com/apikey"),
        Provider("openai", "OpenAI (GPT)", "platform.openai.com/api-keys"),
        Provider("claude", "Claude", "console.anthropic.com"),
    )
    fun providerLabel(id: String): String = PROVIDERS.firstOrNull { it.id == id }?.label ?: id

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
                val text = when (provider) {
                    "claude" -> callClaude(apiKey, prompt, imageJpeg)
                    "openai" -> callOpenAi(apiKey, prompt, imageJpeg)
                    else -> callGemini(apiKey, prompt, imageJpeg)
                }
                return@withContext Result.success(text.trim())
            } catch (e: java.net.UnknownHostException) {
                return@withContext Result.failure(RuntimeException("📡 인터넷에 연결되어 있지 않아요. 와이파이나 데이터를 켜고 다시 시도해줘."))
            } catch (e: java.net.ConnectException) {
                return@withContext Result.failure(RuntimeException("📡 인터넷 연결이 불안정해요. 연결 확인하고 다시 시도해줘."))
            } catch (e: java.io.IOException) {
                lastError = e
                delay(800L * (attempt + 1)) // 0.8s, 1.6s 후 재시도
            } catch (e: Exception) {
                return@withContext Result.failure(RuntimeException(friendlyError(e.message ?: "오류"))) // API 오류(키 등)는 재시도 안 함
            }
        }
        Result.failure(lastError ?: RuntimeException("네트워크 오류로 실패했어요"))
    }

    /** 선수 정보 기반 주간 러닝 프로그램 생성 (JSON 원본 반환). sessions=주당 훈련 횟수 */
    suspend fun generateProgram(provider: String, apiKey: String, profile: String, sessions: Int = 3): Result<String> {
        val n = sessions.coerceIn(1, 7)
        val prompt = buildString {
            appendLine("너는 러닝 코치야. 아래 선수 정보를 바탕으로 '이번 주 러닝 훈련 ${n}회' 프로그램을 설계해.")
            appendLine()
            append(profile)
            appendLine()
            appendLine("반드시 JSON만 출력해 (설명·인사·마크다운·코드펜스 전부 금지).")
            appendLine("형식:")
            appendLine("{\"sessions\":[{\"title\":\"화 - 이지런\",\"focus\":\"유산소 지구력\",\"segments\":[{\"label\":\"워밍업 걷기\",\"durationSec\":300,\"targetPaceSec\":0,\"targetHr\":0},{\"label\":\"이지런\",\"durationSec\":1500,\"targetPaceSec\":390,\"targetHr\":142}]}]}")
            appendLine("규칙: durationSec=초, targetPaceSec=초/km(없으면 0), targetHr=bpm(없으면 0).")
            appendLine("세션 정확히 ${n}개. 각 세션은 워밍업·메인·쿨다운 등 2~5구간. 강도(이지/템포/인터벌/롱런)를 한 주 안에서 적절히 분배하고, 사용자 레벨·목표·부상·고도보정·심박존을 반영하고 무리한 부하는 금지.")
        }
        return generate(provider, apiKey, prompt, null)
    }

    /** 훈련량(최근 3주 러닝 횟수)으로 주당 적정 훈련 횟수 추천 (2~5회) */
    fun recommendSessions(recentRunsLast21Days: Int): Int =
        Math.round(recentRunsLast21Days / 3.0).toInt().coerceIn(2, 5)

    /** 대화형(멀티턴) 코칭. messages: (role[user/assistant], content) */
    suspend fun chat(
        provider: String, apiKey: String, system: String,
        messages: List<Pair<String, String>>,
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val text = when (provider) {
                    "claude" -> chatClaude(apiKey, system, messages)
                    "openai" -> chatOpenAi(apiKey, system, messages)
                    else -> chatGemini(apiKey, system, messages)
                }
                return@withContext Result.success(text.trim())
            } catch (e: java.net.UnknownHostException) {
                return@withContext Result.failure(RuntimeException("📡 인터넷에 연결되어 있지 않아요."))
            } catch (e: java.io.IOException) {
                lastError = e; delay(800L * (attempt + 1))
            } catch (e: Exception) {
                return@withContext Result.failure(RuntimeException(friendlyError(e.message ?: "오류")))
            }
        }
        Result.failure(lastError ?: RuntimeException("네트워크 오류로 실패했어요"))
    }

    private fun chatOpenAi(apiKey: String, system: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        val body = JSONObject().put("model", OPENAI_MODEL).put("max_tokens", 1200).put("messages", arr)
        val (code, resp) = httpPost("https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"), body.toString())
        if (code !in 200..299) throw RuntimeException(parseError(resp, code))
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun chatClaude(apiKey: String, system: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray()
        messages.forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        val body = JSONObject().put("model", CLAUDE_MODEL).put("max_tokens", 1200)
            .put("system", system).put("messages", arr)
        val (code, resp) = httpPost("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
            body.toString())
        if (code !in 200..299) throw RuntimeException(parseError(resp, code))
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun chatGemini(apiKey: String, system: String, messages: List<Pair<String, String>>): String {
        val contents = JSONArray()
        messages.forEach { (r, c) ->
            val role = if (r == "assistant") "model" else "user"
            contents.put(JSONObject().put("role", role)
                .put("parts", JSONArray().put(JSONObject().put("text", c))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        var lastErr = "알 수 없는 오류"
        for (model in GEMINI_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val (code, resp) = httpPost(url, mapOf("Content-Type" to "application/json"), body.toString())
            if (code in 200..299) return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            lastErr = parseError(resp, code)
            if (code != 429) throw RuntimeException(lastErr)
        }
        throw RuntimeException(lastErr)
    }

    /** 대략적 토큰·비용 추정 (입력 글자수 기준). 반환: (입력토큰, 예상USD) */
    fun estimateCostUsd(provider: String, inputChars: Int, expectedOutTokens: Int = 800): Pair<Int, Double> {
        val inTok = (inputChars / 2.0).toInt()  // 한국어 대략 2자/토큰
        val (inRate, outRate) = when (provider) {
            "openai" -> 2.5e-6 to 10e-6      // gpt-4o
            "claude" -> 3e-6 to 15e-6        // sonnet
            else -> 0.0 to 0.0               // gemini 무료
        }
        return inTok to (inTok * inRate + expectedOutTokens * outRate)
    }

    /** 429/quota 등 원시 오류를 사용자 친화 메시지로 변환 */
    private fun friendlyError(raw: String): String {
        val low = raw.lowercase()
        return if (low.contains("quota") || low.contains("resource_exhausted") || low.contains("exceeded") || low.contains("429")) {
            val sec = Regex("retry in ([0-9.]+)s").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
            val whenTxt = if (sec != null && sec < 120) "약 ${sec}초 뒤" else "분당 한도면 1분쯤 뒤, 일일 한도면 내일(태평양 자정)"
            "사용량(quota)·한도에 걸렸어요 😢 $whenTxt 다시 풀려요.\n잠시 후 다시 누르거나, ⚙️ 설정에서 다른 제공사로 바꿔봐."
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

    // ---------- OpenAI (GPT) ----------
    private fun callOpenAi(apiKey: String, prompt: String, image: ByteArray?): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (image != null) {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        val body = JSONObject()
            .put("model", OPENAI_MODEL)
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val (code, resp) = httpPost(
            "https://api.openai.com/v1/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
            body.toString()
        )
        if (code !in 200..299) throw RuntimeException(parseError(resp, code))
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
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

    /** 러닝 코칭 방법론 — 일반론 말고 데이터 기반으로 답하게 주입 */
    private val RUNNING_GUIDE = """
[러닝 코칭 방법론 — 반드시 이 기준으로]
말투: 한국어 반말, 숫자 기반, 구체적. 이모지 쓰지 마. 일반론 금지 — 이 사람의 실제 기록·심박·고도·최근 피로로 "다음에 뭘 어떻게 할지"를 바로 정해줘. 기록 욕심이 과하면 제동, 통증 신호 있으면 보수적으로.

훈련 분류(먼저 판정): 회복런 / 회복성 이지런 / 이지런 / 이지런 상단 / 보통런 / 템포런 / 기록주 / 고도런 / 분산형 유산소런. 페이스만 보지 말고 심박·고도·최근 피로·생활런·하체운동까지 반영해서 분류해.

심박존(일반 기준 — [내 프로필]에 개인 기준 있으면 그게 우선):
- 회복런 7'00~7'40/km, 심박 130~140
- 이지런 6'40~7'20/km, 심박 140~145
- 유산소런 목표심박 142 전후 (페이스보다 심박 우선)
- 롱이지런 6'50~7'30/km, 심박 145~150 이하
- 보통런 5'50~6'10/km, 심박 150대
- 템포/기록주 5'30/km 이하, 심박 159+
심박 목표가 있었으면 성공/실패는 페이스가 아니라 "목표 심박을 지켰나"로 판단해.

경사 보정(GAP): 경사도% = 상승고도m ÷ 거리m × 100.
- +1% 오르막 ≈ 평지보다 10~20초/km 손해, 심박 +5~8
- +2% ≈ 25~40초/km, 심박 +8~12
- +3% ≈ 40~60초/km, +5%↑는 페이스 욕심 금지
누적고도만 보지 말고 "오르막이 후반에 몰렸는지"를 봐 — 후반 오르막은 평균심박을 크게 올려. 같은 페이스도 오르막·더위·선행피로면 심박이 오르니 '효율'로 평가해.

부상 신호(있으면 보수적): 정강이 앞 한 점 통증, 종아리 뒤 부음/열감, 아킬레스 찌릿, 무릎 위 허벅지, 허벅지 사이드, 발가락/발볼 압박. 걷기·계단 내려갈 때 통증이 지속되면 러닝 금지하고 휴식/걷기/자전거로 돌려. 강한 기록주·템포 후엔 24~48시간 회복을 '훈련'으로 처방해.

부하 합산: 생활런·이동조깅도 다리 부담·부상 계산엔 100% 포함(단 기록향상 훈련효과는 낮게 평가). 하체운동(스쿼트 등) 피로와 러닝 피로가 겹치는지 같이 봐. 최근 주간거리가 급증했으면 안정화·부상방지를 최우선으로(심폐는 빨리 좋아져도 힘줄·정강이·발은 천천히 적응).
""".trim()


    /** 챗 시스템 프롬프트 등에서 재사용할 러닝 방법론 */
    fun guide(): String = RUNNING_GUIDE

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
            courseDifficulty(e, w.distanceMeters)?.let {
                appendLine("• 코스 난이도: ${it.label} (km당 +${it.climbPerKm}m 상승) — 페이스 평가 시 이 오르막 부하를 GAP로 감안할 것")
            }
        }
        if (w.splits.isNotEmpty()) {
            append("• 구간별 페이스: ")
            appendLine(w.splits.joinToString(", ") { "${it.label} ${formatPace(it.paceSecPerKm)}" })
        }
    }

    fun build(w: WorkoutRecord, memo: String, profile: String, hasImage: Boolean): String = buildString {
        appendLine("너는 사용자의 러닝/운동 코치야. 한국어 반말로, 숫자 기반으로 구체적으로 코칭해. 이모지는 쓰지 마. 일반론 말고 이 사람의 데이터로 다음 행동을 바로 정해줘.")
        appendLine()
        if (profile.isNotBlank()) { append(profile); appendLine() }
        appendLine("[오늘 운동]")
        append(summarize(w))
        if (memo.isNotBlank()) appendLine("• 메모: $memo")
        if (hasImage) appendLine("\n첨부한 사진도 함께 분석해줘 (기구/자세/장소/식단 등).")
        if (w.type == ExerciseType.RUNNING) {
            appendLine(); append(RUNNING_GUIDE); appendLine(); appendLine()
            appendLine("위 방법론과 [내 프로필]·[오늘 운동]을 종합해서, 정확히 아래 7개 제목으로 답해 (제목 유지):")
            appendLine("### 1. 훈련 분류")
            appendLine("(회복/이지/이지상단/보통/템포/기록주/고도런/분산형 중 판정 + 근거)")
            appendLine("### 2. 기록 요약")
            appendLine("(거리·시간·평균페이스·평균/최대심박·누적고도·경사, 가능하면 이전 기록 대비)")
            appendLine("### 3. 잘한 점")
            appendLine("### 4. 아쉬운 점")
            appendLine("### 5. 부상 리스크")
            appendLine("(체크할 부위 + 내일 아침 확인할 증상)")
            appendLine("### 6. 다음 운동")
            appendLine("(러닝 가능 여부 + 거리·페이스·심박·코스·금지사항까지 구체적으로)")
            appendLine("### 7. 한 줄 결론")
        } else {
            appendLine()
            appendLine("위 [내 프로필]과 [오늘 운동]을 함께 고려해서 아래 형식으로 (제목 유지):")
            appendLine("### 1. 오늘 평가")
            appendLine("### 2. 다음 훈련 처방")
            when (w.type) {
                ExerciseType.STRENGTH -> {
                    appendLine("(다음 분할/종목·중량·세트·휴식)")
                    appendLine("### 3. 자세·폼 팁"); appendLine("### 4. 강도 가이드")
                }
                else -> {
                    appendLine("(다음 목표 거리·시간·강도)")
                    appendLine("### 3. 페이스·심박 가이드")
                }
            }
            appendLine("### 5. 준비운동"); appendLine("### 6. 보조 운동")
        }
        appendLine()
        append("숫자로 구체적으로, 군더더기 없이.")
    }
}
