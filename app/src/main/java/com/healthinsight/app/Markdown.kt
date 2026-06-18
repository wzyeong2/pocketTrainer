package com.healthinsight.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AI 코칭 텍스트의 간단 마크다운(### 제목, **굵게**, - 불릿)을 렌더링 */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, baseSize: TextUnit = 15.sp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        md.replace("\r\n", "\n").split("\n").forEach { raw ->
            val line = raw.trim()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("### ") -> Text(
                    inline(line.removePrefix("### ")), fontWeight = FontWeight.Bold,
                    fontSize = baseSize * 1.05f, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                line.startsWith("## ") -> Text(
                    inline(line.removePrefix("## ")), fontWeight = FontWeight.Bold,
                    fontSize = baseSize * 1.15f, modifier = Modifier.padding(top = 6.dp)
                )
                line.startsWith("# ") -> Text(
                    inline(line.removePrefix("# ")), fontWeight = FontWeight.Bold,
                    fontSize = baseSize * 1.25f, modifier = Modifier.padding(top = 6.dp)
                )
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> Row {
                    Text("•  ", fontSize = baseSize)
                    Text(inline(line.drop(2).trim()), fontSize = baseSize)
                }
                else -> Text(inline(line), fontSize = baseSize)
            }
        }
    }
}

/** 마크다운 코칭 + 길게눌러 선택 복사 + 복사 버튼 */
@Composable
fun CopyableMarkdown(md: String, modifier: Modifier = Modifier, baseSize: TextUnit = 15.sp) {
    val clip = LocalClipboardManager.current
    Column(modifier) {
        SelectionContainer { MarkdownText(md, baseSize = baseSize) }
        TextButton(
            onClick = { clip.setText(AnnotatedString(md)) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) { Text("📋 복사", fontSize = 11.sp) }
    }
}

/** 인라인 **굵게** 처리 */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex("\\*\\*(.+?)\\*\\*")
    var last = 0
    regex.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}
