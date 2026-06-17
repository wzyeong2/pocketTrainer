package com.healthinsight.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 대화형 코치챗 — 대화 누적 + 과거 기록 백필(비용 확인) */
@Composable
fun CoachChatScreen(
    store: CoachStore,
    providerId: String,
    onClose: () -> Unit,
    onSend: ((Result<String>) -> Unit) -> Unit,
    onBackfill: ((Result<String>) -> Unit) -> Unit,
    backfillInfo: () -> Triple<Int, Int, Double>,
) {
    var messages by remember { mutableStateOf(store.chatMessages()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var costDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, loading) { scroll.animateScrollTo(scroll.maxValue) }
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("💬 코치챗", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = { resetDialog = true }) { Text("초기화") }
                TextButton(onClick = onClose) { Text("닫기") }
            }
        }

        val info = backfillInfo()
        OutlinedButton(onClick = { if (info.first > 0) costDialog = true }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (info.first > 0) "📊 과거 기록 분석 (${store.backfillMonths}~${store.backfillMonths + 3}개월 전 · ${info.first}개)"
                else "📊 더 분석할 과거 기록 없음"
            )
        }

        SelectionContainer(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(6.dp))
                if (messages.isEmpty()) {
                    Text(
                        "러닝에 대해 뭐든 물어봐.\n예) 오늘 7km 뛰었는데 내일 뛰어도 돼? / 10km 55분 코스 전략 짜줘\n\n먼저 '과거 기록 분석'을 누르면 내 기록을 학습해서 더 정확해져.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                    )
                }
                messages.forEach { (role, content) ->
                    if (role == "user") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Text(content, Modifier.padding(10.dp).widthIn(max = 280.dp), fontSize = 14.sp)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(10.dp).widthIn(max = 300.dp)) {
                                    MarkdownText(content, baseSize = 14.sp)
                                    // 길게 눌러 선택 복사 + 원탭 복사
                                    TextButton(
                                        onClick = { clipboard.setText(AnnotatedString(content)) },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) { Text("📋 복사", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
                if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("코치가 생각 중...", fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("메시지...") }, maxLines = 4)
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || loading) return@Button
                    store.addChatMessage("user", text); messages = store.chatMessages(); input = ""
                    loading = true
                    onSend { r ->
                        loading = false
                        r.onSuccess { store.addChatMessage("assistant", it) }
                            .onFailure { store.addChatMessage("assistant", "오류: ${it.message}") }
                        messages = store.chatMessages()
                    }
                },
                enabled = !loading
            ) { Text("전송") }
        }
    }

    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    store.clearChat(); store.backfillMonths = 0; store.athleteModel = ""
                    messages = emptyList(); resetDialog = false
                }) { Text("전체 초기화") }
            },
            dismissButton = { TextButton({ resetDialog = false }) { Text("취소") } },
            title = { Text("코치챗 초기화") },
            text = { Text("대화와 과거 기록 분석을 모두 지워요. 이후 '📊 과거 기록 분석'으로 처음부터 다시 분석할 수 있어요.") }
        )
    }

    if (costDialog) {
        val (count, tok, usd) = backfillInfo()
        val won = (usd * 1450).toInt()
        AlertDialog(
            onDismissRequest = { costDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    costDialog = false; loading = true
                    onBackfill { r ->
                        loading = false
                        r.onFailure { store.addChatMessage("assistant", "오류: ${it.message}") }
                        messages = store.chatMessages()
                    }
                }) { Text("동의하고 분석") }
            },
            dismissButton = { TextButton({ costDialog = false }) { Text("취소") } },
            title = { Text("과거 기록 분석 비용 확인") },
            text = {
                Text(
                    buildString {
                        appendLine("대상: ${store.backfillMonths}~${store.backfillMonths + 3}개월 전 러닝 ${count}개")
                        appendLine("예상 입력 토큰: 약 ${tok}개")
                        append(if (providerId == "gemini") "예상 비용: 무료 (Gemini)" else "예상 비용: 약 \$${"%.3f".format(usd)} (~${won}원)")
                        append("\n\n진행할까요?")
                    }
                )
            }
        )
    }
}
