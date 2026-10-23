# AtoN AIS — 복구 프로젝트 (2026-10-23)

Codespace 자동 삭제로 손실된 프로젝트를 복원한 **완전 빌드 가능 버전**입니다.

## 복원 구성
| 구성요소 | 출처 | 상태 |
|---|---|---|
| 프로젝트 뼈대 (Gradle·매니페스트·res) | 7/28 저장소 zip | ✅ |
| 네이티브 C/C++ **AIS-catcher 복조기 + 브리지** + `librtlsdr.so` ×4 ABI | **v14 zip (8월 완결본)** | ✅ |
| `index.html` (UI·디코딩·설정엔진 CFG_ENG·매뉴얼) | 오늘 최신본 | ✅ 최신 |
| `AISDataStore.kt` / `AISHttpServer.kt` / `MainActivity.kt` / `UsbSerialNmea.kt` | 보관 중이던 최신본 | ✅ 최신 |
| `NmeaTcpServer.kt` | 재작성(표준 TCP 브로드캐스트) | ✅ |
| `RtlSdrDriver.kt` | v14 네이티브와 세트 + `tunerName()` 추가 | ✅ |
| 샘플레이트 288000 + 신호세기(PWR/PPM) 태깅 | 대화기록 패치 적용 | ✅ |
| 런처 아이콘(적응형, XML) · `usb-serial` 의존성 · CI 워크플로 | 신규 | ✅ |

## 정상 동작 (빌드 후)
- RTL-SDR(AIS-catcher 고감도 복조) / USB NMEA / 설정포트 수신, 지도·목록·NMEA·상세정보
- **실시간 RF 신호세기 모니터(PWR/SNR·채널 A/B)** — AIS-catcher 브리지가 NMEA에 `,PWR:x,PPM:y` 부착
- 수신주기 점검(`/periods`), 설정 송수신(읽기 Query·쓰기·Read-back), TCP 출력(10110)
- 물리/가상 장비 설정 빌더·자기검증, 새 사용법 매뉴얼

## 네이티브 빌드 관련 유의 (NDK)
- AIS-catcher 네이티브는 당시(8월) 호스트 컴파일만 검증됐고 **NDK 크로스컴파일은 원래부터 미검증**이었습니다. 첫 Actions 빌드에서 NDK 특유의 컴파일 오류가 1~2회 날 수 있습니다 → 그 로그를 보내주시면 바로 고칩니다(대부분 헤더/플래그 문제).
- AIS-catcher 라이브러리는 **대화 당시(2026-08-11) 커밋으로 고정**해 받아 API 어긋남을 방지했습니다.

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
