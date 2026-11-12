import { useEffect, useId, useRef, useState } from "react";
import { Link } from "../../components/Link";
import { requireSignIn, useAuthUser } from "../../lib/auth";
import { useProfile } from "../../lib/profile";
import { won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { affordableQuantity } from "../../market/orderSizing";
import { totals } from "../../market/selectors";
import type { OrderResult } from "../../market/types";
import { nextTickPrice, previousTickPrice, roundToTick, tickSize } from "../../market/universe";
import { AnimatedNav } from "../AnimatedNav";
import { announceOrderImpact } from "../engagement/LiveBoards";
import { refreshEngagement } from "../../lib/engagement";

/*
 * 새 화면의 주문서. 주문 규칙(호가 단위, 수량 한도, 주문 가능 금액, 거래 제한)은
 * 기존 AdvancedOrderTicket과 한 줄도 다르지 않게 옮기고, 배치만 판정선 체계로 다시 짰다.
 */

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

/** 사용자가 참여한 체결에 붙는 수수료율(PRODUCT.md). 화면 안내용 어림값이다. */
const FEE_RATE = 0.001;

export function NxOrderTicket({ code, selectedLimitPrice, selectedLimitPriceRevision, onShowOrders }: {
  code: string;
  selectedLimitPrice?: number | null;
  selectedLimitPriceRevision?: number;
  onShowOrders?: () => void;
}) {
  const quantityId = useId();
  const priceId = useId();
  const submissionPendingRef = useRef(false);
  const limitPriceEditedRef = useRef(false);
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const profile = useProfile();
  const [side, setSide] = useState<"buy" | "sell">("buy");
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("LIMIT");
  const [limitPrice, setLimitPrice] = useState(() => String(roundToTick(snapshot.quotes[code]?.asks[0]?.price ?? snapshot.quotes[code]?.price ?? 1)));
  const [qty, setQty] = useState("");
  const [result, setResult] = useState<OrderResult | null>(null);
  const [notice, setNotice] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (selectedLimitPrice === undefined || selectedLimitPrice === null) return;
    limitPriceEditedRef.current = true;
    setLimitPrice(String(roundToTick(selectedLimitPrice)));
    setOrderType("LIMIT");
    setResult(null);
  }, [selectedLimitPrice, selectedLimitPriceRevision]);

  const quote = snapshot.quotes[code];
  /* 체결 뒤 가격을 읽으려고 최신 시세를 붙잡아 둔다. 주문 직후 1.5초 뒤 값과 비교해 내 주문의 영향을 보여 준다. */
  const latestPrice = useRef(quote?.price ?? 0);
  latestPrice.current = quote?.price ?? 0;
  const liveSuggestedLimit = quote ? (side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price) : null;

  // 수량을 입력하기 시작하면 단가를 더 움직이지 않는다. 주문이 끝나 수량이 비면 다시 호가를 따라간다.
  useEffect(() => {
    if (limitPriceEditedRef.current || qty !== "" || orderType !== "LIMIT" || liveSuggestedLimit === null) return;
    setLimitPrice(String(roundToTick(liveSuggestedLimit)));
  }, [liveSuggestedLimit, orderType, qty]);

  const setQuantityRatio = (ratio: number) => {
    const price = orderType === "LIMIT" && Number(limitPrice) > 0 ? Number(limitPrice) : quote?.price ?? 0;
    const maxQty = snapshot.portfolioError ? 0 : side === "buy" ? affordableQuantity(snapshot.portfolio.cash, price) : Math.min(1000, api.orderable(code, side));
    const next = Math.min(maxQty, Math.max(1, Math.floor(maxQty * ratio)));
    setQty(String(maxQty > 0 ? next : ""));
    setResult(null);
    setNotice("");
  };

  const changeSide = (nextSide: "buy" | "sell") => {
    setSide(nextSide);
    limitPriceEditedRef.current = false;
    setQty("");
    setResult(null);
    setNotice("");
    if (orderType === "LIMIT" && quote) {
      const nextSuggested = nextSide === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const changeOrderType = (nextOrderType: "MARKET" | "LIMIT") => {
    const orderTypeAllowed = nextOrderType === "MARKET" ? quote?.restriction?.marketOrdersAllowed : quote?.restriction?.limitOrdersAllowed;
    if (quote?.restriction && !orderTypeAllowed) {
      setNotice(`${nextOrderType === "MARKET" ? "시장가" : "지정가"} 주문이 현재 제한되어 있습니다.`);
      return;
    }
    setOrderType(nextOrderType);
    limitPriceEditedRef.current = false;
    setResult(null);
    setNotice("");
    if (nextOrderType === "LIMIT" && quote) {
      const nextSuggested = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const moveLimitPrice = (direction: "up" | "down") => {
    if (!quote) return;
    if (quote.restriction && !quote.restriction.limitOrdersAllowed) {
      setNotice("현재 지정가 주문이 제한되어 있습니다.");
      return;
    }
    const typedPrice = Number(limitPrice);
    const currentPrice = Number.isFinite(typedPrice) && typedPrice > 0 ? typedPrice : quote.price;
    const nextPrice = direction === "up" ? nextTickPrice(currentPrice) : previousTickPrice(currentPrice);
    limitPriceEditedRef.current = true;
    setOrderType("LIMIT");
    setLimitPrice(String(nextPrice));
    setResult(null);
    setNotice(`지정가 ${won(nextPrice)}로 이동했습니다.`);
  };

  const useCurrentPrice = () => {
    if (!quote || (quote.restriction && !quote.restriction.limitOrdersAllowed)) return;
    const price = roundToTick(quote.price);
    limitPriceEditedRef.current = true;
    setOrderType("LIMIT");
    setLimitPrice(String(price));
    setResult(null);
    setNotice(`현재가 ${won(price)}를 지정가에 반영했습니다.`);
  };

  if (!quote) return null;

  const restriction = quote.restriction;
  const profileLoading = Boolean(user && !profile);
  const adminTradingBlocked = Boolean(profile?.admin);
  const blocked = profileLoading || adminTradingBlocked || Boolean(restriction && (orderType === "MARKET" ? !restriction.marketOrdersAllowed : !restriction.limitOrdersAllowed));
  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 1 && parsed <= 1000;
  const suggestedLimit = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
  const parsedLimitPrice = Number(limitPrice);
  const validLimitPrice = Number.isFinite(parsedLimitPrice) && Number.isInteger(parsedLimitPrice) && parsedLimitPrice > 0 && roundToTick(parsedLimitPrice) === parsedLimitPrice;
  const executionPrice = orderType === "MARKET" ? quote.price : (validLimitPrice ? parsedLimitPrice : suggestedLimit);
  const amount = valid ? parsed * executionPrice : 0;
  const maxQty = snapshot.portfolioError ? 0 : side === "buy" ? affordableQuantity(snapshot.portfolio.cash, executionPrice) : Math.min(1000, api.orderable(code, side));
  // 대기 중인 매도 주문이나 미정산분 때문에 매도 가능 수량이 보유 수량보다 적을 때만 보유 수량을 함께 보인다.
  const heldQty = snapshot.portfolio.positions[code]?.qty ?? 0;
  const affordable = parsed <= maxQty;
  const quantityError = qty === "" ? "" : !valid ? "수량은 1~1,000주 사이의 정수로 입력해 주세요." : !affordable ? `주문 가능 수량은 최대 ${maxQty.toLocaleString()}주입니다.` : "";
  const priceError = orderType === "LIMIT" && !validLimitPrice ? "단가를 해당 가격대의 호가 단위에 맞춰 입력해 주세요." : "";
  const money = totals(snapshot);
  const loanRoom = Math.max(0, Math.floor(money.net * 0.5) - money.loan);
  const cashShort = side === "buy" && ((valid && !affordable) || result?.message === "매수 주문 가능 금액이 부족합니다.");
  const sideLabel = side === "buy" ? "매수" : "매도";
  const readyMessage = valid && affordable && !blocked && !priceError
    ? (orderType === "MARKET" ? `시장가로 ${parsed.toLocaleString()}주를 ${sideLabel}합니다.` : `${won(parsedLimitPrice)}에 ${parsed.toLocaleString()}주를 ${sideLabel}합니다.`)
    : null;

  const submit = async (orderSide: "buy" | "sell" = side) => {
    if (submissionPendingRef.current) return;
    setNotice("");
    const error = snapshot.portfolioError ?? (profileLoading ? "계정 권한을 확인하는 중입니다. 잠시 후 다시 시도해 주세요."
      : adminTradingBlocked ? "관리자 계정은 모의 주문을 사용할 수 없어요. 일반 계정으로 로그인해 주세요."
      : blocked ? "현재 해당 주문 유형의 거래가 제한되어 있습니다."
      : !valid ? "주문할 수량을 1~1,000주 사이의 정수로 입력해 주세요."
      : orderType === "LIMIT" && !validLimitPrice ? "올바른 호가 단가를 입력해 주세요."
      : orderSide === "buy" && parsed > affordableQuantity(snapshot.portfolio.cash, executionPrice) ? "수수료를 포함한 매수 주문 가능 금액이 부족합니다."
      : orderSide === "sell" && parsed > api.orderable(code, "sell") ? "매도 가능한 보유 수량이 부족합니다."
      : null);
    if (error) {
      setResult({ ok: false, message: error });
      return;
    }
    if (!requireSignIn(`/market/${code}`)) return;
    submissionPendingRef.current = true;
    setSide(orderSide);
    setSubmitting(true);
    setResult(null);
    try {
      const before = latestPrice.current;
      const outcome = await api.placeOrder({ code, side: orderSide, qty: parsed, orderType, ...(orderType === "LIMIT" ? { price: parsedLimitPrice } : {}) });
      setResult(outcome);
      if (outcome.ok) {
        setQty("");
        refreshEngagement();
        window.setTimeout(() => announceOrderImpact(code, before, latestPrice.current, orderSide), 1_500);
      }
    } catch {
      setResult({ ok: false, message: "주문을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." });
    } finally {
      submissionPendingRef.current = false;
      setSubmitting(false);
    }
  };

  const statusMessage = result?.message || notice || (!user ? "로그인 후 주문할 수 있습니다."
    : profileLoading ? "계정 권한을 확인하고 있어요."
    : adminTradingBlocked ? "관리자 계정은 모의 주문을 사용할 수 없어요. 일반 계정으로 로그인해 주세요."
    : readyMessage ?? "");

  return (
    <form tabIndex={-1} className={`nx-ticket is-${side}`} aria-label="주문 입력" aria-busy={submitting}
      onSubmit={(event) => {
        event.preventDefault();
        if (valid && affordable && !blocked && !submitting && (orderType !== "LIMIT" || validLimitPrice)) void submit();
      }}>
      <AnimatedNav as="div" fill activeKey={side} className="nx-ticket-sides" role="group" aria-label="주문 방향">
        {(["buy", "sell"] as const).map((option) => (
          <button key={option} type="button" className={`nx-ticket-side is-${option}${side === option ? " is-active" : ""}`} aria-pressed={side === option} onClick={() => changeSide(option)}>
            {option === "buy" ? "매수" : "매도"}
          </button>
        ))}
      </AnimatedNav>

      <div className="nx-ticket-row">
        <AnimatedNav as="div" fill activeKey={orderType} className="nx-seg" role="group" aria-label="주문 유형">
          <button type="button" className={orderType === "LIMIT" ? "is-active" : undefined} aria-pressed={orderType === "LIMIT"}
            disabled={Boolean(restriction && !restriction.limitOrdersAllowed)} onClick={() => changeOrderType("LIMIT")}>지정가</button>
          <button type="button" className={orderType === "MARKET" ? "is-active" : undefined} aria-pressed={orderType === "MARKET"}
            disabled={Boolean(restriction && !restriction.marketOrdersAllowed)} onClick={() => changeOrderType("MARKET")}>시장가</button>
        </AnimatedNav>
        {onShowOrders && <button type="button" className="nx-link-button" onClick={onShowOrders}>미체결 보기</button>}
      </div>

      {restriction && <p className="nx-ticket-guard" role="status"><strong>{restriction.label}</strong> {restriction.effect}</p>}

      {/* 로그아웃 상태에서는 아래 안내 줄이 로그인을 알려 준다. 같은 말을 여기서 다시 하지 않는다. */}
      {user && <dl className="nx-ticket-power">
        <div>
          <dt>{side === "buy" ? "주문 가능 금액" : "매도 가능 수량"}</dt>
          <dd className="nx-num">
            {side === "buy" ? won(snapshot.portfolio.cash) : `${maxQty.toLocaleString()}주`}
            {side === "sell" && heldQty > maxQty && <>{" "}<span className="nx-ticket-held">보유 {heldQty.toLocaleString()}주</span></>}
          </dd>
        </div>
        {user && side === "buy" && (loanRoom >= 10_000 || money.loan > 0) && (
          <div className="nx-ticket-loan">
            <dt>{money.loan > 0 ? "대출 잔액 · 가능" : "대출 가능"}</dt>
            <dd className="nx-num">
              {money.loan > 0 && <>{won(money.loan)} · </>}{won(loanRoom)}
              {cashShort && loanRoom >= 10_000 && <Link to="/loan" className="nx-ticket-loan-link">대출받기</Link>}
            </dd>
          </div>
        )}
      </dl>}

      <div className="nx-field">
        <div className="nx-field-head">
          <label htmlFor={priceId}>단가</label>
          <button type="button" className="nx-link-button" disabled={submitting || Boolean(restriction && !restriction.limitOrdersAllowed)} onClick={useCurrentPrice}>현재가로</button>
        </div>
        <div className={`nx-stepper${orderType === "MARKET" ? " is-disabled" : ""}`}>
          <button type="button" aria-label="한 호가 낮추기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("down")}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8h9" /></svg>
          </button>
          <input id={priceId} type="number" inputMode="numeric" className="nx-num"
            min={tickSize(orderType === "LIMIT" ? Number(limitPrice) : quote.price)}
            step={tickSize(Number(limitPrice) > 0 ? Number(limitPrice) : quote.price)}
            disabled={orderType === "MARKET"} value={orderType === "MARKET" ? quote.price : limitPrice}
            aria-invalid={Boolean(priceError)} aria-describedby={`${priceId}-hint`}
            onKeyDown={(event) => { if (!event.nativeEvent.isComposing && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey && (event.key === "ArrowUp" || event.key === "ArrowDown")) { event.preventDefault(); moveLimitPrice(event.key === "ArrowUp" ? "up" : "down"); } }}
            onChange={(event) => { limitPriceEditedRef.current = true; setLimitPrice(event.target.value); setResult(null); setNotice(""); }} />
          <span className="nx-stepper-unit">원</span>
          <button type="button" aria-label="한 호가 높이기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("up")}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8h9M8 3.5v9" /></svg>
          </button>
        </div>
        <p id={`${priceId}-hint`} className={`nx-field-hint${priceError ? " is-error" : ""}`} aria-live="polite">
          {priceError || (orderType === "MARKET" ? "시장가 주문은 실제 체결 가격에 따라 금액이 달라집니다." : `호가 단위 ${won(tickSize(Number(limitPrice) > 0 ? Number(limitPrice) : quote.price))}`)}
        </p>
      </div>

      <div className="nx-field">
        <div className="nx-field-head">
          <label htmlFor={quantityId}>수량</label>
          <span className="nx-field-note">1~1,000주</span>
        </div>
        <div className="nx-stepper is-plain">
          <input id={quantityId} type="number" inputMode="numeric" className="nx-num" min={1} max={1000} step={1} value={qty} placeholder="0"
            aria-invalid={Boolean(quantityError)} aria-describedby={`${quantityId}-hint`}
            onChange={(event) => { setQty(event.target.value); setResult(null); setNotice(""); }} />
          <span className="nx-stepper-unit">주</span>
        </div>
        <div className="nx-ratios" role="group" aria-label="주문 수량 비율">
          {RATIOS.map((item) => (
            <button key={item.label} type="button" onClick={() => setQuantityRatio(item.ratio)} disabled={maxQty === 0}>{item.label}</button>
          ))}
        </div>
        <p id={`${quantityId}-hint`} className={`nx-field-hint${quantityError ? " is-error" : ""}`} aria-live="polite">{quantityError}</p>
      </div>

      <dl className="nx-ticket-total">
        <div><dt>총 주문금액</dt><dd className="nx-num nx-ticket-amount">{won(amount)}</dd></div>
        <div><dt>예상 수수료 0.1%</dt><dd className="nx-num">{won(Math.round(amount * FEE_RATE))}</dd></div>
      </dl>

      <button type="submit" className={`nx-ticket-submit is-${side}`} disabled={submitting || blocked || !valid || !affordable || (orderType === "LIMIT" && !validLimitPrice)}>
        {submitting ? "주문 접수 중…" : profileLoading ? "권한 확인 중…" : adminTradingBlocked ? "관리자 계정 주문 불가" : blocked ? "거래 제한 중" : valid ? `${parsed.toLocaleString()}주 ${sideLabel}` : `${sideLabel} 주문`}
      </button>

      <p className={`nx-ticket-status${result ? (result.ok ? " is-success" : " is-error") : ""}`} role="status" aria-live="polite">{statusMessage}</p>

    </form>
  );
}
