import { indexValue, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { sessionRate } from "../market/selectors";

/**
 * 세션 가격 경로를 전일 종가 기준선과 함께 보여 준다.
 * 축 눈금과 현재가를 직접 라벨링해 범례 조회를 없앴다.
 */
export function SeriesChart({ code, tone }: { code: string; tone?: "up" | "down" | "flat" }) {
  const snapshot = useMarket();
  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const series = quote.series.length > 1 ? quote.series : [quote.open, quote.price];
  const values = [...series, quote.prevClose];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || Math.max(1, max * 0.001);
  const hi = max + span * 0.08;
  const lo = min - span * 0.08;
  const range = hi - lo;
  const y = (value: number) => ((hi - value) / range) * 100;
  const step = 100 / (series.length - 1);
  const line = series.map((value, index) => `${(index * step).toFixed(2)},${y(value).toFixed(2)}`).join(" ");
  const area = `0,100 ${line} 100,100`;
  const rate = sessionRate(quote);
  const fallbackTone = rate > 0 ? "up" : rate < 0 ? "down" : "flat";
  /* 전일 종가 눈금이 위·아래 눈금과 겹치면 라벨을 생략한다. */
  const prevY = y(quote.prevClose);
  const showPrev = Math.abs(prevY - y(max)) > 9 && Math.abs(prevY - y(min)) > 9;

  return (
    <figure className={`chart is-${tone ?? fallbackTone}`}>
      <div className="chart-plot">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <line className="chart-grid" x1="0" x2="100" y1={y(max)} y2={y(max)} vectorEffect="non-scaling-stroke" />
          <line className="chart-grid" x1="0" x2="100" y1={y(min)} y2={y(min)} vectorEffect="non-scaling-stroke" />
          <line
            className="chart-prevclose"
            x1="0"
            x2="100"
            y1={y(quote.prevClose)}
            y2={y(quote.prevClose)}
            vectorEffect="non-scaling-stroke"
          />
          <polygon className="chart-area" points={area} />
          <polyline className="chart-line" points={line} vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="chart-tick" style={{ top: `${y(max)}%` }}>
          {won(max)}
        </span>
        {showPrev && (
          <span className="chart-tick is-prev" style={{ top: `${prevY}%` }}>
            전일 {won(quote.prevClose)}
          </span>
        )}
        <span className="chart-tick is-bottom" style={{ top: `${y(min)}%` }}>
          {won(min)}
        </span>
        <span className="chart-current" style={{ top: `${y(quote.price)}%` }}>
          {won(quote.price)}
        </span>
      </div>
      <figcaption className="chart-axis">
        <span>세션 시작</span>
        <span className="num">{series.length}개 시세</span>
        <span>현재</span>
      </figcaption>
      <table className="vh">
        <caption>{`${code} 세션 시세 요약`}</caption>
        <tbody>
          <tr>
            <th scope="row">세션 최고</th>
            <td>{won(max)}</td>
          </tr>
          <tr>
            <th scope="row">세션 최저</th>
            <td>{won(min)}</td>
          </tr>
          <tr>
            <th scope="row">전일 종가</th>
            <td>{won(quote.prevClose)}</td>
          </tr>
          <tr>
            <th scope="row">현재가</th>
            <td>{won(quote.price)}</td>
          </tr>
          <tr>
            <th scope="row">세션 등락률</th>
            <td>{indexValue(rate * 100)}%</td>
          </tr>
        </tbody>
      </table>
    </figure>
  );
}
