import { Chip, rateTone } from "./Chip";
import { Link } from "./Link";
import { PriceCell } from "./PriceCell";
import { Sparkline } from "./Sparkline";
import { compactWon, indexValue, rate, shares, signedNumber, trendArrow } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { busiest, sessionRate, turnover } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

/** 시장 전체를 한 줄로 읽는 지표 띠. 모든 값은 상장 종목에서 계산한다. */
export function IndexStrip() {
  const snapshot = useMarket();
  const index = snapshot.index;
  const change = index.value - index.prevClose;
  const ratio = change / index.prevClose;
  const sparkTone: "up" | "down" | "flat" = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";

  let ups = 0;
  let downs = 0;
  let volume = 0;
  for (const code of snapshot.codes) {
    const quote = snapshot.quotes[code];
    if (!quote) continue;
    volume += quote.volume;
    const itemRate = sessionRate(quote);
    if (itemRate > 0) ups += 1;
    else if (itemRate < 0) downs += 1;
  }
  const breadth = ups + downs || 1;

  const busiestCode = busiest(snapshot);
  const busiestQuote = busiestCode ? snapshot.quotes[busiestCode] : undefined;

  return (
    <section className="index-strip" aria-label="시장 지표">
      <article className="index-tile">
        <div className="index-label">
          게임주 지수
          <span className="index-note">세션 시작 1,000</span>
        </div>
        <p className="index-value">
          <PriceCell value={index.value} format={indexValue} />
        </p>
        <div className="index-foot">
          <Chip tone={rateTone(ratio)}>
            <span aria-hidden="true">{trendArrow(change)}</span>
            <span className="num">{signedNumber(change)}</span>
            <span className="num">{rate(ratio)}</span>
          </Chip>
          <Sparkline series={index.series} prevClose={index.prevClose} tone={sparkTone} width={116} height={26} />
        </div>
      </article>

      <article className="index-tile">
        <div className="index-label">세션 거래대금</div>
        <p className="index-value">
          <PriceCell value={turnover(snapshot)} format={compactWon} />
        </p>
        <div className="index-foot">
          <span className="index-sub num">
            {snapshot.codes.length}종목 · {shares(volume)}
          </span>
        </div>
      </article>

      <article className="index-tile">
        <div className="index-label">상승 · 하락</div>
        <p className="index-value">
          <span className="is-up num">{ups}</span>
          <span className="index-divider">/</span>
          <span className="is-down num">{downs}</span>
        </p>
        <div className="index-foot">
          <div className="bar breadth" role="img" aria-label={`상승 ${ups}종목, 하락 ${downs}종목`}>
            <div className="bar-fill is-up" style={{ width: `${(ups / breadth) * 100}%` }} />
          </div>
        </div>
      </article>

      <article className="index-tile">
        <div className="index-label">최다 거래</div>
        {busiestCode && busiestQuote ? (
          <>
            <p className="index-value is-name">
              <Link to={`/market/${busiestCode}`}>{LISTING_BY_CODE[busiestCode]?.name ?? busiestCode}</Link>
            </p>
            <div className="index-foot">
              <Chip tone={rateTone(sessionRate(busiestQuote))}>
                <span aria-hidden="true">{trendArrow(sessionRate(busiestQuote))}</span>
                <span className="num">{rate(sessionRate(busiestQuote))}</span>
              </Chip>
              <span className="index-sub num">{shares(busiestQuote.volume)}</span>
            </div>
          </>
        ) : (
          <div className="index-foot">집계 중</div>
        )}
      </article>
    </section>
  );
}
