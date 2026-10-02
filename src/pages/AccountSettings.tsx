import { useEffect, useState } from "react";
import { onAuthStateChanged, signOut, type User } from "firebase/auth";
import { Panel } from "../components/Panel";
import { Link } from "../components/Link";
import { MissionBoard } from "../components/MissionBoard";
import { GameIcon } from "../components/GameIcon";
import { RestrictionBadge } from "../components/RestrictionBadge";
import { apiFetch } from "../lib/api";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt } from "../lib/auth";
import { LISTING_BY_CODE, LISTINGS } from "../market/universe";
import { clock, serverTimestamp, won } from "../market/format";

type Profile = {
  nickname: string;
  email: string;
  profileCompleted: boolean;
  resetAvailable: boolean;
  nicknameChangeAvailable?: boolean;
  nicknameChangeAvailableAt?: string | null;
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

export function AccountSettings() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [profile, setProfile] = useState<Profile | null>(null);
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
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const nextNicknameChange = profile?.nicknameChangeAvailableAt ? Date.parse(profile.nicknameChangeAvailableAt) : null;
  const nicknameLocked = profile?.nicknameChangeAvailable === false && (nextNicknameChange === null || now < nextNicknameChange);

  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  useEffect(() => {
    if (!user) {
      setProfile(null);
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
      apiFetch<CompletedTrade[]>("/api/settlements"),
      apiFetch<ActiveOrder[]>("/api/orders"),
      apiFetch<WatchlistEntry[]>("/api/watchlist"),
      apiFetch<PriceAlert[]>("/api/price-alerts"),
    ])
      .then(([nextProfile, nextTrades, nextOpenOrders, nextWatchlist, nextAlerts]) => {
        if (!active) return;
        setProfile(nextProfile);
        setNickname(nextProfile.nickname ?? "");
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

  // 최근 체결과 미체결 주문은 1초마다 갱신한다. 닉네임/프로필은 입력 중
  // 값이 덮어써지지 않도록 위의 초기 조회에서만 읽는다.
  useEffect(() => {
    if (!user) return;
    let active = true;
    const refreshPortfolio = async () => {
      try {
        const [nextTrades, nextOrders] = await Promise.all([
          apiFetch<CompletedTrade[]>("/api/settlements"),
          apiFetch<ActiveOrder[]>("/api/orders"),
        ]);
        if (active) {
          setCompletedTrades(nextTrades.slice(0, 5));
          setOpenOrders(nextOrders);
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
      // 앱(WebView) 안에서만: 첫 알림 저장 때 네이티브 알림 권한을 물어본다.
      window.ReactNativeWebView?.postMessage(JSON.stringify({ type: "PRICE_ALERT_ADDED" }));
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
    if (!window.confirm("현금과 보유 주식, 거래 내역, 투자 미션을 초기 상태로 되돌릴까요?")) return;
    setResetting(true);
    setMessage("");
    try {
      await apiFetch("/api/account/reset", { method: "DELETE" });
      // Reload private views so stale mission completions cannot survive the reset.
      window.location.reload();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "인생 리셋에 실패했습니다.");
    } finally {
      setResetting(false);
    }
  };

  if (!user) {
    return (
      <div className="account-settings">
        <Panel id="mypage-login" title="계정 관리" level={3}>
          <div className="empty">
            <p>계정 관리와 주문, 가격 알림은 로그인 후 이용할 수 있습니다.</p>
            <button type="button" className="account-button is-primary" onClick={() => openLoginPrompt("/mypage")}>로그인 방법 선택</button>
          </div>
        </Panel>
      </div>
    );
  }

  return (
    <div className="account-settings" aria-busy={loading}>
      {message && <p className="account-message" role="status">{message}</p>}
      <Panel id="mypage-profile" title="계정 관리" level={3} meta="프로필 · 로그인">
        {loading ? <p className="empty">계정 정보를 불러오는 중…</p> : (
          <div className="account-profile-grid">
            <form className="account-form" onSubmit={(event) => { event.preventDefault(); void saveNickname(); }}>
              <label htmlFor="mypage-nickname">닉네임</label>
              <div className="account-input-row">
                <input id="mypage-nickname" value={nickname} disabled={nicknameLocked} minLength={2} maxLength={50} required onChange={(event) => setNickname(event.target.value)} />
                <button type="submit" className="account-button is-primary" disabled={saving || !profile || nicknameLocked || nickname.trim() === profile.nickname}>{saving ? "저장 중…" : "저장"}</button>
              </div>
              <p className="account-hint">닉네임은 3일에 한 번 변경할 수 있습니다. · 2~50자</p>
              {nicknameLocked && nextNicknameChange !== null && <p className="account-hint">
                다음 변경 가능: {new Date(nextNicknameChange).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} (한국시간)
              </p>}
            </form>
            <div className="account-identity">
              <span className="account-label">로그인 계정</span>
              <p>{profile?.email || user.email || "연결된 계정"}</p>
              <button className="text-button" type="button" onClick={() => void signOut(firebaseAuth).catch(() => setMessage("로그아웃하지 못했습니다. 다시 시도해 주세요."))}>로그아웃</button>
            </div>
          </div>
        )}
      </Panel>

      <Panel id="mypage-open-orders" title="미체결 주문" level={3} meta={loading ? "불러오는 중…" : openOrders.length + "건"}>
        {loading ? <p className="empty">주문을 불러오는 중…</p> : openOrders.length === 0 ? (
          <div className="empty"><p>현재 미체결 주문이 없습니다.</p><Link to="/market">시장 둘러보기 →</Link></div>
        ) : (
          <div className="table-scroll">
            <table className="table account-orders-table">
              <caption className="vh">미체결 주문 목록</caption>
              <thead><tr>
                <th scope="col" className="cell-name">게임</th>
                <th scope="col">구분</th><th scope="col">유형</th>
                <th scope="col" className="cell-num">주문가</th>
                <th scope="col" className="cell-num">수량 / 잔량</th>
                <th scope="col" className="cell-num">시각</th>
                <th scope="col" className="cell-num">관리</th>
              </tr></thead>
              <tbody>
                {openOrders.map((order) => (
                  <tr key={order.id}>
                    <th scope="row" className="cell-name">
                      <Link className="name-link" to={"/market/" + order.stockCode}>
                        <GameIcon code={order.stockCode} />
                        <span className="name-text">{LISTING_BY_CODE[order.stockCode]?.name ?? order.stockCode}</span>
                      </Link>
                      <RestrictionBadge code={order.stockCode} />
                    </th>
                    <td className={order.side === "BUY" ? "is-up" : "is-down"}>{order.side === "BUY" ? "매수" : "매도"}</td>
                    <td>{order.orderType === "LIMIT" ? "지정가" : "시장가"}</td>
                    <td className="cell-num num">{order.price > 0 ? won(order.price) : "—"}</td>
                    <td className="cell-num num">{order.quantity.toLocaleString("ko-KR")} / {order.remainingQuantity.toLocaleString("ko-KR")}주</td>
                    <td className="cell-num num">{Number.isNaN(serverTimestamp(order.createdAt)) ? "—" : clock(serverTimestamp(order.createdAt))}</td>
                    <td className="cell-num"><button type="button" className="account-button" onClick={() => void cancelOrder(order.id)}>취소</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <div className="account-alert-grid">
        <Panel id="mypage-price-alerts" title="가격 알림" level={3} meta={loading ? "불러오는 중…" : alerts.length + "건"}>
          <form className="account-form account-alert-form" onSubmit={(event) => { event.preventDefault(); void addAlert(); }}>
            <div className="account-field">
              <label htmlFor="alert-stock">게임</label>
              <select id="alert-stock" value={alertCode} onChange={(event) => setAlertCode(event.target.value)}>
                {LISTINGS.map((listing) => <option key={listing.code} value={listing.code}>{listing.name}</option>)}
              </select>
            </div>
            <div className="account-field">
              <label htmlFor="alert-price">목표 가격</label>
              <div className="account-input-row">
                <div className="account-price-input">
                  <input id="alert-price" type="number" min="1" step="1" required value={alertPrice} placeholder="30,000" onChange={(event) => setAlertPrice(event.target.value)} />
                  <span>원</span>
                </div>
                <button type="submit" className="account-button is-primary" disabled={loading}>추가</button>
              </div>
            </div>
          </form>
          <p className="account-hint">목표 가격에 도달하면 알려드려요.</p>
          {loading ? <p className="empty">알림을 불러오는 중…</p> : alerts.length === 0 ? <p className="empty">등록한 가격 알림이 없습니다.</p> : (
            <ul className="account-list">
              {alerts.map((alert) => <li key={alert.id}>
                <Link className="account-alert-name" to={"/market/" + alert.stockCode}>{LISTING_BY_CODE[alert.stockCode]?.name ?? alert.stockCode}</Link>
                <span className="account-alert-value num">{won(alert.targetPrice)}<small>{alert.active ? "대기 중" : "알림 완료"}</small></span>
                <button type="button" className="text-button" aria-label={(LISTING_BY_CODE[alert.stockCode]?.name ?? alert.stockCode) + " " + won(alert.targetPrice) + " 알림 삭제"} onClick={() => void removeAlert(alert.id)}>삭제</button>
              </li>)}
            </ul>
          )}
        </Panel>
        <Panel id="mypage-watchlist" title="관심종목" level={3} meta={loading ? "불러오는 중…" : watchlist.length + "종목"} action={{label: "종목 찾기", to: "/market"}}>
          {loading ? <p className="empty">관심종목을 불러오는 중…</p> : watchlist.length === 0 ? (
            <p className="empty">시장에서 별표를 눌러 관심종목을 담아 보세요.</p>
          ) : (
            <ul className="account-list">
              {watchlist.map((item) => <li key={item.stockCode}>
                <Link className="name-link" to={"/market/" + item.stockCode}>
                  <GameIcon code={item.stockCode} />
                  <span className="name-text">{LISTING_BY_CODE[item.stockCode]?.name ?? item.stockCode}</span>
                </Link>
                <span className="account-hint num">{item.stockCode}</span>
              </li>)}
            </ul>
          )}
        </Panel>
      </div>

      <details className="account-secondary">
        <summary>미션 · 최근 체결 상세</summary>
        <div className="account-secondary-body">
          <MissionBoard />
      <Panel id="mypage-trades" title="최근 체결" meta="최근 5건" level={3}>
        {completedTrades.length === 0 ? (
          <p className="empty is-inline">체결된 거래가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table account-trades-table">
              <caption className="vh">최근 체결 내역</caption>
              <thead><tr><th scope="col">시각</th><th scope="col">종목</th><th scope="col">구분</th><th scope="col">수량</th><th scope="col">체결 금액</th></tr></thead>
              <tbody>
                {completedTrades.map((trade) => (
                  <tr key={trade.id}>
                    <td className="num">{Number.isNaN(serverTimestamp(trade.createdAt)) ? "-" : clock(serverTimestamp(trade.createdAt))}</td>
                    <th scope="row">{LISTING_BY_CODE[trade.stockCode]?.name ?? trade.stockCode}</th>
                    <td className={trade.side === "BUY" ? "is-up" : "is-down"}>{trade.side === "BUY" ? "매수" : "매도"}</td>
                    <td className="num">{trade.quantity.toLocaleString("ko-KR")}주</td>
                    <td className="num">{won(trade.grossAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>


        </div>
      </details>

      <div className="account-reset">
        <div><h3>인생 리셋</h3><p className="account-hint">시작 자본으로 돌아갑니다. 보유 주식과 거래 기록, 투자 미션도 초기화됩니다.</p></div>
        <button type="button" className="account-button is-danger" onClick={() => void resetAccount()} disabled={loading || resetting || !profile || !profile.resetAvailable}>
          {resetting ? "초기화 중…" : "계좌 초기화"}
        </button>
      </div>
    </div>
  );
}