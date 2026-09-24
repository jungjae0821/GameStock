import { Chip } from "./Chip";
import { Panel } from "./Panel";
import { SeriesChart } from "./SeriesChart";
import { compactWon, shares, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate, strengthRatio } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

export function StockChartPanel({ code }: { code: string }) {
  const snapshot = useMarket();
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];

  if (!listing || !quote) return null;

  const ratio = sessionRate(quote);
  const tone: "up" | "down" | "flat" = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";

  return (
    <Panel id="detail-session" title="가격 차트" meta={`전일 종가 ${won(quote.prevClose)}`}>
      <dl className="stat-grid">
        <div><dt>시가</dt><dd className="num">{won(quote.open)}</dd></div>
        <div><dt>고가</dt><dd className="num">{won(quote.high)}</dd></div>
        <div><dt>저가</dt><dd className="num">{won(quote.low)}</dd></div>
        <div><dt>상한가</dt><dd className="num">{won(quote.limitUp)}</dd></div>
        <div><dt>거래량</dt><dd className="num">{shares(quote.volume)}</dd></div>
        <div><dt>거래대금</dt><dd className="num">{compactWon(quote.volume * quote.price)}</dd></div>
        <div><dt>하한가</dt><dd className="num">{won(quote.limitDown)}</dd></div>
        <div><dt>체결강도</dt><dd><Chip tone={strengthRatio(quote) >= 1 ? "up" : "down"}><span className="num">{Math.round(strengthRatio(quote) * 100)}%</span></Chip></dd></div>
      </dl>
      <SeriesChart code={code} tone={tone} />
    </Panel>
  );
}
