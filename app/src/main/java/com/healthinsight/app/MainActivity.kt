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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 에너제틱/스포티 다크 테마 — 비비드 청록 + 주황 포인트 */
private val EnergeticDark = darkColorScheme(
    primary = Color(0xFF2BD4B0),            // 비비드 청록
    onPrimary = Color(0xFF00332A),
    primaryContainer = Color(0xFF0E5247),   // 청록 카드 배경
    onPrimaryContainer = Color(0xFFB6FFEC),
    secondary = Color(0xFFFF8A3D),          // 에너지 주황 포인트
    onSecondary = Color(0xFF3A1500),
    secondaryContainer = Color(0xFF6B3410),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFFFC24B),           // 골드(기록/달성)
    onTertiary = Color(0xFF3A2A00),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE6EEEB),
    surface = Color(0xFF141D1A),            // 카드 기본
    onSurface = Color(0xFFE6EEEB),
    surfaceVariant = Color(0xFF263330),
    onSurfaceVariant = Color(0xFFAFC0BB),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFD9D6),
    outline = Color(0xFF3A4A46),
)

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
            MaterialTheme(colorScheme = EnergeticDark) {
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
                                onDismissFirstVisit = { ui.value = ui.value.copy(firstVisitToday = false) },
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
                        // 기록 경신 빵빠레 (축포 + 축하 배너)
                        ui.value.celebrate?.let { msg ->
                            CelebrationOverlay(msg) { ui.value = ui.value.copy(celebrate = null) }
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
                if (has) loadCachedThenMaybeFetch()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                ui.value = ui.value.copy(message = "Health Connect 업데이트가 필요해요.")
            else -> ui.value = ui.value.copy(message = "이 기기에서 Health Connect를 쓸 수 없어요.")
        }
    }

    /**
     * 켤 때: 저장된 캐시를 즉시 보여주기만 한다 (자동 갱신 안 함).
     * 캐시가 아예 없으면(=첫 사용) 한 번만 자동으로 가져온다.
     * 오늘 처음 연 거면 '새 기록 불러오기' 안내를 띄운다.
     */
    private fun loadCachedThenMaybeFetch() {
        val cached = WorkoutCache.fromJson(store.workoutsCache)
        val today = java.time.LocalDate.now().toString()
        val firstToday = store.lastSeenDate != today
        store.lastSeenDate = today
        if (cached.isNotEmpty()) {
            ui.value = ui.value.copy(workouts = cached, loading = false, firstVisitToday = firstToday)
        } else {
            loadAll() // 캐시 없음 → 최초 1회 자동 로드
        }
    }

    /** Health Connect에서 새로 읽어 캐시 갱신 (수동 '새 기록 불러오기' 전용) */
    private fun loadAll() {
        lifecycleScope.launch {
            ui.value = ui.value.copy(loading = true, message = null, firstVisitToday = false)
            try {
                val ws = repo.allWorkouts(120)
                store.workoutsCache = WorkoutCache.toJson(ws)
                store.lastFetchDate = java.time.LocalDate.now().toString()
                val party = checkPersonalBest(ws)
                ui.value = ui.value.copy(loading = false, workouts = ws, firstVisitToday = false, celebrate = party,
                    message = if (ws.isEmpty()) "최근 120일 운동 기록이 없어요. 운동하고 다시 불러오기 해보세요!" else null)
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

    /** AI 주간 프로그램 생성 (기록+프로필 기반, sessions=주당 횟수) → 성공 시 저장 */
    private fun generateProgram(sessions: Int, onResult: (Result<String>) -> Unit) {
        val provider = store.provider
        val key = store.keyFor(provider)
        if (key.isBlank()) { onResult(Result.failure(RuntimeException("⚙️ 설정에서 ${provider} 키를 먼저 넣어줘!"))); return }
        store.recordAiCall()
        lifecycleScope.launch {
            val r = AiCoach.generateProgram(provider, key, athleteContext(), sessions)
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
        if (store.lastProgramResult.isNotBlank()) sb.appendLine("- 지난 프로그램 세션 결과: ${store.lastProgramResult}")
        return sb.toString()
    }

    /** 새로 불러온 기록에서 5k·10k 최고기록 경신을 감지 → 빵빠레 메시지(없으면 null) */
    private fun checkPersonalBest(ws: List<WorkoutRecord>): String? {
        val level = computeRunningLevel(ws.filter { it.type == ExerciseType.RUNNING })
        val msgs = mutableListOf<String>()
        level.best5kSec?.let { cur ->
            val prev = store.bestKnown5kSec
            if (prev in 1 until cur) {} // 더 느림 — 무시
            if (prev == 0) store.bestKnown5kSec = cur
            else if (cur < prev) { store.bestKnown5kSec = cur; msgs.add("5km 최고기록 경신! ${formatDuration(cur.toLong())}") }
        }
        level.best10kSec?.let { cur ->
            val prev = store.bestKnown10kSec
            if (prev == 0) store.bestKnown10kSec = cur
            else if (cur < prev) { store.bestKnown10kSec = cur; msgs.add("10km 최고기록 경신! ${formatDuration(cur.toLong())}") }
        }
        return if (msgs.isEmpty()) null else "🎉 " + msgs.joinToString(" · ")
    }
}

data class UiState(
    val available: Boolean = false,
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val workouts: List<WorkoutRecord> = emptyList(),
    val message: String? = null,
    val firstVisitToday: Boolean = false,   // 오늘 첫 방문 → 새 기록 불러오기 안내
    val celebrate: String? = null,          // 기록 경신 시 빵빠레 메시지
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
    onDismissFirstVisit: () -> Unit,
    onLive: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onOpenDetail: () -> Unit,
    coachWorkout: (WorkoutRecord, String, (Result<String>) -> Unit) -> Unit,
    loadSplits: (WorkoutRecord, (List<Split>) -> Unit) -> Unit,
    dailyCoach: (List<WorkoutRecord>, (Result<String>) -> Unit) -> Unit,
    generateProgram: (Int, (Result<String>) -> Unit) -> Unit,
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

        // 오늘 첫 방문 안내 — 새 기록 불러오기 권유 (자동 갱신은 안 함)
        if (state.firstVisitToday) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👋 오늘 처음 오셨네요!", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onDismissFirstVisit) { Text("나중에") }
                    }
                    Text("새 운동을 했다면 최신 기록을 불러올까요?", fontSize = 13.sp)
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("⬇️ 새 기록 불러오기") }
                }
            }
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
            val runsLast21 = state.workouts.count {
                it.type == ExerciseType.RUNNING && !it.id.toLocalDate().isBefore(LocalDate.now().minusDays(21))
            }
            val recommended = AiCoach.recommendSessions(runsLast21)
            var sessionCount by remember(recommended) { mutableStateOf(recommended) }
            var countMenuOpen by remember { mutableStateOf(false) }
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📋 AI 주간 프로그램", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (programLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                // 횟수 추천 + 드롭다운으로 변경
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("최근 훈련량 기준 AI 추천: ${recommended}회/주", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { countMenuOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("주 ${sessionCount}회 ▾")
                        }
                        DropdownMenu(countMenuOpen, { countMenuOpen = false }) {
                            (2..6).forEach { n ->
                                DropdownMenuItem(
                                    text = { Text("주 ${n}회" + if (n == recommended) "  (추천)" else "") },
                                    onClick = { sessionCount = n; countMenuOpen = false }
                                )
                            }
                        }
                    }
                }
                if (program.isEmpty() && !programLoading)
                    Text("최근 기록을 바탕으로 이번 주 훈련 ${sessionCount}회를 만들어줄게.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (programLoading) ElapsedProgressBar(expectedSec = 15, label = "AI가 주 ${sessionCount}회 프로그램 설계 중")
                Button(onClick = {
                    programLoading = true; programErr = null
                    generateProgram(sessionCount) { r ->
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

        BestRecordsCard(state.workouts)
        StatsCard(state.workouts)

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
                    if (dailyLoading) ElapsedProgressBar(expectedSec = 12, label = "코치가 종합 분석 중")
                    else Text(dailyText ?: "", fontSize = 14.sp)
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

        // 통계 — 헬스커넥트가 주는 값은 기본적으로 다 표시
        val stats = buildList {
            if (w.type.distanceBased && w.distanceMeters > 0) add("거리" to "%.2f km".format(w.distanceKm))
            add("시간" to formatDuration(w.durationSec))
            if (w.type.distanceBased && w.distanceMeters > 0) add("평균 페이스" to formatPace(w.avgPaceSecPerKm))
            if (w.maxSpeedMps != null && w.maxSpeedMps > 0) {
                add("최고 속도" to "%.1f km/h".format(w.maxSpeedMps * 3.6))
            }
            if (w.avgHr != null) add("평균 심박" to "${w.avgHr} bpm")
            if (w.maxHr != null) add("최고 심박" to "${w.maxHr} bpm")
            if (w.calories != null) add("칼로리" to "%.0f kcal".format(w.calories))
            if (w.steps != null && w.steps > 0) add("걸음 수" to "%,d 걸음".format(w.steps))
            if (w.elevationGainM != null) add("누적 고도" to "%.0f m".format(w.elevationGainM))
            if (w.elevationGainM != null && w.distanceMeters > 0)
                add("평균 경사도" to "%.1f%%".format(w.elevationGainM / w.distanceMeters * 100))
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
            var splitsExpanded by remember(w.id) { mutableStateOf(false) }
            val previewCount = 4
            val shown = if (splitsExpanded) splits else splits.take(previewCount)
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("구간별 페이스 (${splits.size}구간)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.forEach {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(it.label); Text(formatPace(it.paceSecPerKm) + (it.avgHr?.let { h -> " · ${h}bpm" } ?: ""))
                    }
                }
                if (splits.size > previewCount) {
                    TextButton(onClick = { splitsExpanded = !splitsExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (splitsExpanded) "접기 ▲" else "전체 ${splits.size}구간 펼치기 ▼")
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
            Text(if (loading) "코치가 분석 중..." else if (coaching != null) "🔄 다시 코칭 받기" else "💪 코칭 받기")
        }
        if (loading) ElapsedProgressBar(expectedSec = 12, label = "코치가 분석 중")
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
                val maxLen = 1000
                val recommendLen = 300
                OutlinedTextField(
                    profile, { if (it.length <= maxLen) profile = it },
                    label = { Text("목표·부상·선호 등") },
                    placeholder = { Text("예) 무릎 약함, 주 3회, 하프 준비 중") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    isError = profile.length > recommendLen,
                    supportingText = {
                        Text(
                            "${profile.length}/$maxLen 자" +
                                if (profile.length > recommendLen) " · 권장 ${recommendLen}자 이내 (길면 코칭이 산만해져요)" else " · 권장 ${recommendLen}자 이내",
                            fontSize = 11.sp,
                            color = if (profile.length > recommendLen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                )
            }
        }
    )
}

/** 진짜 %를 모르는 작업(AI 호출 등)용 — 경과시간 기준으로 차오르는 진행 바.
 *  expectedSec를 기준으로 95%까지 차고, 응답이 오면 호출부에서 사라진다. */
@Composable
private fun ElapsedProgressBar(expectedSec: Int, label: String) {
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); elapsed++ } }
    val frac = (elapsed.toFloat() / expectedSec).coerceIn(0.03f, 0.95f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label… ⏱️ ${elapsed}초", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Suppress("DEPRECATION")
        LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth())
    }
}

/** 5km·10km 베스트 기록 카드 — 자기 기록 경신 동기부여 */
@Composable
private fun BestRecordsCard(workouts: List<WorkoutRecord>) {
    val runs = workouts.filter { it.type == ExerciseType.RUNNING && it.distanceMeters > 0 }
    if (runs.isEmpty()) return
    val level = computeRunningLevel(runs)
    val best5k = level.best5kSec
    val best10k = level.best10kSec
    // 단일 러닝 최고(최단) 평균 페이스 (3km 이상만, 워밍업 짧은 기록 제외)
    val bestPace = runs.filter { it.distanceKm >= 3.0 }.map { it.avgPaceSecPerKm }.filter { it > 0 }.minOrNull()
    if (best5k == null && best10k == null && bestPace == null) return
    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🏅 베스트 기록", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BestItem("5km 최고", best5k?.let { formatDuration(it.toLong()) } ?: "—", Modifier.weight(1f))
            BestItem("10km 최고", best10k?.let { formatDuration(it.toLong()) } ?: "—", Modifier.weight(1f))
            BestItem("최고 평균 페이스", bestPace?.let { formatPace(it) } ?: "—", Modifier.weight(1f))
        }
        Text("👟 기록을 깨러 가볼까? 다음 러닝에서 도전!", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun BestItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatsCard(workouts: List<WorkoutRecord>) {
    val runs = workouts.filter { it.type == ExerciseType.RUNNING && it.distanceMeters > 0 }
    if (runs.isEmpty()) return
    val today = LocalDate.now()
    val weekKm = DoubleArray(8)
    runs.forEach { w ->
        val wk = (ChronoUnit.DAYS.between(w.id.toLocalDate(), today) / 7).toInt()
        if (wk in 0..7) weekKm[wk] += w.distanceKm
    }
    val maxKm = maxOf(weekKm.maxOrNull() ?: 1.0, 1.0)
    val barColor = MaterialTheme.colorScheme.primary
    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📊 주간 달리기 거리 (최근 8주)", fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = 8
            val gap = 6.dp.toPx()
            val bw = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val km = weekKm[7 - i] // 왼쪽=오래된 주, 오른쪽=이번 주
                val h = (km / maxKm * (size.height - 4)).toFloat()
                drawRect(color = barColor, topLeft = Offset(i * (bw + gap), size.height - h), size = Size(bw, h))
            }
        }
        Text(
            "이번 주 ${"%.1f".format(weekKm[0])}km · 최고 주 ${"%.1f".format(maxKm)}km · 8주 합 ${"%.1f".format(weekKm.sum())}km",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } }
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
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(700)); delay(900); onDone() }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E5247)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = anim.value
                val s = 0.85f + 0.15f * anim.value
                scaleX = s; scaleY = s
            }
        ) {
            Text("🌳", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Text("포켓 트레이너", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("내 주머니 속 AI 운동 코치", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        }
    }
}

/** 기록 경신 축하: 축포(빵빠레) + 가운데 축하 배너. 탭하면 닫힘. */
@Composable
private fun CelebrationOverlay(message: String, onDone: () -> Unit) {
    var show by remember { mutableStateOf(true) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(450)) }
    Box(
        Modifier.fillMaxSize().clickableNoRipple { show = false; onDone() },
        contentAlignment = Alignment.Center
    ) {
        if (show) ConfettiOverlay { show = false; onDone() }
        Card(
            Modifier.padding(28.dp).graphicsLayer {
                val s = 0.6f + 0.4f * pop.value; scaleX = s; scaleY = s; alpha = pop.value
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎉", fontSize = 52.sp)
                Text("신기록 달성!", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(message.removePrefix("🎉 "), fontSize = 15.sp, textAlign = TextAlign.Center)
                Text("탭해서 닫기", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
