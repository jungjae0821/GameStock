import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../lib/api";
import { fetchFandoms, fetchFeed, useEngagement, type Fandom } from "../../lib/engagement";
import { LISTING_BY_CODE } from "../../market/universe";
import { compactWon, rate, won } from "../../market/format";
import { GameIcon } from "../../components/GameIcon";
import { Link } from "../../components/Link";
import { AiBadge } from "./AiBadge";

type Load<T> = { data: T | null; state: "loading" | "ready" | "error" | "missing" };

/**
 * 주기적으로 다시 읽는 공개 데이터. 한 번 받은 뒤에는 오류가 나도 마지막 값을 둔다.
 * 서버가 아직 이 기능을 모르면(404) "missing"이라 구획을 그리지 않는다.
 */
function usePolledPublic<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs: number): Load<T> {
  const [result, setResult] = useState<Load<T>>({ data: null, state: "loading" });
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const run = () => loadRef.current(controller.signal)
      .then((data) => { if (active) setResult({ data, state: "ready" }); })
      .catch((error) => {
        if (!active || controller.signal.aborted) return;
        setResult((current) => current.state === "ready" ? current
          : { data: null, state: error instanceof ApiError && error.status === 404 ? "missing" : "error" });
      });
    void run();
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void run(); }, intervalMs);
    return () => { active = false; controller.abort(); window.clearInterval(timer); };
  }, [intervalMs]);
  return result;
}

const useFeed = (intervalMs: number) => usePolledPublic(fetchFeed, intervalMs);

const ago = (iso: string, now: number) => {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  return seconds < 60 ? "방금" : `${Math.floor(seconds / 60)}분 전`;
};

export function HomeLiveFeed() {
  const { data: items, state } = useFeed(15_000);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 30_000); return () => window.clearInterval(timer); }, []);
  if (state === "missing") return null;
  return (
    <section className="nx-panel nx-live-feed" aria-labelledby="nx-live-feed-title">
      <header className="nx-panel-head">
        <h2 id="nx-live-feed-title" className="nx-panel-title">실시간 체결 소식</h2>
        <span className="nx-muted">최근 30분</span>
      </header>
      {state === "loading" ? (
        <div className="nx-skeleton" aria-label="체결 소식을 불러오는 중"><span /><span /><span /></div>
      ) : !items ? (
        <p className="nx-note is-error" role="status">체결 소식을 불러오지 못했어요. 15초 뒤에 다시 불러와요.</p>
      ) : items.length === 0 ? (
        <p className="nx-muted nx-live-empty">아직 조용해요. 3천만원이 넘는 체결이나, 포트폴리오를 공개한 사람·AI 트레이더의 체결이 여기에 떠요.</p>
      ) : (
        <ol className="nx-live-list">
          {items.slice(0, 8).map((item) => (
            <li key={`${item.id}-${item.side}`} className={item.whale ? "is-whale" : undefined}>
              <GameIcon code={item.stockCode} size="table" />
              <p>
                <strong>{item.nickname ?? "익명"}</strong><AiBadge nickname={item.nickname} />님이 {LISTING_BY_CODE[item.stockCode]?.name ?? item.name}{" "}
                <span className="nx-num">{item.quantity.toLocaleString("ko-KR")}주</span>를{" "}
                <span className={item.side === "BUY" ? "is-up" : "is-down"}>{item.side === "BUY" ? "샀어요" : "팔았어요"}</span>
                {item.whale && <span className="nx-whale-badge">고래</span>}
              </p>
              <span className="nx-num nx-live-amount">{compactWon(item.amount)}</span>
              <span className="nx-muted nx-live-time">{ago(item.at, now)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

export function HomeFandoms() {
  const engagement = useEngagement();
  const { data: fandoms, state } = usePolledPublic<Fandom[]>(fetchFandoms, 60_000);
  if (state === "missing") return null;
  const top = (fandoms ?? []).slice(0, 5);
  const most = Math.max(1, ...top.map((fandom) => fandom.fans));
  const mine = engagement?.fandomCode;
  return (
    <section className="nx-panel nx-home-fandoms" aria-labelledby="nx-home-fandom-title">
      <header className="nx-panel-head">
        <h2 id="nx-home-fandom-title" className="nx-panel-title">팬덤 대항전</h2>
        <Link to="/ranking" className="nx-more" onClick={() => { try { localStorage.setItem("gamestock-ranking-tab", "fandom"); } catch { /* 탭 기억은 선택 */ } }}>전체 순위</Link>
      </header>
      {state === "loading" ? (
        <div className="nx-skeleton" aria-label="팬덤 순위를 불러오는 중"><span /><span /><span /></div>
      ) : !fandoms ? (
        <p className="nx-note is-error" role="status">팬덤 순위를 불러오지 못했어요. 1분 뒤에 다시 불러와요.</p>
      ) : top.every((fandom) => fandom.fans === 0) ? (
        <p className="nx-muted nx-live-empty">아직 주력 게임을 고른 사람이 없어요. 설정에서 가장 먼저 골라 보세요.</p>
      ) : (
      <ol className="nx-fandom-mini">
        {top.map((fandom) => (
          <li key={fandom.stockCode} className={fandom.stockCode === mine ? "is-mine" : undefined}>
            <span className="nx-num nx-fandom-rank">{fandom.rank}</span>
            <GameIcon code={fandom.stockCode} size="table" />
            <span className="nx-fandom-mini-name">{LISTING_BY_CODE[fandom.stockCode]?.name ?? fandom.name}</span>
            <span className="nx-fandom-bar" aria-hidden="true"><span style={{ width: `${(fandom.fans / most) * 100}%` }} /></span>
            <span className="nx-num nx-fandom-count">{fandom.fans.toLocaleString("ko-KR")}명</span>
          </li>
        ))}
      </ol>
      )}
      {engagement && !mine && <p className="nx-muted nx-fandom-cta"><Link to="/settings">주력 게임 고르기</Link>로 내 게임 순위를 올려 보세요.</p>}
    </section>
  );
}

/** 주문이 체결된 직후, 그 주문 앞뒤로 가격이 얼마나 움직였는지. 주문 티켓이 부른다. */
export function announceOrderImpact(code: string, before: number, after: number, side: "buy" | "sell") {
  if (!before || !after || before === after) return;
  window.dispatchEvent(new CustomEvent("gamestock:order-impact", { detail: { code, before, after, side } }));
}

export function OrderImpactToast() {
  const [impact, setImpact] = useState<{ code: string; before: number; after: number; side: "buy" | "sell" } | null>(null);
  useEffect(() => {
    const show = (event: Event) => setImpact((event as CustomEvent).detail);
    window.addEventListener("gamestock:order-impact", show);
    return () => window.removeEventListener("gamestock:order-impact", show);
  }, []);
  useEffect(() => {
    if (!impact) return;
    const timer = window.setTimeout(() => setImpact(null), 4_500);
    return () => window.clearTimeout(timer);
  }, [impact]);
  if (!impact) return null;
  const ratio = (impact.after - impact.before) / impact.before;
  return (
    <div className="nx-alert-toast is-impact" role="status" aria-live="polite">
      <p className="nx-alert-body">
        {/* 그 사이 다른 체결도 섞이므로 원인을 단정하지 않고, 주문 직후 1.5초 동안의 변화만 말한다. */}
        <strong className={ratio > 0 ? "is-up" : "is-down"}>{impact.side === "buy" ? "매수" : "매도"} 체결 뒤 1.5초 동안 {rate(ratio)}</strong>
        <span>{LISTING_BY_CODE[impact.code]?.name ?? impact.code} <span className="nx-num">{won(impact.before)} → {won(impact.after)}</span></span>
      </p>
    </div>
  );
}
