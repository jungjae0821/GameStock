import { Delta } from "./Delta";
import { PriceCell } from "./PriceCell";
import { Sparkline } from "./Sparkline";
import { indexValue, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { totals } from "../market/selectors";

/** 홈 맨 위. 지수 하나와 내 자산 하나, 그게 전부다. */
export function MarketHead() {
  const snapshot = useMarket();
  const index = snapshot.index;
  const change = index.value - index.prevClose;
  const ratio = change / index.prevClose;
  const tone = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
  const money = totals(snapshot);

  return (
    <section className="head" aria-label="시장 요약">
      <div>
        <p className="head-label">게임주 지수</p>
        <p className="head-value">
          <PriceCell value={index.value} format={indexValue} />
        </p>
        <p className="head-sub">
          <Delta change={change} ratio={ratio} showAmount={false} />
          <Sparkline series={index.series} prevClose={index.prevClose} tone={tone} width={120} height={28} />
        </p>
      </div>
      <div className="head-me">
        <p className="head-label">내 자산</p>
        <p className="head-value is-small">
          <PriceCell value={money.total} format={won} />
        </p>
        <p className="head-sub">
          <Delta change={money.pnl} ratio={money.pnlRate} />
        </p>
      </div>
    </section>
  );
}
