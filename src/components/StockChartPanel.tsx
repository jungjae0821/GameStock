import { SeriesChart } from "./SeriesChart";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

export function StockChartPanel({ code }: { code: string }) {
  const snapshot = useMarket();
  const listing = LISTING_BY_CODE[code];
  const quote = snapshot.quotes[code];

  if (!listing || !quote) return null;

  const ratio = sessionRate(quote);
  const tone: "up" | "down" | "flat" = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";

  return (
    <section className="stock-chart-card" aria-label={`${listing.name} 가격 차트`}>
      <SeriesChart code={code} tone={tone} />
    </section>
  );
}
