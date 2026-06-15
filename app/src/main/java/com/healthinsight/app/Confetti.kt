package com.healthinsight.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

/** 기록 달성·완주 시 화면 위로 쏟아지는 축포(빵빠레). 약 2.6초 후 onDone. */
@Composable
fun ConfettiOverlay(onDone: () -> Unit) {
    val colors = listOf(
        Color(0xFF2BC4A3), Color(0xFFFF8A3D), Color(0xFFFFC24B),
        Color(0xFF5B8DEF), Color(0xFFE0599B), Color(0xFF8BD450),
    )
    // 입자: 시작 x(0~1), 색, 크기, 시작 지연, 회전, 좌우 흔들림
    val parts = remember {
        List(90) {
            Particle(
                x = Random.nextFloat(),
                color = colors[Random.nextInt(colors.size)],
                size = 7f + Random.nextFloat() * 11f,
                delay = Random.nextFloat() * 0.35f,
                rot = Random.nextFloat() * 360f,
                drift = (Random.nextFloat() - 0.5f) * 0.25f,
                spin = (Random.nextFloat() - 0.5f) * 720f,
            )
        }
    }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(2600, easing = LinearEasing)); onDone() }

    Canvas(Modifier.fillMaxSize()) {
        parts.forEach { p ->
            val span = (1f - p.delay).coerceAtLeast(0.01f)
            val prog = ((t.value - p.delay) / span).coerceIn(0f, 1f)
            if (prog <= 0f) return@forEach
            val y = prog * (size.height + 80f) - 40f
            val x = p.x * size.width + p.drift * size.width * prog
            val alpha = (1f - prog * prog).coerceIn(0f, 1f)
            rotate(p.rot + prog * p.spin, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(p.size, p.size * 1.7f),
                )
            }
        }
    }
}

private data class Particle(
    val x: Float, val color: Color, val size: Float,
    val delay: Float, val rot: Float, val drift: Float, val spin: Float,
)
