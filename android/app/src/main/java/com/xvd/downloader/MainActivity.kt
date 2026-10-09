package com.xvd.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var pageLink: View
    private lateinit var pageWeb: View
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var input: EditText
    private lateinit var btnFetch: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var hint: View
    private lateinit var pageHistory: View
    private lateinit var historyList: LinearLayout

    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var tab = 0
    private var webLoaded = false
    private var busy = false
    private var historyBusy = false
    private val historyTick = object : Runnable {
        override fun run() {
            if (tab == 2 && historyBusy) {
                renderHistory()
                ui.postDelayed(this, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pageLink = findViewById(R.id.pageLink)
        pageWeb = findViewById(R.id.pageWeb)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        input = findViewById(R.id.input)
        btnFetch = findViewById(R.id.btnFetch)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        hint = findViewById(R.id.hint)
        pageHistory = findViewById(R.id.pageHistory)
        historyList = findViewById(R.id.historyList)

        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        findViewById<View>(R.id.tabLink).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.tabWeb).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.tabHistory).setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.btnClearHistory).setOnClickListener {
            History.clear(this)
            renderHistory()
        }
        findViewById<View>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        btnFetch.setOnClickListener { fetch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { fetch(); true } else false
        }

        val prefs = getSharedPreferences("xvd", Context.MODE_PRIVATE)
        val loginHint = findViewById<View>(R.id.loginHint)
        loginHint.visibility = if (prefs.getBoolean("hint_closed", false)) View.GONE else View.VISIBLE
        findViewById<View>(R.id.loginHintClose).setOnClickListener {
            loginHint.visibility = View.GONE
            prefs.edit().putBoolean("hint_closed", true).apply()
        }

        setupWebView()
        selectTab(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab == 1 && webView.canGoBack() -> webView.goBack()
                    tab != 0 -> selectTab(0)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** X 앱 공유 메뉴로 들어온 링크를 자동으로 처리 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        selectTab(0)
        input.setText(text)
        fetch()
    }

    // ---------------- 탭 ----------------

    private fun selectTab(t: Int) {
        tab = t
        pageLink.visibility = if (t == 0) View.VISIBLE else View.GONE
        pageWeb.visibility = if (t == 1) View.VISIBLE else View.GONE
        pageHistory.visibility = if (t == 2) View.VISIBLE else View.GONE
        styleTab(R.id.tabLink, R.id.tabLinkIcon, R.id.tabLinkText, t == 0)
        styleTab(R.id.tabWeb, R.id.tabWebIcon, R.id.tabWebText, t == 1)
        styleTab(R.id.tabHistory, R.id.tabHistoryIcon, R.id.tabHistoryText, t == 2)
        if (t == 1 && !webLoaded) {
            webLoaded = true
            webView.loadUrl("https://x.com/")
        }
        ui.removeCallbacks(historyTick)
        if (t == 2) renderHistory()
    }

    private fun styleTab(tabId: Int, iconId: Int, textId: Int, selected: Boolean) {
        val color = ContextCompat.getColor(this, if (selected) R.color.accent else R.color.muted)
        findViewById<View>(tabId).setBackgroundResource(if (selected) R.drawable.bg_tab_selected else 0)
        findViewById<ImageView>(iconId).setColorFilter(color)
        findViewById<TextView>(textId).setTextColor(color)
    }

    // ---------------- 링크로 받기 ----------------

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("클립보드가 비어 있어요")
        } else {
            input.setText(text)
            fetch()
        }
    }

    private fun setStatus(msg: String?, error: Boolean = false) {
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
        status.text = msg
        status.setTextColor(ContextCompat.getColor(this, if (error) R.color.danger else R.color.muted))
    }

    private fun fetch() {
        if (busy) return
        val raw = input.text.toString().trim()
        val id = TweetFetcher.parseId(raw)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        if (id == null) {
            setStatus("올바른 X 트윗 링크가 아니에요.", error = true)
            return
        }
        busy = true
        btnFetch.text = "불러오는 중…"
        setStatus(null)
        results.removeAllViews()
        io.execute {
            val outcome = runCatching { TweetFetcher.fetch(id) }
            ui.post {
                busy = false
                btnFetch.text = "영상 가져오기"
                outcome.onSuccess { render(it) }
                    .onFailure { setStatus(it.message ?: "가져오기에 실패했어요.", error = true) }
            }
        }
    }

    private fun render(tweet: TweetFetcher.Tweet) {
        hint.visibility = View.GONE
        results.removeAllViews()
        tweet.medias.forEachIndexed { idx, media ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_video, results, false)
            card.findViewById<TextView>(R.id.title).text = tweet.text.ifBlank { "@${tweet.user}" }
            card.findViewById<TextView>(R.id.sub).text =
                "@${tweet.user}" + if (media.gif) " · GIF" else ""
            val thumb = card.findViewById<ImageView>(R.id.thumb)
            media.thumb?.let { loadImage(it, thumb) }

            val box = card.findViewById<LinearLayout>(R.id.qualities)
            media.variants.forEach { v ->
                val label = (if (v.height > 0) "${v.height}p" else "원본") +
                    if (v.bitrate > 0) "  ·  %.1f Mbps".format(v.bitrate / 1_000_000.0) else ""
                val b = TextView(this).apply {
                    text = "↓  $label"
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    setBackgroundResource(R.drawable.bg_button_quality)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                        topMargin = dp(8)
                    }
                    setOnClickListener {
                        val suffix = if (tweet.medias.size > 1) "_${idx + 1}" else ""
                        download(v.url, "${tweet.user}_${tweet.id}$suffix.mp4")
                    }
                }
                box.addView(b)
            }
            results.addView(card)
        }
    }

    private fun loadImage(url: String, target: ImageView) {
        io.execute {
            val bmp: Bitmap? = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) ui.post { target.setImageBitmap(bmp) }
        }
    }

    // ---------------- 다운로드 ----------------

    private fun download(url: String, filename: String): Boolean {
        return try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setMimeType("video/mp4")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "X-Videos/$filename")
            val dmId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            History.add(this, History.Entry(dmId, filename, System.currentTimeMillis()))
            toast("다운로드 시작: Download/X-Videos")
            true
        } catch (e: Exception) {
            toast("다운로드 실패: ${e.message}")
            false
        }
    }

    // ---------------- X 브라우저 (계정 일괄 다운로드) ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        // 로그인 화면이 막히지 않도록 일반 모바일 크롬처럼 보이게 함
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(Bridge(), "XVDBridge")
        val script = assets.open("xvd.js").bufferedReader().use { it.readText() }
        val useDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (useDocStart) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, script,
                setOf("https://x.com", "https://twitter.com", "https://mobile.twitter.com")
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                val ok = host == "x.com" || host.endsWith(".x.com") ||
                    host == "twitter.com" || host.endsWith(".twitter.com")
                if (!ok && request.url.scheme?.startsWith("http") == true) {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)) // 외부 링크는 브라우저로
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!useDocStart) view.evaluateJavascript(script, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    /** xvd.js 가 호출하는 다리. X 페이지에서 오는 호출이므로 주소를 엄격히 검증한다. */
    inner class Bridge {
        @JavascriptInterface
        fun download(json: String) {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
            var ok = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                val name = o.optString("filename").replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (!url.startsWith("https://video.twimg.com/") || name.isBlank()) continue
                var started = false
                // DownloadManager 는 아무 스레드에서나 호출 가능하지만 토스트는 UI 스레드
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setMimeType("video/mp4")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "X-Videos/$name")
                    val dmId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    History.add(this@MainActivity, History.Entry(dmId, name, System.currentTimeMillis()))
                    started = true
                } catch (_: Exception) {
                }
                if (started) ok++
            }
            ui.post {
                toast("$ok/${arr.length()}개 다운로드 시작 (Download/X-Videos)")
                if (tab == 2) renderHistory()
            }
        }
    }

    // ---------------- 기록 ----------------

    private fun statusOf(id: Long): Int {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        return -1
    }

    private fun renderHistory() {
        val entries = History.load(this)
        historyList.removeAllViews()
        historyBusy = false
        if (entries.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = "아직 받은 영상이 없어요"
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(80), 0, 0)
            })
            return
        }
        val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        entries.forEach { e ->
            val st = statusOf(e.id)
            val row = LayoutInflater.from(this).inflate(R.layout.item_history, historyList, false)
            row.findViewById<TextView>(R.id.hTitle).text = e.name
            row.findViewById<TextView>(R.id.hSub).text = fmt.format(Date(e.time))
            val chip = row.findViewById<TextView>(R.id.hStatus)
            val (label, colorRes) = when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> "▶ 재생" to R.color.accent
                DownloadManager.STATUS_FAILED -> "실패" to R.color.danger
                -1 -> "삭제됨" to R.color.muted
                else -> { historyBusy = true; "받는 중…" to R.color.muted }
            }
            chip.text = label
            chip.setTextColor(ContextCompat.getColor(this, colorRes))
            row.setOnClickListener { if (st == DownloadManager.STATUS_SUCCESSFUL) openVideo(e.id) else if (st == -1) toast("파일이 없어요") }
            row.setOnLongClickListener {
                History.remove(this, e.id)
                renderHistory()
                true
            }
            historyList.addView(row)
        }
        if (historyBusy) ui.postDelayed(historyTick, 1500)
    }

    private fun openVideo(id: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) { toast("파일을 열 수 없어요"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("재생할 앱이 없어요")
        }
    }

    // ---------------- 유틸 ----------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
