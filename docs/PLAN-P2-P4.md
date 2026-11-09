# P2–P4 구체화 계획

기준: VocaColle 7.48.0, 2026-11-09. Morphe 시절부터 계획한 P2(로컬 라이브러리), P3(실험), P4(entitlement 연구)를
정적 분석으로 확인한 앱 구조에 맞춰 구체 기능으로 나눈다. 원칙은 그대로다: 서버가 강제하는 제한·권한은
위조하지 않고, 가능한 경우 기기 전용 대체 기능을 준다. 외부 전송은 기능별로 명시한다.

## 조사 결과

| 항목 | 앱 구조 (7.48.0) | 판단 |
|---|---|---|
| 좋아요 일일 제한 | `DefaultNvLikeService`의 `POST /v1/users/me/likes/items`가 `NvApiException(DAILY_LIMIT_EXCEEDED)`을 던지고, `LikeErrorResolver`가 경고를 띄움 | 서버 제한. 실패한 곡을 기기 즐겨찾기에 저장 |
| 마이리스트 등록 제한 | `DefaultNvMylistService`의 `POST /v1/users/me/mylists/{id}/items`가 `REACHED_LIMIT_BY_MYLIST`/`REACHED_LIMIT_BY_USER` 등을 던짐 | 서버 제한. 같은 이름의 기기 재생목록에 저장 |
| 마이리스트 팔로우 제한 | `DefautlNvFollowingMylistService`의 `POST /v1/users/me/following/mylists/{id}` | 서버 제한. 기기의 "저장한 마이리스트"에 저장 |
| 사용자 팔로우 제한 | `DefaultFollowUserService`의 `followees/niconico-users/{id}`가 `FollowAddApiException(LIMIT_EXCEEDED)` | 서버 제한. 기기의 "크리에이터 워치리스트"에 저장 |
| 크리에이터 페이지 | 딥링크 `nicobox://user?uid=`(네이티브 사용자 페이지), 업로드 목록 `/v1/playlist/user-uploaded/{id}` | 워치리스트에서 네이티브 페이지를 열고, 최신 업로드 피드를 공개 nvapi로 구성 |
| 마이리스트 상세 | 딥링크 `nicobox://mylistDetail?mylistId=` | 저장한 마이리스트를 네이티브 화면으로 열기 |
| 게스트 로그인 | `GuestLoginErrorTypeResolver`가 원인 예외를 `GuestLoginErrorType`으로 분류 (Firebase/App Check 포함). 재서명 APK는 App Check 토큰을 못 받음 | 우회 불가. 진단: 서명 일치 여부, Play 서비스, 시계 오차, 마지막 오류 분류 |
| 오프라인 캐시 | 캐시는 로그인만 필요(프리미엄 아님). `MediaCacheItem(mediaId, …)`이 캐시 항목. 공식 가사 캐시는 마지막 1곡뿐 | 캐시한 곡의 외부 가사를 기한 없이 고정 저장하고 발음·번역도 미리 받아 둠 |
| 트렌드 목록 | 랭킹 3페이지(v0.9.0에서 완료). 다른 목록은 앱이 이미 스크롤 페이지네이션 | 추가 작업 없음 |
| 고음질 | 서버 `isAvailable`/`audioUpperLimit`로 결정 (v0.11 조사) | 해제 없음. 진단에 "서버가 이 곡에 고음질을 제공했는지"만 표시 |

## 구현 순서

### P2-A 이름 레지스트리
표시용 이름을 기기 메모리에 모은다: 플레이어 `VideoInfo`·`MediaDetailData`의 (곡 ID, 제목),
`MediaDetailData`의 (소유자 ID, 소유자 이름), `NvMylist`·`NvCompactMylist`의 (마이리스트 ID, 이름).
제한 대체 저장 때 ID만 알 때도 이름을 붙이기 위함.

### P2-B 크리에이터 워치리스트
- 작품 상세의 `+ 재생목록` 선택 대화상자에 "크리에이터 워치: 이름" 항목 (사용자 업로드만, 채널 제외).
- 라이브러리에 "크리에이터 (N)": 선택 시 네이티브 사용자 페이지 열기·삭제.
- "워치한 크리에이터의 최신곡": 크리에이터별 최신 업로드 10곡을 공개 nvapi에서 받아 등록일 순으로
  합쳐 앱 재생목록으로 재생. 크리에이터 ID가 nicovideo.jp에 전송됨(같은 서비스).

### P2-C 저장한 마이리스트
라이브러리에 "저장한 마이리스트 (N)": 네이티브 마이리스트 상세 열기·삭제.

### P2-D 제한 도달 시 기기에 저장 (스위치, 기본 켜짐)
네 요청 메서드 진입 시 대상 ID를 스레드별로 기억하고, `catch`의 `throw` 직전에 예외의 오류 코드를 읽어
제한 코드일 때만 기기 목록에 저장하고 토스트로 알린다. 앱의 원래 오류 대화상자는 그대로 뜬다
(서버 성공으로 위장하지 않음).

### P3-A 로그인 진단 (Reseam 설정 action)
설치된 앱 서명 SHA-256과 원본 VocaColle 서명(키 순환 2개) 비교, Google Play 서비스 버전, 기기 시계와
서버 `Date` 헤더 차이, 마지막 게스트/로그인 오류 분류, 마지막 곡의 서버 고음질 제공 여부.
결과는 복사 가능.

### P3-B 캐시한 곡의 가사 고정
`MediaCacheItem` 생성 시(캐시 추가·목록 로드) 외부 가사가 켜져 있으면 그 곡을 조회해 고정 저장
(만료 없음, "캐시 삭제"로만 지움). 가사 발음·번역 스위치가 켜져 있으면 그 줄들도 미리 받아 둠.

### P4
v0.11 조사로 종료. 진단에 정보 표시만 추가(P3-A).

## 릴리스
- v0.15.0: P2-A~D — 완료
- v0.16.0: P3-A, P3-B, P4 진단 — 완료

검증: 7.48.0 XAPK에 전 패치 적용(11/11), 패치된 DEX를 디컴파일해 각 훅 위치 확인. 기기 실행 확인은 아직.
조사 중 이 개발 환경의 DNS가 nicovideo.jp를 127.0.0.1로 돌려 실시간 API 응답 확인은 못 했다. 크리에이터 최신곡 피드는
nvapi `/v3/users/{id}/videos`의 `data.items[].essential`과 앱의 `/v1/playlist/user-uploaded` 형식(`watchId`, `content`) 둘 다 읽는다.
