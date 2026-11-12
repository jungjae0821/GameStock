import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { useNarrow, useWidth } from "../lib/useWidth";
import { rate, won } from "../market/format";
import { sessionRate } from "../market/selectors";
import type { Listing, Quote } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { useSteadySnapshot } from "../market/useSteadySnapshot";
import { MARKET_PATH } from "../router";

type Box = { x: number; y: number; w: number; h: number };

/** 시가총액 비중이 이보다 작아도 이 만큼의 면적은 준다. 그보다 작으면 등락률 글자가 칸 밖으로 넘친다. */
const MIN_SHARE = 0.025;
/** 지도가 좁으면 비율만으로는 칸이 너무 작아진다. 어느 칸이든 이 크기의 정사각형만큼 면적은 갖게 한다. */
const MIN_TILE = 92;

/** 가로세로 비가 가장 나쁜 칸. 줄에 칸을 더할지 정하는 기준이다. */
function worst(row: number[], side: number): number {
  const sum = row.reduce((total, area) => total + area, 0);
  return Math.max((side * side * Math.max(...row)) / (sum * sum), (sum * sum) / (side * side * Math.min(...row)));
}

/** 정사각형에 가까운 칸으로 나누는 트리맵(squarified). 면적이 큰 순서로 받아야 한다. */
function squarify(areas: number[], frame: Box): Box[] {
  const boxes: Box[] = [];
  let rest = { ...frame };
  let row: number[] = [];
  const place = () => {
    const sum = row.reduce((total, area) => total + area, 0);
    if (rest.w >= rest.h) {
      const width = sum / rest.h;
      let y = rest.y;
      for (const area of row) {
        boxes.push({ x: rest.x, y, w: width, h: area / width });
        y += area / width;
      }
      rest = { x: rest.x + width, y: rest.y, w: rest.w - width, h: rest.h };
    } else {
      const height = sum / rest.w;
      let x = rest.x;
      for (const area of row) {
        boxes.push({ x, y: rest.y, w: area / height, h: height });
        x += area / height;
      }
      rest = { x: rest.x, y: rest.y + height, w: rest.w, h: rest.h - height };
    }
    row = [];
  };
  for (let index = 0; index < areas.length;) {
    const side = Math.min(rest.w, rest.h);
    const next = [...row, areas[index]];
    if (row.length === 0 || worst(next, side) <= worst(row, side)) {
      row = next;
      index += 1;
    } else {
      place();
    }
  }
  if (row.length) place();
  return boxes;
}

/** 장르 이름 줄의 높이. 구역마다 칸 위에 한 줄씩 둔다. */
const THEME_HEAD = 26;
/** 칸 사이 이음새는 1px, 장르 구역 사이는 한 겹 더 띄운다. CSS의 .nx-heat 바깥 확장(-2px)·이름 줄 오른쪽 끝과 맞물린다. */
const GROUP_GAP = 1;
/** 같은 갈래인 세부 장르는 한 구역에 모은다. 한 종목뿐인 구역이 늘어나면 이름 줄만 많아진다. */
const THEME_OF: Record<string, string> = { "전략 RPG": "RPG" };

type Item = { code: string; quote: Quote; listing: Listing; weight: number };

/** 칸 면적은 시가총액, 색 농도는 등락 크기. 증시 맵처럼 장르(테마)별 구역으로 먼저 나누고 그 안을 다시 나눈다. */
function Heatmap() {
  const snapshot = useSteadySnapshot();
  const [frame, width] = useWidth<HTMLDivElement>();
  const height = useNarrow() ? 640 : 560;
  const listed = snapshot.codes
    .map((code) => ({ code, quote: snapshot.quotes[code], listing: LISTING_BY_CODE[code] }))
    .filter((item) => item.quote && item.listing);
  const total = listed.reduce((sum, item) => sum + (item.quote.marketCap ?? 0), 0) || 1;
  const minShare = width > 0 ? Math.max(MIN_SHARE, (MIN_TILE * MIN_TILE) / (width * height)) : MIN_SHARE;

  const themes = new Map<string, Item[]>();
  for (const item of listed) {
    const theme = THEME_OF[item.listing.genre] ?? item.listing.genre;
    const weight = Math.max(minShare, (item.quote.marketCap ?? 0) / total);
    themes.set(theme, [...(themes.get(theme) ?? []), { ...item, weight }]);
  }
  const groups = [...themes].map(([theme, items]) => ({
    theme,
    items: items.sort((a, b) => b.weight - a.weight),
    weight: items.reduce((sum, item) => sum + item.weight, 0),
  })).sort((a, b) => b.weight - a.weight);
  const weightSum = groups.reduce((sum, group) => sum + group.weight, 0);
  /* 오른쪽·아래 끝 구역의 이음새가 지도 밖으로 밀려 나가 잘리도록 그만큼 크게 잡는다. */
  const outer = { x: 0, y: 0, w: width + GROUP_GAP + 1, h: height + GROUP_GAP + 1 };
  const groupBoxes = width > 0 ? squarify(groups.map((group) => (group.weight / weightSum) * outer.w * outer.h), outer) : [];

  return (
    <div className="nx-heat-frame" ref={frame} style={{ height }}>
      {listed.length === 0 && <p className="nx-heat-status" role="status">시세를 불러오고 있어요.</p>}
      <ul className="nx-heat" aria-label="장르별 종목 등락 지도. 칸 크기는 시가총액">
        {groupBoxes.length > 0 && groups.map((group, groupIndex) => {
          const area = groupBoxes[groupIndex];
          const inner = { x: 0, y: THEME_HEAD, w: area.w - GROUP_GAP, h: Math.max(0, area.h - GROUP_GAP - THEME_HEAD) };
          const boxes = squarify(group.items.map((item) => (item.weight / group.weight) * inner.w * inner.h), inner);
          return (
            <li key={group.theme} className="nx-heat-group" style={{ left: area.x, top: area.y, width: area.w, height: area.h }}>
              <h2 className="nx-heat-theme">{group.theme}</h2>
              <ul aria-label={group.theme}>
                {group.items.map(({ code, quote, listing }, index) => {
                  const box = boxes[index];
                  const ratio = sessionRate(quote);
                  // 칸에 찍히는 소수 둘째 자리 값으로 나눠야 "0.00%" 칸이 색을 띠지 않는다.
                  const percent = Number((ratio * 100).toFixed(2));
                  const size = Math.abs(percent);
                  const level = size === 0 ? 0 : size < 1 ? 1 : size < 3 ? 2 : size < 5 ? 3 : 4;
                  const tone = percent > 0 ? "up" : percent < 0 ? "down" : "flat";
                  return (
                    <li key={code} className="nx-heat-cell" style={{ left: box.x, top: box.y, width: box.w, height: box.h }}>
                      <Link to={MARKET_PATH(code)} className={`nx-heat-tile is-${tone} is-l${level}`} title={`${listing.name} ${rate(ratio)}`}
                        aria-label={`${listing.name} ${rate(ratio)} ${won(quote.price)}${quote.restriction ? ` ${quote.restriction.kind === "CIRCUIT_BREAKER" ? "서킷브레이커" : "VI"}` : ""}`}>
                        <GameIcon code={code} className="nx-heat-logo" />
                        <span className="nx-heat-name">{listing.name}</span>
                        <span className="nx-heat-code" aria-hidden="true">{code}</span>
                        <span className="nx-heat-rate nx-num">{rate(ratio)}</span>
                        <span className="nx-heat-price nx-num">{won(quote.price)}</span>
                        {quote.restriction && <span className="nx-heat-guard">{quote.restriction.kind === "CIRCUIT_BREAKER" ? "서킷브레이커" : "VI"}</span>}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function HeatmapBoard() {
  return (
    <div className="nx-screen nx-heatmap-screen">
      <section className="nx-panel nx-heatmap-panel" aria-labelledby="nx-heatmap-title">
        <h1 id="nx-heatmap-title" className="nx-panel-title">히트맵</h1>
        <Heatmap />
      </section>
    </div>
  );
}
