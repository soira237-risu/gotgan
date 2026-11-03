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

## 릴리스 서명

`vX.Y.Z` 태그를 푸시하면 [release.yml](.github/workflows/release.yml)이 R8로 축소한 release APK를 versionName `X.Y.Z`, versionCode `X*10000 + Y*100 + Z`(Y와 Z는 0-99, 첫 태그는 v1.0.1 이상)로 빌드하고, 서명한 뒤 SHA-256 파일과 함께 GitHub Release로 발행합니다. 아래 Secrets가 없으면 APK 빌드와 검사까지만 하고 러너의 디버그 키로 서명된 APK를 워크플로 아티팩트로 남기며 Release는 건너뜁니다.

| 저장소 Secret | 값 |
| --- | --- |
| `GOTGAN_KEYSTORE_BASE64` | keystore 파일을 base64로 인코딩한 값 |
| `GOTGAN_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `GOTGAN_KEY_ALIAS` | 키 별칭 |
| `GOTGAN_KEY_PASSWORD` | 키 비밀번호 |

새 키 만들기(Android는 다른 키로 서명된 앱을 업데이트할 수 없으므로, 기존 키로 설치된 앱은 한 번 재설치해야 합니다):

```sh
keytool -genkeypair -v -keystore gotgan-release.jks -storetype PKCS12 \
  -alias gotgan -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 gotgan-release.jks > gotgan-release.jks.b64   # macOS: base64 -i gotgan-release.jks
```

기존 설치를 그대로 업데이트하려면 그 앱을 서명한 키를 씁니다. 예를 들어 프로젝트 디버그 키는 `.android/debug.keystore`, 별칭 `androiddebugkey`, 두 비밀번호 모두 `android`입니다.

Secrets는 Settings > Secrets and variables > Actions에서 등록하거나 GitHub CLI로 등록합니다:

```sh
gh secret set GOTGAN_KEYSTORE_BASE64 < gotgan-release.jks.b64
gh secret set GOTGAN_KEYSTORE_PASSWORD
gh secret set GOTGAN_KEY_ALIAS --body gotgan
gh secret set GOTGAN_KEY_PASSWORD
```

그다음 `git tag v1.0.1 && git push origin v1.0.1`로 릴리스합니다. keystore와 비밀번호는 저장소 밖에 백업해 두세요. 키를 잃으면 사용자가 재설치해야 합니다. 릴리스마다 난독화된 스택 트레이스를 읽기 위한 R8 `mapping.txt`도 워크플로 아티팩트로 올라갑니다.

로컬에서 `./gradlew assembleRelease`는 프로젝트 루트의 추적되지 않는 `keystore.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`가 있거나 `GOTGAN_KEYSTORE_FILE`, `GOTGAN_KEYSTORE_PASSWORD`, `GOTGAN_KEY_ALIAS`, `GOTGAN_KEY_PASSWORD` 환경 변수가 있으면 같은 키로 서명하고, 없으면 디버그 키를 씁니다.

## 라이선스

[MIT](LICENSE)

앱 소스는 MIT입니다. 빌드된 APK에는 GPL-3.0인 [NextLib](https://github.com/anilbeesetti/nextlib) FFmpeg 디코더가 포함되므로, 배포되는 APK 전체는 GPL-3.0을 따릅니다.
