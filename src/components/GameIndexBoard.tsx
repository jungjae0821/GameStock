import { Panel } from "./Panel";
import { Link } from "./Link";
import { Chip, rateTone } from "./Chip";
import { rate, shares, trendArrow } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { LISTING_BY_CODE } from "../market/universe";
import { sessionRate } from "../market/selectors";

type Sector = {
  name: string;
  codes: string[];
  rate: number;
  volume: number;
  leader: string;
};

/** 장르별 종목을 묶어 GameStock만의 게임군 지수를 보여준다. */
export function GameIndexBoard() {
  const snapshot = useMarket();
  const sectors = new Map<string, string[]>();

  for (const code of snapshot.codes) {
    const listing = LISTING_BY_CODE[code];
    if (!listing) continue;
    const current = sectors.get(listing.genre) ?? [];
    current.push(code);
    sectors.set(listing.genre, current);
  }

  const rows: Sector[] = [...sectors.entries()]
    .map(([name, codes]) => {
      const volume = codes.reduce((sum, code) => sum + (snapshot.quotes[code]?.volume ?? 0), 0);
      const averageRate = codes.reduce((sum, code) => sum + sessionRate(snapshot.quotes[code]), 0) / codes.length;
      const leader = [...codes].sort((a, b) => (snapshot.quotes[b]?.volume ?? 0) - (snapshot.quotes[a]?.volume ?? 0))[0];
      return { name, codes, rate: averageRate, volume, leader };
    })
    .sort((a, b) => b.volume - a.volume)
    .slice(0, 6);

  return (
    <Panel id="game-index" title="게임군 지수" meta="장르별 평균 등락 · 거래량순">
      <div className="sector-grid">
        {rows.map((sector) => (
          <article className="sector-card" key={sector.name}>
            <div className="sector-head">
              <h3>{sector.name}</h3>
              <span className="sector-count num">{sector.codes.length}종목</span>
            </div>
            <div className="sector-value">
              <Chip tone={rateTone(sector.rate)}>
                <span aria-hidden="true">{trendArrow(sector.rate)}</span>
                <span className="num">{rate(sector.rate)}</span>
              </Chip>
              <span className="sector-volume num">{shares(sector.volume)}</span>
            </div>
            <p className="sector-leader">
              거래량 상위 <Link to={`/market/${sector.leader}`}>{LISTING_BY_CODE[sector.leader]?.name ?? sector.leader}</Link>
            </p>
          </article>
        ))}
      </div>
      {rows.length === 0 && <p className="empty is-inline">게임군 지수를 집계하는 중이야.</p>}
    </Panel>
  );
}
