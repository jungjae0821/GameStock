# 씹덕주식

게임을 종목으로 상장시켜 모의 자금으로 거래하는 서비스다. 시세·뉴스·주문 데이터는
Spring Boot와 MySQL이 관리하고, 로그인은 Firebase Google 인증을 사용한다.
백엔드가 꺼진 개발 환경에서는 화면 확인을 위해 브라우저 모의 시세로 자동 대체된다.

## 실행

```bash
npm install
npm run dev        # http://localhost:5180
npm run typecheck
npm run build      # dist/ 생성
npm start          # dist/ 를 서빙하는 무의존성 Node 서버 (PORT)
```

### 로컬 백엔드 연결

1. MySQL을 실행하고 `database/schema.sql`을 적용한다.
2. 루트 `.env`에 DB와 Firebase Admin 설정을 둔다. 서비스 계정 JSON은
   `backend/secrets/firebase-admin.json`에 보관하며 Git에 커밋하지 않는다.
3. `scripts/run-backend.ps1`을 실행한다. Spring Boot API는 `http://localhost:8081`에서 열린다.
4. `.env.local`의 `VITE_API_BASE_URL=http://localhost:8081`을 확인하고 `npm run dev`를 실행한다.

웹 Firebase 설정은 `.env.local`의 `VITE_FIREBASE_*` 변수로 읽는다. 배포 시에는 같은 변수를
호스팅 서비스의 빌드 환경변수로 등록하고, 백엔드에는 `FIREBASE_SERVICE_ACCOUNT_JSON`,
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GAMESTOCK_CORS_ORIGIN`을 별도 시크릿으로 등록한다.

### 배포

- **Railway**: `railway.json`이 빌드(`npm ci && npm run build`)와 시작(`npm start`), 헬스체크(`/healthz`)를 정의한다.
  `server/index.js`가 `dist/`를 정적 서빙하고 SPA 폴백을 처리한다.
- **정적 호스팅(ChatGPT Sites 등)**: `npm run build` 결과인 `dist/`를 그대로 올린다. 클라이언트 라우팅은
  `history.pushState` 기반이므로 서버에 SPA 폴백 설정이 필요하다.

## 화면

| 경로 | 내용 |
| --- | --- |
| `/` | 자산 현황 · 전광판 · 거래량 상위 시세표 · 속보 (보유 종목이 있으면 내 보유 표 추가) |
| `/market`, `/market/:code` | 전체 15종목. 정렬 가능한 시세표 + 필터(전체/상승/하락/관심) + 검색, 종목 상세(차트·주문·체결) |
| `/news` | 종목별 레일 + 날짜로 묶인 속보 피드. 각 소식은 발행 시점 대비 시세 반영률을 함께 보여 준다 |

## 구조

```
src/
  market/          시세 도메인과 서버 연동
    universe.ts    상장 종목, 호가 단위, 가격제한폭
    engine.ts      백엔드 연결 실패 시 사용하는 로컬 대체 엔진
    newsTemplates.ts  종목별 소재를 쓰는 소식 문구
    selectors.ts   자산 합계, 보유 종목, 정렬, 시세 반영률
    MarketProvider.tsx  Spring API 조회, Firebase 토큰 첨부, 주문 API
  lib/
    firebase.ts    Firebase Web SDK 초기화
    api.ts         Spring Boot 공통 API 호출기
  components/      표현 컴포넌트 (시세표, 전광판, 주문, 차트, 피드)
  pages/           화면 조합
  router.ts        pushState 기반 3개 경로 라우터 (의존성 없음)
  styles/          tokens → base → chrome/board/market/detail/news (CSS 레이어)
server/index.js    배포용 정적 서버
```

프론트는 2초마다 `/api/stocks`와 `/api/market-events`를 조회한다. 로그인 상태에서는 Firebase ID
토큰을 `Authorization: Bearer ...`로 첨부해 포트폴리오·주문·관심 종목 API를 호출한다.

## 설계 방향 계약

- **대상/장면**: 애니·게임을 종목처럼 사고파는 사람이 시세를 훑고 주문한다. 표면 모드는 Operate(작업 완수)이며,
  Persuade(설득)가 아니다. 그래서 히어로·카피·CTA를 두지 않는다.
- **시각 명제**: 증권사 로비의 **전광판과 인쇄된 시세표**. 흰 종이 위 1px 괘선이 구조를 만들고,
  색은 세 갈래뿐이다 — 상승 적색, 하락 청색, 전광판 암버 LED.
- **타입**: Pretendard(로컬 번들) 하나로 UI를 구성하고, 숫자는 tabular numerals를 쓴다.
  전광판 숫자만 모노스페이스로 LED 판독을 흉내 낸다.
- **시그니처**: 전광판 셀이 값이 바뀔 때만 번쩍이고, 번쩍임의 색이 상승/하락 방향을 알린다.
  자동 스크롤·반복 루프·펄스 점은 쓰지 않는다.
- **모션**: 피드백(틱 플래시, 주문 결과)만. 섹션 등장 애니메이션 없음.
  `prefers-reduced-motion`에서는 플래시를 아예 만들지 않고 값만 갱신한다.
- **안티고백**: ① 네온 크립토 대시보드 ② 히어로+피처 카드+통계 스트립 랜딩 ③ 벤토+라운드 카드+알약 필터 대시보드.
- **폐기한 것**: 마케팅 히어로, 영문 eyebrow(GAME STOCK EXCHANGE·MARKET EVENTS·DISCOVER), 카드 중첩,
  장식용 반투명 도형, 알약 필터, 중복된 "실시간" 문구.

## 검증 기록

- `npm run typecheck`, `npm run build` 통과. 빌드 산출물은 `dist/`.
- 정적 휴리스틱 감사(`frontend-apex` 스킬의 `audit_frontend.py`) 결과 0건.
  `outline: none` 2건과 9~10px 각인 배지는 코드에 사유를 남기고 예외 처리했다.
- 렌더 확인: 1440 / 900 / 390px에서 가로 오버플로 없음. 1080px 미만에서 2단→1단 전환,
  종목 상세가 목록 위로 이동, 전광판 열 수 축소, 터치 조작 34px 이상 확보.
- 공개 API 조회와 백엔드 연결 실패 시 대체 화면을 확인했다.
- 남은 작업: 실제 Google 로그인 후 주문·프로필 API의 브라우저 통합 테스트, Railway 배포 환경 점검.
