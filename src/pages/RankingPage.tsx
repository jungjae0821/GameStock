import { useEffect, useState } from "react";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";

type RankingEntry = {
  rank: number;
  nickname: string;
  totalAsset: number;
  changePercent: number;
};

export function RankingPage() {
  const [ranking, setRanking] = useState<RankingEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    void apiFetch<RankingEntry[]>("/api/ranking")
      .then((entries) => {
        if (active) setRanking(entries);
      })
      .catch(() => {
        if (active) {
          setRanking([]);
          setError("랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
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
              <thead>
                <tr>
                  <th scope="col">순위</th>
                  <th scope="col">닉네임</th>
                  <th scope="col">수익률</th>
                  <th scope="col">총 자산</th>
                </tr>
              </thead>
              <tbody>
                {ranking.map((entry) => (
                  <tr key={`${entry.rank}-${entry.nickname}`}>
                    <th scope="row" className="ranking-page-rank num">{entry.rank}</th>
                    <td className="ranking-page-name">{entry.nickname}</td>
                    <td className={`ranking-page-change num${entry.changePercent < 0 ? " is-down" : ""}`}>
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
