# 로드맵

기준 앱: VocaColle 7.48.0 (`jp.nicovideo.nicobox`, versionCode 187), Reseam 엔진 0.39.x.

Morphe 시절 로드맵([vocacolle-morphe-patches/docs/ROADMAP.md](https://github.com/ilikeadofai/vocacolle-morphe-patches/blob/main/docs/ROADMAP.md))의
목표와 원칙은 그대로 유지하되, 구현은 Reseam이 제공하는 기능에 맞춰 다시 설계했다.

## Morphe → Reseam 설계 변경

| 영역 | Morphe 구현 | Reseam 구현 | 이유 |
|---|---|---|---|
| 설정 화면 | 앱의 라이선스 Activity를 빌려 쓴 자체 `PreferenceFragment` (약 1,500줄) | Reseam 앱 런타임의 설정 화면 + 설정 툴바 메뉴 항목 | 테마·다크 모드·내보내기/가져오기를 런타임이 제공 |
| 정적 UI 번역 | `res/values-ko` 추가 + 런타임 표시 언어 전환 | 패치 시점에 리소스 테이블의 기본 문자열을 직접 교체 (`poolAdd` + `replaceEntry`) | Reseam 리소스 API는 언어 qualifier를 만들 수 없음. 대신 XML 레이아웃까지 빠짐없이 번역되고 런타임 비용이 없음 |
| 표시 언어 | 앱 안에서 시스템/일본어/영어/한국어 전환 | 패치 옵션 `language` (`ko`/`en`) | 위와 같음. 바꾸려면 다시 패치 |
| 하드코딩 문자열 | 메서드별 smali 삽입 | `bytecode.replaceAllStrings` + 대화상자 한정 point | 고유 일본어 리터럴은 앱 전체 치환이 가장 단순 |
| 광고/프리미엄 | 난독화 이름에 고정된 fingerprint 20여 개 | 문자열·enum 상수·소스 파일명·리소스 ID로 찾는 target + `gate` | 버전이 바뀌어도 구조가 같으면 그대로 동작 |
| 프리미엄 팝업 | 호출부 7곳을 각각 지정 | 팩토리 `PremiumMeritLeadDialog.newInstance` 호출부 전체를 한 번에 찾아 `show` 생략 | 새 호출부도 자동 포함 |
| 패치 버전 표시 | 앱 정보 화면 버전 문자열 수정 | Reseam 설정 화면 about 카드 | 런타임 기본 기능 |
| 호환성 프로브 | 별도 no-op 패치 | 삭제 | Reseam CLI가 같은 검증을 함 |
| 브랜딩 아이콘 | 원본 PNG 하나를 패치 시 5개 밀도로 리사이즈 + adaptive 생성 | 사용자가 준 PNG(단일 또는 밀도별)를 새 mipmap으로 추가하고 manifest가 가리키게 함 | Reseam 패치는 휴대폰(ART)에서도 실행되어 AWT 리사이즈를 쓸 수 없음 |

## 상태

| Release | 범위 | 상태 |
|---|---|---|
| v0.1 | 설정 호스트, 한국어·영어 UI (7.48.0 신규·변경 문자열 70개 번역 포함), 광고·프리미엄 팝업 제어 (기본 켜짐) | 7.48.0 XAPK에 전 패치 적용 확인. 기기 UI 확인 필요 |
| v0.2 | VocaDB 정확 일치 곡명 (플레이어) | 7.48.0 적용 확인. 7.48.0 플레이어가 Compose로 바뀌어 View 갱신 대신 `VideoInfo` 생성 시점에 표시 제목을 교체 (캐시 우선, 미스는 백그라운드 조회 후 다음 갱신부터 표시). 기기 확인 필요 |
| v0.3 | YouTube 원본 링크 공유 | 7.48.0 적용 확인. 작품 공유 텍스트(`SnsMediaShareText`)의 니코니코 링크만 VocaDB YouTube Original로 교체 |
| v0.4 | 곡 제목 번역 (Google 번역 기본, OpenAI 호환 BYOK 선택) | 7.48.0 적용 확인. Waydroid에서 패치 앱 실행·한국어 시작 화면 확인. 작품 상세 설명 번역은 다음 단계 |
| v0.5 | 공지 제목·본문 번역 | 예정 |
| v1.0 | LRCLIB 외부 가사 fallback | 예정 |
| v1.1 | 태그 번역 (검색은 일본어 원문 유지) | 예정 |
| v1.2 | 가사 발음·번역 다층 표시 | 예정 |
| v2.x | 로컬 리스트·즐겨찾기, guest·offline·trend 실험 | 예정 |
| 연구 | 고음질·프리미엄 영상 (서버 entitlement가 이미 허용한 경우만) | 연구 |

## 알려진 제약

- 7.48.0부터 로그인/게스트 시작에 Firebase App Check(Play Integrity) 토큰이 필요하다. 재서명한 APK는 토큰을 받지 못해 WL-E08로 실패한다. 루트 + CorePatch로 원래 서명을 유지해 설치하거나, Reseam mount 빌드로 원본 설치본 위에 마운트해야 한다.

## 기기 확인 체크리스트 (v0.1)

- [ ] 설정 툴바 메뉴에 "Reseam 패치"가 보이고 Reseam 설정 화면이 열린다.
- [ ] 홈·검색·라이브러리·플레이어·설정·프리미엄 화면이 한국어/영어로 보인다.
- [ ] 랭킹 장르 탭과 푸시 알림 설정의 주제 이름이 번역된다.
- [ ] 각 광고 스위치를 켜고 끌 때 앱 시작 광고, 배너/피드 광고, 곡 사이 음성 광고, 프리미엄 팝업이 따라 바뀐다.
- [ ] 음성 광고 건너뛰기 상태에서 다음 곡 버튼·진행 막대가 정상이고, 미니 플레이어가 멈추지 않는다
      (Morphe 버전에서 조기 반환 방식이 플레이어 상태를 고정시킨 적이 있음).
- [ ] 스위치를 모두 끈 상태가 원본 앱과 같다.
- [ ] (v0.2) VocaDB 스위치를 켠 뒤 곡을 넘기면 VocaDB에 등록된 곡의 제목이 영어/로마자로 바뀌고, 끄면 원래 제목으로 돌아온다.

## 원칙 (Morphe 시절과 동일)

- Target이 정확히 하나를 찾지 못하면 패치가 실패한다. 알 수 없는 문자열·API 응답은 원문 그대로 둔다.
- 화면 표시 값과 서버 요청 값을 분리한다. 번역은 표시 전용이다.
- 외부 네트워크 전송은 기능별 opt-in이며, 전송 내용은 설정 화면에 명시한다.
- 서버가 강제하는 프리미엄 권한이나 수량 제한은 클라이언트에서 위조하지 않는다.
