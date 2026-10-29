# 종토방 · BlockQuant · snScript 백엔드 명세 v1

프론트 작업 시작점. 기존 웹/모바일 화면은 변경하지 않았다. 추가 의존성 없이 기존 Spring/JDBC/MySQL/주문 큐를 사용한다.

기계 판독용 계약: [OpenAPI 3.1 JSON](research-api.openapi.json). 타입 생성/요청 클라이언트 생성 도구에 넣을 수 있다. 문법·실행 의미는 이 문서를 함께 따른다.

## 공통 계약

- Base URL: 기존 백엔드의 `/api`. JSON 요청은 `Content-Type: application/json`.
- 인증: 기존 Firebase 로그인과 동일한 `Authorization: Bearer <ID token>`. OpenAPI key 인증과는 별도다.
- 공개: 종토방/채팅 조회, 차트 분석, capabilities. 나머지는 로그인 필요. 정지 계정의 변경 요청은 기존 인증 계층에서 차단한다. StockTalk 조회는 정지 계정도 허용한다.
- 사용자 ID와 역할은 인증 결과로 결정한다. 요청에 `userId`, `admin`을 넣지 않는다. 프로젝트는 소유자만 조회·변경할 수 있다.
- 시간은 UTC ISO 8601 문자열. 가격/금액은 원, 주문 수량은 정수 주. ID는 DB BIGINT이지만 현재 JSON number로 반환한다.
- `/quant`, `/community`, `/chart-analysis` POST/PUT/PATCH 본문은 최대 128 KiB. DTO에 정의되지 않은 필드, 소수로 전달한 정수, primitive 필드의 명시적 null은 400.
- 400: 입력 오류, 401: 인증 없음, 403: 권한/정지, 404: 없는 종목·게시물·본인 소유가 아닌 프로젝트, 409: revision/실행 상태 충돌, 413: 본문 크기, 429: 작성/동시 계산 한도.
- 입력 검증 오류의 `message`를 표시한다. 인증/바인딩 오류는 기존 Spring 오류 응답도 가능하므로 HTTP 상태에 따른 기본 문구를 준비한다.
- 모든 글·제목·닉네임·소스는 **일반 텍스트**다. HTML로 렌더링하지 않는다.

## 종목별 종토방 — origin StockTalk 사용

`origin/main`의 `ed3dd56`(PR #27) 구현과 보관 테스트 수정 `bcf8fe8`을 가져왔다. 종토방은 `StockTalkController` / `StockTalkService`를 그대로 사용한다. 별도로 작성했던 `/community/posts` API는 제거했다. 기존 `/stocks/{code}/comments`는 별도 기존 API로 유지된다.

| Method | Path (`/api` 생략) | 요청 | 응답 |
|---|---|---|---|
| GET | `/stocks/{code}/talk` | `before=0`, 인증 선택 | TalkPage |
| POST | `/stocks/{code}/talk` | `{body, parentId?: number 또는 null}` | 200 TalkPost |
| DELETE | `/talk/{id}` | 인증 필수 | 200 `{id}`, 본인만 삭제 |
| POST | `/talk/{id}/like` | 인증 필수, 본문 없음 | 200 `{id, likes, liked}`, 좋아요 토글 |

```ts
type TalkPost = {
  id: number; nickname: string; body: string; createdAt: string;
  likes: number; liked: boolean; mine: boolean;
  priceAtPost: number | null; replies: TalkPost[];
};
type TalkPage = {
  posts: TalkPost[]; hasMore: boolean; maxLength: number; retentionDays: number;
};
```

- 제목 없는 본문형 종토방이다. 본문 최대 300 Unicode code points, 계정별 글·답글 작성 간격 20초. 링크·주소·금칙 표현은 원격 구현의 검증을 그대로 따른다.
- 최신 원글 20개씩 ID 내림차순. `before=0`은 첫 페이지, 다음 페이지는 마지막 원글 ID를 before로 보낸다. limit/after 쿼리는 없다.
- 각 원글의 `replies`에 답글이 ID 오름차순으로 포함된다. 답글 작성 시 원글 ID를 parentId에 넣는다. 대댓글은 지원하지 않는다.
- 비로그인 조회는 `mine=false`, `liked=false`. 로그인 토큰을 보내면 사용자별 상태를 포함한다. 닉네임 설정 전에는 글을 쓸 수 없으며 BOT/TRADER 계정은 작성·좋아요를 할 수 없다.
- 글 수정 API는 없다. 본인 원글 삭제 시 답글과 좋아요도 함께 삭제된다. ADMIN 전용 타인 글 삭제는 이 API에 없다.
- 원글 생성 후 7일이 지난 글타래는 기존 DataRetentionService가 답글·좋아요와 함께 정리한다. 조회 시 즉시 TTL 필터링하는 방식은 아니다.
- 좋아요는 토글이므로 네트워크 오류 후 무조건 재시도하지 말고 목록을 다시 조회한다.
- 종토방 입력·없는 글·삭제 권한 오류는 원격 구현에 따라 400 + `{message}`다.
- 기존 프론트 `src/lib/stockTalk.ts`, `src/next/trade/StockTalk.tsx`도 origin의 구현을 그대로 가져왔다. 추가 UI 변경은 하지 않았다.

## 종목별 채팅방

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/stocks/{code}/community/chat` | `before?` 또는 `after?`, `limit=50` | Page |
| POST | `/stocks/{code}/community/chat` | `{body}` | 201 Entry |
| DELETE | `/stocks/{code}/community/entries/{id}` | 없음 | 204 |

```ts
type Entry = {
  id: number; stockCode: string; authorId: number; nickname: string;
  parentId: null; kind: 'CHAT'; title: '';
  body: string; deleted: boolean; createdAt: string; updatedAt: string;
};
type Page = { items: Entry[]; nextCursor: number | null; hasMore: boolean };
```

채팅 삭제는 본인 또는 ADMIN만 가능하고 tombstone으로 유지된다(`deleted=true`, body 빈 문자열). `limit`은 1~100, before는 양수 exclusive ID다. nextCursor는 반환 목록의 마지막 ID이고 다음 페이지 여부는 hasMore로 판단한다. parentId/kind/title은 기존 채팅 응답 호환 필드이며 별도 게시판 기능을 의미하지 않는다.

본문 1~1,000자, 계정당 분당 30회, 수정 불가. 종목마다 독립된 채널이며 저장 후 조회할 수 있다. v1 실시간 전송은 **커서 폴링**이고 WebSocket/SSE 채팅 이벤트는 없다.

연결 순서:

1. 최초 GET은 최신 50개를 ID 내림차순으로 반환한다. 화면에서는 뒤집어서 표시하고 최댓값 ID를 `after`로 기억한다.
2. 새 메시지는 `?after=<마지막 ID>`로 약 2초마다 조회한다. 응답은 ID 오름차순이다. 빈 채널은 `after=0`부터 시작한다.
3. `hasMore=true`면 `nextCursor`로 이어 받아 누락 없이 따라잡는다. ID로 중복 제거한다. 빈 응답일 때 기존 커서를 유지한다.
4. 과거 메시지는 `?before=<가장 오래된 ID>`로 조회한다. `before`와 `after`를 함께 보내면 400.
5. 메시지 전송 응답은 즉시 append하고 폴링 결과와 ID로 합친다. 전송 POST를 무조건 자동 재시도하면 중복 글이 생길 수 있다.
6. `after`는 **새 ID** 조회다. 이전 메시지 삭제/수정은 이 커서로 전파되지 않는다. 활성 창의 최신 목록을 주기적으로 다시 조회하거나 삭제 후 재조회한다. 비활성 탭의 폴링은 중단한다.

## 상세 차트와 기본 인디케이터

`POST /stocks/{code}/chart-analysis` (공개, 읽기 전용)

```json
{
  "range": "1h",
  "indicators": [
    {"id":"ma5","type":"SMA","period":5},
    {"id":"ema20","type":"EMA","period":20},
    {"id":"rsi14","type":"RSI","period":14},
    {"id":"macd","type":"MACD","period":26},
    {"id":"bands","type":"BBANDS","period":20},
    {"id":"atr14","type":"ATR","period":14}
  ]
}
```

```ts
type Candle = {
  recordedAt: string; openPrice: number; highPrice: number;
  lowPrice: number; closePrice: number; volume: number; updatedAt: string;
};
type Chart = {
  stockCode: string; range: string; intervalSeconds: number;
  volumeUnit: 'TRADE_COUNT'; candles: Candle[];
  indicators: Array<{
    id: string; pane: 'overlay' | 'oscillator';
    values: Record<string, Array<number | null>>;
  }>;
  asOf: string;
};
```

각 지표 배열 인덱스는 `candles`와 1:1 대응한다. 준비 기간은 null이며 0으로 치환하지 않는다. 캔들은 시간 오름차순, 최대 2,000개이고 아직 진행 중인 마지막 캔들을 포함할 수 있다. 거래 없는 구간을 인위적으로 채우지 않는다. 현재 데이터 소스의 `volume`은 **체결 건수**다. 거래량(주)으로 표시하지 않는다.

| range | 조회 기간 | 캔들 간격(초) |
|---|---|---:|
| 1h | 1시간 | 60 |
| 6h | 6시간 | 300 |
| 12h | 12시간 | 600 |
| 1d | 1일 | 1800 |
| 3d | 3일 | 3600 |
| 1w | 7일 | 7200 |
| 2w | 14일 | 21600 |
| 1m | 30일 | 86400 |
| 1y | 365일 | 604800 |

v1은 기존 차트의 기간/간격 조합을 따른다. 임의 시작일/끝일, 기간과 간격의 독립 선택, 과거 캔들 페이지네이션은 지원하지 않는다. 실제 보관 데이터가 부족하면 배열은 더 짧다. 200기간 지표를 요청해도 캔들이 200개 미만이면 값이 생기지 않는다.

| type | values 키 | 계산 |
|---|---|---|
| SMA | value | close 단순 이동평균 |
| EMA | value | 첫 period개의 SMA로 seed, α=2/(period+1) |
| RSI | value | Wilder 평활, period개의 변화량 필요, 횡보 50 |
| MACD | macd, signal, histogram | EMA12−EMA26, signal EMA9; period 입력은 검증만 하고 계산은 12/26/9 고정 |
| BBANDS | middle, upper, lower | SMA ± 2×모집단 표준편차 |
| ATR | value | Wilder true range, 최초 캔들은 high−low |

지표 최대 12개. id는 중복 없는 `[A-Za-z][A-Za-z0-9_]{0,31}`. period는 2~500 정수. RSI/ATR/MACD는 oscillator, 나머지는 overlay.

상세 페이지의 나머지 데이터는 기존 API를 병렬 연결하면 된다: `/stocks`, `/stocks/{code}/orderbook`, `/stocks/{code}/trades`, `/stocks/{code}/news`, `/stocks/{code}/price-drivers`. 이들 응답은 이번 변경에서 수정하지 않았다.

## BlockQuant / snScript 공통 API

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/quant/capabilities` | 없음, 공개 | version/languages/ranges/indicators/functions/한도 |
| POST | `/quant/compile` | Source | Compilation |
| POST | `/quant/backtest` | BacktestRequest | BacktestResult |
| GET | `/quant/projects` | 없음 | 본인 Project[], 최신 순, 최대 20개 |
| POST | `/quant/projects` | Save | 201 Project, 초기 PAUSED |
| GET | `/quant/projects/{id}` | 없음 | Project |
| PUT | `/quant/projects/{id}` | Save + 현재 revision | Project, revision + 1 |
| PATCH | `/quant/projects/{id}/state` | `{active:true 또는 false, revision}` | Project |
| DELETE | `/quant/projects/{id}` | 없음 | 204, PAUSED만 가능, 실행 로그도 삭제 |
| GET | `/quant/projects/{id}/runs` | `before?`, `limit=30` (1~100) | Run[], ID 내림차순 |

```ts
type Source = { language: 'SNSCRIPT'; source: string; blocks?: null }
            | { language: 'BLOCKS'; blocks: BlockProgram; source?: null };
type Save = {
  name: string; stockCode: string; range: string; code: Source;
  maxOrders: number; maxNotional: number; revision?: number;
};
type Compilation = {
  valid: boolean; program: Program | null;
  diagnostics: Array<{line: number; column: number; message: string}>;
};
```

컴파일 문법 오류는 HTTP 200 + `valid=false`. 첫 진단의 line/column은 1부터 시작한다. 블록/의미 분석 오류 위치는 1:1일 수 있다. 저장/백테스트 시 컴파일 오류는 400이다. 서버는 임의 Java/JS/Python을 실행하지 않고 허용된 AST만 계산한다. 프론트 에디터는 약 300~500ms debounce 후 compile을 호출하고 이전 요청을 취소한다.

### snScript v1 문법

Pine 전체 호환 언어가 아니다. 다음 예시는 그대로 compile의 `source`에 전달한다.

```text
// 종가 기준 이동평균 교차
let fast = ta.sma(close, 5)
let slow = ta.ema(close, 20)
plot fastLine = fast
plot slowLine = slow
plot rsiLine = ta.rsi(close, 14)
buy when ta.crossover(fast, slow) quantity 2
sell when ta.crossunder(fast, slow) quantity 2
```

- 문장: `let NAME = EXPR`, `plot NAME = EXPR`, `buy when EXPR quantity INTEGER`, `sell when EXPR quantity INTEGER`.
- 줄바꿈 또는 `;`로 문장 종료. 하나의 식을 여러 줄에 걸쳐 작성하지 않는다. `//` 주석 지원.
- 기본 시계열: `open`, `high`, `low`, `close`, `volume`(체결 건수).
- 함수: `ta.sma(series, period)`, `ta.ema`, `ta.rsi`, `ta.stddev`, `ta.atr(period)`, `ta.crossover(a,b)`, `ta.crossunder(a,b)`, `abs(series)`. `ta.` 접두사는 생략 가능.
- 연산: `+ - * / > >= < <= == != and or not`, 단항 `-`, 괄호, 숫자, `true/false`.
- 우선순위: 단항 > 곱/나눗셈 > 덧/뺄셈 > 비교 > and > or. 비교 체인은 쓰지 말고 `a < b and b < c`로 표현한다.
- 조건은 유한한 0이 아닌 값이면 참. 결측/0으로 나눔은 결측이며 매매 신호를 내지 않는다. 결측 피연산자는 논리 연산에서도 결측으로 전파된다.
- 변수는 선언 순서로 참조하고 중복/기본 시계열 이름 덮어쓰기는 금지. plot 이름은 중복 불가이며 변수가 아니다.
- 상수 절댓값 ≤ 10^12, period 2~500, 주문 수량 1~1,000. 이름은 영문/숫자/밑줄 최대 32자이며 숫자로 시작하지 않는다.
- 최대 소스 32,768 UTF-16 code units, 토큰 2,048개(소스 토큰 기준), 변수 32개, plot 8개, 규칙 8개, AST 노드 합계 256개, 식 깊이 24단계.
- 문자열, 배열 인덱싱, 루프, 사용자 함수, 외부 요청, 파일 접근, 타 종목 시계열, 전략 포지션 변수는 지원하지 않는다.

### BlockQuant v1 블록 직렬화

Scratch 방식의 UI는 아래 JSON으로 저장한다. 같은 예시와 snScript는 공통 Program으로 컴파일된다.

```json
{
  "version": 1,
  "bindings": [
    {"name":"fast","expression":{"op":"sma","period":5,"args":[{"op":"ref","name":"close"}]}},
    {"name":"slow","expression":{"op":"ema","period":20,"args":[{"op":"ref","name":"close"}]}}
  ],
  "plots": [{"name":"fastLine","expression":{"op":"ref","name":"fast"}}],
  "rules": [
    {"side":"BUY","quantity":2,"condition":{"op":"crossover","args":[{"op":"ref","name":"fast"},{"op":"ref","name":"slow"}]}},
    {"side":"SELL","quantity":2,"condition":{"op":"crossunder","args":[{"op":"ref","name":"fast"},{"op":"ref","name":"slow"}]}}
  ]
}
```

`bindings`, `plots`, `rules`는 빈 배열이어도 반드시 포함한다. 전체 blocks 직렬화 길이 최대 32,768자. 블록의 좌표/접힘 등 UI 메타데이터는 blocks 내부의 별도 필드에 넣으면 원본 source에 보존되지만 컴파일에는 사용되지 않는다.

```ts
type Expr =
  | {op:'number'; value:number}
  | {op:'ref'; name:string}
  | {op:'sma'|'ema'|'rsi'|'stddev'; period:number; args:[Expr]}
  | {op:'atr'; period:number; args:[]}
  | {op:'abs'|'not'|'neg'; args:[Expr]}
  | {op:'+'|'-'|'*'|'/'|'>'|'>='|'<'|'<='|'=='|'!='|'and'|'or'|'crossover'|'crossunder'; args:[Expr,Expr]};
type BlockProgram = {
  version:1; bindings:Array<{name:string; expression:Expr}>;
  plots:Array<{name:string; expression:Expr}>;
  rules:Array<{side:'BUY'|'SELL'; quantity:number; condition:Expr}>;
};
// 컴파일된 Program은 같은 구조지만 Expr의 미사용 value/name/period는 null,
// args는 항상 배열로 반환된다. 편집 시에는 원본 source를 사용한다.
type Program = BlockProgram;
```

### 저장·실행 상태

```ts
type Project = {
  id:number; userId:number; name:string; stockCode:string; range:string;
  language:'SNSCRIPT'|'BLOCKS'; source:string; program:Program;
  revision:number; status:'PAUSED'|'ACTIVE'|'RUNNING'|'PAUSING';
  maxOrders:number; maxNotional:number; ordersUsed:number; notionalUsed:number;
  lastBar:string|null; updatedAt:string;
};
type Run = {
  id:number; revision:number; barTime:string; side:'BUY'|'SELL'; quantity:number;
  limitPrice:number; status:'SUBMITTING'|'ACCEPTED'|'REJECTED'|'UNKNOWN';
  message:string; createdAt:string; orderId:number|null;
};
```

`source`는 항상 문자열이다. SNSCRIPT는 그대로 편집하고 BLOCKS는 `JSON.parse(project.source)`로 복원한다. 프로젝트 이름 1~80자, 본인 프로젝트 최대 20개, 저장 계정당 분당 20회. PUT은 전체 교체이며 PAUSED 상태만 가능하다. 다른 탭에서 저장하면 409가 나므로 GET 후 병합한다.

자동매매 정책:

- 저장·컴파일·백테스트는 실제 주문을 내지 않는다. `/state`에 `active:true`를 명시해야 활성화된다.
- 소유권을 가진 시장 서버가 기본 2초마다 최대 20개 프로젝트를 차례로 확인한다. 사용자가 로그아웃해도 서버 실행은 유지된다.
- **완성된 마지막 캔들**만 평가하며, 바 시작부터 2개 간격보다 오래된 데이터는 주문에 사용하지 않는다. 비활성 기간의 과거 신호를 모두 재생하지 않는다.
- 캔들당 처음 참이 된 규칙 **1개만** 실행한다. 실패해도 같은 캔들의 다음 규칙으로 넘어가지 않는다. 조건 `true`는 새 캔들마다 반복할 수 있다. 교차 시에만 실행하려면 crossover/crossunder를 쓴다.
- 실제 주문은 기존 FIFO의 **지정가 주문**이다. 매수는 현재가를 호가단위 내림, 매도는 올림한 가격이다. 미체결 주문이 남을 수 있다. 자동 취소나 시장가 전환은 하지 않는다.
- `maxOrders` 1~100, `maxNotional` 1~10^12. `ordersUsed`는 접수 시도 횟수, `notionalUsed`는 `지정가격×수량`의 누적합이다. 매수·매도 모두 합산하며 실패/미체결/취소에도 되돌리지 않는다. 수수료와 실제 체결대금의 합계 한도는 아니다.
- 누적 카운터와 `lastBar`는 중지/재개/편집으로 초기화되지 않는다. 한도는 수정할 수 있지만 maxOrders 최대 100은 유지된다. 자동매매 실행도 잔고·보유 수량·정지·호가·거래 제한 검증을 받는다. ADMIN/BOT/TRADER 계정은 활성화 불가.
- RUNNING 중 중지하면 PAUSING → 처리 완료 후 PAUSED. 이미 접수한 주문은 취소되지 않는다. 미체결 취소는 기존 `/orders/{orderId}` API를 사용한다.
- ACCEPTED는 주문 접수가 성공했다는 뜻이며 체결 완료를 뜻하지 않는다. `message`에 기존 주문 상태를 담고, 반환된 `orderId`가 있으면 기존 주문 API와 연결한다. 즉시 체결 등으로 ID가 없을 수도 있다.
- DB에 claim을 먼저 저장해 중복 실행을 방지한다. 서버 장애로 처리 결과가 애매하면 UNKNOWN + PAUSED로 유지한다. SUBMITTING이 5분 이상 남으면 복구 처리한다. 재주문은 자동으로 하지 않는다. 주문 내역을 확인한 뒤 사용자가 재개해야 한다. **분산 장애 상황에서 exactly-once 체결을 보장하는 API는 아니다.**
- 계정 리셋은 기존 전략을 중지하며 처리 중 주문이 있으면 리셋을 거절한다. 저장된 전략과 실행 기록은 유지한다.
- runs는 cursor 배열이다. 마지막 id를 before로 보내고 limit보다 적으면 끝이다. 프로젝트 삭제 시 해당 실행 기록도 삭제된다.

### 백테스트 / 지표 미리보기

```json
{
  "stockCode":"GI", "range":"1h",
  "code":{"language":"SNSCRIPT","source":"let ma = ta.sma(close, 5)\nplot maLine = ma\nbuy when ta.crossover(close, ma) quantity 1"},
  "initialCash":1000000, "feeBps":10, "slippageBps":5
}
```

plot 이름은 변수가 아니므로 편집기의 변수 블록과 plot 블록은 별개로 취급한다.

```ts
type BacktestResult = {
  fillModel:'NEXT_BAR_OPEN_LONG_ONLY'; initialCash:number; finalEquity:number;
  returnPercent:number; maxDrawdownPercent:number; rejectedOrders:number;
  trades:Array<{signalAt:string; filledAt:string; side:'BUY'|'SELL'; quantity:number; price:number; fee:number}>;
  equity:Array<{time:string; cash:number; quantity:number; equity:number}>;
  plots:Record<string, Array<number|null>>;
};
```

초기자금 1~10^12, 수수료/슬리피지 0~1,000 bps(1bp=0.01%). 완성된 캔들만 사용한다. 신호는 t 종가에서 계산하고 다음 존재하는 캔들 t+1 시가에 슬리피지를 적용해 체결한다. 매수 cash/매도 보유량이 부족하면 전량 거절한다. 공매도·레버리지·분할체결·미체결 큐·T+1 정산·가격제한·시장 충격은 모델링하지 않는다. 실제 지정가 자동매매와 결과가 다를 수 있다. 포지션은 마지막에 강제 청산하지 않고 종가 평가한다.

수수료는 bps가 0이면 0, 그 외 max(1원, 반올림(거래대금×bps/10000))이다. 백테스트에서 슬리피지 적용 가격은 소수가 될 수 있다. plot만 있는 프로그램도 백테스트할 수 있고 `plots`를 차트에 그리면 에디터 미리보기가 된다. 이때 `equity[].time`이 plot의 시간 축이다.

## 운영·성능·검증

종토방은 origin의 `StockTalkService.ensureTables()`로 `stock_talk_posts`, `stock_talk_likes`를 생성한다. 채팅·전략은 `ResearchSchema.ensureTables()`로 4개 테이블을 생성한다: `stock_discussions`, `research_write_limits`, `quant_projects`, `quant_runs`. `stock_discussions`는 채팅 저장에만 사용하며 종토방 게시글을 중복 저장하지 않는다. 기존 테이블 데이터 변경이나 파괴적 마이그레이션은 없다. 신규 DB용 `database/schema.sql`에도 포함한다.

설정값: `gamestock.quant.check-ms=2000`, `gamestock.quant.initial-delay-ms=45000`. 기존 Spring scheduler를 사용한다. 프로젝트 평가 시 같은 tick의 동일 종목/range 캔들은 공유한다. 지표는 선형 시간 계산이고 계산 API 동시 실행은 JVM당 4개로 제한한다. 조회는 cursor/index/limit을 사용한다. 대규모 배포의 보관/삭제 정책과 사용자 수에 따른 스케줄러 용량은 별도 운영 과제다.

120Hz(8.33ms frame budget)는 프론트 렌더링과 장치/네트워크를 포함하므로 이 백엔드 구현만으로 모든 환경에서 보장하지 않는다. 프론트는 네트워크 응답을 프레임 루프와 분리하고, 차트 응답 재사용·요청 취소·비활성 탭 폴링 중단을 적용한다. API latency SLA 또는 부하 테스트 완료로 해석하지 않는다.

검증 명령(Fish):

```fish
mvn -f backend/pom.xml test
mvn -f backend/pom.xml '-Dtest=ResearchIntegrationTest' '-Dresearch.test.jdbc=jdbc:mysql://127.0.0.1:13367/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' test
```

MySQL 통합 테스트는 `research.test.user`(기본 root), `research.test.password`(기본 빈 문자열)를 사용한다. 테스트마다 `research_test_<uuid>` 임시 스키마를 만들고 종료 시 삭제한다. 테스트용 서버에만 연결한다. 실제 주문 큐/시장 체결을 대신하는 mock admission과 실제 DB 트랜잭션을 함께 사용해 claim/한도/복구를 검증한다.
