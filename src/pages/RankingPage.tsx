import { useEffect, useRef, useState } from "react";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";

type RankingEntry = {
  rank: number;
  nickname: string;
  totalAsset: number;
  changePercent: number;
};

const medalByRank: Record<number, { emoji: string; label: string; className: string }> = {
  1: { emoji: "🥇", label: "금메달", className: "is-gold" },
  2: { emoji: "🥈", label: "은메달", className: "is-silver" },
  3: { emoji: "🥉", label: "동메달", className: "is-bronze" },
};

export function RankingPage() {
  const [ranking, setRanking] = useState<RankingEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const hasData = useRef(false);

  useEffect(() => {
    let active = true;

    const loadRanking = async (initial = false) => {
      // 1초 주기 요청이 느린 네트워크에서 겹치지 않도록 한 번에 하나만 보낸다.
      if (!active || inFlight.current) return;
      inFlight.current = true;
      if (initial) setLoading(true);
      try {
        const entries = await apiFetch<RankingEntry[]>("/api/ranking");
        if (!active) return;
        // 기존 행을 유지한 채 데이터만 교체하므로 갱신 때 표가 깜빡이지 않는다.
        setRanking(entries);
        hasData.current = true;
        setError("");
      } catch {
        if (active && !hasData.current) {
          setError("랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      } finally {
        inFlight.current = false;
        // StrictMode에서 초기 effect가 한 번 정리돼도, 다음 주기에서 로딩 표시가 남지 않게 한다.
        if (active) setLoading(false);
      }
    };

    void loadRanking(true);
    const refreshId = window.setInterval(() => void loadRanking(), 1000);
    return () => {
      active = false;
      window.clearInterval(refreshId);
    };
  }, []);

  return (
    <div className="page-stack ranking-page">
      <div className="page-title">
        <h1>투자 랭킹</h1>
        <span className="page-meta num">{ranking.length}명 · 총 자산 기준</span>
      </div>

      <Panel id="ranking-board" title="투자자 순위" meta="봇 제외 · 실시간">
        {loading ? (
          <p className="empty is-inline">랭킹을 불러오는 중…</p>
        ) : error ? (
          <p className="empty is-inline">{error}</p>
        ) : ranking.length === 0 ? (
          <p className="empty is-inline">아직 랭킹에 참여한 사용자가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="ranking-table">
              <caption className="sr-only">투자자 랭킹</caption>
              <colgroup>
                <col className="ranking-col-rank" />
                <col className="ranking-col-name" />
                <col className="ranking-col-change" />
                <col className="ranking-col-asset" />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col" className="ranking-col-rank">순위</th>
                  <th scope="col" className="ranking-col-name">닉네임</th>
                  <th scope="col" className="ranking-col-change">수익률</th>
                  <th scope="col" className="ranking-col-asset">총 자산</th>
                </tr>
              </thead>
              <tbody>
                {ranking.map((entry) => (
                  <tr key={`${entry.rank}-${entry.nickname}`}>
                    <th scope="row" className="ranking-page-rank num">
                      <span className="ranking-rank-content">
                        {medalByRank[entry.rank] && (
                          <span
                            className={`ranking-medal ${medalByRank[entry.rank].className}`}
                            role="img"
                            aria-label={medalByRank[entry.rank].label}
                          >
                            {medalByRank[entry.rank].emoji}
                          </span>
                        )}
                        <span>{entry.rank}</span>
                      </span>
                    </th>
                    <td className="ranking-page-name">{entry.nickname}</td>
                    <td className={`ranking-page-change num${entry.changePercent > 0 ? " is-up" : entry.changePercent < 0 ? " is-down" : " is-flat"}`}>
                      {entry.changePercent >= 0 ? "+" : ""}{entry.changePercent.toFixed(2)}%
                    </td>
                    <td className="ranking-page-asset num">{Math.round(entry.totalAsset).toLocaleString("ko-KR")}원</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </div>
  );
}
