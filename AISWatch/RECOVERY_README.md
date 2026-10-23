# AtoN AIS — 복구 프로젝트 (2026-10-23)

Codespace 자동 삭제로 손실된 프로젝트를 복원한 **완전 빌드 가능 버전**입니다.

## 복원 구성
| 구성요소 | 출처 | 상태 |
|---|---|---|
| 프로젝트 뼈대 (Gradle·매니페스트·res) | 7/28 저장소 zip | ✅ |
| 네이티브 C/C++ (rtl-ais) + `librtlsdr.so` ×4 ABI | 7/28 저장소 zip | ✅ |
| `index.html` (UI·디코딩·설정엔진 CFG_ENG·매뉴얼) | 오늘 최신본 196KB | ✅ 최신 |
| `AISDataStore.kt` / `AISHttpServer.kt` / `MainActivity.kt` / `UsbSerialNmea.kt` | 보관 중이던 최신본 | ✅ 최신 |
| `NmeaTcpServer.kt` | 재작성(표준 TCP 브로드캐스트) | ✅ |
| `RtlSdrDriver.kt` | 새 호출부에 맞춰 보강 | ✅ |
| 런처 아이콘(적응형, XML) | 신규 | ✅ |
| `usb-serial-for-android` 의존성 + JitPack | 추가 | ✅ |

## 정상 동작 (빌드 후)
- RTL-SDR / USB NMEA / 설정포트 수신, 지도·목록·NMEA·상세정보
- 수신주기 점검(`/periods`), 설정 송수신(읽기 Query·쓰기·Read-back), TCP 출력(10110)
- 물리/가상 장비 설정 빌더·자기검증, 새 사용법 매뉴얼

## 아직 비어 있는 부분 (7/28 이후 Codespace에서만 존재 → 손실)
- **RTL-SDR 신호세기(PWR/SNR)·게인 제어·튜너명 네이티브 조회** — AIS-catcher 브리지(`aiswatch_bridge.cpp`)가 7/28 이후 추가된 것이라, 현재는 7/28의 rtl-ais 네이티브로 복원됨.
  - 영향: RTL-SDR 경로에서 **실시간 RF 모니터의 신호세기 막대**가 채워지지 않음(수신·디코딩 자체는 정상).
  - USB NMEA/설정포트 경로는 **영향 없음**(설정 읽기·쓰기 포함 전부 정상).
  - 필요 시 AIS-catcher 브리지를 다시 올려 확장 가능.

## 빌드 방법 — GitHub Actions 자동 빌드
이 복구본에는 `.github/workflows/build-apk.yml` 이 포함되어 있습니다(압축 해제 시 저장소 루트에 생성).
`AISWatch/` 소스를 **main 브랜치에 push하면 APK가 자동으로 빌드**됩니다.

- 빌드 결과(APK)는 저장소 **Actions 탭 → 해당 실행 → Artifacts → `AtoN-AIS-debug-apk`** 에서 내려받습니다.
- 수동 실행: Actions 탭 → `Build APK` → `Run workflow`.
- 로컬(Android Studio)에서도 `AISWatch/` 를 열어 바로 빌드할 수 있습니다.

## ⚠️ 재발 방지 — 반드시 git 커밋·푸시
이번 사고 원인은 **소스가 git에 없고 Codespace 안에만 있었던 것**입니다.
```bash
cd AISWatch
git init
git add .
git commit -m "Restore AtoN AIS project (full source)"
git branch -M main
git remote add origin https://github.com/cjkscout202-bot/aton-ais-monitor.git
git push -u origin main        # 기존 zip 저장소에 소스 추가(또는 새 저장소)
```
이후 작업 끝낼 때마다 `git add . && git commit && git push` 습관화.
`.gitignore`는 포함돼 있고, **`index.html`·`.so`·네이티브 소스는 커밋 대상**입니다.
