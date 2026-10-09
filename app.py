"""X(트위터) 링크를 넣으면 영상/GIF를 다운로드해주는 간단한 웹앱."""
import os
import re
import tempfile
import shutil

from flask import Flask, jsonify, render_template, request, send_file, after_this_request
import yt_dlp

app = Flask(__name__)

# x.com / twitter.com 의 트윗 링크만 허용 (임의 URL 요청 방지)
TWEET_RE = re.compile(
    r"^https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/[A-Za-z0-9_]+/status/\d+"
)


def valid_url(url: str) -> bool:
    return bool(url) and bool(TWEET_RE.match(url.strip()))


def human_size(n):
    if not n:
        return ""
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


@app.get("/")
def index():
    return render_template("index.html")


@app.post("/api/info")
def info():
    url = (request.get_json(silent=True) or {}).get("url", "").strip()
    if not valid_url(url):
        return jsonify(error="올바른 X(트위터) 트윗 링크가 아니에요."), 400
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "noplaylist": True}) as ydl:
            data = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as e:
        msg = str(e)
        if "No video could be found" in msg or "no video" in msg.lower():
            msg = "이 트윗에는 영상이 없어요."
        return jsonify(error=f"가져오기 실패: {msg}"), 422

    # 트윗에 영상이 여러 개면 entries 로 옴
    entries = data.get("entries") or [data]
    items = []
    for idx, e in enumerate(entries, 1):
        formats = []
        for f in e.get("formats", []):
            if f.get("vcodec") in (None, "none") or f.get("ext") != "mp4":
                continue
            formats.append(
                {
                    "format_id": f["format_id"],
                    "height": f.get("height"),
                    "label": f"{f.get('height') or '?'}p",
                    "size": human_size(f.get("filesize") or f.get("filesize_approx")),
                }
            )
        formats.sort(key=lambda x: x["height"] or 0, reverse=True)
        items.append(
            {
                "index": idx,
                "title": e.get("title") or "video",
                "thumbnail": e.get("thumbnail"),
                "duration": e.get("duration"),
                "uploader": e.get("uploader"),
                "formats": formats,
            }
        )
    return jsonify(items=items)


@app.get("/api/download")
def download():
    url = request.args.get("url", "").strip()
    fmt = request.args.get("format", "best")
    index = request.args.get("index", "1")
    if not valid_url(url):
        return "잘못된 링크", 400
    if not re.fullmatch(r"[A-Za-z0-9_\-+/\[\]=<>,.:*]+", fmt) or not index.isdigit():
        return "잘못된 파라미터", 400

    tmpdir = tempfile.mkdtemp(prefix="xdl_")

    @after_this_request
    def cleanup(resp):
        shutil.rmtree(tmpdir, ignore_errors=True)
        return resp

    opts = {
        "quiet": True,
        "noplaylist": True,
        "format": fmt,
        "playlist_items": index,
        "outtmpl": os.path.join(tmpdir, "%(uploader)s_%(id)s.%(ext)s"),
        "merge_output_format": "mp4",
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.extract_info(url, download=True)
    except yt_dlp.utils.DownloadError as e:
        return f"다운로드 실패: {e}", 422

    files = os.listdir(tmpdir)
    if not files:
        return "다운로드된 파일이 없어요.", 500
    path = os.path.join(tmpdir, files[0])
    return send_file(path, as_attachment=True, download_name=files[0])


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
