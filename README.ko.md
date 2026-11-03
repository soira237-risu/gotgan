<p align="center">
  <img src="docs/images/icon.png" width="88" alt="곳간 아이콘">
</p>

<h1 align="center">곳간</h1>

<p align="center">
  휴대폰 저장소와 WebDAV를 위한 Android 파일관리자.<br>
  <a href="README.md">English</a>
</p>

<p align="center">
  <a href="https://github.com/soira237-risu/gotgan/actions/workflows/android.yml"><img src="https://github.com/soira237-risu/gotgan/actions/workflows/android.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
</p>

<p align="center">
  <img src="docs/images/browse.png" width="24%" alt="탐색">
  <img src="docs/images/viewer.png" width="24%" alt="뷰어">
  <img src="docs/images/selection.png" width="24%" alt="선택">
  <img src="docs/images/dark.png" width="24%" alt="어두운 테마">
</p>

## 기능

- 휴대폰 저장소와 WebDAV(HTTPS)
- 검증하는 복사·이동, 작업 대기열
- 복원되는 휴지통
- 이미지·텍스트·PDF·ZIP/CBZ 뷰어
- 백그라운드 재생과 PiP, 캠코더 .mts/.m2ts와 FFmpeg 기반 AC-3·DTS 오디오
- 색상 테마 6종, 어둡게·OLED

## 빌드

JDK 17과 Android SDK 36이 필요합니다.

```sh
./gradlew assembleDebug
```

## 라이선스

[MIT](LICENSE)

앱 소스는 MIT입니다. 빌드된 APK에는 GPL-3.0인 [NextLib](https://github.com/anilbeesetti/nextlib) FFmpeg 디코더가 포함되므로, 배포되는 APK 전체는 GPL-3.0을 따릅니다.
