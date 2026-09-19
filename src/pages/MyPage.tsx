import { useEffect, useState } from "react";
import { onAuthStateChanged, signInWithPopup, type User } from "firebase/auth";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { LISTING_BY_CODE } from "../market/universe";
import { clock, serverTimestamp, won } from "../market/format";

type Profile = {
  nickname: string;
  email: string;
  profileCompleted: boolean;
  resetAvailable: boolean;
};

type Portfolio = {
  positions: Array<{
    stockCode: string;
    quantity: number;
    averagePrice: number;
    marketValue: number;
    profitLoss: number;
    profitLossPercent: number;
  }>;
};

type CompletedTrade = {
  id: number;
  side: string;
  stockCode: string;
  quantity: number;
  grossAmount: number;
  fee: number;
  netAmount: number;
  status: string;
  createdAt: string;
};

export function MyPage() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [completedTrades, setCompletedTrades] = useState<CompletedTrade[]>([]);
  const [nickname, setNickname] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  useEffect(() => {
    if (!user) {
      setProfile(null);
      setPortfolio(null);
      setCompletedTrades([]);
      return;
    }
    let active = true;
    setLoading(true);
    setMessage("");
    void Promise.all([
      apiFetch<Profile>("/api/profile"),
      apiFetch<Portfolio>("/api/portfolio"),
      apiFetch<CompletedTrade[]>("/api/settlements"),
    ])
      .then(([nextProfile, nextPortfolio, nextTrades]) => {
        if (!active) return;
        setProfile(nextProfile);
        setNickname(nextProfile.nickname ?? "");
        setPortfolio(nextPortfolio);
        setCompletedTrades(nextTrades.slice(0, 5));
      })
      .catch((error) => {
        if (active) setMessage(error instanceof Error ? error.message : "마이페이지를 불러오지 못했습니다.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [user]);

  // 평가 손익·평가액은 현재가를 따라가야 하므로 마이페이지에서만
  // 포트폴리오 값을 1초마다 갱신한다. 닉네임/프로필은 입력 중 값이
  // 덮어써지지 않도록 위의 초기 조회에서만 읽는다.
  useEffect(() => {
    if (!user) return;
    let active = true;
    const refreshPortfolio = async () => {
      try {
        const [nextPortfolio, nextTrades] = await Promise.all([
          apiFetch<Portfolio>("/api/portfolio"),
          apiFetch<CompletedTrade[]>("/api/settlements"),
        ]);
        if (active) {
          setPortfolio(nextPortfolio);
          setCompletedTrades(nextTrades.slice(0, 5));
        }
      } catch {
        // 일시적인 네트워크 오류가 있어도 마지막 손익을 유지한다.
      }
    };
    const timer = window.setInterval(() => void refreshPortfolio(), 1_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [user]);

  const saveNickname = async () => {
    const value = nickname.trim();
    if (value.length < 2 || value.length > 50) {
      setMessage("닉네임은 2~50자로 입력해 주세요.");
      return;
    }
    setSaving(true);
    setMessage("");
    try {
      const next = await apiFetch<Profile>("/api/profile", {
        method: "PATCH",
        body: JSON.stringify({ nickname: value }),
      });
      setProfile(next);
      setNickname(next.nickname);
      setMessage("닉네임을 저장했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "닉네임 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const resetAccount = async () => {
    if (!window.confirm("현금과 보유 주식, 거래 내역을 초기 상태로 되돌릴까요?")) return;
    setResetting(true);
    setMessage("");
    try {
      await apiFetch("/api/account/reset", { method: "DELETE" });
      const next = await apiFetch<Portfolio>("/api/portfolio");
      setPortfolio(next);
      setCompletedTrades([]);
      setProfile((current) => current ? { ...current, resetAvailable: false } : current);
      setMessage("계좌를 초기화했습니다. 시작 금액 1,000,000원으로 돌아갔습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "인생 리셋에 실패했습니다.");
    } finally {
      setResetting(false);
    }
  };

  if (!user) {
    return (
      <div className="page-stack mypage-page">
        <div className="page-title"><h1>마이페이지</h1></div>
        <Panel id="mypage-login" title="로그인 필요">
          <div className="empty is-inline">
            <p>닉네임과 보유 주식은 로그인 후 확인할 수 있습니다.</p>
            <button type="button" className="mypage-button" onClick={() => void signInWithPopup(firebaseAuth, googleProvider)}>
              Google 로그인
            </button>
          </div>
        </Panel>
      </div>
    );
  }

  const positions = portfolio?.positions ?? [];
  return (
    <div className="page-stack mypage-page">
      <div className="page-title">
        <h1>마이페이지</h1>
        <span className="page-meta">{profile?.email ?? user.email ?? "로그인 계정"}</span>
      </div>

      <Panel id="mypage-profile" title="프로필">
        {loading ? <p className="empty is-inline">불러오는 중…</p> : (
          <div className="mypage-form">
            <label htmlFor="mypage-nickname">닉네임</label>
            <div className="mypage-form-row">
              <input id="mypage-nickname" value={nickname} maxLength={50} onChange={(event) => setNickname(event.target.value)} />
              <button type="button" className="mypage-button" onClick={() => void saveNickname()} disabled={saving}>
                {saving ? "저장 중…" : "저장"}
              </button>
            </div>
            {message && <p className="mypage-message" role="status">{message}</p>}
          </div>
        )}
      </Panel>

      <Panel id="mypage-holdings" title="보유 주식" meta={`${positions.length}종목`} flush>
        {positions.length === 0 ? (
          <p className="empty is-inline">보유 중인 주식이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="quote-table mypage-holdings-table">
              <caption className="vh">종목별 보유 수량</caption>
              <thead><tr><th scope="col">종목</th><th scope="col">보유 수량</th><th scope="col">평균 단가</th><th scope="col">평가액</th><th scope="col">평가 손익</th></tr></thead>
              <tbody>
                {positions.map((position) => {
                  const listing = LISTING_BY_CODE[position.stockCode];
                  return (
                    <tr key={position.stockCode}>
                      <th scope="row" className="cell-name">{listing?.name ?? position.stockCode}</th>
                      <td className="num">{position.quantity.toLocaleString("ko-KR")}주</td>
                      <td className="num">{won(position.averagePrice)}</td>
                      <td className="num">{won(position.marketValue)}</td>
                      <td className={`num ${position.profitLoss < 0 ? "mypage-loss" : position.profitLoss > 0 ? "mypage-profit" : ""}`}>
                        {won(position.profitLoss)} ({position.profitLossPercent.toFixed(2)}%)
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel id="mypage-trades" title="최근 체결" meta="최근 5건" flush>
        {completedTrades.length === 0 ? (
          <p className="empty is-inline">체결된 거래가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="quote-table mypage-trades-table">
              <caption className="vh">최근 체결 내역</caption>
              <thead><tr><th scope="col">시각</th><th scope="col">종목</th><th scope="col">구분</th><th scope="col">수량</th><th scope="col">체결 금액</th></tr></thead>
              <tbody>
                {completedTrades.map((trade) => (
                  <tr key={trade.id}>
                    <td className="num">{Number.isNaN(serverTimestamp(trade.createdAt)) ? "-" : clock(serverTimestamp(trade.createdAt))}</td>
                    <th scope="row">{LISTING_BY_CODE[trade.stockCode]?.name ?? trade.stockCode}</th>
                    <td className={trade.side === "BUY" ? "mypage-profit" : "mypage-loss"}>{trade.side === "BUY" ? "매수" : "매도"}</td>
                    <td className="num">{trade.quantity.toLocaleString("ko-KR")}주</td>
                    <td className="num">{won(trade.grossAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel id="mypage-reset" title="인생 리셋">
        <div className="mypage-reset-row">
          <p>계좌를 시작 자본과 빈 보유 목록으로 되돌립니다. 거래 기록도 삭제됩니다.</p>
          <button type="button" className="mypage-button is-danger" onClick={() => void resetAccount()} disabled={resetting || profile?.resetAvailable === false}>
            {resetting ? "초기화 중…" : "인생 리셋"}
          </button>
        </div>
      </Panel>
    </div>
  );
}
