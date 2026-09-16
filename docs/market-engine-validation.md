# 거래 엔진·데이터 신뢰성 검증

GameStock의 거래 엔진은 주문 접수, 예약, 가격·시간 우선 매칭, 부분 체결, 즉시 정산, 취소·만료 반환을 하나의 DB 트랜잭션과 시장 잠금 안에서 처리한다. 검증 러너는 `scripts/test-market-quality.ps1`이다.

## 자동 시나리오

인증 토큰 없이도 다음을 자동 확인한다.

- 시장·뉴스·호가·체결·가격 이력·일봉 API가 200을 반환하는지
- 프로필·포트폴리오·주문·정산·주문 생성·취소·계정 초기화·관리자 API가 토큰 없이 401인지
- 종목별 뉴스가 최대 5건인지, 가격 설명의 현재가가 종목 API와 일치하는지
- 호가창에 매수·매도 배열이 존재하는지
- 각 HTTP 요청의 응답 시간을 측정하는지

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\test-market-quality.ps1
```

Firebase ID 토큰을 알고 있는 테스트 계정은 읽기 API까지 인증 상태로 점검할 수 있다.

```powershell
.\scripts\test-market-quality.ps1 -FirebaseIdToken $token
```

`-RunOrderScenarios`를 함께 지정하면 UMA 1주 지정가 주문을 현재가보다 충분히 낮게 접수한 뒤 취소하고, 예약 현금이 원래대로 돌아오는지 확인한다. 이 옵션은 실제 테스트 계정의 DB에 잠깐 주문을 만들므로 테스트 환경에서만 사용한다.

```powershell
.\scripts\test-market-quality.ps1 -FirebaseIdToken $token -RunOrderScenarios
```

## 정합성 규칙

다음 조건은 DB에서 항상 참이어야 한다.

- `orders.remaining_quantity`가 0 이상이고 원래 수량을 넘지 않는다.
- `orders.reserved_cash`와 `reserved_quantity`는 지정가 미체결 잔량과 일치한다.
- `portfolios.quantity`·`settled_quantity`는 음수가 아니며 settled 수량은 보유 수량을 넘지 않는다.
- 체결이 취소되지 않았다면 `trades`와 연결된 `settlements`가 존재하고, 현재 구현의 체결 상태는 `SETTLED`다.
- 주문·체결·자산·수수료 갱신은 같은 트랜잭션에서 처리된다.
- `market_locks` 행 잠금으로 동시에 들어온 주문도 한 종목씩 직렬화된다.

운영 점검에서는 읽기 전용 SQL로 음수 잔고·음수 잔량·고아 외래키를 검사하고 결과를 배포 기록에 남긴다. 테스트 러너는 DB를 초기화하거나 임의의 거래 데이터를 삭제하지 않는다.

읽기 전용 정합성 검사는 다음 명령으로 실행한다. `.env`의 DB 접속 정보를 사용하며 비밀번호를 명령행이나 출력에 기록하지 않는다.

```powershell
.\scripts\check-market-invariants.ps1
```

## 응답 시간·실시간·재시작 측정

- API 러너의 `avgLatencyMs`와 개별 요청 시간을 저장한다. 발표용 기준은 개발 PC에서 공개 조회 API p95 1초 이하를 목표로 한다.
- WebSocket `/ws/market` 연결 직후 `MARKET_UPDATED` 수신 시각과 연결 시각의 차이를 측정하고, REST 가격과 payload의 종목 목록이 일치하는지 확인한다.
- `scripts/stop.ps1` 실행 후 `scripts/start.ps1`로 재기동하고 `/api/health`, `/api/stocks`, `simulation_state.tick`을 확인한다. 재기동 뒤 종목·뉴스·가격 이력이 유지되고 틱이 이어지면 복구 성공으로 기록한다.

현재 자동 점검에서는 공개 API 22개, 데이터 형태, WebSocket, 봇 가격 상한을 확인했다. Firebase ID 토큰을 제공하지 않은 실행에서는 개인정보·주문 변경 시나리오를 의도적으로 수행하지 않는다.
