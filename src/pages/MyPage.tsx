import { useEffect, useState } from "react";
import { onAuthStateChanged, signInWithPopup, type User } from "firebase/auth";
import { Panel } from "../components/Panel";
import { Link } from "../components/Link";
import { apiFetch } from "../lib/api";
import { firebaseAuth, googleProvider } from "../lib/firebase";
import { LISTING_BY_CODE, LISTINGS } from "../market/universe";
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

type ActiveOrder = {
  id: number;
  stockCode: string;
  side: string;
  quantity: number;
  remainingQuantity: number;
  price: number;
  status: string;
  orderType: string;
  reservedCash: number;
  reservedQuantity: number;
  createdAt: string;
  expiresAt: string | null;
};

type WatchlistEntry = { stockCode: string; addedAt: string };
type PriceAlert = { id: number; stockCode: string; targetPrice: number; active: boolean; createdAt: string; triggeredAt: string | null };

export function MyPage() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [completedTrades, setCompletedTrades] = useState<CompletedTrade[]>([]);
  const [openOrders, setOpenOrders] = useState<ActiveOrder[]>([]);
  const [watchlist, setWatchlist] = useState<WatchlistEntry[]>([]);
  const [alerts, setAlerts] = useState<PriceAlert[]>([]);
  const [alertCode, setAlertCode] = useState(LISTINGS[0]?.code ?? "");
  const [alertPrice, setAlertPrice] = useState("");
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
      setOpenOrders([]);
      setWatchlist([]);
      setAlerts([]);
      return;
    }
    let active = true;
    setLoading(true);
    setMessage("");
    void Promise.all([
      apiFetch<Profile>("/api/profile"),
      apiFetch<Portfolio>("/api/portfolio"),
      apiFetch<CompletedTrade[]>("/api/settlements"),
      apiFetch<ActiveOrder[]>("/api/orders"),
      apiFetch<WatchlistEntry[]>("/api/watchlist"),
      apiFetch<PriceAlert[]>("/api/price-alerts"),
    ])
      .then(([nextProfile, nextPortfolio, nextTrades, nextOpenOrders, nextWatchlist, nextAlerts]) => {
        if (!active) return;
        setProfile(nextProfile);
        setNickname(nextProfile.nickname ?? "");
        setPortfolio(nextPortfolio);
        setCompletedTrades(nextTrades.slice(0, 5));
        setOpenOrders(nextOpenOrders);
        setWatchlist(nextWatchlist);
        setAlerts(nextAlerts);
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
          setOpenOrders(await apiFetch<ActiveOrder[]>("/api/orders"));
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

  const addAlert = async () => {
    const targetPrice = Number(alertPrice);
    if (!alertCode || !Number.isFinite(targetPrice) || targetPrice < 1) {
      setMessage("알림 종목과 목표 가격을 확인해 주세요.");
      return;
    }
    try {
      const created = await apiFetch<PriceAlert>("/api/price-alerts", {
        method: "POST",
        body: JSON.stringify({ stockCode: alertCode, targetPrice: Math.round(targetPrice) }),
      });
      setAlerts((current) => [created, ...current]);
      setAlertPrice("");
      setMessage("가격 알림을 저장했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "가격 알림 저장에 실패했습니다.");
    }
  };

  const removeAlert = async (id: number) => {
    try {
      await apiFetch(`/api/price-alerts/${id}`, { method: "DELETE" });
      setAlerts((current) => current.filter((alert) => alert.id !== id));
      setMessage("가격 알림을 삭제했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "가격 알림 삭제에 실패했습니다.");
    }
  };

  const cancelOrder = async (id: number) => {
    setMessage("");
    try {
      await apiFetch(`/api/orders/${id}`, { method: "DELETE" });
      setOpenOrders((current) => current.filter((order) => order.id !== id));
      setMessage("미체결 주문을 취소했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "주문 취소에 실패했습니다.");
    }
  };

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

      <Panel id="mypage-watch-alerts" title="관심종목·가격 알림" meta={`${watchlist.length}종목 · ${alerts.length}건`}>
        <div className="feature-columns">
          <section>
            <h3 className="feature-heading">관심종목</h3>
            {watchlist.length === 0 ? <p className="empty is-inline">관심종목이 없습니다. 시장에서 별표를 눌러 담아봐.</p> : (
              <ul className="feature-list">
                {watchlist.map((item) => (
                  <li key={item.stockCode}>
                    <Link to={`/market/${item.stockCode}`}>{LISTING_BY_CODE[item.stockCode]?.name ?? item.stockCode}</Link>
                    <span className="num">{item.stockCode}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <h3 className="feature-heading">목표가 알림 추가</h3>
            <div className="alert-form">
              <label htmlFor="alert-stock">종목</label>
              <select id="alert-stock" value={alertCode} onChange={(event) => setAlertCode(event.target.value)}>
                {LISTINGS.map((listing) => <option key={listing.code} value={listing.code}>{listing.name}</option>)}
              </select>
              <label htmlFor="alert-price">목표 가격</label>
              <div className="alert-input-row">
                <input id="alert-price" type="number" min="1" step="1" value={alertPrice} placeholder="예: 30000" onChange={(event) => setAlertPrice(event.target.value)} />
                <button type="button" className="mypage-button" onClick={() => void addAlert()}>저장</button>
              </div>
            </div>
            {alerts.length > 0 && <ul className="feature-list alert-list">
              {alerts.map((alert) => <li key={alert.id}>
                <span><strong>{LISTING_BY_CODE[alert.stockCode]?.name ?? alert.stockCode}</strong> · <span className="num">{won(alert.targetPrice)}</span></span>
                <button type="button" className="text-button" onClick={() => void removeAlert(alert.id)}>삭제</button>
              </li>)}
            </ul>}
          </section>
        </div>
      </Panel>

      <Panel id="mypage-open-orders" title="미체결 주문" meta={`${openOrders.length}건`} flush>
        {openOrders.length === 0 ? (
          <p className="empty is-inline">현재 미체결 주문이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="quote-table mypage-orders-table">
              <caption className="vh">미체결 주문 목록</caption>
              <thead>
                <tr>
                  <th scope="col">종목</th>
                  <th scope="col">구분</th>
                  <th scope="col">주문 유형</th>
                  <th scope="col">주문가</th>
                  <th scope="col">주문 수량</th>
                  <th scope="col">잔량</th>
                  <th scope="col">주문 시각</th>
                  <th scope="col">관리</th>
                </tr>
              </thead>
              <tbody>
                {openOrders.map((order) => (
                  <tr key={order.id}>
                    <th scope="row">{LISTING_BY_CODE[order.stockCode]?.name ?? order.stockCode}</th>
                    <td className={order.side === "BUY" ? "mypage-profit" : "mypage-loss"}>{order.side === "BUY" ? "매수" : "매도"}</td>
                    <td>{order.orderType === "LIMIT" ? "지정가" : "시장가"}</td>
                    <td className="num">{order.price > 0 ? won(order.price) : "-"}</td>
                    <td className="num">{order.quantity.toLocaleString("ko-KR")}주</td>
                    <td className="num">{order.remainingQuantity.toLocaleString("ko-KR")}주</td>
                    <td className="num">{Number.isNaN(serverTimestamp(order.createdAt)) ? "-" : clock(serverTimestamp(order.createdAt))}</td>
                    <td><button type="button" className="text-button" onClick={() => void cancelOrder(order.id)}>취소</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
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
