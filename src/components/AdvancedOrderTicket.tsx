import { useEffect, useId, useRef, useState } from "react";
import { won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import type { OrderResult } from "../market/types";
import { requireSignIn } from "../lib/auth";
import { useTradingShortcuts } from "../lib/useTradingShortcuts";
import { nextTickPrice, previousTickPrice, roundToTick, tickSize } from "../market/universe";
import { LISTING_BY_CODE } from "../market/universe";
import { useAuthUser } from "../lib/auth";
import { GameIcon } from "./GameIcon";
import { navigate, TRADE_PATH } from "../router";

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

export function AdvancedOrderTicket({ code, initialSide = "buy", selectedLimitPrice, selectedLimitPriceRevision, onShowOrders }: { code: string; initialSide?: "buy" | "sell"; selectedLimitPrice?: number | null; selectedLimitPriceRevision?: number; onShowOrders?: () => void }) {
  const quantityId = useId();
  const priceId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const submissionPendingRef = useRef(false);
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const [side, setSide] = useState<"buy" | "sell">(initialSide);
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("LIMIT");
  const [limitPrice, setLimitPrice] = useState(() => String(roundToTick(snapshot.quotes[code]?.asks[0]?.price ?? snapshot.quotes[code]?.price ?? 1)));
  const [qty, setQty] = useState("");
  const [result, setResult] = useState<OrderResult | null>(null);
  const [shortcutMessage, setShortcutMessage] = useState("");
  const [shortcutPending, setShortcutPending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [shortcutMode, setShortcutMode] = useState<"off" | "editing" | "active">("off");

  useEffect(() => {
    if (selectedLimitPrice === undefined || selectedLimitPrice === null) return;
    setLimitPrice(String(roundToTick(selectedLimitPrice)));
    setOrderType("LIMIT");
    setResult(null);
  }, [selectedLimitPrice, selectedLimitPriceRevision]);

  const quote = snapshot.quotes[code];

  const setQuantityRatio = (ratio: number) => {
    const price = orderType === "LIMIT" && Number(limitPrice) > 0 ? Number(limitPrice) : quote?.price ?? 0;
    const maxQty = side === "buy" ? (price > 0 ? Math.min(1000, Math.floor(snapshot.portfolio.cash / price)) : 0) : Math.min(1000, api.orderable(code, side));
    const next = Math.min(maxQty, Math.max(1, Math.floor(maxQty * ratio)));
    setQty(String(maxQty > 0 ? next : ""));
    setResult(null);
    setShortcutMessage("");
  };

  const changeSide = (nextSide: "buy" | "sell") => {
    setSide(nextSide);
    setQty("");
    setResult(null);
    setShortcutMessage("");
    if (orderType === "LIMIT" && quote) {
      const nextSuggested = nextSide === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const changeOrderType = (nextOrderType: "MARKET" | "LIMIT") => {
    const orderTypeAllowed = nextOrderType === "MARKET" ? quote?.restriction?.marketOrdersAllowed : quote?.restriction?.limitOrdersAllowed;
    if (quote?.restriction && !orderTypeAllowed) {
      setShortcutMessage(`${nextOrderType === "MARKET" ? "시장가" : "지정가"} 주문이 현재 제한되어 있습니다.`);
      return;
    }
    setOrderType(nextOrderType);
    setResult(null);
    setShortcutMessage("");
    if (nextOrderType === "LIMIT" && quote) {
      const nextSuggested = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const moveLimitPrice = (direction: "up" | "down") => {
    if (!quote) return;
    if (quote.restriction && !quote.restriction.limitOrdersAllowed) {
      setShortcutMessage("현재 지정가 주문이 제한되어 있습니다.");
      return;
    }
    const typedPrice = Number(limitPrice);
    const currentPrice = Number.isFinite(typedPrice) && typedPrice > 0 ? typedPrice : quote.price;
    const nextPrice = direction === "up" ? nextTickPrice(currentPrice) : previousTickPrice(currentPrice);
    setOrderType("LIMIT");
    setLimitPrice(String(nextPrice));
    setResult(null);
    setShortcutMessage(`지정가 ${won(nextPrice)}로 이동했습니다.`);
  };

  const useCurrentPrice = () => {
    if (!quote || (quote.restriction && !quote.restriction.limitOrdersAllowed)) return;
    const price = roundToTick(quote.price);
    setOrderType("LIMIT");
    setLimitPrice(String(price));
    setResult(null);
    setShortcutMessage(`현재가 ${won(price)}를 지정가에 반영했습니다.`);
  };

  const cancelOrders = async (all: boolean) => {
    if (shortcutPending) return;
    setShortcutPending(true);
    const outcome = all ? await api.cancelAllOpenOrders() : await api.cancelOpenOrder(code);
    setShortcutMessage(outcome.message);
    setShortcutPending(false);
  };

  useTradingShortcuts({
    onBuy: () => { if (quote) void submit("buy"); },
    onSell: () => { if (quote) void submit("sell"); },
    onCancel: (all) => void cancelOrders(all),
    onMarket: () => changeOrderType("MARKET"),
    onMovePrice: moveLimitPrice,
    onQuantityPreset: (preset) => {
      const ratio = RATIOS[preset - 1]?.ratio;
      if (ratio !== undefined) setQuantityRatio(ratio);
    },
  }, formRef, submitting || shortcutPending);

  if (!quote) return null;

  const restriction = quote.restriction;
  const blocked = Boolean(restriction && (orderType === "MARKET" ? !restriction.marketOrdersAllowed : !restriction.limitOrdersAllowed));
  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 1 && parsed <= 1000;
  const suggestedLimit = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
  const parsedLimitPrice = Number(limitPrice);
  const validLimitPrice = Number.isFinite(parsedLimitPrice) && Number.isInteger(parsedLimitPrice) && parsedLimitPrice > 0 && roundToTick(parsedLimitPrice) === parsedLimitPrice;
  const executionPrice = orderType === "MARKET" ? quote.price : (validLimitPrice ? parsedLimitPrice : suggestedLimit);
  const amount = valid ? parsed * executionPrice : 0;
  const maxQty = side === "buy" ? Math.min(1000, Math.floor(snapshot.portfolio.cash / executionPrice)) : Math.min(1000, api.orderable(code, side));
  const affordable = parsed <= maxQty;
  const quantityError = qty === "" ? "" : !valid ? "수량은 1~1,000주 사이의 정수로 입력해 주세요." : !affordable ? `주문 가능 수량은 최대 ${maxQty.toLocaleString()}주입니다.` : "";
  const priceError = orderType === "LIMIT" && !validLimitPrice ? "단가를 해당 가격대의 호가 단위에 맞춰 입력해 주세요." : "";
  const watched = snapshot.watch.includes(code);

  const submit = async (orderSide: "buy" | "sell" = side) => {
    if (submissionPendingRef.current || shortcutPending) return;
    setShortcutMessage("");
    const error = blocked ? "현재 해당 주문 유형의 거래가 제한되어 있습니다."
      : !valid ? "주문할 수량을 1~1,000주 사이의 정수로 입력해 주세요."
      : orderType === "LIMIT" && !validLimitPrice ? "올바른 호가 단가를 입력해 주세요."
      : orderSide === "buy" && amount > snapshot.portfolio.cash ? "매수 주문 가능 금액이 부족합니다."
      : orderSide === "sell" && parsed > api.orderable(code, "sell") ? "매도 가능한 보유 수량이 부족합니다."
      : null;
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
      const outcome = await api.placeOrder({
      code,
      side: orderSide,
      qty: parsed,
      orderType,
      ...(orderType === "LIMIT" ? { price: parsedLimitPrice } : {}),
    });
      setResult(outcome);
      if (outcome.ok) setQty("");
    } catch {
      setResult({ ok: false, message: "주문을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." });
    } finally {
      submissionPendingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <form ref={formRef} tabIndex={-1} className={`ticket terminal-ticket is-${side}`} aria-label="주문 입력" aria-busy={submitting}
      onFocusCapture={(event) => {
        setShortcutMode(event.target.matches("input, select, textarea, [contenteditable=true]") ? "editing" : "active");
      }}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setShortcutMode("off");
      }}
      onClick={(event) => {
        if (event.target instanceof Element && !event.target.closest("button, input, select, textarea, label, a, summary, [contenteditable=true]")) {
          event.currentTarget.focus({ preventScroll: true });
        }
      }}
      onSubmit={(event) => {
        event.preventDefault();
        if (valid && affordable && !blocked && !submitting && (orderType !== "LIMIT" || validLimitPrice)) void submit();
      }}>
      <div className="terminal-tabs" role="group" aria-label="주문 방향">
        {(["buy", "sell"] as const).map((option) => (
          <button key={option} type="button" className={`terminal-tab is-${option}${side === option ? " is-active" : ""}`}
            aria-pressed={side === option} onClick={() => changeSide(option)}>
            {option === "buy" ? "구매하기" : "판매하기"}
          </button>
        ))}
        {onShowOrders && <button type="button" className="terminal-tab" onClick={onShowOrders}>미체결 / 취소</button>}
      </div>

      <div className={`terminal-shortcuts${shortcutMode === "active" && !submitting && !shortcutPending ? " is-active" : ""}`}>
        <div className="terminal-shortcuts-head">
          <strong>주문 단축키</strong>
          <button type="button" onClick={() => formRef.current?.focus({ preventScroll: true })} disabled={submitting || shortcutPending}>단축키 사용</button>
          <span role="status">{submitting || shortcutPending ? "처리 중" : shortcutMode === "active" ? "활성" : shortcutMode === "editing" ? "입력 중" : "대기"}</span>
        </div>
        <div className="terminal-shortcuts-keys" aria-label="주문 단축키 안내">
          <span><kbd>B</kbd> 매수 주문</span><span><kbd>S</kbd> 매도 주문</span><span><kbd>M</kbd> 시장가</span>
          <span><kbd>↑↓</kbd> 호가</span><span><kbd>1~4</kbd> 수량 비율</span>
          <span><kbd>C</kbd> 현재 종목 1건 취소</span><span><kbd>Shift+C</kbd> 전체 취소</span>
        </div>
        <p>{shortcutMode === "editing" ? "입력 후 단축키 사용을 누르세요. B/S는 입력한 수량·단가로 주문을 접수합니다." : "B/S는 입력한 수량·단가로 바로 주문합니다. 주문창 빈 곳을 클릭하면 활성화됩니다."}</p>
      </div>

      <div className="terminal-ticket-body">
        <div className="terminal-account-line"><span>{user?.displayName || (user ? "내 모의투자 계좌" : "모의투자 계좌")}</span><span>KRW</span></div>
        <div className="terminal-symbol">
          <GameIcon code={code} size="small" />
          <select aria-label="거래 종목" value={code} onChange={(event) => navigate(TRADE_PATH(event.target.value))}>
            {snapshot.codes.map((item) => <option key={item} value={item}>{LISTING_BY_CODE[item]?.name ?? item}</option>)}
          </select>
          <button type="button" className={`terminal-watch${watched ? " is-on" : ""}`}
            aria-label={watched ? "관심 종목 해제" : "관심 종목 등록"} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
            <span className="terminal-watch-mark" aria-hidden="true"><span className="watch-outline">☆</span><span className="watch-filled">★</span></span>
          </button>
          <span className="terminal-symbol-code">{code}</span>
        </div>
        <div className="terminal-field">
          <label htmlFor={`${priceId}-type`}>유형</label>
          <select id={`${priceId}-type`} value={orderType} onChange={(event) => changeOrderType(event.target.value as "MARKET" | "LIMIT")}>
            <option value="LIMIT" disabled={Boolean(restriction && !restriction.limitOrdersAllowed)}>지정가</option>
            <option value="MARKET" disabled={Boolean(restriction && !restriction.marketOrdersAllowed)}>시장가</option>
          </select>
          <span className="terminal-field-note">{blocked ? "거래 제한" : "실시간 시세"}</span>
        </div>
        {restriction && <p className="ticket-restriction" role="status"><strong>{restriction.label}</strong> {restriction.effect}</p>}
        <div className="terminal-buying-power"><span>{side === "buy" ? "주문 가능 금액" : "매도 가능 수량"}</span><strong className="num">{user ? (side === "buy" ? won(snapshot.portfolio.cash) : `${maxQty.toLocaleString()}주`) : "로그인 필요"}</strong></div>

        <div className="terminal-field">
          <label htmlFor={quantityId}>수량 <small>1주 단위</small></label>
          <div className="terminal-quantity-control">
            <button type="button" className="terminal-small-button" onClick={() => setQuantityRatio(1)} disabled={maxQty === 0}>최대</button>
            <div className="ticket-input">
              <input id={quantityId} type="number" inputMode="numeric" min={1} max={1000} step={1} value={qty} placeholder="0"
                aria-invalid={Boolean(quantityError)} aria-describedby={`${quantityId}-hint`}
                onChange={(event) => { setQty(event.target.value); setResult(null); setShortcutMessage(""); }} />
              <span className="ticket-unit">주</span>
            </div>
          </div>
        </div>
        <p id={`${quantityId}-hint`} className={`terminal-field-hint${quantityError ? " is-error" : ""}`} aria-live="polite">{quantityError || "한 번에 최대 1,000주까지 주문할 수 있습니다."}</p>
        <div className="terminal-presets" aria-label="주문 수량 비율">
          {RATIOS.map((item) => <button key={item.label} type="button" onClick={() => setQuantityRatio(item.ratio)} disabled={maxQty === 0}>{item.label}</button>)}
        </div>
        <div className="terminal-field">
          <label htmlFor={priceId}>단가</label>
          <div className="terminal-price-control">
            <button className="terminal-small-button terminal-current-price" type="button"
              disabled={submitting || Boolean(restriction && !restriction.limitOrdersAllowed)}
              onClick={useCurrentPrice}>현재가</button>
            <div className="ticket-input terminal-price-input">
              <input id={priceId} type="number" inputMode="numeric" min={tickSize(orderType === "LIMIT" ? Number(limitPrice) : quote.price)}
                step={tickSize(Number(limitPrice) > 0 ? Number(limitPrice) : quote.price)}
                disabled={orderType === "MARKET"} value={orderType === "MARKET" ? quote.price : limitPrice}
                aria-invalid={Boolean(priceError)} aria-describedby={`${priceId}-hint`}
                onKeyDown={(event) => { if (!event.nativeEvent.isComposing && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey && (event.key === "ArrowUp" || event.key === "ArrowDown")) { event.preventDefault(); moveLimitPrice(event.key === "ArrowUp" ? "up" : "down"); } }}
                onChange={(event) => { setLimitPrice(event.target.value); setResult(null); setShortcutMessage(""); }} />
              <span className="ticket-unit">원</span>
            </div>
            <button className="terminal-step" type="button" aria-label="한 호가 낮추기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("down")}>−</button>
            <button className="terminal-step" type="button" aria-label="한 호가 높이기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("up")}>+</button>
          </div>
        </div>
        <p id={`${priceId}-hint`} className={`terminal-input-hint${priceError ? " is-error" : ""}`} aria-live="polite">
          {priceError || (orderType === "MARKET" ? "시장가 주문은 실제 체결 가격에 따라 금액이 달라집니다." : "호가를 누르거나 + / − 버튼으로 단가를 조정하세요.")}
        </p>
        <div className="terminal-total"><span>총 주문금액</span><strong className="num">{won(amount)}</strong></div>
        <button type="submit" className={`submit-button is-${side}`} disabled={submitting || blocked || !valid || !affordable || (orderType === "LIMIT" && !validLimitPrice)}>
          {submitting ? "주문 접수 중…" : blocked ? "거래 제한 중" : side === "buy" ? "구매하기" : "판매하기"}
        </button>
      </div>
      <p className={`terminal-status${result ? (result.ok ? " is-success" : " is-error") : ""}`} role="status" aria-live="polite">
        {shortcutPending ? "취소 처리 중…" : result?.message || shortcutMessage || (user ? "주문할 수량과 단가를 입력해 주세요." : "로그인 후 주문할 수 있습니다.")}
      </p>
    </form>
  );
}
