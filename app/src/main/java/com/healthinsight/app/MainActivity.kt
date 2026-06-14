package com.healthinsight.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TYPE_COLORS = mapOf(
    ExerciseType.RUNNING to Color(0xFF3DA58A),
    ExerciseType.STRENGTH to Color(0xFFE0A23B),
    ExerciseType.WALKING to Color(0xFF5B8DEF),
    ExerciseType.CYCLING to Color(0xFFC065D6),
    ExerciseType.HIKING to Color(0xFFD9705A),
)

class MainActivity : ComponentActivity() {

    private lateinit var repo: WorkoutRepository
    private lateinit var store: CoachStore

    private val ui = mutableStateOf(UiState())
    private val screen = mutableStateOf("main")
    private val hasLocation = mutableStateOf(false)
    private val capturedBitmap = mutableStateOf<Bitmap?>(null)
    private val consent = mutableStateOf(false)
    private val showSplash = mutableStateOf(false)
    private val hasBle = mutableStateOf(false)

    private val requestHc =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(WorkoutRepository.REQUIRED)) {
                ui.value = ui.value.copy(hasPermission = true); loadAll()
            } else {
                ui.value = ui.value.copy(message = "운동·심박·거리·속도·걸음 권한을 모두 허용해줘.")
                Toast.makeText(this, "권한이 일부만 허용됐어요. 운동·심박·거리·속도·걸음을 모두 켜줘!", Toast.LENGTH_LONG).show()
            }
        }
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val requestLoc = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasLocation.value = it
    }
    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasBle.value = res.values.all { it }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) capturedBitmap.value = bmp
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { s -> capturedBitmap.value = BitmapFactory.decodeStream(s) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WorkoutRepository(this)
        store = CoachStore(this)
        consent.value = store.consentAccepted
        val today = java.time.LocalDate.now().toString()
        if (store.lastSplashDate != today) { showSplash.value = true; store.lastSplashDate = today }
        hasLocation.value = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasBle.value = if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else true
        if (Build.VERSION.SDK_INT >= 33) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)

        checkStatus()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF3DA58A))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            !consent.value -> ConsentScreen(onAccept = { store.consentAccepted = true; consent.value = true })
                            screen.value == "live" -> LiveRunningScreen(
                                store = store,
                                hasLocationPermission = hasLocation.value,
                                onRequestLocation = { requestLoc.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                                hasBlePermission = hasBle.value,
                                onRequestBle = {
                                    if (Build.VERSION.SDK_INT >= 31)
                                        requestBle.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                                    else hasBle.value = true
                                },
                                onClose = { screen.value = "main" },
                            )
                            else -> MainScreen(
                                state = ui.value,
                                store = store,
                                capturedBitmap = capturedBitmap.value,
                                onConnect = { requestHc.launch(WorkoutRepository.PERMISSIONS) },
                                onRefresh = { loadAll() },
                                onLive = { screen.value = "live" },
                                onTakePhoto = { takePicture.launch(null) },
                                onPickPhoto = { pickImage.launch("image/*") },
                                onClearPhoto = { capturedBitmap.value = null },
                                onOpenDetail = { capturedBitmap.value = null },
                                coachWorkout = ::coachWorkout,
                                loadSplits = ::loadSplits,
                                dailyCoach = ::dailyCoach,
                                generateProgram = ::generateProgram,
                            )
                        }
                        if (showSplash.value) SplashScreen { showSplash.value = false }
                    }
                }
            }
        }
    }

    private fun checkStatus() {
        when (repo.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                val has = repo.hasAllPermissions()
                ui.value = ui.value.copy(available = true, hasPermission = has)
                if (has) loadAll()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                ui.value = ui.value.copy(message = "Health Connect 업데이트가 필요해요.")
            else -> ui.value = ui.value.copy(message = "이 기기에서 Health Connect를 쓸 수 없어요.")
        }
    }

    private fun loadAll() {
        lifecycleScope.launch {
            ui.value = ui.value.copy(loading = true, message = null)
            try {
                val ws = repo.allWorkouts(120)
                ui.value = ui.value.copy(loading = false, workouts = ws,
                    message = if (ws.isEmpty()) "최근 120일 운동 기록이 없어요. 운동하고 새로고침 해보세요!" else null)
            } catch (e: Exception) {
                ui.value = ui.value.copy(loading = false, message = "데이터를 읽지 못했어요: ${e.message}")
                Toast.makeText(this@MainActivity, "데이터를 읽지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 상세 화면용 구간 페이스 지연 로딩 */
    private fun loadSplits(w: WorkoutRecord, onResult: (List<Split>) -> Unit) {
        lifecycleScope.launch {
            val s = try { repo.splitsFor(w) } catch (e: Exception) { emptyList() }
            onResult(s)
        }
    }

    /** AI 주간 프로그램 생성 (기록+프로필 기반) → 성공 시 저장 */
    private fun generateProgram(onResult: (Result<String>) -> Unit) {
        val provider = store.provider
        val key = store.keyFor(provider)
        if (key.isBlank()) { onResult(Result.failure(RuntimeException("⚙️ 설정에서 ${provider} 키를 먼저 넣어줘!"))); return }
        store.recordAiCall()
        lifecycleScope.launch {
            val r = AiCoach.generateProgram(provider, key, athleteContext())
            r.onSuccess { store.programJson = it; store.programDate = java.time.LocalDate.now().toString() }
            onResult(r)
        }
    }

    /** 하루 종합 코칭: 그날 운동 전체를 묶어 분석 */
    private fun dailyCoach(workouts: List<WorkoutRecord>, onResult: (Result<String>) -> Unit) {
        val provider = store.provider
        val key = store.keyFor(provider)
        if (key.isBlank()) { onResult(Result.failure(RuntimeException("⚙️ 설정에서 ${provider} 키를 먼저 넣어줘!"))); return }
        store.recordAiCall()
        lifecycleScope.launch {
            val sb = StringBuilder("너는 친한 운동 코치 친구야. 반말로 코칭해줘.\n\n")
            sb.append(athleteContext()).append("\n")
            sb.append("[오늘 한 운동들]\n")
            workouts.sortedBy { it.start }.forEach { sb.append("• ").append(CoachPrompt.summarize(it).replace("\n", " ").trim()).append("\n") }
            sb.append("\n위 [내 프로필]을 참고해서 아래 형식으로 (제목 유지):\n### 1. 오늘 전체 평가\n### 2. 운동 조합/균형 피드백\n### 3. 내일 추천\n핵심만, 너무 길지 않게.")
            val r = AiCoach.generate(provider, key, sb.toString(), null)
            onResult(r)
        }
    }

    /** 코칭 호출: 결과 콜백으로 전달 */
    private fun coachWorkout(w: WorkoutRecord, memo: String, onResult: (Result<String>) -> Unit) {
        store.setMemo(w.id, memo)
        val provider = store.provider
        val key = store.keyFor(provider)
        if (key.isBlank()) { onResult(Result.failure(RuntimeException("⚙️ 설정에서 ${provider} 키를 먼저 넣어줘!"))); return }
        val profile = athleteContext()
        val image = capturedBitmap.value?.let { jpegBytes(it) }
        store.recordAiCall()
        lifecycleScope.launch {
            val prompt = CoachPrompt.build(w, memo, profile, image != null)
            val r = AiCoach.generate(provider, key, prompt, image)
            r.onSuccess { store.setCoaching(w.id, it) }
            onResult(r)
        }
    }

    /** 운동 기록 + 사용자 메모로 만든 '선수 프로필' (코칭에 항상 참고) */
    private fun athleteContext(): String {
        val runs = ui.value.workouts.filter { it.type == ExerciseType.RUNNING }
        val level = computeRunningLevel(runs)
        val sb = StringBuilder("[내 프로필]\n")
        sb.appendLine("- 러닝 레벨: ${level.current.label} (${level.current.goal})")
        level.best10kSec?.let { sb.appendLine("- 10km 최고 기록: ${formatDuration(it.toLong())}") }
        level.best5kSec?.let { sb.appendLine("- 5km 최고 기록: ${formatDuration(it.toLong())}") }
        val recent = runs.sortedByDescending { it.start }.take(5).map { it.avgPaceSecPerKm }.filter { it > 0 }
        if (recent.isNotEmpty()) sb.appendLine("- 최근 달리기 평균 페이스: ${formatPace(recent.average().toInt())}")
        sb.appendLine("- 최종 목표: 10km 50분 이내(5:00/km)")
        val note = store.athleteProfile
        if (note.isNotBlank()) sb.appendLine("- 본인 메모: $note")
        return sb.toString()
    }
}

data class UiState(
    val available: Boolean = false,
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val workouts: List<WorkoutRecord> = emptyList(),
    val message: String? = null,
)

private fun jpegBytes(bmp: Bitmap): ByteArray {
    val max = 1024
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}

private fun Long.toLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private val dateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val timeFmt = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN).withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    store: CoachStore,
    capturedBitmap: Bitmap?,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onLive: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onOpenDetail: () -> Unit,
    coachWorkout: (WorkoutRecord, String, (Result<String>) -> Unit) -> Unit,
    loadSplits: (WorkoutRecord, (List<Split>) -> Unit) -> Unit,
    dailyCoach: (List<WorkoutRecord>, (Result<String>) -> Unit) -> Unit,
    generateProgram: ((Result<String>) -> Unit) -> Unit,
) {
    var filter by remember { mutableStateOf<ExerciseType?>(null) }
    var dailyDialog by remember { mutableStateOf(false) }
    var dailyText by remember { mutableStateOf<String?>(null) }
    var dailyLoading by remember { mutableStateOf(false) }
    var dailySelectOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var program by remember { mutableStateOf(ProgramParser.parse(store.programJson)) }
    var programLoading by remember { mutableStateOf(false) }
    var programErr by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var detail by remember { mutableStateOf<WorkoutRecord?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(store.hiddenIds()) }
    var resolved by remember { mutableStateOf(store.resolvedPairs()) }

    val visible = state.workouts.filter { it.id !in hidden }
    val filtered = visible.filter { filter == null || it.type == filter }
    val byDate = filtered.groupBy { it.id.toLocalDate() }
    val dayWorkouts = if (selectedDate != null) byDate[selectedDate].orEmpty()
        else filtered.filter { it.id.toLocalDate() == LocalDate.now() }
    val suspected = remember(state.workouts, hidden, resolved) {
        detectDuplicates(state.workouts.filter { it.id !in hidden }, resolved)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🏃 포켓 트레이너", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onLive, label = { Text("🔴 라이브") })
                AssistChip(onClick = { settingsOpen = true }, label = { Text("⚙️") })
            }
        }

        if (!state.available) { InfoCard("Health Connect 사용 불가", state.message ?: ""); return@Column }

        if (!state.hasPermission) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("연결이 필요해요", fontWeight = FontWeight.Bold)
                Text("운동 기록(달리기·헬스·걷기 등)을 읽으려면 권한을 허용해줘.")
                Button(onClick = onConnect) { Text("Health Connect 연결하기") }
            } }
            state.message?.let { Spacer(Modifier.height(4.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            if (settingsOpen) SettingsDialog(store) { settingsOpen = false }
            return@Column
        }

        // 운동별 필터
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null; selectedDate = null }, { Text("📋 전체") })
            ExerciseType.entries.forEach { t ->
                FilterChip(filter == t, { filter = t; selectedDate = null }, { Text("${t.emoji} ${t.label}") })
            }
        }

        // 캘린더
        MonthCalendar(month, byDate, selectedDate,
            onPrev = { month = month.minusMonths(1) },
            onNext = { month = month.plusMonths(1) },
            onSelect = { selectedDate = if (selectedDate == it) null else it })

        // AI 주간 프로그램 (기록 5개 이상)
        val runCount = state.workouts.count { it.type == ExerciseType.RUNNING }
        if (runCount >= 5) {
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📋 AI 주간 프로그램", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (programLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                if (program.isEmpty() && !programLoading)
                    Text("최근 기록을 바탕으로 이번 주 훈련 3회를 만들어줄게.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                program.forEach { s ->
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("• ${s.title}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (s.focus.isNotBlank()) Text("  ${s.focus}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.segments.forEach { seg ->
                            val tp = if (seg.targetPaceSec > 0) " @${formatPace(seg.targetPaceSec)}" else ""
                            val th = if (seg.targetHr > 0) " ·${seg.targetHr}bpm" else ""
                            Text("   - ${seg.label} ${seg.durationSec / 60}분$tp$th", fontSize = 12.sp)
                        }
                        if (s.segments.any { it.durationSec > 0 }) {
                            TextButton(onClick = {
                                LiveCoach.programSegments = s.segments
                                LiveCoach.programTitle = s.title
                                LiveCoach.goalType = "program"
                                onLive()
                            }) { Text("▶️ 이 세션 라이브로") }
                        }
                    }
                }
                programErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Button(onClick = {
                    programLoading = true; programErr = null
                    generateProgram { r ->
                        programLoading = false
                        r.onSuccess {
                            val parsed = ProgramParser.parse(it)
                            if (parsed.isEmpty()) programErr = "형식 파싱 실패 — 다시 만들어줘" else program = parsed
                        }.onFailure { programErr = it.message }
                    }
                }, enabled = !programLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (program.isEmpty()) "✨ 이번 주 프로그램 만들기" else "🔄 프로그램 새로 만들기")
                }
            } }
        }

        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("불러오는 중...")
            }
        }

        // 리스트
        val listWorkouts = (if (selectedDate != null) byDate[selectedDate].orEmpty() else filtered)
            .sortedByDescending { it.id }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(selectedDate?.let { dateFmt.format(it) } ?: "최근 운동", fontWeight = FontWeight.Bold)
            if (selectedDate != null) TextButton({ selectedDate = null }) { Text("전체 보기 ✕") }
        }
        if (dayWorkouts.size >= 2) {
            FilledTonalButton(onClick = {
                selectedIds = dayWorkouts.map { it.id }.toSet()
                dailySelectOpen = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text("📋 ${if (selectedDate != null) "이 날" else "오늘"} 종합 코칭 (${dayWorkouts.size}개 운동)")
            }
        }
        if (listWorkouts.isEmpty()) {
            Text("해당 조건의 운동이 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            listWorkouts.forEach { w ->
                WorkoutItem(w, coached = store.getCoaching(w.id) != null) { onOpenDetail(); detail = w }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(20.dp))
    }

    // 상세 시트
    detail?.let { w ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            WorkoutDetail(w, store, capturedBitmap, onTakePhoto, onPickPhoto, onClearPhoto, coachWorkout, loadSplits,
                onHide = { id -> store.hide(id); hidden = store.hiddenIds(); detail = null })
        }
    }
    if (settingsOpen) SettingsDialog(store) { settingsOpen = false }

    if (dailySelectOpen) {
        AlertDialog(
            onDismissRequest = { dailySelectOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sel = dayWorkouts.filter { it.id in selectedIds }
                        if (sel.isEmpty()) return@TextButton
                        dailySelectOpen = false
                        dailyDialog = true; dailyLoading = true; dailyText = null
                        dailyCoach(sel) { r -> dailyLoading = false; dailyText = r.fold({ it }, { "실패: ${it.message}" }) }
                    },
                    enabled = selectedIds.isNotEmpty()
                ) { Text("코칭 받기 (${selectedIds.size})") }
            },
            dismissButton = { TextButton({ dailySelectOpen = false }) { Text("취소") } },
            title = { Text("📋 종합 코칭할 운동 선택") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    dayWorkouts.forEach { w ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(w.id in selectedIds, { c -> selectedIds = if (c) selectedIds + w.id else selectedIds - w.id })
                            val amount = if (w.type.distanceBased && w.distanceMeters > 0) "%.2fkm".format(w.distanceKm) else formatDuration(w.durationSec)
                            Text("${w.type.emoji} ${w.type.label} · $amount · ${timeFmt.format(w.start)}", fontSize = 13.sp)
                        }
                    }
                }
            }
        )
    }

    if (dailyDialog) {
        AlertDialog(
            onDismissRequest = { dailyDialog = false },
            confirmButton = { TextButton({ dailyDialog = false }) { Text("닫기") } },
            title = { Text("📋 오늘 종합 코칭") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (dailyLoading) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("코치가 종합 분석 중...")
                    } else Text(dailyText ?: "", fontSize = 14.sp)
                }
            }
        )
    }

    // 중복 의심 기록 물어보기
    suspected.firstOrNull()?.let { (a, b) ->
        DuplicateDialog(a, b,
            onHide = { id -> store.hide(id); hidden = store.hiddenIds() },
            onKeepBoth = { store.addResolved(pairKey(a, b)); resolved = store.resolvedPairs() })
    }
}

private fun pairKey(a: WorkoutRecord, b: WorkoutRecord): String =
    listOf(a.id, b.id).sorted().joinToString("-")

/** 같은 종류 + 시작 시각 5분 이내인 기록 쌍을 중복 의심으로 감지 */
private fun detectDuplicates(list: List<WorkoutRecord>, resolved: Set<String>): List<Pair<WorkoutRecord, WorkoutRecord>> {
    val out = mutableListOf<Pair<WorkoutRecord, WorkoutRecord>>()
    list.groupBy { it.type }.forEach { (_, g) ->
        val s = g.sortedBy { it.start }
        for (i in 0 until s.size - 1) {
            val a = s[i]; val b = s[i + 1]
            if (kotlin.math.abs(a.start.epochSecond - b.start.epochSecond) <= 300) {
                if (pairKey(a, b) !in resolved) out.add(a to b)
            }
        }
    }
    return out
}

@Composable
private fun DuplicateDialog(
    a: WorkoutRecord, b: WorkoutRecord,
    onHide: (Long) -> Unit, onKeepBoth: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepBoth,
        confirmButton = { TextButton(onKeepBoth) { Text("둘 다 유지") } },
        title = { Text("🤔 중복 같은 기록이 있어요") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("같은 종류 운동이 가까운 시각에 2개 있어요. 아래 정보 보고 숨길 걸 골라줘.", fontSize = 13.sp)
                Text("💡 보통 심박수가 기록된 워치 기록을 남기는 게 좋아요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                DupCard(a, onHide)
                DupCard(b, onHide)
            }
        }
    )
}

@Composable
private fun DupCard(w: WorkoutRecord, onHide: (Long) -> Unit) {
    val amount = if (w.type.distanceBased && w.distanceMeters > 0) "%.2fkm".format(w.distanceKm) else formatDuration(w.durationSec)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${w.type.emoji} ${w.type.label} · $amount", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (w.fromWatch) Text("⌚ 워치 (추천)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text("🕐 ${timeFmt.format(w.start)} ~ ${timeFmt.format(w.end)}  (${formatDuration(w.durationSec)})", fontSize = 12.sp)
            if (w.type.distanceBased && w.distanceMeters > 0)
                Text("🏃 ${formatPace(w.avgPaceSecPerKm)}", fontSize = 12.sp)
            Text("❤️ 심박 " + (if (w.avgHr != null) "평균 ${w.avgHr} / 최고 ${w.maxHr}" else "없음") +
                (w.calories?.let { "   🔥 ${"%.0f".format(it)}kcal" } ?: ""), fontSize = 12.sp,
                color = if (w.avgHr != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("📲 출처: ${w.source.ifBlank { "알 수 없음" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FilledTonalButton(onClick = { onHide(w.id) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("이 기록 숨기기")
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    byDate: Map<LocalDate, List<WorkoutRecord>>,
    selected: LocalDate?,
    onPrev: () -> Unit, onNext: () -> Unit, onSelect: (LocalDate) -> Unit,
) {
    Card { Column(Modifier.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onPrev) { Text("‹", fontSize = 20.sp) }
            Text("${month.year}년 ${month.monthValue}월", fontWeight = FontWeight.Bold)
            TextButton(onNext) { Text("›", fontSize = 20.sp) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("일","월","화","수","목","금","토").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value % 7 // 일요일=0
        val days = month.lengthOfMonth()
        val cells = ArrayList<LocalDate?>()
        repeat(offset) { cells.add(null) }
        for (d in 1..days) cells.add(month.atDay(d))
        while (cells.size % 7 != 0) cells.add(null)
        val today = LocalDate.now()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val ws = byDate[date].orEmpty()
                            val isSel = date == selected
                            Column(
                                Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) MaterialTheme.colorScheme.primary else if (ws.isNotEmpty()) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .then(if (ws.isNotEmpty()) Modifier.clickableNoRipple { onSelect(date) } else Modifier),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${date.dayOfMonth}", fontSize = 12.sp,
                                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    ws.take(3).forEach {
                                        Box(Modifier.size(5.dp).clip(CircleShape).background(TYPE_COLORS[it.type] ?: Color.Gray))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } }
}

@Composable
private fun WorkoutItem(w: WorkoutRecord, coached: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickableNoRipple(onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(w.type.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.type.label, fontWeight = FontWeight.Bold)
                Text("${dateFmt.format(w.id.toLocalDate())} · ${timeFmt.format(w.start)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (coached) Text("✓ 코칭 받음", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (w.type.distanceBased && w.distanceMeters > 0) {
                    Text("%.2fkm".format(w.distanceKm), fontWeight = FontWeight.Bold)
                    Text(formatPace(w.avgPaceSecPerKm), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(formatDuration(w.durationSec), fontWeight = FontWeight.Bold)
                    Text("근력", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WorkoutDetail(
    w: WorkoutRecord,
    store: CoachStore,
    capturedBitmap: Bitmap?,
    onTakePhoto: () -> Unit, onPickPhoto: () -> Unit, onClearPhoto: () -> Unit,
    coachWorkout: (WorkoutRecord, String, (Result<String>) -> Unit) -> Unit,
    loadSplits: (WorkoutRecord, (List<Split>) -> Unit) -> Unit,
    onHide: (Long) -> Unit,
) {
    val context = LocalContext.current
    var splits by remember(w.id) { mutableStateOf(w.splits) }
    var splitsLoading by remember(w.id) { mutableStateOf(false) }
    LaunchedEffect(w.id) {
        if (w.type == ExerciseType.RUNNING && splits.isEmpty()) {
            splitsLoading = true
            loadSplits(w) { splits = it; splitsLoading = false }
        }
    }
    var memo by remember(w.id) { mutableStateOf(store.getMemo(w.id).ifBlank { "" }) }
    var coaching by remember(w.id) { mutableStateOf(store.getCoaching(w.id)) }
    var loading by remember(w.id) { mutableStateOf(false) }
    var error by remember(w.id) { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(coaching) { if (coaching != null) scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 30.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${w.type.emoji} ${w.type.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(dateFmt.format(w.id.toLocalDate()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${timeFmt.format(w.start)} ~ ${timeFmt.format(w.end)}",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 통계
        val stats = buildList {
            if (w.type.distanceBased && w.distanceMeters > 0) add("거리" to "%.2f km".format(w.distanceKm))
            add("시간" to formatDuration(w.durationSec))
            if (w.type.distanceBased && w.distanceMeters > 0) add("평균 페이스" to formatPace(w.avgPaceSecPerKm))
            if (w.avgHr != null) add("평균 심박" to "${w.avgHr} bpm")
            if (w.maxHr != null) add("최고 심박" to "${w.maxHr} bpm")
            if (w.calories != null) add("칼로리" to "%.0f kcal".format(w.calories))
            if (w.elevationGainM != null) add("고도" to "%.0f m".format(w.elevationGainM))
        }
        stats.chunked(2).forEach { rowStats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowStats.forEach { (k, v) ->
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) {
                        Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    } }
                }
                if (rowStats.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (splitsLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("구간 페이스 계산 중...", fontSize = 12.sp)
            }
        } else if (splits.isNotEmpty()) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("구간별 페이스", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                splits.forEach {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(it.label); Text(formatPace(it.paceSecPerKm) + (it.avgHr?.let { h -> " · ${h}bpm" } ?: ""))
                    }
                }
            } }
        }

        OutlinedTextField(memo, { memo = it }, label = { Text("메모 (종목·중량·컨디션 등)") }, modifier = Modifier.fillMaxWidth())

        // 사진
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onTakePhoto) { Text("📷 촬영") }
            OutlinedButton(onPickPhoto) { Text("🖼️ 갤러리") }
            if (capturedBitmap != null) TextButton(onClearPhoto) { Text("✕ 빼기") }
        }
        capturedBitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.size(140.dp).clip(RoundedCornerShape(10.dp)))
        }

        Button(onClick = {
            loading = true; error = null
            coachWorkout(w.copy(splits = splits), memo) { r ->
                loading = false
                r.onSuccess {
                    coaching = it
                    Toast.makeText(context, "코칭 완료! 👍", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    error = it.message
                    Toast.makeText(context, it.message ?: "코칭에 실패했어요", Toast.LENGTH_LONG).show()
                }
            }
        }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("코치가 분석 중...") }
            else Text(if (coaching != null) "🔄 다시 코칭 받기" else "💪 코칭 받기")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "오늘 AI 코칭 ${store.usageToday()}회 사용 · 무료(Gemini)는 분당·일일 한도가 있어요",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        coaching?.let {
            HorizontalDivider()
            Text(it, fontSize = 15.sp)
        }

        HorizontalDivider()
        TextButton(
            onClick = {
                onHide(w.id)
                Toast.makeText(context, "기록을 숨겼어요 (삼성헬스엔 그대로 있어요)", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("🗑️ 이 기록 숨기기 (중복·잘못된 기록일 때)") }
    }
}

@Composable
private fun SettingsDialog(store: CoachStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(store.provider) }
    var gKey by remember { mutableStateOf(store.geminiKey) }
    var cKey by remember { mutableStateOf(store.claudeKey) }
    var profile by remember { mutableStateOf(store.athleteProfile) }
    var testing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton({
            store.provider = provider; store.geminiKey = gKey; store.claudeKey = cKey
            store.athleteProfile = profile
            Toast.makeText(context, "저장됐어요 ✅", Toast.LENGTH_SHORT).show()
            onClose()
        }) { Text("저장") } },
        dismissButton = { TextButton(onClose) { Text("취소") } },
        title = { Text("🤖 AI 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(provider == "gemini", { provider = "gemini" }, { Text("Gemini (무료)") })
                    FilterChip(provider == "claude", { provider = "claude" }, { Text("Claude") })
                }
                val key = if (provider == "gemini") gKey else cKey
                if (provider == "gemini")
                    OutlinedTextField(gKey, { gKey = it }, label = { Text("Gemini API 키") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else
                    OutlinedTextField(cKey, { cKey = it }, label = { Text("Claude API 키") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(
                    onClick = {
                        if (key.isBlank()) { Toast.makeText(context, "키를 먼저 입력해줘!", Toast.LENGTH_SHORT).show(); return@TextButton }
                        testing = true
                        Toast.makeText(context, "연결 확인 중...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val r = AiCoach.generate(provider, key, "안녕")
                            testing = false
                            r.onSuccess { Toast.makeText(context, "✅ 연결 성공!", Toast.LENGTH_LONG).show() }
                                .onFailure { Toast.makeText(context, "❌ 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    enabled = !testing,
                ) { Text("🔌 키 연결 테스트") }
                Text(
                    if (provider == "gemini") "무료 키: aistudio.google.com/apikey" else "키: console.anthropic.com",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("🏃 내 프로필 (코칭에 항상 참고돼요)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    profile, { profile = it },
                    label = { Text("목표·부상·선호 등") },
                    placeholder = { Text("예) 무릎 약함, 주 3회, 하프 준비 중") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        }
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card { Column(Modifier.padding(16.dp)) {
        Text(title, fontWeight = FontWeight.Bold); Text(body)
    } }
}

private const val PRIVACY_TEXT = """포켓 트레이너는 운동 코칭을 위해 아래 정보를 사용합니다.

[수집·사용하는 정보]
• 운동 기록(Health Connect): 운동 종류·거리·시간·페이스·심박수·칼로리·고도·걸음. 코칭과 통계 표시에 사용합니다.
• 위치(GPS): 라이브 러닝 코치에서 실시간 페이스 측정에만 사용합니다.
• 사진(선택): 사용자가 첨부할 때만, AI 분석을 위해 사용합니다.
• API 키: AI 코칭 연결에 사용하며 기기에만 저장됩니다.

[AI 전송]
코칭을 요청할 때, 운동 요약(및 첨부 사진)이 사용자가 선택한 AI 제공사(Google Gemini 또는 Anthropic Claude) 서버로 전송되어 코칭 결과를 생성합니다.

[저장·보관]
별도 서버 없이 모든 데이터·설정·코칭 결과는 이 기기 내부에만 저장됩니다. 앱을 삭제하면 함께 삭제됩니다.

[권한]
권한은 거부할 수 있으며, 거부 시 해당 기능이 제한될 수 있습니다.

[의료 고지]
본 앱은 일반적인 운동 참고용이며 의학적 진단·치료를 제공하지 않습니다."""

@Composable
private fun ConsentScreen(onAccept: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("🏃 포켓 트레이너", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("개인정보 처리방침 및 이용 동의", fontWeight = FontWeight.Bold)
        Card { Text(PRIVACY_TEXT, Modifier.padding(14.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("동의하고 시작하기") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1500); onDone() }
    Box(Modifier.fillMaxSize().background(Color(0xFF2E7D6B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌳", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Text("포켓 트레이너", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("내 주머니 속 AI 운동 코치", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
    }
}

/** 리플 없는 단순 클릭 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onClick() }
}
