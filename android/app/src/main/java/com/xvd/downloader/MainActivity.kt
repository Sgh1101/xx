package com.xvd.downloader

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.appcompat.widget.SwitchCompat
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
    private lateinit var pageSettings: View
    private lateinit var btnWifi: TextView
    private lateinit var historyList: LinearLayout

    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var tab = 0
    private var webLoaded = false
    private var busy = false
    private var renderedSig = ""
    private val historyListener: () -> Unit = { if (tab == 2) renderHistory(false) }

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
        pageSettings = findViewById(R.id.pageSettings)
        btnWifi = findViewById(R.id.btnWifi)
        historyList = findViewById(R.id.historyList)

        Downloads.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        findViewById<View>(R.id.tabLink).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.tabWeb).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.tabHistory).setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { selectTab(3) }
        btnWifi.setOnClickListener { setWifiOnly(!Settings.wifiOnly(this)) }
        findViewById<View>(R.id.btnPauseAll).setOnClickListener { Downloads.pauseAll() }
        findViewById<View>(R.id.btnResumeAll).setOnClickListener { Downloads.resumeAll() }
        findViewById<View>(R.id.btnCancelAll).setOnClickListener { Downloads.cancelAll() }
        findViewById<View>(R.id.btnClearHistory).setOnClickListener { Downloads.clearFinished() }
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
        setupSettings()
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

    override fun onStart() {
        super.onStart()
        Downloads.addListener(historyListener)
        Downloads.resumeFromBackground()
        if (tab == 2) renderHistory(true)
    }

    override fun onStop() {
        Downloads.removeListener(historyListener)
        if (!Settings.background(this)) Downloads.pauseForBackground()
        super.onStop()
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
        pageSettings.visibility = if (t == 3) View.VISIBLE else View.GONE
        styleTab(R.id.tabLink, R.id.tabLinkIcon, R.id.tabLinkText, t == 0)
        styleTab(R.id.tabWeb, R.id.tabWebIcon, R.id.tabWebText, t == 1)
        styleTab(R.id.tabHistory, R.id.tabHistoryIcon, R.id.tabHistoryText, t == 2)
        styleTab(R.id.tabSettings, R.id.tabSettingsIcon, R.id.tabSettingsText, t == 3)
        if (t == 1 && !webLoaded) {
            webLoaded = true
            webView.loadUrl("https://x.com/")
        }
        if (t == 2) renderHistory(true)
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
        if (Settings.autoStart(this)) autoDownload(tweet)
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

    /** 설정의 화질 규칙대로 영상마다 하나씩 골라 바로 받기 */
    private fun autoDownload(tweet: TweetFetcher.Tweet) {
        var added = 0
        tweet.medias.forEachIndexed { idx, media ->
            val v = Settings.pick(this, media.variants) ?: return@forEachIndexed
            val suffix = if (tweet.medias.size > 1) "_${idx + 1}" else ""
            if (Downloads.enqueue(this, v.url, "${tweet.user}_${tweet.id}$suffix.mp4")) added++
        }
        toast(if (added > 0) "${added}개를 기록 탭에 추가했어요" else "이미 받았거나 받는 중이에요")
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
        val added = Downloads.enqueue(this, url, filename)
        toast(if (added) "기록 탭에 추가했어요" else "이미 받았거나 받는 중이에요")
        return added
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
                setOf("https://x.com", "https://www.x.com", "https://mobile.x.com", "https://twitter.com", "https://mobile.twitter.com")
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
    private fun variantOf(url: String, bitrate: Int): TweetFetcher.Variant {
        val m = Regex("""/(\d{2,4})x(\d{2,4})/""").find(url)
        val h = m?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
        return TweetFetcher.Variant(url, h, bitrate)
    }

    inner class Bridge {
        /** 팝업에서 호출: 가로챈 데이터가 없을 때 트윗 번호로 직접 영상을 찾아 받기 */
        @JavascriptInterface
        fun downloadTweet(id: String, user: String) {
            if (!id.matches(Regex("\\d{5,25}"))) return
            io.execute {
                val r = runCatching { TweetFetcher.fetch(id) }
                r.onSuccess { tw ->
                    var added = 0
                    val who = tw.user.ifBlank { user }.replace(Regex("[^A-Za-z0-9_]"), "_")
                    tw.medias.forEachIndexed { idx, media ->
                        val v = Settings.pick(this@MainActivity, media.variants) ?: return@forEachIndexed
                        val suffix = if (tw.medias.size > 1) "_${idx + 1}" else ""
                        if (Downloads.enqueue(this@MainActivity, v.url, "${who}_${id}$suffix.mp4")) added++
                    }
                    ui.post { toast(if (added > 0) "${added}개를 기록 탭에 추가했어요" else "이미 받았거나 받는 중이에요") }
                }.onFailure { e ->
                    ui.post { toast(e.message ?: "영상을 찾지 못했어요") }
                }
            }
        }

        @JavascriptInterface
        fun download(json: String) {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
            var added = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("filename").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val cands = ArrayList<TweetFetcher.Variant>()
                val vs = o.optJSONArray("variants")
                if (vs != null) {
                    for (j in 0 until vs.length()) {
                        val v = vs.optJSONObject(j) ?: continue
                        cands.add(variantOf(v.optString("url"), v.optInt("bitrate")))
                    }
                } else {
                    cands.add(variantOf(o.optString("url"), 0))
                }
                val pick = Settings.pick(this@MainActivity, cands.filter { it.url.startsWith("https://video.twimg.com/") })
                if (pick == null || name.isBlank()) continue
                if (Downloads.enqueue(this@MainActivity, pick.url, name)) added++
            }
            val skipped = arr.length() - added
            ui.post {
                toast(
                    if (added > 0) "${added}개를 기록 탭에 추가했어요" + if (skipped > 0) " (${skipped}개는 이미 있음)" else ""
                    else "이미 받았거나 받는 중이에요"
                )
            }
        }
    }

    // ---------------- 기록 ----------------

    private fun mb(b: Long) = "%.1fMB".format(b / 1_048_576.0)

    private fun statusText(t: Downloads.Task): String {
        val pct = if (t.total > 0) (t.bytes * 100 / t.total).toInt() else -1
        val size = if (t.total > 0) "${mb(t.bytes)} / ${mb(t.total)}" else mb(t.bytes)
        return when (t.state) {
            Downloads.State.QUEUED -> "대기 중"
            Downloads.State.RUNNING -> (if (pct >= 0) "받는 중 $pct%  ·  " else "받는 중  ·  ") + size
            Downloads.State.PAUSED -> (
                when {
                    t.waitNet -> "Wi-Fi 연결을 기다리는 중"
                    t.waitBg -> "앱 밖에서는 일시정지"
                    else -> "일시정지"
                }
                ) + (if (pct >= 0) " $pct%" else "") + "  ·  " + size
            Downloads.State.DONE -> "완료  ·  " + mb(if (t.total > 0) t.total else t.bytes)
            Downloads.State.FAILED -> "실패: ${t.error ?: "알 수 없는 오류"}"
            Downloads.State.CANCELED -> "중지됨"
        }
    }

    private fun renderHistory(force: Boolean) {
        val list = Downloads.snapshot()
        val sig = list.joinToString(",") { it.id + it.state.name }
        val rebuild = force || sig != renderedSig
        if (rebuild) {
            renderedSig = sig
            historyList.removeAllViews()
            if (list.isEmpty()) {
                historyList.addView(TextView(this).apply {
                    text = "아직 받은 영상이 없어요"
                    setTextColor(ContextCompat.getColor(context, R.color.muted))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(80), 0, 0)
                })
                return
            }
        }
        val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        list.forEachIndexed { i, t ->
            val row: View = if (rebuild) {
                LayoutInflater.from(this).inflate(R.layout.item_history, historyList, false).also {
                    it.tag = t.id
                    historyList.addView(it)
                    bindRow(it, t)
                }
            } else historyList.getChildAt(i)
            row.findViewById<TextView>(R.id.hSub).text = fmt.format(Date(t.time)) + "  ·  " + statusText(t)
            val bar = row.findViewById<ProgressBar>(R.id.hProgress)
            bar.visibility = if (t.state == Downloads.State.DONE || t.state == Downloads.State.CANCELED) View.GONE else View.VISIBLE
            bar.progress = if (t.total > 0) (t.bytes * 1000 / t.total).toInt() else 0
        }
    }

    /** 행의 제목과 버튼(상태가 바뀔 때만 다시 만들어짐) */
    private fun bindRow(row: View, t: Downloads.Task) {
        row.findViewById<TextView>(R.id.hTitle).text = t.name
        val action = row.findViewById<TextView>(R.id.hAction)
        val cancel = row.findViewById<TextView>(R.id.hCancel)
        val active = t.state == Downloads.State.QUEUED || t.state == Downloads.State.RUNNING
        action.text = when (t.state) {
            Downloads.State.QUEUED, Downloads.State.RUNNING -> "⏸ 일시정지"
            Downloads.State.PAUSED -> "▶ 이어받기"
            Downloads.State.DONE -> "▶ 재생"
            Downloads.State.FAILED -> "↻ 재시도"
            Downloads.State.CANCELED -> "↻ 다시 받기"
        }
        cancel.visibility = if (t.state == Downloads.State.DONE || t.state == Downloads.State.CANCELED) View.GONE else View.VISIBLE
        action.setOnClickListener {
            when (t.state) {
                Downloads.State.DONE -> openVideo(t)
                else -> if (active) Downloads.pause(t.id) else {
                    if (!Downloads.canDownloadNow()) toast("Wi-Fi 전용이라 Wi-Fi에 연결되면 시작돼요")
                    Downloads.resume(t.id)
                }
            }
        }
        cancel.setOnClickListener { Downloads.cancel(t.id) }
        row.setOnClickListener { if (t.state == Downloads.State.DONE) openVideo(t) }
        row.setOnLongClickListener {
            Downloads.remove(t.id)
            true
        }
    }

    private fun openVideo(t: Downloads.Task) {
        val uri = t.uri?.let { Uri.parse(it) }
        if (uri == null) { toast("파일을 열 수 없어요. 잠시 후 다시 눌러 보세요"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("재생할 수 없어요 (파일이 지워졌을 수 있어요)")
        }
    }

    // ---------------- 설정 ----------------

    private fun updateWifiChip() {
        val on = Settings.wifiOnly(this)
        btnWifi.text = if (on) "📶 Wi-Fi만: 켜짐" else "📶 Wi-Fi만: 꺼짐"
        btnWifi.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.muted))
    }

    private fun setWifiOnly(on: Boolean) {
        Settings.setWifiOnly(this, on)
        findViewById<SwitchCompat>(R.id.swWifi).isChecked = on
        updateWifiChip()
        Downloads.recheckNetwork()
        toast(if (on) "Wi-Fi에서만 받아요" else "모바일 데이터로도 받아요")
    }

    /** 조각난 선택 버튼(세그먼트) 만들기 */
    private fun segmented(container: LinearLayout, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        labels.forEachIndexed { i, label ->
            container.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, if (i == selected) R.color.text else R.color.muted))
                if (i == selected) setBackgroundResource(R.drawable.bg_tab_selected) else background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    onPick(i)
                    segmented(container, labels, i, onPick)
                }
            })
        }
    }

    private fun setupSettings() {
        val swWifi = findViewById<SwitchCompat>(R.id.swWifi)
        val swBg = findViewById<SwitchCompat>(R.id.swBg)
        val swAuto = findViewById<SwitchCompat>(R.id.swAuto)
        swWifi.isChecked = Settings.wifiOnly(this)
        swBg.isChecked = Settings.background(this)
        swAuto.isChecked = Settings.autoStart(this)
        swWifi.setOnCheckedChangeListener { v, on -> if (v.isPressed) setWifiOnly(on) }
        swBg.setOnCheckedChangeListener { _, on -> Settings.setBackground(this, on) }
        swAuto.setOnCheckedChangeListener { _, on -> Settings.setAutoStart(this, on) }
        updateWifiChip()

        val qValues = listOf(0, 1080, 720, 480)
        segmented(
            findViewById(R.id.segQuality), listOf("최대", "1080p", "720p", "480p"),
            qValues.indexOf(Settings.quality(this)).coerceAtLeast(0)
        ) { Settings.setQuality(this, qValues[it]) }

        val cValues = listOf(1, 2, 3, 5)
        segmented(
            findViewById(R.id.segConcurrent), listOf("1개", "2개", "3개", "5개"),
            cValues.indexOf(Settings.concurrent(this)).let { if (it < 0) 2 else it }
        ) { Settings.setConcurrent(this, cValues[it]) }
    }

    // ---------------- 유틸 ----------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
