package com.healthinsight.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun mmss(sec: Int): String = if (sec <= 0) "-" else "%d:%02d".format(sec / 60, sec % 60)
private fun parsePace(s: String): Int {
    val p = s.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

@Composable
fun LiveRunningScreen(
    store: CoachStore,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    hasBlePermission: Boolean,
    onRequestBle: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var partyShown by remember { mutableStateOf(false) }
    LaunchedEffect(LiveCoach.phase) { if (LiveCoach.phase != "finished") partyShown = false }

    var targetText by remember { mutableStateOf("5:30") }
    var distText by remember { mutableStateOf("5") }
    var timeText by remember { mutableStateOf("30") }
    var workText by remember { mutableStateOf("60") }
    var restText by remember { mutableStateOf("90") }
    var setsText by remember { mutableStateOf("6") }
    var hrText by remember { mutableStateOf("145") }
    var summaryCoach by remember { mutableStateOf<String?>(null) }
    var coachLoading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🔴 라이브 러닝 코치", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                if (LiveCoach.phase == "running") LiveCoachService.stop(context)
                LiveCoach.phase = "idle"; onClose()
            }) { Text("닫기") }
        }

        when (LiveCoach.phase) {
            "running" -> RunningView(context)
            "finished" -> FinishedView(store, scope, summaryCoach, coachLoading,
                onCoach = { km, sec ->
                    val key = store.keyFor(store.provider)
                    if (key.isBlank()) { summaryCoach = "AI 설정에서 키를 먼저 넣어줘!"; return@FinishedView }
                    coachLoading = true; summaryCoach = null
                    store.recordAiCall()
                    scope.launch {
                        val pace = if (km > 0) (sec / km).roundToInt() else 0
                        val sb = StringBuilder("너는 친한 러닝 코치 친구야. 반말로 코칭해줘.\n")
                        if (store.athleteProfile.isNotBlank()) sb.append("[내 프로필] ${store.athleteProfile}\n")
                        sb.append("[방금 라이브 러닝] 거리 ${"%.2f".format(km)}km, 시간 ${mmss(sec.toInt())}, 평균 페이스 ${mmss(pace)}\n")
                        BleHeart.bpm?.let { sb.append("[종료 시 심박] ${it}bpm\n") }
                        if (LiveCoach.goalType == "program" && LiveCoach.programSegments.isNotEmpty()) {
                            sb.append("\n[처방됐던 세션] ${LiveCoach.programTitle}\n")
                            LiveCoach.programSegments.forEach { s ->
                                val tp = if (s.targetPaceSec > 0) " 목표${mmss(s.targetPaceSec)}" else ""
                                val th = if (s.targetHr > 0) " 심박${s.targetHr}" else ""
                                sb.append("- ${s.label} ${s.durationSec / 60}분$tp$th\n")
                            }
                            sb.append("\n위 처방 세션을 실제로 뛴 결과야. 처방 대비 얼마나 지켰는지(페이스·심박 목표 달성도) 평가해줘.\n")
                            sb.append("### 1. 처방 준수 평가\n### 2. 잘한 점·아쉬운 점\n### 3. 다음 세션 조언\n핵심만 짧게.")
                            // 다음 프로그램 생성에 반영할 결과 저장
                            store.lastProgramResult = "세션 '${LiveCoach.programTitle}' 실제 ${"%.2f".format(km)}km, 평균 ${mmss(pace)}" +
                                (BleHeart.bpm?.let { ", 종료심박 ${it}" } ?: "")
                        } else {
                            sb.append("[목표] 10km 50분 이내\n### 1. 오늘 평가\n### 2. 다음 훈련 처방\n### 3. 페이스 전략\n핵심만 짧게.")
                        }
                        val r = AiCoach.generate(store.provider, key, sb.toString())
                        coachLoading = false
                        summaryCoach = r.fold({ it }, { "실패: ${it.message}" })
                    }
                },
                onCloseClick = { LiveCoach.phase = "idle"; summaryCoach = null; onClose() })
            else -> SetupView(
                targetText, { targetText = it }, distText, { distText = it }, timeText, { timeText = it },
                workText, { workText = it }, restText, { restText = it }, setsText, { setsText = it },
                hrText, { hrText = it },
                hasLocationPermission, onRequestLocation, hasBlePermission, onRequestBle,
                onStart = {
                    LiveCoach.targetPace = parsePace(targetText)
                    LiveCoach.simPace = LiveCoach.targetPace + 30
                    LiveCoach.goalDistanceKm = distText.toDoubleOrNull() ?: 5.0
                    LiveCoach.goalTimeMin = timeText.toIntOrNull() ?: 30
                    LiveCoach.intervalWorkSec = workText.toIntOrNull() ?: 60
                    LiveCoach.intervalRestSec = restText.toIntOrNull() ?: 90
                    LiveCoach.intervalSets = setsText.toIntOrNull() ?: 6
                    LiveCoach.targetHr = hrText.toIntOrNull() ?: 145
                    if (LiveCoach.mode == "gps" && !hasLocationPermission) { onRequestLocation(); return@SetupView }
                    LiveCoachService.start(context)
                })
        }
    }
        if (LiveCoach.phase == "finished" && !partyShown) {
            ConfettiOverlay { partyShown = true }
        }
    }
}

@Composable
private fun SetupView(
    targetText: String, onTarget: (String) -> Unit,
    distText: String, onDist: (String) -> Unit,
    timeText: String, onTime: (String) -> Unit,
    workText: String, onWork: (String) -> Unit,
    restText: String, onRest: (String) -> Unit,
    setsText: String, onSets: (String) -> Unit,
    hrText: String, onHr: (String) -> Unit,
    hasLoc: Boolean, onRequestLoc: () -> Unit,
    hasBle: Boolean, onRequestBle: () -> Unit,
    onStart: () -> Unit,
) {
    val ctx = LocalContext.current
    Text("목표 종류", fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        goalChip("자유", "free"); goalChip("페이스", "pace"); goalChip("거리", "distance")
        goalChip("시간", "time"); goalChip("인터벌", "interval"); goalChip("심박존", "hr")
    }
    when (LiveCoach.goalType) {
        "pace" -> OutlinedTextField(targetText, onTarget, label = { Text("목표 페이스 (분:초 /km)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "distance" -> OutlinedTextField(distText, onDist, label = { Text("목표 거리 (km)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "time" -> OutlinedTextField(timeText, onTime, label = { Text("목표 시간 (분)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "hr" -> OutlinedTextField(hrText, onHr, label = { Text("목표 심박수 (bpm) — 심박 센서 필요") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "program" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("📋 ${LiveCoach.programTitle}", fontWeight = FontWeight.Bold)
                LiveCoach.programSegments.forEach { seg ->
                    val tp = if (seg.targetPaceSec > 0) " @${mmss(seg.targetPaceSec)}" else ""
                    val th = if (seg.targetHr > 0) " ·${seg.targetHr}bpm" else ""
                    Text("• ${seg.label} ${seg.durationSec / 60}분$tp$th", fontSize = 13.sp)
                }
                Text("시작하면 이 순서대로 음성 가이드 + 페이스·심박 체크!", fontSize = 11.sp)
            }
        }
        "interval" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(workText, onWork, label = { Text("빠르게(초)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(restText, onRest, label = { Text("회복(초)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(setsText, onSets, label = { Text("세트 수") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetText, onTarget, label = { Text("빠르게 구간 목표 페이스 (분:초/km)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        else -> Text("자유 주행 — 페이스 압박 없이 가볍게! 거리·시간은 계속 알려줄게.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // 심박 센서 (BLE)
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⌚ 심박 센서 연결 (선택)", fontWeight = FontWeight.Bold)
            if (BleHeart.connected) {
                Text("연결됨 ✅  현재 ${BleHeart.bpm ?: "-"} bpm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                TextButton({ BleHeart.disconnect() }) { Text("연결 해제") }
            } else {
                if (BleHeart.status.isNotBlank()) Text(BleHeart.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ if (!hasBle) onRequestBle() else BleHeart.scan(ctx) }) {
                    Text(if (BleHeart.scanning) "검색 중..." else "🔍 심박 센서 스캔")
                }
                BleHeart.found.forEach { (name, addr) ->
                    TextButton({ BleHeart.connect(ctx, addr) }) { Text("→ $name 연결") }
                }
                Text("워치에 'HR 브로드캐스터' 앱을 켜두면 여기 잡혀요.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Text("모드", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(LiveCoach.mode == "gps", { LiveCoach.mode = "gps"; if (!hasLoc) onRequestLoc() }, { Text("📍 실제 GPS") })
        FilterChip(LiveCoach.mode == "sim", { LiveCoach.mode = "sim" }, { Text("🎮 시뮬레이션") })
    }
    if (LiveCoach.mode == "sim") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "⚠️ 테스트용 모드예요 — 실제 GPS 측정이 아니라 페이스를 자동으로 만들어내요. " +
                    "진짜로 뛰실 땐 위에서 '📍 실제 GPS'를 선택하세요.",
                Modifier.padding(12.dp), fontSize = 12.sp
            )
        }
    } else {
        Text(
            "실제 GPS: 벨트/주머니에 폰 넣고 뛰어도 음성 코칭 계속됨 (블루투스 이어폰 권장)",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(LiveCoach.voice, { LiveCoach.voice = it }); Text("🔊 음성 코칭") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(LiveCoach.aiOn, { LiveCoach.aiOn = it }); Text("🤖 km마다 AI 응원 (토큰 소량)") }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("▶️ 시작") }
}

@Composable
private fun goalChip(label: String, value: String) {
    FilterChip(LiveCoach.goalType == value, { LiveCoach.goalType = value }, { Text(label) })
}

@Composable
private fun RunningView(context: android.content.Context) {
    if (LiveCoach.mode == "sim") {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("🎮 시뮬레이션 중 — 아래 페이스는 실제 측정이 아니에요 (테스트용)", Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveStat("⏱️ 시간", "%d:%02d".format(LiveCoach.elapsedSec / 60, LiveCoach.elapsedSec % 60), Modifier.weight(1f))
        LiveStat("📏 거리", "%.2f km".format(LiveCoach.distM / 1000), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveStat("🏃 현재 페이스", mmss(LiveCoach.curPace), Modifier.weight(1f))
        LiveStat("📊 평균 페이스", mmss(LiveCoach.avgPace), Modifier.weight(1f))
    }
    BleHeart.bpm?.let { hr ->
        val over = LiveCoach.goalType == "hr" && hr > LiveCoach.targetHr + 5
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (over) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                "❤️ 심박 $hr bpm" + (if (LiveCoach.goalType == "hr") "  (목표 ${LiveCoach.targetHr})" else ""),
                Modifier.padding(14.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
    }
    if ((LiveCoach.goalType == "interval" || LiveCoach.goalType == "program") && LiveCoach.intervalLabel.isNotBlank()) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text("${if (LiveCoach.goalType == "program") "📋" else "인터벌:"} ${LiveCoach.intervalLabel}", Modifier.padding(14.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    } else if (LiveCoach.goalType == "pace" && LiveCoach.curPace > 0) {
        val delta = LiveCoach.curPace - LiveCoach.targetPace
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("목표 대비", style = MaterialTheme.typography.bodySmall)
                Text("${if (delta > 0) "+" else ""}$delta 초/km ${if (delta > 10) "느림 🐢" else if (delta < -10) "빠름 🔥" else "딱 좋아 ✅"}",
                    fontWeight = FontWeight.Bold)
            }
        }
    }
    Card(Modifier.fillMaxWidth()) { Text(LiveCoach.cue, Modifier.padding(16.dp), fontSize = 16.sp) }
    if (LiveCoach.mode == "sim") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ LiveCoach.simPace = (LiveCoach.simPace - 10).coerceIn(180, 720) }, Modifier.weight(1f)) { Text("⬆️ 빠르게") }
            OutlinedButton({ LiveCoach.simPace = (LiveCoach.simPace + 10).coerceIn(180, 720) }, Modifier.weight(1f)) { Text("⬇️ 느리게") }
        }
    }
    Button(
        onClick = { LiveCoachService.stop(context) },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) { Text("⏹️ 종료") }
}

@Composable
private fun FinishedView(
    store: CoachStore,
    scope: kotlinx.coroutines.CoroutineScope,
    summaryCoach: String?,
    coachLoading: Boolean,
    onCoach: (Double, Double) -> Unit,
    onCloseClick: () -> Unit,
) {
    val km = LiveCoach.distM / 1000
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.mascot), "마스코트", Modifier.size(112.dp), contentScale = ContentScale.Fit)
        }
    }
    Text("🏁 완주!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveStat("거리", "%.2f km".format(km), Modifier.weight(1f))
        LiveStat("시간", "%d:%02d".format(LiveCoach.elapsedSec / 60, LiveCoach.elapsedSec % 60), Modifier.weight(1f))
    }
    LiveStat("평균 페이스", mmss(LiveCoach.avgPace), Modifier.fillMaxWidth())
    Button(onClick = { onCoach(km, LiveCoach.elapsedSec.toDouble()) }, enabled = !coachLoading, modifier = Modifier.fillMaxWidth()) {
        if (coachLoading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text("💪 이 달리기 전체 코칭 받기")
    }
    summaryCoach?.let { Card(Modifier.fillMaxWidth()) { MarkdownText(it, Modifier.padding(14.dp), baseSize = 14.sp) } }
    OutlinedButton(onCloseClick, Modifier.fillMaxWidth()) { Text("닫기") }
}

@Composable
private fun LiveStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
