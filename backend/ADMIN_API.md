# 관리자 백엔드 API

로컬 소스에 추가된 운영 API의 계약이다. 프론트엔드 연결과 운영 배포는 포함하지 않는다. 모든 요청은 기존과 동일한 Firebase ID 토큰을 `Authorization: Bearer <token>`으로 보낸다. 토큰·DB 비밀번호를 코드나 문서에 저장하지 않는다.

## 권한

- 기존 환경변수 `GAMESTOCK_ADMIN_GOOGLE_UID` / `GAMESTOCK_ADMIN_GOOGLE_EMAIL`로 지정한 `ADMIN`은 최고 관리자다. 이 API로 최고 관리자를 만들 수 없다.
- 최고 관리자는 일반 회원에게 `READ`, `USERS`, `MARKET`, `CONTENT`, `REWARDS`, `SEASONS`를 위임할 수 있다. 어떤 운영 권한이든 있으면 조회 권한을 포함한다. `READ`만 주면 변경 작업은 할 수 없다.
- 위임 권한은 회원의 `role`을 바꾸지 않는다. 현재 프론트엔드는 위임 권한 메뉴를 표시하지 않으며 API에서만 사용할 수 있다.
- 권한 위임·회수, 전체 보상, 체결 취소, 시즌 초기화는 최고 관리자만 실행한다. 권한은 요청마다 DB에서 검사한다.
- 기존 `/api/admin/users`, 거래 제한·해제, 닉네임 변경도 각각 `READ` / `USERS` 권한으로 사용할 수 있다. 일반 회원은 403, 인증되지 않은 요청은 401이다.

## 공통 규칙

- 신규 변경 API는 `reason`(필수, 최대 300자)을 받는다. 기존 계정 관리 API의 요청 형식은 유지한다.
- 목록의 `limit`은 1~100(기본 50), `before`는 마지막으로 받은 ID보다 작은 행을 조회하는 커서(기본 0)다. 별도 표기가 없는 목록은 ID 내림차순이다. 시즌 목록은 최근 100개다.
- 보상·현금 조정의 `requestKey`는 영문·숫자와 `:`, `_`, `-`를 사용하는 1~80자 문자열이다. 같은 키로 같은 회원·금액·사유를 재요청하면 `applied:false`로 응답하고 재지급하지 않는다. 다른 내용으로 키를 재사용하면 400이다. 키를 대소문자만 바꾸어 재사용하지 않는다.
- 모든 자산·시장 변경은 기존 `market_locks`의 독점 잠금을 사용한다. 주문·봇의 공유 잠금과 직렬화되며, 변경한 데이터와 매칭 캐시 revision, 감사 기록은 같은 DB 트랜잭션에 포함된다.
- `/api/admin/audit`는 신규 감사 기록과 기존 계정 조치의 변경 전후 값을 반환한다. 이전 버전에서 저장한 기록은 `/api/admin/audit/legacy`로 조회한다. 개인정보가 포함되므로 운영 권한이 필요하다.
- 숫자·상태·길이·업무 조건 오류는 400이다. 변경 API에서 빈 사유를 허용하지 않는다. JSON 시각은 UTC ISO 8601이다.

## 조회·시장·자산

경로는 `/api/admin` 기준이다.

| 메서드·경로 | 권한 | 입력·동작 |
|---|---|---|
| GET `/dashboard` | READ | 일반 회원 수, 이용 제한 수, 미체결 수, 최근 24시간 거래 수·수량·금액, 미처리 신고, 런타임, 매칭 소유 상태, 내부 metrics |
| GET `/users?q=...` | READ | 기존 회원 검색. 최근/검색 결과 최대 50명 |
| GET `/users/{id}` | READ | 계정 정보, 대출 잔액·최근 원장 100개, 포트폴리오, 보유 주식 |
| DELETE `/users/{id}?confirm=닉네임` | USERS | 회원 완전 삭제(204). `confirm`이 현재 닉네임과 같아야 함. 관리자·본인 계정 거부, 정산 대기 체결이 있으면 거부. 미체결·자동 주문을 취소한 뒤 회원을 참조하는 모든 행(상대 회원과의 체결·정산 포함)을 외래 키 순서로 지운다. `admin_actions`·`admin_audit`에 `USER_DELETE`로 남음 |
| GET `/orders` | READ | `userId`, `stockCode`, `status`, `before`, `limit`. 상태 OPEN/FILLED/PARTIAL/CANCELLED |
| GET `/trades` | READ | `userId`, `stockCode`, `before`, `limit`. 체결과 정산 상태 |
| GET `/settlements` | READ | `userId`, `status`, `before`, `limit`. 상태 PENDING/SETTLED/CANCELLED |
| POST `/orders/{id}/cancel` | MARKET | `{reason}`. 미체결 잔량 취소, 예약 현금 반환·주식 예약 해제 |
| POST `/settlements/{id}/settle` | MARKET | `{reason}`. 구버전 PENDING 정산을 한 번만 완료 |
| POST `/settlements/{id}/reverse` | 최고 관리자 | `{reason}`. 아래 조건을 충족하는 정산 거래 취소 |
| GET `/stocks` | READ | 종목·개발사·장르·가격·운영 상태 |
| POST `/stocks` | MARKET | `{code,name,developer,genre,initialPrice,reason}`. 종목과 가격·보호·캐시 기반 데이터 생성 |
| PUT `/stocks/{code}` | MARKET | `{name,developer,genre,reason}`. 가격·종목 코드는 변경하지 않음. 재시작 후에도 편집 정보 보존 |
| PUT `/stocks/{code}/state` | MARKET | `{state,reason}`. ACTIVE/HALTED/DELISTED. 정지·폐지 시 해당 종목 미체결 전부 취소 |
| PUT `/runtime` | MARKET | `{botsPaused,tradersPaused,marketPaused,cadenceMs,targetPerSymbol,traderRampSeconds,reason}`. 전체 설정 교체 |
| POST `/users/{id}/cash-adjustments` | REWARDS | `{requestKey,amount,reason}`. 0 제외 ±10억원. 가용 현금을 넘는 차감 거부 |
| POST `/users/{id}/mission-rewards` | REWARDS | `{missionId,rewardedOn:"2026-10-18",amount,reason}`. 오늘 또는 과거의 누락 미션 보상 복구. 금액1~100만원. 회원·미션·날짜 수령 기록이 있으면 재지급하지 않음 |
| POST `/rewards/all` | 최고 관리자 | 동일 형식. 양수만, 일반 회원 최대 1만명. 일괄 키는 최대 48자. 완료된 키 재실행은 400 |
| PUT `/users/{id}/holdings` | REWARDS | `{requestKey,stockCode,quantity,averagePrice,reason}`. 잔고를 절대값으로 복구. 수량 0~100만, 평균 단가 0~10억원(수량 양수이면 단가 양수) |
| GET `/permissions` | 최고 관리자 | 위임된 권한 목록 |
| PUT `/users/{id}/permissions` | 최고 관리자 | `{permissions:["READ","CONTENT"],reason}`. 전체 교체. 빈 배열로 회수 |
| GET `/audit` | READ | `actorId`, `action`, `before`, `limit` |
| GET `/audit/legacy` | READ | `userId`, `before`, `limit` |
| GET `/suspicious-trades` | READ | `hours` 1~168(기본24), `minimum` 2~10000(기본10). 회원 쌍별 반복 체결·자전 거래 후보 최대100개 |

`cadenceMs`는 50~10000ms, `targetPerSymbol`은 1~1000이다. `null`은 기존 서버 설정으로 복귀한다. 시장 정지는 신규 주문과 체결을 막고 주문 취소는 허용한다. 봇 정지는 두 단계다.

- `tradersPaused`: 트레이더 봇만 멈추고 트레이더 미체결 호가를 철회한다. 유동성 봇은 계속 호가를 내므로 회원은 그 호가와 거래할 수 있다.
- `botsPaused`: 유동성 봇을 포함한 모든 봇을 멈추고 봇 호가를 전부 철회한다. 호가창이 비어 회원끼리만 체결된다. 체결 취소·시즌 초기화는 이 상태와 시장 정지가 모두 필요하다.

`traderRampSeconds`(0~3600, `null`·0은 즉시)는 트레이더 봇이 다시 켜질 때의 재가동 시간이다. 트레이더가 정지 상태에서 운영 상태로 바뀌는 순간부터 거래 참여 비율이 5%에서 시작해 이 시간 동안 100%까지 선형으로 늘어난다. 운영 중에 값만 바꾸면 램프를 다시 시작하지 않는다. 응답의 `traderShare`는 현재 참여 비율(정지 시 0)이다. 기존 만료 처리·정산·보호장치 유지 작업은 계속된다.

```json
{
  "botsPaused": true,
  "tradersPaused": false,
  "marketPaused": true,
  "cadenceMs": null,
  "targetPerSymbol": null,
  "traderRampSeconds": 300,
  "reason": "장애 조사 및 복구"
}
```

종목 코드는 `[A-Z][A-Z0-9_]{0,11}`이며 이전 버전 마이그레이션용 `NEXA`, `STAR`, `MOMO`, `VOID`는 예약되어 있다. 초기 가격은 100~10억원이고 호가 단위에 맞아야 한다. 종목은 DB에서 삭제하지 않고 DELISTED로 보존한다. 재개는 ACTIVE로 변경한다. 신규 종목은 즉시 사람 주문을 받을 수 있지만, 기존 봇의 종목별 작업·배치 배분은 시작 시 구성하므로 **신규 종목까지 봇을 배정하려면 매칭 서버 재시작이 필요하다.** 이번 작업에서는 서버를 재시작하지 않는다.

### 체결 취소·복구 정책

- 체결 취소 전에 `botsPaused`와 `marketPaused`를 모두 true로 설정해야 한다.
- 자동 취소는 체결 전 스냅샷이 남아 있는 일반 회원 간 거래만 지원한다. 봇 거래·자전 거래·스냅샷 누락 거래는 조사 후 개별 자산 복구로 처리한다.
- 해당 종목에서 두 회원의 후속 체결, 미체결 주문, 체결 이후 계정 초기화가 있으면 거부한다. 체결 이후 관리자 보유량 복구가 기록된 경우에도 보수적으로 거부한다.
- 현재 수량·결제 수량이 체결 직후 예상값과 일치해야 한다. 판매자 현금이 이미 사용됐다면 자동 취소하지 않는다.
- 취소 시 양쪽의 현금, 수량·평균 단가·실현손익, 양쪽 수수료를 되돌리고 정산을 CANCELLED로 표시한다. 재요청은 거부한다. 주문·체결 원본은 조사 목적으로 보존한다.
- 과거에 관측된 시세·차트와 누적 시장 거래량은 소급해서 지우지 않는다. CANCELLED 정산은 기존 공개 체결 목록과 지표 재계산에서 제외된다. 이미 획득한 칭호도 자동 회수하지 않는다.
- 절대 보유량 복구는 해당 회원·종목에 미체결 주문과 PENDING 정산이 없어야 한다. 체결 원장·상대방 자산을 수정하는 기능과 구분한다. 같은 복구 키 재사용은 400이다.

### 보상과 수익률

현금 보상·차감은 별도 원장에 남고 전체 랭킹 수익률과 주간 수익률·칭호의 투자 성과 계산에서 제외된다. 포트폴리오 응답에 `adminAdjustmentCash`가 추가된다. **계좌 손익을 직접 계산하는 프론트엔드는 이 값을 외부 자금으로 차감하도록 후속 연결해야 한다.** 기존 필드는 유지한다.

미션 누락 복구는 일반 현금 조정과 달리 기존 `mission_rewards`에 수령 기록을 생성한다. 같은 날짜의 일반 미션 수령 경로에서도 추가 지급되지 않는다. 수익률에서는 기존 미션 보상으로 한 번만 제외한다. 이미 수령 기록이 있는데 잔액만 손상된 경우에는 조사 후 별도 현금 조정을 사용한다.

## 공지·뉴스·이벤트·미션

| 메서드·경로 | 권한 | 입력·동작 |
|---|---|---|
| GET `/api/admin/content` | READ | `kind` NOTICE/NEWS/EVENT, `before`, `limit`. 초안·예약·종료 포함 |
| POST `/api/admin/content` | CONTENT | 아래 콘텐츠 JSON으로 생성 |
| PUT `/api/admin/content/{id}` | CONTENT | 같은 형식으로 전체 편집·발행·비공개 전환 |
| DELETE `/api/admin/content/{id}` | CONTENT | `{reason}`. 원본은 감사 기록에 보존 |
| GET `/api/content` | 공개 | 같은 목록 조건. 발행 중이며 현재 공개 기간인 콘텐츠만 반환 |
| GET `/api/admin/missions` | READ | 기존 미션 ID와 활성화·보상·관심종목 조건 |
| PUT `/api/admin/missions/{id}` | CONTENT | `{enabled,rewardCash,minimumWatchlist,reason}`. 보상 0~100만원, 관심종목 0~100개 |

```json
{
  "kind": "NOTICE",
  "title": "점검 안내",
  "body": "점검 중에도 주문 취소는 가능합니다.",
  "stockCode": null,
  "published": true,
  "startsAt": null,
  "endsAt": null,
  "reason": "운영 공지 게시",
  "imageUrl": "/notice/maintenance.png",
  "actionLabel": "자세히 보기",
  "actionUrl": "/news",
  "dismissLabel": "닫기"
}
```

`imageUrl`, `actionLabel`, `actionUrl`, `dismissLabel`은 선택 항목이며 NOTICE 공지 창의 머리 그림과 두 버튼에 쓰인다. 주소는 `/`로 시작하는 사이트 경로나 `https://` 주소만 받고, 이동 버튼은 문구와 주소를 함께 적어야 한다. 이동 버튼 문구는 40자, 닫기 버튼 문구는 40자, 주소는 500자까지 받는다.

제목 150자, 본문 2만자. 끝 시각은 시작 시각 이후여야 한다. NEWS는 운영자가 작성한 콘텐츠이며 기존 외부 뉴스 수집·가격 영향 엔진과 별도로 `/api/content`에서 제공한다. 기존 하드코딩 공지 팝업과 뉴스 화면은 이번 작업에서 변경하지 않았다. EVENT의 현금 보상은 별도 보상 API로 실행한다.

미션은 현재 클라이언트가 이해하는 기존 ID만 수정한다. 현재 날짜의 일일 추첨은 유지하며 중지된 미션은 목록·수령에서 제외한다. 임의의 신규 미션 ID나 새 행동 추적을 만들지는 않는다. 관심종목 조건은 서버에서 검증하며, 페이지 열람형 미션의 기존 검증 한계는 유지한다. 설정된 보상액은 실제 지급 경로에 적용되지만 기존 클라이언트의 고정 보상 표시는 후속 연결이 필요하다.

## 프로모션 코드

| 요청 | 권한 | 내용 |
|---|---|---|
| GET `/api/admin/promos` | READ | `{codes,titles}`. 코드마다 보상·제한·`used_count`·`last_used_at`, 그리고 보상으로 고를 수 있는 칭호(주간 칭호 제외) |
| GET `/api/admin/promos/{code}/redemptions` | READ | 최근 사용 50건(회원·닉네임·받은 보상·시각) |
| POST `/api/admin/promos` | REWARDS | `{code,rewardCash,titleIds,maxUses,startsAt,endsAt,active,memo,reason}` |
| PUT `/api/admin/promos/{code}` | REWARDS | 같은 본문. 코드 문자열은 바꿀 수 없다 |

코드는 소문자로 저장하고 영문·숫자·`_`·`-` 2~40자다. 현금 보상은 0~1억원, 칭호는 0개 이상이며 둘 중 하나는 있어야 한다. `maxUses`가 `null`이면 인원 제한이 없다. 한 계정은 코드 하나를 한 번만 쓴다. 사용자는 POST `/api/titles/promo` `{code}`로 쓰고, 응답은 칭호 목록에 `rewardCash`가 더해진다. 현금 보상은 `admin_cash_adjustments`에 `promo:<code>:<userId>`로 남아 랭킹 수익률에서 빠진다. 끄기는 `active:false`로 하고 삭제는 없다.

## 신고·커뮤니티

| 메서드·경로 | 권한 | 입력·동작 |
|---|---|---|
| POST `/api/reports` | 로그인 회원 | `{targetUserId,detail}`. 내용 1000자. 본인 신고 금지, 하루10건, 동일 대상 OPEN 신고 중복 거부 |
| GET `/api/admin/reports` | READ | `status` OPEN/RESOLVED/DISMISSED, `before`, `limit` |
| PUT `/api/admin/reports/{id}` | USERS | `{status,resolution,reason}`. OPEN 건을 RESOLVED/DISMISSED로 처리 |
| DELETE `/api/admin/comments/{id}` | USERS | `{reason}`. 댓글과 변경 전 기록 저장 |

반복 체결 후보는 저장된 인간 계정 간 체결을 집계한다. 동일인·다계정의 확정 판정이 아니고 자동 제재하지 않는다. IP·기기 추적이나 새 개인정보 수집은 추가하지 않았다. compact 봇 전용 체결은 관리자 체결 목록·24시간 DB 집계에 모두 포함되지 않으므로, 시장 전체 처리량은 metrics·기존 봇 원장과 함께 해석해야 한다. metrics의 소켓 수는 해당 프로세스의 연결 수이고 누적 카운터는 서버 재시작 시 초기화된다.

## 시즌

기존 주간 칭호 스케줄과 별도로 명시적인 시즌을 운영한다.

| 메서드·경로 | 권한 | 입력·동작 |
|---|---|---|
| GET `/api/admin/seasons` | READ | 최근100개 시즌 |
| POST `/api/admin/seasons` | SEASONS | `{name,reason}`. DRAFT 생성 |
| POST `/api/admin/seasons/{id}/start` | SEASONS | `{reason}`. DRAFT→ACTIVE, 시작 자산 스냅샷. 진행 시즌은 하나만 허용 |
| POST `/api/admin/seasons/{id}/close` | SEASONS | `{rankRewards:[100000,50000,10000],reason}`. ACTIVE→CLOSED, 최종 자산·순위 고정 및 보상 원자 지급 |
| GET `/api/admin/seasons/{id}/ranking?limit=50` | READ | 확정 순위·시작/종료 자산·보상. 최대100행 |
| POST `/api/admin/seasons/{id}/reset` | 최고 관리자 | `{reason}`. CLOSED→RESET. 아래 조건으로 일반 회원 자산 초기화 |

순위는 `종료 순자산−시작 순자산−시즌 중 관리자 현금 조정액`의 **순증가 금액** 내림차순이다. 동률은 회원 ID 오름차순. 순자산은 현금+예약금+미정산 매도대금+보유주식 현재가 평가액−대출이다. 수익률 퍼센트 기반인 기존 주간 칭호 랭킹과 다른 명시적 정책이다. 시작 후 가입한 회원은 종료 시 기본 자금100만원을 기준으로 참가한다. 시즌을 두 번 종료해 보상을 중복 지급할 수 없다.

초기화는 다음 조건을 모두 검사한다.

1. 시즌이 CLOSED이며 진행 중인 다른 시즌이 없다.
2. 시장·봇이 모두 일시 정지돼 있고 PENDING 정산이 없다.
3. 일반 회원이 최대1만명이다. 봇·데모·최고 관리자 계정은 제외한다.

대상 회원의 변경 전 현금·대출·보유량·미체결 주문·출석/미션 보상은 감사 기록으로 보존한다. 이후 미체결 주문 취소, 보유량 제거, 현금100만원, 대출0원, 출석·미션 보상 초기화를 수행한다. 관리자 자금 조정 기준점도 갱신한다. 닉네임·로그인·신고·원본 체결·시즌 확정 기록·칭호는 보존하며 기존 개인용 1회 리셋 사용권도 소비하지 않는다. 초기화 후 자동으로 거래를 재개하지 않는다.

## 마이그레이션·검증

새 테이블은 매칭 소유 서버의 기존 `MarketSchema` 시작 마이그레이션에 포함된다. 최초 적용은 매칭 서버부터 시작해야 하며 읽기 전용 API 인스턴스는 기존 정책대로 DDL을 실행하지 않는다. 관리 기능의 기본값은 기존 동작 유지(거래·봇 정상, 설정 덮어쓰기 없음)다.

일반 테스트:

```powershell
cd backend
mvn test
```

새 통합 테스트는 `ADMIN_TEST_JDBC`가 `jdbc:mysql://localhost:<port>/...` 또는 `127.0.0.1`일 때만 실행된다. `ADMIN_TEST_USER`, `ADMIN_TEST_PASSWORD`를 환경변수로 공급한다. **테스트는 지정 DB의 기존 테이블을 수정하지 않고 `admin_test_<UUID>` 전용 스키마를 생성·삭제한다.** 계정에는 임시 스키마 생성 권한이 필요하다. 비밀번호를 명령줄 인자로 넣지 않는다.

```powershell
# 위 환경변수는 로컬 테스트용 자격증명으로 미리 설정
mvn '-Dtest=AdminAccessTest,AdminOperationsIntegrationTest' test
```

검증 대상: 인증·세부 권한·권한 회수, 실제 HTTP 라우팅, 중복/동시 보상, 감사 실패 롤백, 가용 현금 차감 제한, 주문 예약금 반환, 종목 정지/재개 매칭, 체결 취소·수수료 복구, 공개 기간, 신고 중복·처리, 실제 미션 지급, 시즌 상태 전이·보상·초기화, 봇 호가 철회, 전체 보상·보유량 복구, 보상 수익률 중립, 신규 종목 주문·메타데이터 보존.

운영 배포·실제 Firebase 관리자 로그인·프론트엔드 연결은 이 테스트로 증명하지 않는다.
