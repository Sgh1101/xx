# X 영상 다운로더

X(트위터) 트윗 링크를 붙여넣으면 영상/GIF를 mp4로 다운로드해주는 웹앱입니다. (Flask + yt-dlp)

## 실행

```bash
pip install -r requirements.txt
python app.py          # http://localhost:5000
```

- `ffmpeg`가 설치되어 있으면 가장 안정적입니다.
- 동작이 갑자기 안 되면 X 쪽 변경 때문일 수 있으니 `pip install -U yt-dlp` 로 업데이트하세요.
- 본인이 볼 권리가 있는 콘텐츠를 개인 용도로만 사용하세요.
