# 러닝 코치 (Running Coach)

삼성 헬스 달리기 기록을 **Health Connect**로 자동 수집해, **Claude AI**가 러닝 코치처럼 분석하고 다음 훈련을 처방해주는 안드로이드 앱.

## 동작 원리

```
삼성 헬스(달리기) ──기록──▶ Health Connect ──읽기──▶ 러닝 코치 앱 ──▶ Claude AI 코칭
```

## 핵심 기능

1. **자동 감지 알림** — 3시간마다 새 달리기를 확인, 발견하면 "오늘 달리기 하셨네요! 코칭 받아볼까요?" 알림 (WorkManager)
2. **기록 분석** — 거리 / 시간 / 평균 페이스 / 평균·최고 심박수 / 1km 구간별 페이스
3. **AI 코칭 (친구 말투)**
   - 오늘 기록 평가
   - 다음 훈련 처방 (인터벌 / 템포런 / 롱런)
   - 구간별 목표 페이스
   - 심박수 목표 구간
   - 준비운동 루틴 + 보조 근력운동
4. **다음날 비교** — 직전 처방을 저장해두고, 새 기록이 들어오면 "지난 처방대로 했어?" 비교 평가
5. **레벨 시스템**
   - Lv.1: 5km 완주
   - Lv.2: 5km 36분 이내
   - Lv.3: 10km 50분 이내 (목표)

## 폰 준비 (갤럭시 / 안드로이드 14+)

1. **삼성 헬스 → Health Connect 연동 켜기**
   - 삼성 헬스 → 설정 → `Health Connect` → 운동·심박·거리 공유 켜기
2. **APK 설치** — PC에서 빌드한 `app-debug.apk`를 폰으로 옮겨 설치 ("출처를 알 수 없는 앱" 허용)
3. 앱에서 **"Health Connect 연결하기"** → 권한 모두 허용 + 알림 권한 허용
4. (선택) **AI 코칭**용 Claude API 키 입력 — https://console.anthropic.com 에서 발급

## 빌드 (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Android\sdk"
C:\Android\gradle\gradle-8.9\bin\gradle.bat assembleDebug
# 결과: app\build\outputs\apk\debug\app-debug.apk
```

## 기술 스택

- Kotlin + Jetpack Compose (Material 3)
- AndroidX Health Connect (`connect-client`) — ExerciseSession / Speed / HeartRate / Distance
- WorkManager (백그라운드 자동 감지)
- Claude API (`claude-sonnet-4-6`)
- minSdk 28 / targetSdk 34

## 주요 파일

| 파일 | 역할 |
|------|------|
| `RunRepository.kt` | 달리기 세션·구간 페이스·심박 읽기 |
| `RunModels.kt` | 데이터 모델 + 레벨 계산 + 페이스/시간 포맷 |
| `RunningCoach.kt` | Claude 러닝 코치 프롬프트·호출 |
| `RunCheckWorker.kt` | 새 달리기 자동 감지 + 알림 |
| `CoachStore.kt` | API 키·직전 처방 저장 (비교용) |
| `MainActivity.kt` | Compose UI |
