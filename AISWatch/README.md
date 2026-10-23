# AtoN AIS 모니터링 — Android 단일 APK

RTL-SDR + Android 스마트폰으로 AIS 신호를 수신하는 단일 앱.
AIS-catcher, Termux, Python 불필요 — APK 하나로 완결.

## ✅ librtlsdr 이미 포함 — 추가 빌드 불필요!

`jniLibs/` 폴더에 Android용 librtlsdr.so가 이미 포함되어 있습니다.
(출처: github.com/Mr-Precise/rtl-sdr, GPL v2)

---

## 빌드 방법 (3단계)

### 1단계: Android Studio 설치
- https://developer.android.com/studio 다운로드 및 설치
- 최초 실행 시 SDK 자동 다운로드 (약 1~2시간)
- SDK Manager → SDK Tools → **NDK (Side by side)** ✓ 체크 후 설치

### 2단계: 프로젝트 열기
```
Android Studio → File → Open → AISWatch 폴더 선택
```
Gradle 동기화 자동 실행 (처음 5~10분 소요)

### 3단계: APK 빌드
```
메뉴: Build → Build Bundle(s) / APK(s) → Build APK(s)
```
완료 후: `app/build/outputs/apk/debug/app-debug.apk`

### 스마트폰에 설치
APK 파일을 스마트폰으로 복사 후 파일 관리자에서 터치하여 설치
(설정 → 보안 → 알 수 없는 앱 설치 허용 필요)

---

## 사용 방법

1. RTL-SDR 동글을 USB-C OTG 케이블로 스마트폰에 연결
2. AIS Watch 앱 실행
3. USB 권한 허용 팝업 → **확인**
4. AIS 수신 자동 시작 → 웹앱 화면 표시
5. Chrome에서도 접속 가능: `http://localhost:8100`

---

## 프로젝트 구조

```
AISWatch/
├── app/src/main/
│   ├── java/com/aiswatch/
│   │   ├── MainActivity.kt      USB 권한 + WebView
│   │   ├── RtlSdrDriver.kt      RTL-SDR USB 제어 + JNI
│   │   ├── AISDataStore.kt      AIS 파싱 (MSG21/6/8)
│   │   └── AISHttpServer.kt     내장 HTTP 서버
│   ├── cpp/
│   │   ├── CMakeLists.txt       NDK 빌드 설정
│   │   ├── rtlsdr_android.c     JNI 브릿지 (C)
│   │   ├── include/rtl-sdr.h   RTL-SDR 헤더
│   │   └── rtl-ais/            rtl-ais DSP 소스 (C, 9파일)
│   ├── jniLibs/                 ← librtlsdr.so (포함됨!)
│   │   ├── arm64-v8a/
│   │   ├── armeabi-v7a/
│   │   ├── x86/
│   │   └── x86_64/
│   └── assets/index.html        AIS Watch 웹앱
```

---

## 라이선스
- rtl-ais: GPL v2
- librtlsdr: GPL v2 (Mr-Precise/rtl-sdr)
- NanoHTTPD: BSD
- AIS Watch: 자체 개발
