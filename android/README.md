# X 다운로더 (Android)

다크 테마의 안드로이드 앱입니다.

- **링크로 받기** — 트윗 링크를 붙여넣거나, X 앱에서 *공유 → X 다운로더* 를 고르면 화질별 다운로드 버튼이 나옵니다. (로그인 불필요, 공개 트윗)
- **X 브라우저** — 앱 안에서 X에 로그인하면 영상 트윗마다 ↓ 버튼이 생기고, 계정 페이지에서는 *끝까지 스크롤하며 수집 → 전체 다운로드* 로 한 번에 받습니다.
- 파일은 `Download/X-Videos/` 에 저장됩니다.

## 설치
GitHub Actions 가 푸시할 때마다 APK 를 빌드합니다.
- 레포 **Releases → latest-debug** 에서 `app-debug.apk` 를 폰으로 받아 설치 (출처를 알 수 없는 앱 허용 필요)
- 또는 Actions 실행 결과의 Artifacts 에서 받기

## 직접 빌드
Android Studio 로 `android/` 폴더를 열거나 `cd android && ./gradlew assembleDebug`
