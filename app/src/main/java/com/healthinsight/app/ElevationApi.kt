package com.healthinsight.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 지도 지형 고도(DEM) — Open-Meteo Elevation API (무료, 키 없음, Copernicus DEM ~30~90m).
 * 폰 GPS 경로(위도,경도)를 넣으면 각 점의 해발고도(m)를 돌려준다.
 * 기압계 누적고도는 노이즈로 과대계상되므로, 이 지형고도로 정확한 오르막/내리막을 계산한다.
 */
object ElevationApi {

    /** 좌표 리스트 → 각 점의 해발고도(m). 실패 시 빈 리스트. (100점씩 나눠 호출) */
    fun elevations(points: List<Pair<Double, Double>>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf<Double>()
        for (chunk in points.chunked(100)) {
            try {
                val lat = chunk.joinToString(",") { "%.5f".format(it.first) }
                val lon = chunk.joinToString(",") { "%.5f".format(it.second) }
                val url = URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val arr = JSONObject(text).getJSONArray("elevation")
                for (i in 0 until arr.length()) out.add(arr.getDouble(i))
            } catch (e: Exception) {
                return emptyList()  // 일부라도 실패하면 보정 포기(기압계 폴백)
            }
        }
        return out
    }

    /**
     * 고도 배열 → (총 오르막m, 총 내리막m).
     * DEM도 미세 노이즈가 있어 3점 이동평균 후, 0.7m 누적 히스테리시스로 합산
     * (완만한 오르막도 누적되면 잡고, 작은 흔들림은 무시).
     */
    fun gainLoss(elev: List<Double>): Pair<Double, Double> {
        if (elev.size < 2) return 0.0 to 0.0
        val sm = smooth(elev)
        var gain = 0.0; var loss = 0.0
        var ref = sm.first()
        for (i in 1 until sm.size) {
            val d = sm[i] - ref
            when {
                d >= 0.7 -> { gain += d; ref = sm[i] }
                d <= -0.7 -> { loss += -d; ref = sm[i] }
            }
        }
        return gain to loss
    }

    private fun smooth(e: List<Double>): List<Double> = e.indices.map { i ->
        val lo = maxOf(0, i - 1); val hi = minOf(e.size - 1, i + 1)
        var s = 0.0; for (j in lo..hi) s += e[j]; s / (hi - lo + 1)
    }
}
