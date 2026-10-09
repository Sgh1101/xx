package com.xvd.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * 이어받기를 지원하는 자체 다운로더.
 * 상태: 대기 / 받는 중 / 일시정지 / 완료 / 실패 / 취소. 기록은 SharedPreferences 에 저장한다.
 */
object Downloads {
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

    class Task(val id: String, val url: String, val name: String, val time: Long) {
        @Volatile var state = State.QUEUED
        @Volatile var bytes = 0L
        @Volatile var total = 0L
        @Volatile var error: String? = null
        @Volatile var uri: String? = null
        @Volatile var gen = 0 // 일시정지/취소/재개 때마다 증가 → 이전 작업 스레드가 스스로 종료
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var waitNet = false // Wi-Fi 전용이라 연결을 기다리는 중
        @Volatile var waitBg = false  // 백그라운드 금지 설정으로 멈춘 상태
        @Volatile var submitted = false // 실행 슬롯을 점유 중 (동시 다운로드 수 제한용)
        val runLock = ReentrantLock() // 같은 파일을 두 스레드가 동시에 쓰지 않도록
    }

    private const val KEY = "downloads"
    private const val MAX = 200

    private val lock = Any()
    private val tasks = ArrayList<Task>() // 최신순
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var loaded = false
    private lateinit var app: Context
    @Volatile private var lastNotify = 0L

    // ---------------- 초기화 / 저장 ----------------

    fun init(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            app = ctx.applicationContext
            loaded = true
            registerNetworkCallback()
            val arr = runCatching {
                JSONArray(app.getSharedPreferences("xvd", Context.MODE_PRIVATE).getString(KEY, "[]"))
            }.getOrDefault(JSONArray())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = Task(o.optString("id"), o.optString("url"), o.optString("name"), o.optLong("time"))
                val st = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.FAILED)
                // 앱이 죽었으면 진행 중이던 건 일시정지로 복구
                t.state = if (st == State.RUNNING || st == State.QUEUED) State.PAUSED else st
                t.bytes = o.optLong("bytes")
                t.total = o.optLong("total")
                t.uri = o.optString("uri").ifEmpty { null }
                tasks.add(t)
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(lock) {
            tasks.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("url", it.url).put("name", it.name).put("time", it.time)
                        .put("state", it.state.name).put("bytes", it.bytes).put("total", it.total)
                        .put("uri", it.uri ?: "")
                )
            }
        }
        app.getSharedPreferences("xvd", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    // ---------------- 네트워크 (Wi-Fi 전용) ----------------

    private fun isWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Wi-Fi 전용 설정이 꺼져 있거나 Wi-Fi 에 연결돼 있으면 true */
    fun canDownloadNow(): Boolean = !Settings.wifiOnly(app) || isWifi()

    private fun registerNetworkCallback() {
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheckNetwork()
                override fun onLost(network: Network) = recheckNetwork()
            })
        }
    }

    /** 네트워크가 바뀌었거나 Wi-Fi 설정이 바뀌었을 때: 멈추거나 자동으로 이어받기 */
    fun recheckNetwork() {
        if (!loaded) return
        val ok = canDownloadNow()
        val resumeIds = ArrayList<String>()
        synchronized(lock) {
            for (t in tasks) {
                if (!ok && (t.state == State.QUEUED || t.state == State.RUNNING)) {
                    t.state = State.PAUSED
                    t.waitNet = true
                    abort(t)
                } else if (ok && t.waitNet && t.state == State.PAUSED) {
                    resumeIds.add(t.id)
                }
            }
        }
        resumeIds.forEach { resume(it) }
        save(); changed(true)
    }

    // ---------------- 백그라운드 설정 ----------------

    fun pauseForBackground() {
        var any = false
        synchronized(lock) {
            for (t in tasks) {
                if (t.state == State.QUEUED || t.state == State.RUNNING) {
                    t.state = State.PAUSED
                    t.waitBg = true
                    abort(t)
                    any = true
                }
            }
        }
        if (any) { save(); changed(true) }
    }

    fun resumeFromBackground() {
        val ids = synchronized(lock) { tasks.filter { it.waitBg && it.state == State.PAUSED }.map { it.id } }
        ids.forEach { resume(it) }
    }

    // ---------------- 조회 / 리스너 ----------------

    fun snapshot(): List<Task> = synchronized(lock) { tasks.toList() }

    fun activeCount(): Int = synchronized(lock) { tasks.count { it.state == State.QUEUED || it.state == State.RUNNING } }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** UI 스레드에서 리스너 호출. 진행률 갱신은 0.25초로 제한 */
    private fun changed(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 250) return
        lastNotify = now
        main.post { listeners.forEach { it() } }
    }

    // ---------------- 제어 ----------------

    /** 새 다운로드 추가. 같은 이름이 이미 있으면(실패/취소 제외) false */
    fun enqueue(ctx: Context, url: String, name: String): Boolean {
        init(ctx)
        val t = Task(UUID.randomUUID().toString(), url, name, System.currentTimeMillis())
        synchronized(lock) {
            if (tasks.any { it.name == name && it.state != State.FAILED && it.state != State.CANCELED }) return false
            tasks.add(0, t)
            while (tasks.size > MAX) {
                val idx = tasks.indexOfLast { it.state == State.DONE || it.state == State.CANCELED || it.state == State.FAILED }
                if (idx < 0) break
                tasks.removeAt(idx)
            }
        }
        save()
        submit(t)
        changed(true)
        DownloadService.start(app)
        return true
    }

    private fun find(id: String): Task? = synchronized(lock) { tasks.firstOrNull { it.id == id } }

    private fun abort(t: Task) {
        t.gen++
        val c = t.conn
        if (c != null) pool.execute { runCatching { c.disconnect() } } // 읽기 대기 중인 스레드를 즉시 깨움
    }

    fun pause(id: String) {
        val t = find(id) ?: return
        synchronized(lock) {
            if (t.state != State.QUEUED && t.state != State.RUNNING) return
            t.state = State.PAUSED
            abort(t)
        }
        save(); changed(true)
    }

    fun resume(id: String) {
        val t = find(id) ?: return
        synchronized(lock) {
            if (t.state != State.PAUSED && t.state != State.FAILED && t.state != State.CANCELED) return
            if (t.state == State.CANCELED) { t.bytes = 0; t.total = 0 }
            t.state = State.QUEUED
            t.error = null
            t.waitNet = false
            t.waitBg = false
            t.gen++
        }
        save(); submit(t); changed(true)
        DownloadService.start(app)
    }

    fun cancel(id: String) {
        val t = find(id) ?: return
        synchronized(lock) {
            if (t.state == State.DONE || t.state == State.CANCELED) return
            t.state = State.CANCELED
            abort(t)
        }
        pool.execute { t.runLock.lock(); try { partFile(t).delete() } finally { t.runLock.unlock() } }
        save(); changed(true)
    }

    fun pauseAll() = snapshot().forEach { pause(it.id) }
    fun resumeAll() = snapshot().filter { it.state == State.PAUSED || it.state == State.FAILED }.forEach { resume(it.id) }
    fun cancelAll() = snapshot().forEach { if (it.state != State.DONE && it.state != State.CANCELED) cancel(it.id) }

    /** 기록에서만 제거 (받은 파일은 그대로) */
    fun remove(id: String) {
        cancel(id)
        synchronized(lock) { tasks.removeAll { it.id == id } }
        save(); changed(true)
    }

    fun clearFinished() {
        synchronized(lock) {
            tasks.removeAll { it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELED }
        }
        save(); changed(true)
    }

    // ---------------- 실제 다운로드 ----------------

    /** 대기 중인 작업을 동시 다운로드 수 한도 안에서 오래된 순으로 시작 */
    private fun submit(@Suppress("UNUSED_PARAMETER") t: Task) = pump()

    private fun pump() {
        val start = ArrayList<Pair<Task, Int>>()
        var blocked = false
        synchronized(lock) {
            val wifiBlock = !canDownloadNow()
            var slots = Settings.concurrent(app) - tasks.count { it.submitted }
            for (t in tasks.asReversed()) {
                if (t.state != State.QUEUED || t.submitted) continue
                if (wifiBlock) { // Wi-Fi 전용인데 Wi-Fi 가 아님 → 연결될 때까지 대기
                    t.state = State.PAUSED
                    t.waitNet = true
                    blocked = true
                    continue
                }
                if (slots <= 0) continue
                t.submitted = true
                slots--
                start.add(t to t.gen)
            }
        }
        if (blocked) { save(); changed(true) }
        start.forEach { (t, g) -> pool.execute { run(t, g) } }
    }

    private fun partFile(t: Task): File {
        val dir = app.getExternalFilesDir("partial") ?: File(app.filesDir, "partial")
        dir.mkdirs()
        return File(dir, t.id + ".part")
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun run(t: Task, g: Int) {
        t.runLock.lock()
        try {
            synchronized(lock) {
                if (t.gen != g || t.state != State.QUEUED) return
                t.state = State.RUNNING
                t.error = null
            }
            changed(true)
            val part = partFile(t)
            var existing = if (part.exists()) part.length() else 0L
            var c = open(t.url, existing)
            t.conn = c
            var code = c.responseCode
            if (code == 416) { // 이미 끝까지 받았거나 범위 오류 → 처음부터
                c.disconnect(); part.delete(); existing = 0
                c = open(t.url, 0); t.conn = c; code = c.responseCode
            }
            val append: Boolean
            when (code) {
                206 -> { append = true; t.total = existing + c.contentLengthLong.coerceAtLeast(0) }
                200 -> { append = false; existing = 0; t.total = c.contentLengthLong.coerceAtLeast(0) }
                else -> throw IOException("서버 응답 오류 ($code)")
            }
            t.bytes = existing
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (t.gen != g) return // 일시정지/취소: 상태는 호출한 쪽이 이미 바꿔 둠
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        t.bytes += n
                        changed(false)
                    }
                }
            }
            if (t.total > 0 && t.bytes < t.total) throw IOException("연결이 끊겼어요")
            if (t.gen != g) return
            val uri = publish(t, part)
            synchronized(lock) {
                if (t.gen != g) return
                t.state = State.DONE
                t.uri = uri
                if (t.total > 0) t.bytes = t.total
            }
            save(); changed(true)
        } catch (e: Exception) {
            if (t.gen != g) return // 일시정지로 연결을 끊은 경우
            synchronized(lock) {
                t.state = State.FAILED
                t.error = e.message ?: "네트워크 오류"
            }
            save(); changed(true)
        } finally {
            t.conn?.let { c -> runCatching { c.disconnect() } }
            t.conn = null
            synchronized(lock) { t.submitted = false }
            t.runLock.unlock()
            pump()
        }
    }

    /** 앱 전용 폴더의 임시 파일을 공용 Download/X-Videos 로 옮기고 열 수 있는 uri 반환 */
    private fun publish(t: Task, src: File): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, t.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/X-Videos")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("저장 위치를 만들 수 없어요")
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            src.delete()
            return uri.toString()
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "X-Videos")
        dir.mkdirs()
        var dest = File(dir, t.name)
        var n = 1
        while (dest.exists()) dest = File(dir, t.name.removeSuffix(".mp4") + " ($n++).mp4")
        src.copyTo(dest, overwrite = true)
        src.delete()
        MediaScannerConnection.scanFile(app, arrayOf(dest.path), arrayOf("video/mp4")) { _, uri ->
            if (uri != null) { t.uri = uri.toString(); save(); changed(true) }
        }
        return null
    }
}
