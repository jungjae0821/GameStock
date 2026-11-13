import { useEffect, useState, type ReactNode } from "react";
import { useAuthUser } from "../../lib/auth";
import { RankingAvatar } from "../../components/RankingAvatar";
import { titleInkStyle } from "../../lib/titles";
import {
  formatCountdown, formatWeekDay, RANKING_CACHE_KEY, RANKING_TAB_KEY, readRankingCache, readTab, readWealthCache, readWeeklyCache,
  usePolledRanking, WEALTH_RANKING_CACHE_KEY, WEEKLY_RANKING_CACHE_KEY,
  type RankingEntry, type RankingTab, type WealthEntry, type WeeklyRanking,
} from "./rankingData";
import { AnimatedNav } from "../AnimatedNav";
import { FandomBoard, ProfileButton, PublicProfileDialog, SeasonBoard } from "../engagement/RankingBoards";
import { usePublicNicknames } from "../../lib/engagement";

type Row = Omit<RankingEntry, "changePercent">;

const formatChange = (percent: number) => `${percent >= 0 ? "+" : ""}${percent.toFixed(2)}%`;
const tone = (percent: number) => (percent > 0 ? "is-up" : percent < 0 ? "is-down" : "");
const MEDALS: Record<number, string> = { 1: "1위 금메달", 2: "2위 은메달", 3: "3위 동메달" };

/**
 * 한 줄. 가운데 칸은 기본으로 수익률이고, 부자 랭킹처럼 다른 걸 보여 줄 때는 middle로 바꾼다.
 * 마지막 칸은 기본으로 총 자산이다.
 */
function RankRow({ entry, percent = 0, mine = false, middle, value, grade }: {
  entry: Omit<Row, "totalAsset"> & { totalAsset?: number }; percent?: number; mine?: boolean; middle?: ReactNode; value?: number; grade?: string | null;
}) {
  const publicNames = usePublicNicknames();
  return (
    <tr className={`${mine ? "is-mine" : ""}${entry.rank <= 3 ? " is-top" : ""}`} aria-current={mine ? "true" : undefined}>
      <th scope="row" className="nx-rank-cell" aria-label={MEDALS[entry.rank]}>
        {MEDALS[entry.rank] ? (
          <RankingAvatar rank={entry.rank} src={entry.profileImageUrl} />
        ) : (
          <span className="nx-rank">{entry.rank}</span>
        )}
      </th>
      <td className="nx-player-cell">
        <span className="nx-player">
          <span className="nx-player-text">
            {entry.equippedTitle && (
              <span className={`nx-player-title has-title-ink${entry.equippedTitleWeekly ? " is-weekly" : ""}`} style={titleInkStyle(entry.equippedTitle)} title={entry.equippedTitle}>
                {entry.equippedTitle}
              </span>
            )}
            <ProfileButton nickname={entry.nickname} open={publicNames.has(entry.nickname)}>
              <span className="nx-player-name" title={entry.nickname}>
                {grade && <span className="nx-player-grade">{grade}</span>}
                {entry.nickname}{mine && <span className="nx-player-me">나</span>}
              </span>
            </ProfileButton>
          </span>
        </span>
      </td>
      {middle !== undefined ? <td>{middle}</td> : <td className={`nx-num ${tone(percent)}`}>{formatChange(percent)}</td>}
      <td className="nx-num">{Math.round(value ?? entry.totalAsset ?? 0).toLocaleString("ko-KR")}원</td>
    </tr>
  );
}

function Board({ label, changeLabel, valueLabel = "총 자산", children }: { label: string; changeLabel: string; valueLabel?: string; children: ReactNode }) {
  return (
    <div className="nx-rank-scroll">
      <table className="nx-grid-table nx-rank-table" aria-label={label}>
        <thead>
          <tr>
            <th scope="col" className="nx-rank-cell">순위</th>
            <th scope="col">투자자</th>
            <th scope="col">{changeLabel}</th>
            <th scope="col">{valueLabel}</th>
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function TotalBoard() {
  const { data, loading, error } = usePolledRanking<RankingEntry[]>("/api/ranking", RANKING_CACHE_KEY, (() => {
    const cached = readRankingCache();
    return cached.length > 0 ? cached : null;
  })());
  if (loading) return <div className="nx-skeleton nx-rank-loading" aria-label="랭킹을 불러오는 중"><span /><span /><span /><span /><span /></div>;
  if (error) return <p className="nx-note is-error">{error}</p>;
  // 서버가 직급 → 직급 수익률 순으로 매겨 보낸다. 운용 자산은 회사 돈이라 다시 정렬하지 않는다.
  const rows = data ?? [];
  if (rows.length === 0) return <p className="nx-empty-state">아직 랭킹에 참여한 사용자가 없어요.</p>;
  return (
    <>
      <Board label="트레이더 랭킹" changeLabel="직급 수익률" valueLabel="운용 자산">
        {rows.map((entry) => <RankRow key={`${entry.rank}-${entry.nickname}`} entry={entry} percent={entry.changePercent} grade={entry.gradeLabel} />)}
      </Board>
      <p className="nx-rank-note">높은 직급이 먼저이고, 같은 직급끼리는 지금 직급을 시작할 때 자산 대비 수익률로 매겨요. 운용 자산은 회사가 맡긴 돈이라 순위에 쓰지 않아요.</p>
    </>
  );
}

function WealthBoard() {
  const { data, loading, error } = usePolledRanking<WealthEntry[]>("/api/ranking/wealth", WEALTH_RANKING_CACHE_KEY, readWealthCache());
  if (loading) return <div className="nx-skeleton nx-rank-loading" aria-label="부자 랭킹을 불러오는 중"><span /><span /><span /><span /><span /></div>;
  if (error) return <p className="nx-note is-error">{error}</p>;
  const rows = data ?? [];
  if (rows.length === 0) return <p className="nx-empty-state">아직 지갑에 돈이 쌓인 사람이 없어요. 성과급과 미션 보상이 쌓이면 여기에 올라와요.</p>;
  return (
    <>
      <Board label="부자 랭킹" changeLabel="직급" valueLabel="내 지갑">
        {rows.map((entry) => (
          <RankRow key={`${entry.rank}-${entry.nickname}`} entry={entry} value={entry.personalCash}
            middle={<span className="nx-rank-grade">{entry.gradeLabel ?? "이전 대기"}</span>} />
        ))}
      </Board>
      <p className="nx-rank-note">성과급, 출석·미션·예측 보상처럼 내 지갑에 들어온 돈만 세요. 회사가 맡긴 운용계좌는 넣지 않아요.</p>
    </>
  );
}

function WeeklyBoard() {
  const { user } = useAuthUser();
  const { data, loading, error } = usePolledRanking<WeeklyRanking>("/api/titles/weekly-ranking", WEEKLY_RANKING_CACHE_KEY, readWeeklyCache());
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const tickId = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(tickId);
  }, []);

  if (loading) return <div className="nx-skeleton nx-rank-loading" aria-label="주간 랭킹을 불러오는 중"><span /><span /><span /><span /><span /></div>;
  if (error || !data) return <p className="nx-note is-error">{error || "주간 랭킹을 불러오지 못했어요."}</p>;
  const me = data.me;
  const meShown = me !== null && data.entries.some((entry) => entry.rank === me.rank);

  return (
    <>
      <dl className="nx-figures nx-week-figures">
        <div><dt>집계 기간</dt><dd className="nx-num">{formatWeekDay(data.weekStart)} ~ {formatWeekDay(data.weekEnd)}</dd></div>
        <div><dt>주간 정산까지</dt><dd className="nx-num">{formatCountdown(data.awardAt, now)}</dd></div>
        <div><dt>참여</dt><dd className="nx-num">{data.participants.toLocaleString("ko-KR")}명</dd></div>
        {me && <div><dt>내 순위</dt><dd className="nx-num">{me.rank}위 <span className={tone(me.weeklyReturnPercent)}>{formatChange(me.weeklyReturnPercent)}</span></dd></div>}
      </dl>
      {data.entries.length === 0 ? (
        <p className="nx-empty-state">이번 주에 거래한 사용자가 아직 없어요. 첫 거래로 1위에 올라 보세요.</p>
      ) : (
        <Board label="이번 주 수익률 랭킹" changeLabel="주간 수익률">
          {data.entries.map((entry) => (
            <RankRow key={`${entry.rank}-${entry.nickname}`} entry={entry} percent={entry.weeklyReturnPercent} mine={me !== null && entry.rank === me.rank} />
          ))}
          {me && !meShown && (
            <>
              <tr className="nx-rank-gap" aria-hidden="true"><td colSpan={4}><span /></td></tr>
              <RankRow entry={me} percent={me.weeklyReturnPercent} mine />
            </>
          )}
        </Board>
      )}
      <p className="nx-rank-note">
        {user && !me ? "이번 주에 한 번 이상 거래하면 순위에 들어가요. " : ""}
        이번 주에 1회 이상 거래한 사용자만 집계하며, 승진 충전·관리자 지급과 대출금은 수익률에서 제외해요.
      </p>
    </>
  );
}

export function NxRankingPage() {
  const [tab, setTab] = useState<RankingTab>(readTab);
  const select = (next: RankingTab) => {
    setTab(next);
    try { window.localStorage.setItem(RANKING_TAB_KEY, next); } catch { /* 저장소를 못 써도 탭은 바뀐다. */ }
  };

  return (
    <div className="nx-screen nx-ranking-screen">
      <section className="nx-panel nx-ranking-panel" aria-labelledby="nx-ranking-title">
        <h1 id="nx-ranking-title" className="nx-panel-title">랭킹</h1>
        <AnimatedNav as="div" fill activeKey={tab} className="nx-seg nx-ranking-tabs" role="tablist" aria-label="랭킹 종류">
          <button type="button" role="tab" id="nx-rank-total" aria-selected={tab === "total"} aria-controls="nx-rank-panel" className={tab === "total" ? "is-active" : undefined} onClick={() => select("total")}>트레이더</button>
          <button type="button" role="tab" id="nx-rank-wealth" aria-selected={tab === "wealth"} aria-controls="nx-rank-panel" className={tab === "wealth" ? "is-active" : undefined} onClick={() => select("wealth")}>부자</button>
          <button type="button" role="tab" id="nx-rank-weekly" aria-selected={tab === "weekly"} aria-controls="nx-rank-panel" className={tab === "weekly" ? "is-active" : undefined} onClick={() => select("weekly")}>이번 주</button>
          <button type="button" role="tab" id="nx-rank-season" aria-selected={tab === "season"} aria-controls="nx-rank-panel" className={tab === "season" ? "is-active" : undefined} onClick={() => select("season")}>시즌</button>
          <button type="button" role="tab" id="nx-rank-fandom" aria-selected={tab === "fandom"} aria-controls="nx-rank-panel" className={tab === "fandom" ? "is-active" : undefined} onClick={() => select("fandom")}>팬덤전</button>
        </AnimatedNav>
        <div id="nx-rank-panel" role="tabpanel" aria-labelledby={`nx-rank-${tab}`}>
          {tab === "total" ? <TotalBoard /> : tab === "wealth" ? <WealthBoard /> : tab === "weekly" ? <WeeklyBoard /> : tab === "season" ? <SeasonBoard /> : <FandomBoard />}
        </div>
        <PublicProfileDialog />
      </section>
    </div>
  );
}
