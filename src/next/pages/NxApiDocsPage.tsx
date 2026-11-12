import { ApiKeyManager } from "./apiDocs/ApiKeyManager";
import { ACCOUNT_ENDPOINTS, BASE, ERROR_ROWS, KEY_ENDPOINTS, QUOTE_ENDPOINTS, SPEC_URL, TOC, TRADE_ENDPOINTS } from "./apiDocs/apiDocsData";
import { CodeBlock, CopyButton, EndpointTable, OrderExamples, Section } from "./apiDocs/DocsParts";

export function NxApiDocsPage() {
  const quoteCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/quotes"`;
  const candleCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/stocks/GI/candles?range=1d"`;

  const keyListCurl = `curl --fail-with-body "${BASE}/api/openapi/keys" \\
  -H "Authorization: Bearer $FIREBASE_ID_TOKEN"`;

  const keyRevokeCurl = `curl --fail-with-body -X DELETE "${BASE}/api/openapi/keys/KEY_ID" \\
  -H "Authorization: Bearer $FIREBASE_ID_TOKEN"`;

  const orderCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/orders" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: stock-buy-001" \\
  -H "Content-Type: application/json" \\
  -d '{"product":"STOCK","symbol":"GI","side":"BUY","quantity":1,"orderType":"MARKET"}'`;

  const cancelCurl = `curl --fail-with-body -X DELETE "${BASE}/api/openapi/v1/orders/123" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: cancel-unique-001"`;

  const closeCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/positions/123/close" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: close-unique-001" \\
  -H "Content-Type: application/json" \\
  -d '{"quantity":1}'`;

  return (
    <div className="nx-screen nx-docs">
      <header className="nx-docs-hero">
        <h1 className="nx-panel-title">게임주 OpenAPI</h1>
        <p className="nx-docs-hero-copy">
          외부 프로그램에서 슈엔증권의 모의 주식·ETF·선물·옵션 시세를 읽고, 같은 계좌로 주문할 수 있는 REST API입니다.
          시세는 누구나 조회할 수 있고, 계좌와 주문은 발급받은 API 키로 인증합니다.
        </p>
        <div className="nx-docs-actions">
          <a className="nx-btn is-primary" href={SPEC_URL} target="_blank" rel="noreferrer">OpenAPI 명세 열기</a>
        </div>
        <p className="nx-docs-disclaimer">모의 투자 API입니다. 실제 화폐·금융상품과 관계없습니다.</p>
      </header>

      <div className="nx-docs-layout">
        <nav className="nx-docs-toc" aria-label="문서 목차">
          <p className="nx-docs-toc-title">이 문서에서</p>
          <ol>
            {TOC.map(([id, label]) => (
              <li key={id}><a href={`#${id}`}>{label}</a></li>
            ))}
          </ol>
        </nav>

        <div className="nx-docs-body">
          <Section id="quick-start" title="API 키 발급" lede="로그인한 계정으로 키를 발급하고 관리합니다. 이름·권한·만기를 선택하세요.">
            <ApiKeyManager />
            <p className="nx-docs-note-inline">기본 만기는 90일이며 계정당 활성 키는 최대 10개입니다. 관리자 계정은 조회 전용 키만 발급할 수 있습니다.</p>
            <h3>발급받은 키로 요청하기</h3>
            <p>개인 요청의 <code>Authorization</code> 헤더에 <code>Bearer &lt;발급받은 키&gt;</code>를 넣습니다. 시세는 인증 없이 조회할 수 있습니다.</p>
          </Section>

          <Section id="auth" title="인증과 키" lede="키는 계정 단위로 관리합니다. 요청의 소유자는 항상 키에서 결정되며, 다른 사용자의 계좌를 지정할 수 없습니다.">
            <div className="nx-docs-facts">
              <div>
                <span>기준 URL</span>
                <code>{BASE}</code>
                <CopyButton text={BASE} label="URL" />
              </div>
              <div>
                <span>응답</span>
                <strong>JSON · UTF-8</strong>
              </div>
              <div>
                <span>인증</span>
                <strong>Authorization: Bearer</strong>
              </div>
              <div>
                <span>명세</span>
                <a href={SPEC_URL} target="_blank" rel="noreferrer">OpenAPI 3.0.3</a>
              </div>
            </div>

            <EndpointTable label="API 키 관리 엔드포인트" endpoints={KEY_ENDPOINTS} />
            <div className="nx-docs-two-col">
              <CodeBlock title="키 목록" code={keyListCurl} />
              <CodeBlock title="키 폐기" code={keyRevokeCurl} />
            </div>

            <div className="nx-docs-callout">
              <strong>권한과 계정 상태</strong>
              <ul>
                <li><code>READ</code> 키는 시세·계좌·주문 조회만 할 수 있습니다.</li>
                <li><code>TRADE</code> 키만 주문·취소·청산을 실행할 수 있습니다.</li>
                <li>정지된 계정도 키 목록 조회와 폐기는 가능하지만, 키를 통한 개인 기능은 사용할 수 없습니다.</li>
                <li>관리자 계정은 API로 거래할 수 없고 <code>TRADE</code> 키도 발급할 수 없습니다.</li>
              </ul>
            </div>
            <p className="nx-docs-note-inline">원격 서버에는 HTTPS를 사용하고, 키를 URL·로그·소스 저장소에 남기지 마세요. 키를 폐기하거나 만료시킨 뒤에는 같은 키로 요청할 수 없습니다.</p>
          </Section>

          <Section id="quotes" title="시세 조회" lede="아래 경로는 모두 인증이 필요 없습니다. 종목 코드는 대문자입니다.">
            <EndpointTable label="시세 조회 엔드포인트" endpoints={QUOTE_ENDPOINTS} />
            <div className="nx-docs-two-col">
              <CodeBlock title="전체 시세" code={quoteCurl} />
              <CodeBlock title="차트" code={candleCurl} />
            </div>
            <div className="nx-docs-callout">
              <strong>차트와 파생상품 읽는 법</strong>
              <ul>
                <li>차트 기간은 <code>1h</code>, <code>6h</code>, <code>12h</code>, <code>1d</code>, <code>3d</code>, <code>1w</code>, <code>2w</code>, <code>1m</code>, <code>1y</code>이고, 봉 간격은 각각 1분·5분·10분·30분·1시간·2시간·6시간·1일·1주입니다.</li>
                <li>차트의 <code>volume</code>은 거래 주식 수가 아니라 가격 표본·집계 건수입니다.</li>
                <li>선물 <code>price</code>와 옵션 <code>mark</code>·<code>strike</code>는 지수 포인트입니다. 선물 1포인트는 계약당 1,000원입니다.</li>
                <li>옵션 <code>premium</code>은 계약당 원화, <code>expiryEpoch</code>는 초 단위 Unix 시각입니다. 회차는 <code>10M</code>·<code>1H</code>·<code>SUN</code>입니다.</li>
                <li>ETF·선물의 <code>series</code>는 서버 메모리의 최근 표본이며, 시각이 붙은 OHLC 이력이 아닙니다.</li>
              </ul>
            </div>
            <p className="nx-docs-note-inline">시세는 체결 가격을 보장하지 않습니다. 주문 시 서버가 최신 가격과 잔액을 다시 계산합니다.</p>
          </Section>

          <Section id="orders" title="주문하기" lede="모든 신규 주문은 POST /api/openapi/v1/orders 하나로 보냅니다. Authorization과 Idempotency-Key가 필요합니다.">
            <EndpointTable label="주문·취소·청산 엔드포인트" endpoints={TRADE_ENDPOINTS} />
            <div className="nx-docs-table-wrap">
              <table className="nx-docs-table" aria-label="상품별 주문 방향">
                <thead>
                  <tr><th scope="col">상품</th><th scope="col">BUY</th><th scope="col">SELL</th><th scope="col">주문 방식</th></tr>
                </thead>
                <tbody>
                  <tr><td data-label="상품"><code>STOCK</code></td><td data-label="BUY">주식 매수</td><td data-label="SELL">보유 주식 매도</td><td data-label="주문 방식">MARKET 또는 LIMIT</td></tr>
                  <tr><td data-label="상품"><code>ETF</code></td><td data-label="BUY">ETF 매수</td><td data-label="SELL">보유 ETF 매도</td><td data-label="주문 방식">현재 NAV</td></tr>
                  <tr><td data-label="상품"><code>FUTURE</code></td><td data-label="BUY">롱 신규 진입</td><td data-label="SELL">숏 신규 진입</td><td data-label="주문 방식">현재 지수, 레버리지 2·5·10</td></tr>
                  <tr><td data-label="상품"><code>OPTION</code></td><td data-label="BUY">콜/풋 매수</td><td data-label="SELL">보유 옵션 매도</td><td data-label="주문 방식">서버 계산 프리미엄</td></tr>
                </tbody>
              </table>
            </div>

            <div className="nx-docs-callout is-guard">
              <strong>선물의 SELL은 청산이 아닙니다</strong>
              <p>기존 선물 포지션을 닫으려면 <code>/positions/&#123;positionId&#125;/close</code>를 사용하세요. 옵션 신규 매도(발행)는 지원하지 않습니다.</p>
            </div>

            <OrderExamples />

            <CodeBlock title="주식 시장가 주문" code={orderCurl} />
            <p className="nx-docs-note-inline">수량은 주식 1~1,000주, ETF·선물·옵션 1~100좌/계약입니다. 상품에 맞지 않는 필드나 알 수 없는 필드가 있으면 거절합니다. 주식 <code>orderType</code>을 생략하면 시장가, 선물 <code>leverage</code>를 생략하면 5배입니다.</p>

            <div className="nx-docs-callout">
              <strong>옵션 매도는 보유 포지션으로 지정합니다</strong>
              <CodeBlock code={`{"product":"OPTION","side":"SELL","quantity":1,"positionId":123}`} />
            </div>

            <p className="nx-docs-note-inline">성공하면 <code>201</code>과 <code>&#123; requestId, operation, result &#125;</code>를 돌려줍니다. 주식의 <code>OPEN</code>은 대기 또는 부분 체결이며, 접수만으로 전량 체결을 뜻하지 않습니다.</p>
          </Section>

          <Section id="account" title="계좌·주문 조회" lede="아래 조회는 READ 또는 TRADE 키를 사용합니다. 소유자는 항상 API 키에서 결정됩니다.">
            <EndpointTable label="계좌와 주문 조회 엔드포인트" endpoints={ACCOUNT_ENDPOINTS} />
            <div className="nx-docs-callout">
              <strong>잔고를 읽을 때 주의할 점</strong>
              <ul>
                <li><code>portfolio.cash</code>와 <code>derivatives.cash</code>는 같은 현금입니다. 둘을 더하지 마세요.</li>
                <li>파생 <code>equity</code>에는 주식 평가액이 포함되지 않습니다.</li>
                <li>두 요약은 순차 조회이므로 동시에 다른 주문이 체결되면 기준 시각이 다를 수 있습니다.</li>
                <li>거래 내역의 <code>cashFlow</code>는 수수료를 포함한 순현금 증감입니다. <code>fee</code>를 다시 빼지 마세요.</li>
                <li>미체결 목록은 id 내림차순이며 <code>limit</code>은 1~100입니다. 다음 페이지는 마지막 행의 <code>id</code>를 <code>beforeId</code>로 보냅니다.</li>
              </ul>
            </div>
          </Section>

          <Section id="close" title="취소·청산" lede="주식 미체결 주문은 DELETE, ETF·선물·옵션 포지션은 close 엔드포인트로 정리합니다. 둘 다 TRADE 키와 Idempotency-Key가 필요합니다.">
            <div className="nx-docs-two-col">
              <CodeBlock title="주식 주문 취소" code={cancelCurl} />
              <CodeBlock title="포지션 청산" code={closeCurl} />
            </div>
            <p className="nx-docs-note-inline">청산 수량은 1~100이면서 보유 수량 이하여야 합니다. 이미 체결된 주식 주문은 취소할 수 없습니다. 두 작업 모두 성공 시 <code>200</code>과 요청 결과를 반환합니다.</p>
          </Section>

          <Section id="idempotency" title="중복 방지" lede="주문·취소·청산은 네트워크 오류로 다시 보내도 한 번만 실행되어야 합니다. Idempotency-Key가 그 기준입니다.">
            <ol className="nx-docs-steps is-compact">
              <li>새 요청의 키와 본문을 로컬에 먼저 기록합니다.</li>
              <li>시간 초과·연결 끊김·5xx가 나면 <strong>같은 키와 같은 본문</strong>으로 재시도합니다.</li>
              <li>성공한 요청은 최소 7일 동안 원래 응답을 돌려줍니다. 서버 재시작이나 동시 요청에도 주문을 다시 실행하지 않습니다.</li>
              <li>같은 키로 다른 본문이나 다른 작업을 보내면 <code>409 CONFLICT</code>입니다.</li>
            </ol>
            <div className="nx-docs-two-col">
              <CodeBlock title="권장 형식" code={`Idempotency-Key: 6f9d2c1a-4a0e-4b77-9f5e-2c4d8f0b9a11`} />
              <CodeBlock title="규칙" code={`영문·숫자·점·밑줄·하이픈 1~64자\n계정별로 구분 · 새 주문마다 새 키`} />
            </div>
            <p className="nx-docs-note-inline">실패하여 롤백한 요청은 완료 결과를 남기지 않습니다. <code>/requests/&#123;requestId&#125;</code>의 <code>404</code>는 아직 처리 중일 때도 나올 수 있으므로 미실행으로 단정하지 마세요. 재시도 응답의 계좌는 처음 완료된 시점의 값입니다. 현재 상태는 <code>/account</code> 또는 <code>/orders/&#123;orderId&#125;</code>로 확인합니다.</p>
          </Section>

          <Section id="errors" title="오류·호출 한도" lede="오류는 항상 { code, message } 형식입니다. message는 사람이 읽는 설명이며 분기 기준으로 쓰지 마세요.">
            <div className="nx-docs-table-wrap">
              <table className="nx-docs-table" aria-label="오류 코드">
                <thead>
                  <tr><th scope="col">상태</th><th scope="col">코드</th><th scope="col">의미</th></tr>
                </thead>
                <tbody>
                  {ERROR_ROWS.map(([status, code, meaning]) => (
                    <tr key={status}>
                      <td className="nx-num" data-label="상태">{status}</td>
                      <td data-label="코드">{code === "—" ? "—" : <code>{code}</code>}</td>
                      <td data-label="의미">{meaning}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="nx-docs-callout">
              <strong>호출 한도</strong>
              <p>서버 인스턴스별로 IP당 분당 120회, 계정당 분당 120회이며, 거래 작업은 그중 분당 30회입니다. 키를 여러 개 만들어도 계정 한도는 공유합니다.</p>
              <p>IP는 서버가 확인한 원격 주소를 사용하며 임의의 <code>X-Forwarded-For</code>를 신뢰하지 않습니다. 리버스 프록시 뒤에서는 신뢰할 프록시 주소를 서버에 설정하지 않으면 클라이언트가 IP 한도를 공유할 수 있습니다.</p>
              <p><code>429</code> 응답의 <code>Retry-After</code> 초만큼 기다린 뒤 재시도하세요. 기존 시장의 주문 빈도 제한·가격제한폭·수수료·잔액·정지 규칙도 그대로 적용됩니다.</p>
            </div>
          </Section>

          <Section id="tools" title="명세와 도구" lede="OpenAPI 3.0.3 명세를 그대로 가져가 Postman·Insomnia·Swagger UI나 클라이언트 생성기에 넣을 수 있습니다.">
            <div className="nx-docs-tools">
              <div className="nx-docs-tool">
                <h3>명세 URL</h3>
                <code>{SPEC_URL}</code>
                <div className="nx-docs-tool-actions">
                  <a className="nx-btn is-small" href={SPEC_URL} target="_blank" rel="noreferrer">열기</a>
                  <CopyButton text={SPEC_URL} label="URL" />
                </div>
              </div>
              <div className="nx-docs-tool">
                <h3>인증 헤더</h3>
                <code>Authorization: Bearer gs_...</code>
                <p>시세 조회에는 필요 없습니다.</p>
              </div>
              <div className="nx-docs-tool">
                <h3>콘텐츠 형식</h3>
                <code>Content-Type: application/json</code>
                <p>주문·취소·청산 요청에 필요합니다.</p>
              </div>
            </div>
            <p className="nx-docs-note-inline">명세를 URL로 가져오면 서버 주소가 API 서버로 자동 설정됩니다. 파일로 내려받아 쓸 때는 서버 주소를 위 기준 URL로 지정하세요. 다른 환경에서 시험할 때는 <code>VITE_API_BASE_URL</code>로 프론트엔드의 API 주소를 바꿀 수 있습니다.</p>
          </Section>
        </div>
      </div>
    </div>
  );
}
