package com.xvd.downloader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 로그인 없이 공개 트윗의 영상·사진 주소를 가져온다 (X 공식 임베드용 syndication 엔드포인트). */
object TweetFetcher {

    data class Variant(val url: String, val height: Int, val bitrate: Int)
    data class Media(val thumb: String?, val gif: Boolean, val variants: List<Variant>, val photo: Boolean = false)
    data class Tweet(val id: String, val user: String, val text: String, val medias: List<Media>)

    private val ID_RE = Regex("""(?:x|twitter)\.com/[^/\s]+/status(?:es)?/(\d+)""")
    private val SIZE_RE = Regex("""/(\d{2,4})x(\d{2,4})/""")

    fun parseId(input: String): String? = ID_RE.find(input)?.groupValues?.get(1)

    private val PHOTO_RE = Regex("""^(https://pbs\.twimg\.com/media/[^.?]+)\.(\w+)""")

    /** https://pbs.twimg.com/media/ABC.jpg → ...ABC?format=jpg&name=orig (원본 화질) */
    fun origPhoto(u: String): String =
        PHOTO_RE.find(u)?.let { "${it.groupValues[1]}?format=${it.groupValues[2]}&name=orig" } ?: u

    /** 저장할 파일 확장자 */
    fun ext(url: String, photo: Boolean): String {
        if (!photo) return "mp4"
        return (Regex("format=(\\w+)").find(url) ?: Regex("\\.(\\w+)(?:\\?|$)").find(url))
            ?.groupValues?.get(1)?.lowercase() ?: "jpg"
    }

    /** 임베드 서버가 요구하는 토큰 (react-tweet 방식). */
    private fun token(id: String): String {
        val v = id.toDouble() / 1e15 * Math.PI
        val ip = v.toLong()
        var fp = v - ip
        val sb = StringBuilder(java.lang.Long.toString(ip, 36)).append('.')
        repeat(12) {
            fp *= 36
            val d = fp.toInt()
            sb.append(Character.forDigit(d, 36))
            fp -= d
        }
        return sb.toString().replace(Regex("(0+|\\.)"), "")
    }

    fun fetch(id: String): Tweet {
        val conn = URL("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${token(id)}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        try {
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("트윗을 찾을 수 없어요. 삭제됐거나 비공개일 수 있어요.")
            if (code != 200) throw IllegalStateException("서버 응답 오류 ($code)")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.length() == 0 || json.optString("__typename") == "TweetTombstone") {
                throw IllegalStateException("이 트윗은 가져올 수 없어요. (민감한 콘텐츠이거나 비공개) ‘X 브라우저’ 탭에서 로그인해 받아보세요.")
            }
            val medias = ArrayList<Media>()
            val arr = json.optJSONArray("mediaDetails")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    if (m.optString("type") == "photo") {
                        val src = m.optString("media_url_https")
                        if (src.isNotEmpty()) medias.add(Media(src, false, listOf(Variant(origPhoto(src), 0, 0)), photo = true))
                        continue
                    }
                    val info = m.optJSONObject("video_info") ?: continue
                    val vs = info.optJSONArray("variants") ?: continue
                    val list = ArrayList<Variant>()
                    for (j in 0 until vs.length()) {
                        val v = vs.getJSONObject(j)
                        if (v.optString("content_type") != "video/mp4") continue
                        val url = v.optString("url")
                        val size = SIZE_RE.find(url)
                        val h = size?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
                        list.add(Variant(url, h, v.optInt("bitrate", 0)))
                    }
                    list.sortByDescending { it.bitrate }
                    if (list.isNotEmpty()) {
                        medias.add(Media(m.optString("media_url_https").ifEmpty { null }, m.optString("type") == "animated_gif", list))
                    }
                }
            }
            if (medias.isEmpty()) throw IllegalStateException("이 트윗에는 영상이나 사진이 없어요.")
            val user = json.optJSONObject("user")?.optString("screen_name").orEmpty()
            return Tweet(id, user, json.optString("text"), medias)
        } finally {
            conn.disconnect()
        }
    }
}
