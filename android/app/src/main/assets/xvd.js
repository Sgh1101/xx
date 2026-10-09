// X 브라우저 탭(WebView)에 주입되는 스크립트.
// API 응답을 가로채 영상 mp4 / 원본 사진 주소를 모으고, 트윗마다 버튼 + 프로필 일괄 다운로드 패널을 단다.
(function () {
  if (window.__xvdLoaded) return;
  window.__xvdLoaded = true;

  var tweets = {}; // id -> {id, user, media:[{kind:'video'|'photo', url, gif?, variants?}], rtBy:[]}
  var API_RE = /\/i\/api\/|\/graphql\//;
  var ICON = '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 16l-5-5 1.4-1.4 2.6 2.6V3h2v9.2l2.6-2.6L17 11l-5 5zm-7 2h14v2H5v-2z"/></svg>';

  function screenName(node) {
    var u = node.core && node.core.user_results && node.core.user_results.result;
    return (u && ((u.core && u.core.screen_name) || (u.legacy && u.legacy.screen_name))) || "";
  }

  // rtBy: 이 트윗을 리포스트한 계정 (타임라인의 리포스트는 원본 트윗이 retweeted_status_result 안에 들어 있음)
  function collect(node, out, seen, rtBy) {
    if (!node || typeof node !== "object" || seen.has(node)) return;
    seen.add(node);
    if (Array.isArray(node)) { node.forEach(function (n) { collect(n, out, seen, rtBy); }); return; }
    var media = node.legacy && node.legacy.extended_entities && node.legacy.extended_entities.media;
    if (node.rest_id && Array.isArray(media)) {
      var items = [];
      media.forEach(function (m) {
        if (m.type === "photo" && m.media_url_https) {
          items.push({ kind: "photo", url: origPhoto(m.media_url_https) });
          return;
        }
        if (!m.video_info || !Array.isArray(m.video_info.variants)) return;
        var mp4 = m.video_info.variants
          .filter(function (v) { return v.content_type === "video/mp4" && v.url; })
          .sort(function (a, b) { return (b.bitrate || 0) - (a.bitrate || 0); });
        if (mp4.length) items.push({ kind: "video", url: mp4[0].url, gif: m.type === "animated_gif", variants: mp4.map(function (v) { return { url: v.url, bitrate: v.bitrate || 0 }; }) });
      });
      if (items.length) out.push({ id: node.rest_id, user: screenName(node), media: items, rtBy: rtBy ? [rtBy] : [] });
    }
    var isRt = node.legacy && node.legacy.retweeted_status_result;
    var by = isRt ? screenName(node).toLowerCase() : "";
    for (var k in node) collect(node[k], out, seen, k === "legacy" && by ? by : rtBy);
  }

  function handle(text) {
    try {
      var out = [];
      collect(JSON.parse(text), out, new WeakSet(), "");
      if (out.length) {
        out.forEach(function (t) {
          var old = tweets[t.id];
          if (old) t.rtBy = old.rtBy.concat(t.rtBy.filter(function (u) { return old.rtBy.indexOf(u) < 0; }));
          tweets[t.id] = t;
        });
        schedule();
      }
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

  // https://pbs.twimg.com/media/ABC.jpg → https://pbs.twimg.com/media/ABC?format=jpg&name=orig (원본 화질)
  function origPhoto(u) {
    var m = u.match(/^(https:\/\/pbs\.twimg\.com\/media\/[^.?]+)\.(\w+)/);
    return m ? m[1] + "?format=" + m[2] + "&name=orig" : u;
  }
  function extOf(item) {
    if (item.kind !== "photo") return "mp4";
    var m = item.url.match(/format=(\w+)/) || item.url.match(/\.(\w+)(?:\?|$)/);
    return m ? m[1] : "jpg";
  }
  function files(t, noPhotos) {
    var many = t.media.length > 1;
    var out = [];
    t.media.forEach(function (it, i) {
      if (noPhotos && it.kind === "photo") return;
      out.push({
        kind: it.kind, url: it.url, variants: it.variants,
        filename: (t.user || "unknown") + "_" + t.id + (many ? "_" + (i + 1) : "") + "." + extOf(it)
      });
    });
    return out;
  }
  function countKinds(list) {
    var v = 0, p = 0;
    list.forEach(function (t) { t.media.forEach(function (it) { if (it.kind === "photo") p++; else v++; }); });
    return { v: v, p: p };
  }
  function hasMediaDom(a) {
    return a.querySelector('video, [data-testid="tweetPhoto"], img[src*="pbs.twimg.com/media"]');
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
      "#xvd-pop{position:fixed;left:12px;bottom:84px;z-index:99999;padding:8px 14px;border-radius:9999px;border:1px solid #4da3ff;background:rgba(20,24,29,.94);color:#fff;font:700 13px system-ui,sans-serif;box-shadow:0 4px 14px rgba(0,0,0,.55);}" +
      "#xvd-pop:active{background:#4da3ff;}" +
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

  // ---- 지금 보고 있는 영상 트윗용 작은 팝업 ----
  function statusInfo(href) {
    var m = (href || "").match(/^\/([A-Za-z0-9_]+)\/status\/(\d+)/);
    return m ? { user: m[1], id: m[2] } : null;
  }
  function articleInfo(a) {
    var tm = a.querySelector('a[href*="/status/"] time');
    var link = tm && tm.closest("a");
    return link && statusInfo(link.getAttribute("href"));
  }
  function currentTweet() {
    var m = location.pathname.match(/^\/([A-Za-z0-9_]+)\/status\/(\d+)/);
    var arts = document.querySelectorAll('article[data-testid="tweet"]');
    var i, a, info;
    if (m) { // 게시물 상세: 주인공 트윗에 영상이 있으면
      for (i = 0; i < arts.length; i++) {
        a = arts[i]; info = articleInfo(a);
        if (info && info.id === m[2] && (hasMediaDom(a) || tweets[m[2]])) return { user: m[1], id: m[2] };
      }
      if (tweets[m[2]]) return { user: m[1], id: m[2] };
    }
    // 타임라인: 화면 가운데에 가장 가까운 영상 트윗
    var best = null, bestD = 1e9, vh = window.innerHeight;
    for (i = 0; i < arts.length; i++) {
      a = arts[i];
      var v = hasMediaDom(a);
      if (!v) continue;
      var r = v.getBoundingClientRect();
      if (r.bottom < 0 || r.top > vh || r.height < 40) continue;
      var d = Math.abs((r.top + r.bottom) / 2 - vh / 2);
      info = articleInfo(a);
      if (info && d < bestD) { best = info; bestD = d; }
    }
    return best;
  }

  var pop = null;
  function renderPop() {
    var cur = currentTweet();
    if (!cur) { if (pop) pop.style.display = "none"; return; }
    if (!document.body) return;
    if (!pop) {
      pop = document.createElement("button");
      pop.id = "xvd-pop";
      pop.addEventListener("click", function (e) {
        e.preventDefault(); e.stopPropagation();
        var c = pop._cur;
        if (!c) return;
        var tw = tweets[c.id];
        if (tw) send(files(tw));
        else if (window.XVDBridge) window.XVDBridge.downloadTweet(c.id, c.user); // 가로채기 실패 시 앱이 직접 찾아서 받음
      });
      document.body.appendChild(pop);
    }
    pop._cur = cur;
    pop.style.display = "";
    var tw = tweets[cur.id];
    var c = tw ? countKinds([tw]) : null;
    pop.textContent = "⬇ 받기" + (c ? " (" + [c.v ? "영상 " + c.v : "", c.p ? "사진 " + c.p : ""].filter(Boolean).join(" · ") + ")" : "");
  }
  var lastScroll = 0;
  window.addEventListener("scroll", function () {
    var n = Date.now();
    if (n - lastScroll > 250) { lastScroll = n; renderPop(); }
  }, { passive: true, capture: true });

  // ---- 프로필 일괄 다운로드 패널 ----
  var RESERVED = ["home", "explore", "notifications", "messages", "i", "search", "settings", "compose", "tos", "privacy"];
  function profileUser() {
    var m = location.pathname.match(/^\/([A-Za-z0-9_]+)(?:\/(?:media|with_replies|highlights))?\/?$/);
    return m && RESERVED.indexOf(m[1].toLowerCase()) < 0 ? m[1] : null;
  }
  var includeRt = true;
  var includePhotos = true;
  function userVideos(user) {
    var u = user.toLowerCase();
    return Object.keys(tweets).map(function (k) { return tweets[k]; })
      .filter(function (t) { return t.user.toLowerCase() === u || (includeRt && t.rtBy.indexOf(u) >= 0); });
  }

  var panel = null, scrolling = false;
  function renderPanel() {
    var user = profileUser();
    if (!user) { if (panel) { panel.remove(); panel = null; } return; }
    if (!document.body) return;
    if (!panel) {
      panel = document.createElement("div");
      panel.id = "xvd-panel";
      panel.innerHTML = '<div class="info"></div><button class="alt" data-act="rt"></button><button class="alt" data-act="ph"></button><button class="alt" data-act="scan"></button><button data-act="dl"></button>';
      document.body.appendChild(panel);
      panel.addEventListener("click", onPanelClick);
    }
    var list = userVideos(user);
    var c = countKinds(list);
    var n = c.v + (includePhotos ? c.p : 0);
    var rt = list.filter(function (t) { return t.user.toLowerCase() !== user.toLowerCase(); }).length;
    panel.querySelector(".info").textContent = "@" + user + "  영상 " + c.v + " · 사진 " + c.p + (rt ? "  (리포스트 게시물 " + rt + ")" : "");
    panel.querySelector('[data-act="rt"]').textContent = includeRt ? "리포스트 포함 ✓" : "리포스트 제외";
    panel.querySelector('[data-act="ph"]').textContent = includePhotos ? "사진 포함 ✓" : "사진 제외 (영상만)";
    panel.querySelector('[data-act="scan"]').textContent = scrolling ? "수집 중지" : "끝까지 스크롤하며 수집";
    var dl = panel.querySelector('[data-act="dl"]');
    dl.textContent = "전체 다운로드 (" + n + ")";
    dl.disabled = n === 0;
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  async function onPanelClick(e) {
    var act = e.target.dataset && e.target.dataset.act, user = profileUser();
    if (!act || !user) return;
    if (act === "rt") { includeRt = !includeRt; return renderPanel(); }
    if (act === "ph") { includePhotos = !includePhotos; return renderPanel(); }
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
      userVideos(user).forEach(function (t) { items = items.concat(files(t, !includePhotos)); });
      send(items);
    }
  }

  var timer;
  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(function () { addStyle(); decorate(); renderPanel(); renderPop(); }, 150);
  }
  function start() {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    schedule();
  }
  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start);
})();
