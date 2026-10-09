// 격리된 world: 수집된 영상 목록을 들고, 트윗마다 버튼 + 프로필 페이지 일괄 다운로드 패널을 단다.
(() => {
  const tweets = new Map(); // id -> {id, user, videos:[{url, gif}]}
  const ICON =
    '<svg viewBox="0 0 24 24"><path d="M12 16l-5-5 1.4-1.4 2.6 2.6V3h2v9.2l2.6-2.6L17 11l-5 5zm-7 2h14v2H5v-2z"/></svg>';

  window.addEventListener("message", (e) => {
    if (e.source !== window || !e.data || !e.data.__xvd) return;
    for (const t of e.data.tweets) {
      const old = tweets.get(t.id);
      if (old) t.rtBy = [...new Set([...old.rtBy, ...t.rtBy])];
      tweets.set(t.id, t);
    }
    scheduleRefresh();
  });

  const files = (t) =>
    t.videos.map((v, i) => ({
      url: v.url,
      filename: `${t.user || "unknown"}_${t.id}${t.videos.length > 1 ? "_" + (i + 1) : ""}.mp4`,
    }));

  const send = (items) =>
    new Promise((res) => chrome.runtime.sendMessage({ type: "download", items }, (r) => res(r || { ok: 0, total: items.length })));

  // ---- 트윗별 다운로드 버튼 ----
  function tweetIdOf(article) {
    const a = article.querySelector('a[href*="/status/"] time');
    const link = a && a.closest("a");
    const m = link && link.getAttribute("href").match(/\/status\/(\d+)/);
    return m ? m[1] : null;
  }

  function decorate() {
    document.querySelectorAll('article[data-testid="tweet"]').forEach((article) => {
      const id = tweetIdOf(article);
      const t = id && tweets.get(id);
      const bar = article.querySelector('div[role="group"]');
      if (!t || !bar || bar.querySelector(".xvd-btn")) return;
      const btn = document.createElement("button");
      btn.className = "xvd-btn";
      btn.title = `영상 다운로드 (${t.videos.length}개)`;
      btn.innerHTML = ICON;
      btn.addEventListener("click", async (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        const r = await send(files(t));
        btn.title = r.ok ? "다운로드 시작됨" : "다운로드 실패";
      });
      bar.appendChild(btn);
    });
  }

  // ---- 프로필 페이지 일괄 다운로드 패널 ----
  const RESERVED = new Set(["home", "explore", "notifications", "messages", "i", "search", "settings", "compose", "tos", "privacy"]);
  function profileUser() {
    const m = location.pathname.match(/^\/([A-Za-z0-9_]+)(?:\/(?:media|with_replies|highlights))?\/?$/);
    return m && !RESERVED.has(m[1].toLowerCase()) ? m[1] : null;
  }

  let panel, scrolling = false;
  let includeRt = true; // 그 계정이 리포스트한 영상도 포함
  function userVideos(user) {
    const u = user.toLowerCase();
    return [...tweets.values()].filter((t) => t.user.toLowerCase() === u || (includeRt && t.rtBy.includes(u)));
  }

  function renderPanel() {
    const user = profileUser();
    if (!user) { if (panel) { panel.remove(); panel = null; } return; }
    if (!panel) {
      panel = document.createElement("div");
      panel.id = "xvd-panel";
      panel.innerHTML = '<div class="info"></div><button class="alt" data-act="rt"></button><button class="alt" data-act="scan"></button><button data-act="dl"></button>';
      document.body.appendChild(panel);
      panel.addEventListener("click", onPanelClick);
    }
    const list = userVideos(user);
    const n = list.reduce((s, t) => s + t.videos.length, 0);
    const rt = list.filter((t) => t.user.toLowerCase() !== user.toLowerCase()).reduce((s, t) => s + t.videos.length, 0);
    panel.querySelector(".info").textContent = `@${user} 영상 ${n}개` + (rt ? ` (리포스트 ${rt})` : "");
    panel.querySelector('[data-act="rt"]').textContent = includeRt ? "리포스트 포함 ✓" : "리포스트 제외";
    const scan = panel.querySelector('[data-act="scan"]');
    scan.textContent = scrolling ? "수집 중지" : "끝까지 스크롤하며 수집";
    const dl = panel.querySelector('[data-act="dl"]');
    dl.textContent = `전체 다운로드 (${n})`;
    dl.disabled = n === 0;
  }

  async function onPanelClick(e) {
    const act = e.target.dataset && e.target.dataset.act;
    const user = profileUser();
    if (!act || !user) return;
    if (act === "rt") { includeRt = !includeRt; return renderPanel(); }
    if (act === "scan") {
      if (scrolling) { scrolling = false; return renderPanel(); }
      scrolling = true;
      renderPanel();
      let lastCount = -1, same = 0;
      while (scrolling && same < 6) {
        window.scrollTo(0, document.body.scrollHeight);
        await new Promise((r) => setTimeout(r, 1200));
        const c = tweets.size;
        same = c === lastCount ? same + 1 : 0;
        lastCount = c;
      }
      scrolling = false;
      renderPanel();
    } else if (act === "dl") {
      const items = userVideos(user).flatMap(files);
      e.target.disabled = true;
      const r = await send(items);
      e.target.textContent = `${r.ok}/${r.total}개 다운로드 시작됨`;
      setTimeout(renderPanel, 3000);
    }
  }

  let timer;
  function scheduleRefresh() {
    clearTimeout(timer);
    timer = setTimeout(() => { decorate(); renderPanel(); }, 150);
  }

  new MutationObserver(scheduleRefresh).observe(document.documentElement, { childList: true, subtree: true });
  scheduleRefresh();
})();
