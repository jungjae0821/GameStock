import { Chip } from "./Chip";
import { Delta } from "./Delta";
import { Panel } from "./Panel";
import { PriceCell } from "./PriceCell";
import { rate, won } from "../market/format";
import { INITIAL_CASH } from "../market/engine";
import { useMarket } from "../market/MarketProvider";
import { totals } from "../market/selectors";

export function PortfolioStrip() {
  const snapshot = useMarket();
  const money = totals(snapshot);
  const tone = money.pnl > 0 ? "up" : money.pnl < 0 ? "down" : "flat";

  return (
    <Panel
      id="portfolio-title"
      title="자산 현황"
      meta={`시작 자본 ${won(INITIAL_CASH)}`}
    >
      <dl className="portfolio-grid">
        <div className="portfolio-cell">
          <dt>보유 현금</dt>
          <dd>
            <PriceCell className="num portfolio-value" value={money.cash} />
          </dd>
        </div>
        <div className="portfolio-cell">
          <dt>주식 평가액</dt>
          <dd>
            <PriceCell className="num portfolio-value" value={money.stockValue} />
          </dd>
        </div>
        <div className="portfolio-cell">
          <dt>총 자산</dt>
          <dd>
            <PriceCell className="num portfolio-value" value={money.total} />
          </dd>
        </div>
        <div className="portfolio-cell">
          <dt>평가 손익</dt>
          <dd>
            <Chip tone={tone}>
              <Delta change={money.pnl} ratio={money.pnlRate} />
            </Chip>
          </dd>
          <p className="portfolio-note num">수익률 {rate(money.pnlRate)}</p>
        </div>
      </dl>
    </Panel>
  );
}
