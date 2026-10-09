chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type !== "download") return;
  let ok = 0;
  Promise.all(
    msg.items.map(
      (it) =>
        new Promise((resolve) =>
          chrome.downloads.download(
            { url: it.url, filename: "X-Videos/" + it.filename, conflictAction: "uniquify" },
            (id) => {
              if (id !== undefined) ok++;
              resolve();
            }
          )
        )
    )
  ).then(() => sendResponse({ ok, total: msg.items.length }));
  return true; // 비동기 응답
});
