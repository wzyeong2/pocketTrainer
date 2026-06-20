package com.healthinsight.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 라이브 러닝 코치 공유 상태 (UI ↔ 서비스). Compose State라 UI가 자동 갱신됨. */
object LiveCoach {
    var phase by mutableStateOf("idle")   // idle / running / finished
    var distM by mutableStateOf(0.0)
    var elapsedSec by mutableStateOf(0L)
    var curPace by mutableStateOf(0)
    var avgPace by mutableStateOf(0)
    var kmDone by mutableStateOf(0)
    var cue by mutableStateOf("코칭 준비 중...")
    var targetPace by mutableStateOf(330)
    var simPace by mutableStateOf(360)
    var mode by mutableStateOf("gps")     // gps / sim — 기본은 실제 GPS (시뮬은 테스트용)
    var voice by mutableStateOf(true)
    var aiOn by mutableStateOf(false)

    // 목표: free / pace / distance / time / interval
    var goalType by mutableStateOf("pace")
    var goalDistanceKm by mutableStateOf(5.0)
    var goalTimeMin by mutableStateOf(30)
    var intervalWorkSec by mutableStateOf(60)
    var intervalRestSec by mutableStateOf(90)
    var intervalSets by mutableStateOf(6)
    var intervalLabel by mutableStateOf("")   // 현재 인터벌 상태 표시 (예: "빠르게 3/6")
    var targetHr by mutableStateOf(145)        // 심박존 목표

    // 프로그램 모드: 처방된 세션을 라이브로 재생
    var programSegments by mutableStateOf<List<ProgramSegment>>(emptyList())
    var programTitle by mutableStateOf("")

    // 실시간 고도·경사 (폰 기압계)
    var hasBaro by mutableStateOf(false)
    var gradePct by mutableStateOf(0)      // 현재 경사도 %
    var elevGainM by mutableStateOf(0.0)   // 누적 상승고도 m (심폐 부하)
    var elevLossM by mutableStateOf(0.0)   // 누적 하강고도 m (근골격·제동 부하)

    // 라이브 타임라인: 음성 코칭 시점 + 심박/페이스/경사 샘플 (사후 AI 분석용)
    var timeline by mutableStateOf<List<String>>(emptyList())
}

/**
 * 라이브 러닝 코치를 포그라운드 서비스로 실행 → 화면 꺼지거나 폰이 벨트/주머니에 있어도
 * GPS 추적 + 음성 코칭이 계속 돌아간다. (블루투스 이어폰으로 음성 출력)
 */
class LiveCoachService : Service() {
    private var tts: TextToSpeech? = null
    private var scope: CoroutineScope? = null
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var startTs = 0L
    private var lastCueMs = 0L
    private var kmSplitSec = 0.0
    private var lastLoc: Location? = null
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var goalAnnounced = false
    private var lastIntervalPhase = ""
    private var lastProgSeg = -1
    private var hrOver = false          // 현재 심박이 목표 초과 알림 상태인지
    private var lastHrAlertMs = 0L      // 마지막 심박 초과 알림 시각 (리트라이 쿨다운용)
    private val hrRealertMs = 120_000L  // 초과 지속 시 2분마다 재알림

    // 기압계 고도/경사
    private var sensorMgr: SensorManager? = null
    private var baroListener: SensorEventListener? = null
    private var curAlt = 0.0            // 스무딩된 현재 고도(m)
    private var altInit = false
    private var altRef = 0.0            // 누적 상승 기준점(저점)
    private var altRefDown = 0.0        // 누적 하강 기준점(고점)
    private val gradeWindow = ArrayDeque<Pair<Double, Double>>()  // (수평거리m, 고도m)
    private var lastGradeCueMs = 0L
    private var lastSampleSec = 0L      // 타임라인 샘플 주기
    private var hrMaxSeen = 0
    private var gradeMaxSeen = 0

    private fun addTimeline(s: String) {
        if (LiveCoach.timeline.size < 150) LiveCoach.timeline = LiveCoach.timeline + s
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("live_coach", "라이브 러닝 코치", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { finishRun(); return START_NOT_STICKY }
        val type = if (LiveCoach.mode == "gps") ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        ServiceCompat.startForeground(this, 1, buildNotif("러닝 시작!", "0.00km"), type)
        startRun()
        return START_STICKY
    }

    private fun startRun() {
        startTs = System.currentTimeMillis()
        lastCueMs = 0L; kmSplitSec = 0.0; lastLoc = null; window.clear()
        goalAnnounced = false; lastIntervalPhase = ""; lastProgSeg = -1; LiveCoach.intervalLabel = ""
        hrOver = false; lastHrAlertMs = 0L
        altInit = false; curAlt = 0.0; altRef = 0.0; altRefDown = 0.0; gradeWindow.clear(); lastGradeCueMs = 0L
        lastSampleSec = 0L; LiveCoach.timeline = emptyList(); hrMaxSeen = 0; gradeMaxSeen = 0
        LiveCoach.elevLossM = 0.0
        LiveCoach.phase = "running"
        LiveCoach.distM = 0.0; LiveCoach.elapsedSec = 0; LiveCoach.kmDone = 0
        LiveCoach.curPace = 0; LiveCoach.avgPace = 0; LiveCoach.cue = "코칭 준비 중..."
        LiveCoach.hasBaro = false; LiveCoach.gradePct = 0; LiveCoach.elevGainM = 0.0
        speak("자, 출발! 목표 페이스 ${mmss(LiveCoach.targetPace)} 맞춰서 가보자!")

        if (LiveCoach.mode == "gps") { startGps(); startBaro() }

        scope = CoroutineScope(Dispatchers.Main)
        scope!!.launch { while (isActive) { delay(1000); tick() } }
    }

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LiveCoach.cue = "⚠️ 위치 권한이 필요해요"
            return
        }
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listener = LocationListener { loc ->
            val last = lastLoc
            if (last != null) { val d = haversine(last, loc); if (d < 50) LiveCoach.distM += d }
            lastLoc = loc
        }
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener!!) }
        catch (e: SecurityException) { LiveCoach.cue = "⚠️ 위치 권한 오류" }
    }

    /** 폰 기압계로 실시간 고도 추적 (EMA 스무딩) */
    private fun startBaro() {
        sensorMgr = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorMgr?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) { LiveCoach.hasBaro = false; return }
        LiveCoach.hasBaro = true
        baroListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
                curAlt = if (!altInit) { altInit = true; altRef = alt; altRefDown = alt; alt } else curAlt * 0.9 + alt * 0.1
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensorMgr?.registerListener(baroListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopBaro() {
        baroListener?.let { sensorMgr?.unregisterListener(it) }
        baroListener = null
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (LiveCoach.mode == "sim") LiveCoach.distM += 1000.0 / LiveCoach.simPace
        LiveCoach.elapsedSec = (now - startTs) / 1000
        window.addLast(now to LiveCoach.distM)
        while (window.isNotEmpty() && now - window.first().first > 6000) window.removeFirst()
        LiveCoach.curPace = if (window.size >= 2) {
            val a = window.first(); val b = window.last()
            val dd = (b.second - a.second) / 1000.0; val dt = (b.first - a.first) / 1000.0
            if (dd > 0) (dt / dd).roundToInt() else 0
        } else 0
        LiveCoach.avgPace = if (LiveCoach.distM > 0) (LiveCoach.elapsedSec / (LiveCoach.distM / 1000.0)).roundToInt() else 0

        val km = (LiveCoach.distM / 1000).toInt()
        if (km > LiveCoach.kmDone) {
            LiveCoach.kmDone = km
            val split = (LiveCoach.elapsedSec - kmSplitSec).toInt()
            kmSplitSec = LiveCoach.elapsedSec.toDouble()
            val base = "${km}킬로미터 통과! 스플릿 ${mmss(split)}"
            LiveCoach.cue = "✅ $base"; speak(base)
            if (LiveCoach.aiOn) aiCheer(km)
        }
        BleHeart.bpm?.let { if (it > hrMaxSeen) hrMaxSeen = it }
        if (LiveCoach.gradePct > gradeMaxSeen) gradeMaxSeen = LiveCoach.gradePct
        // 타임라인 샘플 (45초마다 심박/페이스/경사)
        if (LiveCoach.elapsedSec - lastSampleSec >= 45 && LiveCoach.distM > 0) {
            lastSampleSec = LiveCoach.elapsedSec
            val hr = BleHeart.bpm?.let { " 심박$it" } ?: ""
            val gr = if (LiveCoach.hasBaro && LiveCoach.gradePct != 0) " 경사${LiveCoach.gradePct}%" else ""
            addTimeline("${fmtDur(LiveCoach.elapsedSec)} ${"%.2f".format(LiveCoach.distM / 1000)}km 페이스${mmss(LiveCoach.curPace)}$hr$gr")
        }
        handleAltitude(now)
        handleGoal(now)
        updateNotif()
    }

    /** 실시간 고도/경사 계산 + 오르막·내리막 코칭 (GPS 모드 + 기압계) */
    private fun handleAltitude(now: Long) {
        if (LiveCoach.mode != "gps" || !altInit) return
        // 누적 상승/하강고도 (히스테리시스로 기압 노이즈 억제)
        if (curAlt >= altRef + 1.0) { LiveCoach.elevGainM += curAlt - altRef; altRef = curAlt }
        else if (curAlt < altRef) altRef = curAlt
        if (curAlt <= altRefDown - 1.0) { LiveCoach.elevLossM += altRefDown - curAlt; altRefDown = curAlt }
        else if (curAlt > altRefDown) altRefDown = curAlt
        // 실시간 경사: 최근 약 40m 수평거리 구간의 고도차 / 거리
        gradeWindow.addLast(LiveCoach.distM to curAlt)
        while (gradeWindow.size >= 2 && LiveCoach.distM - gradeWindow.first().first > 40.0) gradeWindow.removeFirst()
        val start = gradeWindow.first()
        val dh = LiveCoach.distM - start.first
        if (dh < 15.0) return  // 수평 이동이 충분치 않으면 경사 판단 보류
        val grade = ((curAlt - start.second) / dh * 100).roundToInt().coerceIn(-40, 40)
        LiveCoach.gradePct = grade
        if (now - lastGradeCueMs > 45000) {
            when {
                grade >= 4 -> { lastGradeCueMs = now; val m = "${grade}퍼센트 오르막이야. 페이스 욕심내지 말고 심박 지켜"; LiveCoach.cue = "⛰️ $m"; speak(m) }
                grade <= -4 -> { lastGradeCueMs = now; val m = "내리막이야. 보폭 짧게, 무릎 보호하면서 가자"; LiveCoach.cue = "🏞️ $m"; speak(m) }
            }
        }
    }

    private fun handleGoal(now: Long) {
        when (LiveCoach.goalType) {
            "program" -> handleProgram(now)
            "hr" -> handleHrZone(now)
            "interval" -> handleInterval()
            "distance" -> {
                if (!goalAnnounced && LiveCoach.distM >= LiveCoach.goalDistanceKm * 1000) {
                    goalAnnounced = true
                    val m = "목표 ${"%.1f".format(LiveCoach.goalDistanceKm)}킬로미터 완주! 정말 잘했어 🎉"
                    LiveCoach.cue = "🎯 $m"; speak(m)
                } else paceTick(now)
            }
            "time" -> {
                if (!goalAnnounced && LiveCoach.elapsedSec >= LiveCoach.goalTimeMin * 60L) {
                    goalAnnounced = true
                    val m = "목표 ${LiveCoach.goalTimeMin}분 달성! 끝까지 잘 버텼어 🎉"
                    LiveCoach.cue = "🎯 $m"; speak(m)
                } else paceTick(now)
            }
            "free" -> {
                if (now - lastCueMs > 30000 && LiveCoach.curPace > 0) {
                    lastCueMs = now
                    val m = "좋아, 이 리듬 그대로 가자 👍"; LiveCoach.cue = "🗣️ $m"; speak(m)
                }
            }
            else -> paceTick(now) // pace
        }
    }

    private fun handleProgram(now: Long) {
        val segs = LiveCoach.programSegments
        if (segs.isEmpty()) { paceTick(now); return }
        val total = LiveCoach.elapsedSec
        var acc = 0L; var idx = -1
        for (i in segs.indices) {
            val end = acc + segs[i].durationSec
            if (total < end) { idx = i; break }
            acc = end
        }
        if (idx < 0) { // 모든 구간 완료
            if (lastProgSeg != -999) {
                lastProgSeg = -999
                val m = "프로그램 세션 완료! 정말 잘했어"; LiveCoach.cue = "🎯 $m"; speak(m)
            }
            return
        }
        val seg = segs[idx]
        LiveCoach.intervalLabel = "${seg.label} (${idx + 1}/${segs.size})"
        if (idx != lastProgSeg) { // 새 구간 시작 안내
            lastProgSeg = idx
            val tp = if (seg.targetPaceSec > 0) ", 목표 ${mmss(seg.targetPaceSec)}" else ""
            val th = if (seg.targetHr > 0) ", 심박 ${seg.targetHr}" else ""
            val m = "${seg.label}, ${seg.durationSec / 60}분$tp$th"
            LiveCoach.cue = "📋 $m"; speak(m); lastCueMs = now
            return
        }
        // 구간 중 페이스+심박 통합 준수 체크 (15초마다)
        if (now - lastCueMs > 15000) {
            lastCueMs = now
            val paceOk = seg.targetPaceSec <= 0 || LiveCoach.curPace <= 0 ||
                kotlin.math.abs(LiveCoach.curPace - seg.targetPaceSec) <= 15
            val hr = BleHeart.bpm
            val hrHigh = seg.targetHr > 0 && hr != null && hr > seg.targetHr + 5
            val msg = when {
                !paceOk && hrHigh -> "페이스도 심박도 높아, 무리 말고 줄여"
                paceOk && hrHigh -> "페이스는 좋은데 심박 ${hr} 높아 — 오르막/피로일 수 있어, 살짝 줄여"
                !paceOk && LiveCoach.curPace > seg.targetPaceSec -> "페이스 느려, 목표 ${mmss(seg.targetPaceSec)}로 올려"
                !paceOk -> "페이스 빨라, 목표 ${mmss(seg.targetPaceSec)}로 줄여"
                seg.targetHr > 0 && hr != null && hr < seg.targetHr - 10 -> "여유 있어, 조금 올려도 돼"
                else -> "좋아, 이 구간 잘 지키고 있어"
            }
            LiveCoach.cue = "🗣️ $msg"; speak(msg)
        }
    }

    private fun handleHrZone(now: Long) {
        val hr = BleHeart.bpm
        if (hr == null) {
            if (now - lastCueMs > 20000) { lastCueMs = now; LiveCoach.cue = "⌚ 심박 센서 연결을 기다리는 중..." }
            return
        }
        val t = LiveCoach.targetHr
        if (hr > t) {
            // 목표 초과: 처음 넘었거나(상태 전환) 마지막 알림 후 쿨다운(2분) 지났을 때만 알림
            if (!hrOver || now - lastHrAlertMs >= hrRealertMs) {
                hrOver = true; lastHrAlertMs = now
                val msg = "심박 ${hr}, 목표 ${t} 넘었어. 속도 줄이고 호흡 골라"
                LiveCoach.cue = "❤️ $msg"; speak(msg)
            }
        } else {
            // 목표존으로 복귀: 직전에 초과 알림 상태였다면 안정화 안내 1회
            if (hrOver) {
                hrOver = false
                val msg = "좋아, 심박 ${hr}으로 안정됐어"
                LiveCoach.cue = "❤️ $msg"; speak(msg)
            } else if (hr < t - 10 && now - lastCueMs > 120000) {
                // 너무 여유로우면 가끔(2분 간격) 한 번 격려
                lastCueMs = now
                val msg = "심박 ${hr}, 여유 있어. 살짝 올려도 돼"
                LiveCoach.cue = "❤️ $msg"; speak(msg)
            }
        }
    }

    private fun paceTick(now: Long) {
        if (LiveCoach.curPace > 0 && now - lastCueMs > 15000) {
            lastCueMs = now
            val delta = LiveCoach.curPace - LiveCoach.targetPace
            val msg = when {
                delta > 15 -> "페이스가 느려지고 있어! 조금만 더 힘내자 💪"
                delta < -15 -> "너무 빠른데? 페이스 유지하면서 힘 아끼자"
                else -> "좋아! 목표 페이스 딱 맞아, 이대로 가자 👍"
            }
            LiveCoach.cue = "🗣️ $msg"; speak(msg)
        }
    }

    private fun handleInterval() {
        val work = LiveCoach.intervalWorkSec
        val rest = LiveCoach.intervalRestSec
        val sets = LiveCoach.intervalSets
        val cycle = work + rest
        if (cycle <= 0) return
        val total = LiveCoach.elapsedSec
        val done = (total / cycle).toInt()
        if (done >= sets) {
            if (lastIntervalPhase != "done") {
                lastIntervalPhase = "done"
                LiveCoach.intervalLabel = "완료"
                val m = "인터벌 ${sets}세트 완료! 수고했어 🔥"; LiveCoach.cue = "🎯 $m"; speak(m)
            }
            return
        }
        val inCycle = total % cycle
        val setNo = done + 1
        val phase = if (inCycle < work) "work" else "rest"
        val key = "$phase$setNo"
        if (key != lastIntervalPhase) {
            lastIntervalPhase = key
            if (phase == "work") {
                LiveCoach.intervalLabel = "빠르게 $setNo/$sets"
                val m = "$setNo 세트! 빠르게 달려 🔥"; LiveCoach.cue = "🏃 $m"; speak(m)
            } else {
                LiveCoach.intervalLabel = "회복 $setNo/$sets"
                val m = "회복! 천천히 숨 골라 😮‍💨"; LiveCoach.cue = "🧘 $m"; speak(m)
            }
        }
    }

    private fun aiCheer(km: Int) {
        val store = CoachStore(this)
        val key = store.keyFor(store.provider)
        if (key.isBlank()) return
        scope?.launch {
            val prompt = "러닝 코치로서 딱 한 문장, 20자 내외로 짧고 힘차게 응원해줘. 지금 ${km}km 지났어. 한국어로."
            AiCoach.generate(store.provider, key, prompt).onSuccess {
                val line = it.replace("\n", " ").take(60)
                LiveCoach.cue = "🤖 $line"; speak(line)
            }
        }
    }

    private fun finishRun() {
        listener?.let { lm?.removeUpdates(it) }; listener = null
        stopBaro()
        scope?.cancel(); scope = null
        val km = LiveCoach.distM / 1000
        LiveCoach.avgPace = if (km > 0) (LiveCoach.elapsedSec / km).roundToInt() else 0
        // 라이브 세션 상세(고도·경사·심박·타임라인) 저장 → 삼성 기록과 합쳐 코칭에 사용.
        // 별도 운동 기록으로는 안 남겨서 마일리지 중복(뻥튀기) 방지.
        if (LiveCoach.mode == "gps" && LiveCoach.distM >= 100.0 && LiveCoach.elapsedSec >= 60) {
            try {
                CoachStore(this).addLiveSession(
                    LiveSession(
                        start = startTs,
                        end = System.currentTimeMillis(),
                        distM = LiveCoach.distM,
                        elevGainM = LiveCoach.elevGainM,
                        elevLossM = LiveCoach.elevLossM,
                        hrMax = hrMaxSeen,
                        gradeMax = gradeMaxSeen,
                        timeline = LiveCoach.timeline,
                    )
                )
            } catch (_: Exception) {}
        }
        speak("수고했어! ${"%.1f".format(km)}킬로미터 완주!")
        LiveCoach.phase = "finished"
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.let { lm?.removeUpdates(it) }
        stopBaro()
        scope?.cancel()
        tts?.stop(); tts?.shutdown()
    }

    private fun speak(s: String) {
        addTimeline("${fmtDur(LiveCoach.elapsedSec)} [코칭]${BleHeart.bpm?.let { " 심박$it" } ?: ""}: $s")
        if (!LiveCoach.voice) return
        // 음성은 이모지·기호 제거 (TTS가 "체크 표시" 같이 읽는 것 방지)
        val clean = s.replace(Regex("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9 .,!?~:/%-]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isNotEmpty()) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "c")
    }

    private fun buildNotif(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "live_coach")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setContentIntent(pi).build()
    }

    private fun updateNotif() {
        val hrTxt = BleHeart.bpm?.let { " · ❤️$it" } ?: ""
        val n = buildNotif(
            "🏃 러닝 중 ${"%.2f".format(LiveCoach.distM / 1000)}km",
            "현재 ${mmss(LiveCoach.curPace)} · 평균 ${mmss(LiveCoach.avgPace)} · ${fmtDur(LiveCoach.elapsedSec)}$hrTxt"
        )
        getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun mmss(sec: Int) = if (sec <= 0) "-" else "%d:%02d".format(sec / 60, sec % 60)
    private fun fmtDur(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)
    private fun haversine(a: Location, b: Location): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val s = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(s))
    }

    companion object {
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveCoachService::class.java))
        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, LiveCoachService::class.java).setAction("STOP"))
    }
}
