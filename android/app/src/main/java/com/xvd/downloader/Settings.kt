package com.xvd.downloader

import android.content.Context

/** 앱 설정 (SharedPreferences "xvd") */
object Settings {
    private fun p(c: Context) = c.getSharedPreferences("xvd", Context.MODE_PRIVATE)

    /** Wi-Fi(무제한 네트워크)에서만 받기 */
    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi_only", v).apply()

    /** 0 = 항상 최대 화질, 그 외는 이 높이(p) 이하 중 가장 높은 화질 */
    fun quality(c: Context) = p(c).getInt("quality", 0)
    fun setQuality(c: Context, v: Int) = p(c).edit().putInt("quality", v).apply()

    /** 동시에 받는 개수 */
    fun concurrent(c: Context) = p(c).getInt("concurrent", 3).coerceIn(1, 8)
    fun setConcurrent(c: Context, v: Int) = p(c).edit().putInt("concurrent", v).apply()

    /** 앱을 벗어나도 계속 받기 (끄면 앱 밖에서는 일시정지, 돌아오면 이어받기) */
    fun background(c: Context) = p(c).getBoolean("background", true)
    fun setBackground(c: Context, v: Boolean) = p(c).edit().putBoolean("background", v).apply()

    /** 링크를 가져오면 화질 설정대로 바로 받기 */
    fun autoStart(c: Context) = p(c).getBoolean("auto_start", false)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start", v).apply()

    /** X 탭 떠 있는 다운로드 버튼 크기(px 기준 dp) */
    fun popSize(c: Context) = p(c).getInt("pop_size", 36)
    fun setPopSize(c: Context, v: Int) = p(c).edit().putInt("pop_size", v).apply()

    /** 화질 설정에 맞는 변형 고르기 */
    fun pick(c: Context, list: List<TweetFetcher.Variant>): TweetFetcher.Variant? {
        if (list.isEmpty()) return null
        val sorted = list.sortedByDescending { it.bitrate }
        val limit = quality(c)
        if (limit == 0) return sorted.first()
        return sorted.firstOrNull { it.height in 1..limit } ?: sorted.last()
    }
}
