import { won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

/** 세션 가격 경로. 선과 면적, 전일 종가 기준선, 최고·최저 두 숫자만 둔다. */
export function ReferenceSeriesChart({ code }: { code: string }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const series = quote.series.length > 1 ? quote.series : [quote.open, quote.price];
  const values = [...series, quote.prevClose];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || Math.max(1, max * 0.001);
  const hi = max + span * 0.1;
  const lo = min - span * 0.1;
  const y = (value: number) => ((hi - value) / (hi - lo)) * 100;
  const step = 100 / (series.length - 1);
  const line = series.map((value, index) => `${(index * step).toFixed(2)},${y(value).toFixed(2)}`).join(" ");
  const rate = sessionRate(quote);
  const tone = rate > 0 ? "up" : rate < 0 ? "down" : "flat";

  return (
    <figure className={`chart is-${tone}`}>
      <div className="chart-plot">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <line
            className="chart-prevclose"
            x1="0"
            x2="100"
            y1={y(quote.prevClose)}
            y2={y(quote.prevClose)}
            vectorEffect="non-scaling-stroke"
          />
          <polygon className="chart-area" points={`0,100 ${line} 100,100`} />
          <polyline className="chart-line" points={line} vectorEffect="non-scaling-stroke" />
        </svg>
      </div>
      <figcaption className="chart-caption">
        <span>
          저가 <span className="num">{won(min)}</span>
        </span>
        <span>
          고가 <span className="num">{won(max)}</span>
        </span>
      </figcaption>
    </figure>
  );
}
