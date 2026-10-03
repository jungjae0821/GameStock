# GameStock DB 저장 정책

Railway Hobby의 제한된 MySQL Volume을 고려한 시장 저장 정책이다. 매칭은 계속 메모리의 `BatchOrderBook`에서 수행하고, 커밋 시점에 `BatchMarketRepository`가 사람 원장과 집계 projection을 한 트랜잭션으로 저장한다.

## 저장 경계

| 데이터 | 저장 방식 | 보존 정책 |
| --- | --- | --- |
| 사람 주문 | `orders`에 원본 상세 저장 | 사용자 주문내역과 체결 FK를 위해 보존 |
| 사람 관련 체결 | `trades`와 `settlements`에 상세 저장 | 사용자 거래내역·손익 계산에 사용 |
| 봇 주문 | 열린 주문만 `orders`에 임시 저장. `compact_origin=TRUE` 표시 | 봇 전용으로 종료되고 체결 FK가 없으면 60초 후 삭제 |
| 봇-사람 체결 | `trades`에 저장 | 사람 거래내역과 주문 FK 보존을 위해 유지 |
| 봇-봇 체결 | 원본 주문·체결 row를 만들지 않음 | `bot_stats`, candle, 누적 카운터만 유지 |
| 시세 | `market_candles`의 1초·1분 upsert | 1초봉 24시간, 1분봉 365일 |
| 봇 영향 | `bot_stats`의 1초·1분 upsert | 1초봉 24시간, 1분봉 365일 |
| 현재 계좌·포지션 | `accounts`, `positions`의 동일 PK row UPDATE | 이력 INSERT를 만들지 않음 |
| 포트폴리오 이력 | `account_snapshots` | 사용자 기준 1시간 bucket, 365일 |

`stock_id`를 symbol의 정규화 표현으로 사용해 문자열 반복 저장도 줄였다. `users.cash`와 `portfolios`는 기존 API와 정산 호환을 위해 현재 원장으로 남아 있고, `accounts`와 `positions`는 같은 배치 트랜잭션에서 갱신되는 조회 projection이다.

## 실행 흐름

```text
사람 주문
  -> OrderService FIFO (최대 2,048 pending)
  -> PersistenceWorker (bounded DB queue 64, 최대 200건 batch)
  -> MatchingEngine / BatchOrderBook
  -> BatchMarketRepository
  -> orders + trades + settlements + accounts/positions UPDATE

봇 주문
  -> BotTradingScheduler
  -> PersistenceWorker (shard당 1개 pending)
  -> 메모리 매칭
  -> market_candles + bot_stats 집계
  -> MySQL batch upsert
```

`BotLedgerJournal`라는 호환 클래스 이름은 남아 있지만, 새 실행 경로는 `bot_ledger_batches` MEDIUMBLOB payload를 만들지 않는다. 기존 Railway DB에 이미 있는 legacy payload row는 새로 추가되지 않고, 유지보수 주기의 indexed cleanup에서 제한된 건수씩 정리된다.

## 장애·종료 처리

- DB batch는 multi-row insert/upsert로 처리한다.
- 일시적 DB 오류는 설정된 횟수(기본 4회)까지만 retry한다.
- 저장공간 부족 오류가 감지되면 새 DB write를 30초 동안 backpressure하고, bounded queue가 무한히 커지지 않게 한다.
- `PersistenceWorker` 종료 시 새 입력을 막고 이미 승인된 작업을 최대 30초 graceful drain한 뒤 남은 future만 실패 처리한다.
- 유지보수 작업은 500ms scheduler에 매달리지만 retention SQL은 30초마다 한 번만 실행하며 `LIMIT`로 작은 단위 삭제를 한다.

## 나중의 archive 경계

초기 구현에서는 archive 파일 업로드를 수행하지 않는다. 사람 `orders`와 `trades`에는 이미 사용자·종목·생성시각과 체결 FK가 있으므로, 이후 90일 초과 데이터를 order/trade 쌍으로 묶어 JSON/CSV/Parquet object로 옮기고 MySQL에서는 archive manifest와 최근 데이터만 남기는 방식으로 확장할 수 있다. 봇 원본을 archive로 되살리는 경로는 만들지 않는다.

## 적용 확인

- 신규 DB: `database/schema.sql`에 `accounts`, `positions`, `market_candles`, `bot_stats`, `account_snapshots`가 포함된다.
- 기존 DB: 애플리케이션 시작 시 `BotLedgerJournal.ensureTables()`가 누락된 테이블·컬럼·필요 index만 확인한다. 구형 compact candle 전체 이관은 기동 경로에서 수행하지 않으며, 기존 레거시 projection은 새 쓰기 경로를 막지 않도록 유지보수 정리 대상으로 남긴다.
- 운영 전환 후에는 `market_candles`와 `bot_stats`의 interval별 row 수, `orders.compact_origin=TRUE`의 종료 row 수, legacy `bot_ledger_batches`의 신규 증가 여부를 확인한다.
- 로컬에서 실행한 기본 백엔드 테스트는 DB 없는 테스트만 포함한다. Railway 적용, 실제 MySQL 통합 테스트, Firebase/Railway release는 별도 확인 대상이다.
