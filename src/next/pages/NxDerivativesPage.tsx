import { useRef, useState } from "react";
import { Link } from "../../components/Link";
import { EtfBoard } from "../EtfBoard";
import { MarketTabs } from "../MarketTabs";
import { requireSignIn } from "../../lib/auth";
import { useMarketApi } from "../../market/MarketProvider";
import { closeDerivative, openDerivative, useDerivativeMarket } from "../../market/derivatives";
import type { DerivativeAccount, DerivativeOrderRequest } from "../../market/derivatives";
import { productAccount } from "../../market/derivativeAccount";
import { indexValue, kstDateTime, serverTimestamp, signedWon, won } from "../../market/format";
import { ETF_PATH, type DerivativesTab } from "../../router";
import { EtfPanel } from "./derivatives/EtfPanel";
import { FuturesTerminal } from "./derivatives/FuturesTerminal";
import { OptionsPanel } from "./derivatives/OptionsPanel";

const ACTION_LABEL: Record<string, string> = {
  OPEN: "신규",
  CLOSE: "청산",
  EXPIRE: "만기",
  LIQUIDATE: "강제청산",
};

export function NxDerivativesPage({ tab, etfTicker = null }: { tab: DerivativesTab | "etf"; etfTicker?: string | null }) {
  const { snapshot, account, loading, error, setAccount, user } = useDerivativeMarket();
  const stockApi = useMarketApi();
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [notice, setNotice] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const isEtf = tab === "etf";
  const label = isEtf ? "ETF" : "파생";
  const path = isEtf ? ETF_PATH(etfTicker) : `/derivatives/${tab}`;
  const selectedEtf = snapshot?.etfs.find(quote => quote.code === etfTicker);
  const { trades: productTrades, positionValue, unrealizedPnl } = productAccount(account, isEtf ? "etf" : "derivatives");
  const trades = isEtf ? productTrades.filter(trade => trade.symbol === etfTicker) : productTrades;

  const run = async (action: () => Promise<{ message: string; account: DerivativeAccount }>) => {
    if (pending.current || !requireSignIn(path)) return;
    pending.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const result = await action();
      setAccount(result.account);
      setNotice({ tone: "ok", text: result.message });
      await stockApi.refreshPortfolio();
    } catch (failure) {
      setNotice({ tone: "error", text: failure instanceof Error ? failure.message : "주문 처리에 실패했습니다." });
    } finally {
      pending.current = false;
      setBusy(false);
    }
  };

  const open = (request: DerivativeOrderRequest) => void run(() => openDerivative(request));
  const close = (positionId: number, quantity: number) => void run(() => closeDerivative(positionId, quantity));

  if (isEtf && !etfTicker) return <EtfBoard quotes={snapshot?.etfs ?? null} loading={loading} error={error} />;

  // 선물은 주식 거래 화면과 같은 판(시세·차트 + 오른쪽 주문서)을 쓴다. 계좌 요약과 체결 내역도 그 안에 있다.
  if (tab === "futures") return (
    <div className="nx-trade-page nx-futures-page">
      <MarketTabs active="futures" />
      {snapshot
        ? <FuturesTerminal snapshot={snapshot} account={account} signedIn={Boolean(user)} busy={busy} notice={notice}
            stale={Boolean(error)} onOpen={open} onClose={close} />
        : <p className="nx-empty" role="status">{loading ? "선물 시장을 불러오는 중이에요." : "선물 시세를 불러오지 못했어요. 잠시 후 다시 확인해 주세요."}</p>}
    </div>
  );

  if (isEtf && snapshot && !selectedEtf) return <div className="nx-market">
    <section className="nx-empty-state">
      <h1 className="nx-page-title">ETF를 찾을 수 없어요.</h1>
      <p>{etfTicker} 종목이 상장되어 있지 않습니다.</p>
      <Link to={ETF_PATH()} className="nx-back-link">ETF 목록으로</Link>
    </section>
  </div>;

  return (
    <div className={`page-stack derivatives-page nx-product-page${isEtf ? " nx-etf-page" : ""}`}>
      <MarketTabs active={isEtf ? "stocks" : tab} />
      {!isEtf && <header className="derivatives-head">
        <div>
          {/* 화면 이름은 상단 메뉴가 이미 보여 준다. 제목은 화면 낭독기용으로만 둔다. */}
          <h1 className="vh">{label}</h1>
          <p className="derivatives-lede">
            게임주 지수 선물과 옵션을 거래하세요. 모의 시장 · 24시간
          </p>
        </div>
      </header>}

      {isEtf && <div className="nx-trade-toolbar">
        <Link to="/market" className="nx-back-link"><span aria-hidden="true">←</span> 주식 목록</Link>
      </div>}

      {!snapshot ? <p className="empty" role="status">{loading ? `${label} 시장을 불러오는 중…` : `${label} 시세를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.`}</p> : <>

      {/* 로그인한 사람은 계좌를 받는 동안에도 같은 자리를 "—"로 채운다. 로그인 안내가 잠깐 비치지 않게. */}
      {!isEtf && (user ? (
        <dl className="derivative-account" aria-label={`${label} 계좌 요약`} aria-busy={!account}>
          <div><dt>사용 가능 현금</dt><dd className="num">{account ? won(account.cash) : "—"}</dd></div>
          <div><dt>{label} 평가액</dt><dd className="num">{account ? won(positionValue) : "—"}</dd></div>
          <div><dt>{label} 평가손익</dt><dd className={`num ${account && unrealizedPnl > 0 ? "is-up" : account && unrealizedPnl < 0 ? "is-down" : ""}`}>{account ? signedWon(unrealizedPnl) : "—"}</dd></div>
        </dl>
      ) : (
        <div className="derivative-signin">
          <p>로그인하면 {label} 보유 내역을 확인하고 주문할 수 있어요.</p>
          <button type="button" className="nx-text-button" onClick={() => requireSignIn(path)}>
            로그인
          </button>
        </div>
      ))}

      {notice && (
        <p className={`derivative-notice is-${notice.tone}`} role="status">{notice.text}</p>
      )}
      {error && <p className="derivative-notice is-error" role="status">시세 갱신이 지연되어 이전 시세를 표시합니다.</p>}

      {tab === "etf" && selectedEtf && (
        <EtfPanel selected={selectedEtf} account={account} busy={busy} onOpen={open} onClose={close} notice={notice} />
      )}
      {tab === "options" && (
        <OptionsPanel snapshot={snapshot} account={account} busy={busy} onOpen={open} onClose={close} />
      )}

      {account && <details className="nx-product-totals">
        <summary>계좌 전체 누적 손익·수수료</summary>
        <p>ETF·선물·옵션을 합산한 누적 내역입니다.</p>
        <dl>
          <div><dt>확정 손익</dt><dd className={`num ${account.realizedPnl > 0 ? "is-up" : account.realizedPnl < 0 ? "is-down" : ""}`}>{signedWon(account.realizedPnl)}</dd></div>
          <div><dt>누적 수수료</dt><dd className="num">{won(account.totalFees)}</dd></div>
        </dl>
      </details>}

      {trades.length > 0 && (
        <section className="derivative-card">
          <header className="derivative-card-head">
            <div><h2>최근 {label} 체결</h2><p>{isEtf ? "ETF 매수·매도 기록입니다." : "신규·청산·만기·강제청산 기록입니다."}</p></div>
            <span className="derivative-badge">{trades.length}건</span>
          </header>
          <div className="table-scroll" tabIndex={0} role="region" aria-label={`최근 ${label} 체결 표`}>
            <table className="table derivative-table">
              <caption className="vh">최근 {label} 체결</caption>
              <thead>
                <tr><th scope="col">시각</th><th scope="col">구분</th><th scope="col">상품</th>
                  <th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">가격</th>
                  <th scope="col" className="cell-num">현금 흐름</th><th scope="col" className="cell-num">수수료</th></tr>
              </thead>
              <tbody>
                {trades.map((trade) => (
                  <tr key={trade.id}>
                    <td className="num">{kstDateTime(serverTimestamp(trade.createdAt))}</td>
                    <td>{ACTION_LABEL[trade.action] ?? trade.action}</td>
                    <td>{trade.symbol} · {trade.side}</td>
                    <td className="num">{trade.quantity}</td>
                    <td className="num">{trade.kind === "OPTION" ? `${trade.price.toFixed(2)}p` : trade.kind === "ETF" ? won(trade.price) : indexValue(trade.price)}</td>
                    <td className={`cell-num ${trade.cashFlow >= 0 ? "is-up" : "is-down"}`}>{won(trade.cashFlow)}</td>
                    <td className="num">{won(trade.fee)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      <p className="nx-trade-disclaimer">실제 금융상품이 아닌 게임주 기반 모의 거래입니다.</p>
      </>}
    </div>
  );
}
