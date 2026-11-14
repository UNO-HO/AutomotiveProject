# 편안길 (Pleos Connect 앱)

이 앱은 뒷좌석 아이의 멀미를 미리 막아 주는 Pleos Connect 앱입니다. 앞으로 갈 길(내비 회전 안내)과 지금의 흔들림(속도·회전)을 함께 보고, 다음 세 가지를 합니다.

1. **경로 선행 예보와 휴식 제안:** 앞으로 30km 길을 2km 단위로 나눠 편안 / 보통 / 위험으로 보여 줍니다. 위험 구간에 들어서기 전에 쉬어 가자고 제안합니다.
2. **운전 계기판·코칭·편안함 리포트:** 최근 15초 운전을 부드러워요/거칠어요/과격해요로 보여 주고, 급가감속이나 급회전이 반복되면 짧은 음성으로 알려 줍니다. 주행이 끝나면 편안함 점수와 가장 흔들렸던 순간 3곳을 보여 줍니다.
3. **아이 오디오 큐:** 위험 구간 직전, 뒷좌석 아이에게 차량 스피커로 창밖(먼 곳)을 보도록 말을 겁니다.

## 실행 방법

1. Android Studio(JDK 17)에서 이 폴더를 엽니다. Gradle 동기화를 하면 Pleos 공개 Maven 저장소에서 SDK를 받습니다.
2. [Pleos Connect Emulator 설정 가이드](https://pleos.ai/playground/resources/en/docs/guide/getting-started/application-development/setup-connect-sdk-emulator)대로 AVD를 만듭니다. Pleos Playground의 My Project에서 받은 CRN도 에뮬레이터에 넣습니다.
3. 앱을 실행하고 차량 속도 권한을 허용합니다.
4. 위쪽에서 모드를 고른 뒤 **출발**을 누릅니다.
   - **시연 모드:** 59분짜리 가상 주행(서울 → 강원도 산길)을 15배 빠르게, 약 4분 동안 재생합니다. 발표와 데모 영상은 이 모드로 하면 됩니다.
   - **실차 연동:** 속도·기어는 AAOS Car API에서, 앞길은 Pleos NaviHelper에서 받습니다. Pleos 내비에서 목적지를 정하면 경로 예보가 켜집니다.

> 시연 모드가 따로 있는 이유: Pleos 에뮬레이터에서는 내비 위치가 움직이지 않습니다. Virtual Console로 속도는 바꿀 수 있지만, 경로를 따라 실제로 달리는 상황은 재현할 수 없습니다.

## 시연 흐름 (시연 모드, 약 4분)

| 가상 시각 | 구간 | 화면과 음성 |
| --- | --- | --- |
| 0~6분 | 도심 | 가다 서기로 지수가 천천히 올라가고, 예보 막대 끝에 빨간 구간이 보입니다 |
| 6분 | 고속도로 진입 | 급가속을 코칭합니다: "뒷자리가 많이 흔들려요. 조금만 부드럽게요." |
| 13분 | 고속도로 | **휴식 제안 카드**: "약 9분 뒤 구불구불한 길이 14분쯤 이어져요." |
| 16~24분 | 정체 | 가다 서기가 이어져 지수가 오릅니다 |
| 29분 | 휴게소 정차 | **"아이 상태 어때요?"** 탭 카드가 뜨고, 쉬는 동안 지수가 회복됩니다 |
| 38분 | 산길 직전 | 아이에게 오디오 큐를, 운전자에게 회생제동 제안을 합니다 |
| 39~57분 | 산길 | 지수가 올라 "멀미 주의"로 바뀌고, 계기판이 거칠어요/과격해요로 바뀌며, 커브 코칭이 나옵니다 |
| 59분 | 도착 | **편안함 리포트**(점수, 운전 느낌 비율, 지수 변화, 오늘의 여정)가 나옵니다 |

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (API 34)
- 화면 규격: [에뮬레이터 해상도 기입]
- Android Studio, JDK 17, Android Gradle Plugin 8.7.3, Kotlin 2.0.21
- Pleos SDK: NaviHelper 3.0.5, TextToSpeech 3.3.1

## APK

- `apk/` 폴더: [release APK 업로드 후 파일명 기입]

## 구조

```
app/src/main/java/com/pyeonangil/
  engine/   순수 Kotlin. 안드로이드 없이 단위 테스트 가능
    RideEngine.kt       중심 로직: 지수·예보·이벤트
    MotionFilters.kt    속도→가속도, 대역통과 필터, 멀미 누적량(MSDV 개념)
    RouteForecaster.kt  TBT 회전 밀도로 앞길 예보
    DriverCoach.kt      급이벤트·코칭·가족 민감도·점수
    DemoTrip.kt         시연용 가상 주행 (결정적, 난수 없음)
    Tuning.kt           모든 설계값 한곳에
  data/
    PleosDriveSource.kt  실차: Car API + 자이로 + NaviHelper
    DemoDriveSource.kt   시연: DemoTrip 재생
    ProfileStore.kt      가족 민감도 저장
  voice/Speaker.kt       Pleos TTS(ON_DEVICE), 실패 시 안드로이드 TTS
  ui/                    Compose 화면, 뷰모델
app/src/test/            엔진 단위 테스트 16개
```

## 사용 SDK와 API

| 용도 | API |
| --- | --- |
| 속도, 기어 | AAOS `CarPropertyManager` (`PERF_VEHICLE_SPEED`, `GEAR_SELECTION`). Pleos의 "표준 우선" 정책을 따릅니다 |
| 회전율(횡가속도) | 헤드유닛 자이로를 중력 방향으로 투영해, 장착 각도와 상관없이 구합니다 |
| 앞길 예보, 남은 거리 | Pleos NaviHelper 3.0.5: `getTBTInfo`, `getDestinationInfo`, `onNavigationStatus` |
| 휴게소 찾기 | NaviHelper `requestNavigationScreen(SearchPlace("휴게소"))` |
| 음성 안내 | Pleos TextToSpeech 3.3.1 (ON_DEVICE) |

NaviHelper는 경로 좌표열(polyline)을 주지 않습니다. 그래서 커브 정도는 **회전 안내 지점의 밀도**로 추정합니다.

## 실차·에뮬레이터에서 꼭 확인할 것

1. **TBT 거리 단위와 기준:** `TbtMapper`(PleosDriveSource.kt 맨 아래)는 거리를 "현재 위치부터 미터"로 가정합니다. 길안내를 켠 뒤 logcat 태그 `PyeonanPleos`의 `onTBTInfo` 값을 보고, 다르면 `distanceToMeters`와 `distanceIsCumulative`만 바꾸면 됩니다.
2. **설계값:** `Tuning.kt`의 임계값은 ISO 2631-1 멀미 누적량 개념에서 출발한 초기값입니다. 실차 주행 기록으로 다시 맞춰야 합니다.
3. **회생제동:** 지금은 운전자에게 음성으로 제안만 합니다. 회생제동 단계를 직접 바꾸는 API는 확인하지 못했습니다.

## 테스트

```
./gradlew test
```

엔진 테스트는 다음을 확인합니다: 정속 주행에서는 지수가 낮음, 산길에서는 지수가 누적, 정차하면 회복, 민감도 반영, 휴식 제안·아이 큐·코칭은 한 번씩만, 정차 질문, 모션 큐 방향, 시연 주행 전체 흐름.
