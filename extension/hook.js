// 페이지 컨텍스트(MAIN world)에서 X의 API 응답을 가로채 트윗의 mp4 주소를 뽑아낸다.
(() => {
  if (window.__xvdHooked) return;
  window.__xvdHooked = true;

  const API_RE = /\/i\/api\/|\/graphql\//;

  function screenName(node) {
    const u = node.core && node.core.user_results && node.core.user_results.result;
    return (u && ((u.core && u.core.screen_name) || (u.legacy && u.legacy.screen_name))) || "";
  }

  // 응답 JSON 어디에 있든 {rest_id, legacy.extended_entities.media} 모양의 트윗을 찾는다.
  function collect(node, out, seen) {
    if (!node || typeof node !== "object" || seen.has(node)) return;
    seen.add(node);
    if (Array.isArray(node)) {
      for (const n of node) collect(n, out, seen);
      return;
    }
    const media = node.legacy && node.legacy.extended_entities && node.legacy.extended_entities.media;
    if (node.rest_id && Array.isArray(media)) {
      const videos = [];
      for (const m of media) {
        if (!m.video_info || !Array.isArray(m.video_info.variants)) continue;
        const mp4 = m.video_info.variants
          .filter((v) => v.content_type === "video/mp4" && v.url)
          .sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
        if (mp4.length) videos.push({ url: mp4[0].url, gif: m.type === "animated_gif" });
      }
      if (videos.length) out.push({ id: node.rest_id, user: screenName(node), videos });
    }
    for (const k in node) collect(node[k], out, seen);
  }

  function handle(text) {
    try {
      const out = [];
      collect(JSON.parse(text), out, new WeakSet());
      if (out.length) window.postMessage({ __xvd: true, tweets: out }, "*");
    } catch (_) {}
  }

  const origFetch = window.fetch;
  window.fetch = async function (...args) {
    const res = await origFetch.apply(this, args);
    try {
      const url = typeof args[0] === "string" ? args[0] : (args[0] && args[0].url) || "";
      if (API_RE.test(url)) res.clone().text().then(handle).catch(() => {});
    } catch (_) {}
    return res;
  };

  const origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    if (API_RE.test(String(url))) {
      this.addEventListener("load", () => {
        try { handle(this.responseText); } catch (_) {}
      });
    }
    return origOpen.call(this, method, url, ...rest);
  };
})();
