// X 브라우저 탭(WebView)에 주입되는 스크립트.
// API 응답을 가로채 영상 mp4 주소를 모으고, 트윗마다 버튼 + 프로필 일괄 다운로드 패널을 단다.
(function () {
  if (window.__xvdLoaded) return;
  window.__xvdLoaded = true;

  var tweets = {}; // id -> {id, user, videos:[{url,gif}]}
  var API_RE = /\/i\/api\/|\/graphql\//;
  var ICON = '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 16l-5-5 1.4-1.4 2.6 2.6V3h2v9.2l2.6-2.6L17 11l-5 5zm-7 2h14v2H5v-2z"/></svg>';

  function screenName(node) {
    var u = node.core && node.core.user_results && node.core.user_results.result;
    return (u && ((u.core && u.core.screen_name) || (u.legacy && u.legacy.screen_name))) || "";
  }

  function collect(node, out, seen) {
    if (!node || typeof node !== "object" || seen.has(node)) return;
    seen.add(node);
    if (Array.isArray(node)) { node.forEach(function (n) { collect(n, out, seen); }); return; }
    var media = node.legacy && node.legacy.extended_entities && node.legacy.extended_entities.media;
    if (node.rest_id && Array.isArray(media)) {
      var videos = [];
      media.forEach(function (m) {
        if (!m.video_info || !Array.isArray(m.video_info.variants)) return;
        var mp4 = m.video_info.variants
          .filter(function (v) { return v.content_type === "video/mp4" && v.url; })
          .sort(function (a, b) { return (b.bitrate || 0) - (a.bitrate || 0); });
        if (mp4.length) videos.push({ url: mp4[0].url, gif: m.type === "animated_gif" });
      });
      if (videos.length) out.push({ id: node.rest_id, user: screenName(node), videos: videos });
    }
    for (var k in node) collect(node[k], out, seen);
  }

  function handle(text) {
    try {
      var out = [];
      collect(JSON.parse(text), out, new WeakSet());
      if (out.length) { out.forEach(function (t) { tweets[t.id] = t; }); schedule(); }
    } catch (e) {}
  }

  var origFetch = window.fetch;
  window.fetch = function () {
    var args = arguments;
    return origFetch.apply(this, args).then(function (res) {
      try {
        var url = typeof args[0] === "string" ? args[0] : (args[0] && args[0].url) || "";
        if (API_RE.test(url)) res.clone().text().then(handle).catch(function () {});
      } catch (e) {}
      return res;
    });
  };
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (API_RE.test(String(url))) {
      this.addEventListener("load", function () { try { handle(this.responseText); } catch (e) {} });
    }
    return origOpen.apply(this, arguments);
  };

  function files(t) {
    return t.videos.map(function (v, i) {
      return { url: v.url, filename: (t.user || "unknown") + "_" + t.id + (t.videos.length > 1 ? "_" + (i + 1) : "") + ".mp4" };
    });
  }
  function send(items) {
    if (window.XVDBridge) window.XVDBridge.download(JSON.stringify(items));
  }

  // ---- 스타일 ----
  function addStyle() {
    if (document.getElementById("xvd-style")) return;
    var s = document.createElement("style");
    s.id = "xvd-style";
    s.textContent =
      ".xvd-btn{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;border:0;border-radius:9999px;background:transparent;color:#8b98a5;}" +
      ".xvd-btn:active{background:rgba(77,163,255,.18);color:#4da3ff;}" +
      "#xvd-panel{position:fixed;right:12px;bottom:84px;z-index:99999;display:flex;flex-direction:column;gap:8px;align-items:flex-end;font:600 14px system-ui,sans-serif;}" +
      "#xvd-panel button{padding:11px 16px;border:0;border-radius:9999px;color:#fff;font:700 14px system-ui,sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.5);background:linear-gradient(90deg,#3b82f6,#8b5cf6);}" +
      "#xvd-panel button.alt{background:#1c2128;border:1px solid #262c34;}" +
      "#xvd-panel button:disabled{opacity:.55;}" +
      "#xvd-panel .info{padding:5px 11px;border-radius:9999px;background:rgba(11,13,16,.85);border:1px solid #262c34;color:#e7e9ea;font-size:12px;}";
    (document.head || document.documentElement).appendChild(s);
  }

  // ---- 트윗별 버튼 ----
  function tweetIdOf(article) {
    var t = article.querySelector('a[href*="/status/"] time');
    var link = t && t.closest("a");
    var m = link && link.getAttribute("href").match(/\/status\/(\d+)/);
    return m ? m[1] : null;
  }
  function decorate() {
    document.querySelectorAll('article[data-testid="tweet"]').forEach(function (article) {
      var id = tweetIdOf(article), t = id && tweets[id];
      var bar = article.querySelector('div[role="group"]');
      if (!t || !bar || bar.querySelector(".xvd-btn")) return;
      var btn = document.createElement("button");
      btn.className = "xvd-btn";
      btn.innerHTML = ICON;
      btn.addEventListener("click", function (ev) {
        ev.preventDefault(); ev.stopPropagation();
        send(files(t));
      });
      bar.appendChild(btn);
    });
  }

  // ---- 프로필 일괄 다운로드 패널 ----
  var RESERVED = ["home", "explore", "notifications", "messages", "i", "search", "settings", "compose", "tos", "privacy"];
  function profileUser() {
    var m = location.pathname.match(/^\/([A-Za-z0-9_]+)(?:\/(?:media|with_replies|highlights))?\/?$/);
    return m && RESERVED.indexOf(m[1].toLowerCase()) < 0 ? m[1] : null;
  }
  function userVideos(user) {
    return Object.keys(tweets).map(function (k) { return tweets[k]; })
      .filter(function (t) { return t.user.toLowerCase() === user.toLowerCase(); });
  }

  var panel = null, scrolling = false;
  function renderPanel() {
    var user = profileUser();
    if (!user) { if (panel) { panel.remove(); panel = null; } return; }
    if (!document.body) return;
    if (!panel) {
      panel = document.createElement("div");
      panel.id = "xvd-panel";
      panel.innerHTML = '<div class="info"></div><button class="alt" data-act="scan"></button><button data-act="dl"></button>';
      document.body.appendChild(panel);
      panel.addEventListener("click", onPanelClick);
    }
    var n = userVideos(user).reduce(function (s, t) { return s + t.videos.length; }, 0);
    panel.querySelector(".info").textContent = "@" + user + " 영상 " + n + "개 찾음";
    panel.querySelector('[data-act="scan"]').textContent = scrolling ? "수집 중지" : "끝까지 스크롤하며 수집";
    var dl = panel.querySelector('[data-act="dl"]');
    dl.textContent = "전체 다운로드 (" + n + ")";
    dl.disabled = n === 0;
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  async function onPanelClick(e) {
    var act = e.target.dataset && e.target.dataset.act, user = profileUser();
    if (!act || !user) return;
    if (act === "scan") {
      if (scrolling) { scrolling = false; return renderPanel(); }
      scrolling = true; renderPanel();
      var last = -1, same = 0;
      while (scrolling && same < 6) {
        window.scrollTo(0, document.body.scrollHeight);
        await sleep(1200);
        var c = Object.keys(tweets).length;
        same = c === last ? same + 1 : 0;
        last = c;
      }
      scrolling = false; renderPanel();
    } else if (act === "dl") {
      var items = [];
      userVideos(user).forEach(function (t) { items = items.concat(files(t)); });
      send(items);
    }
  }

  var timer;
  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(function () { addStyle(); decorate(); renderPanel(); }, 150);
  }
  function start() {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    schedule();
  }
  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start);
})();
