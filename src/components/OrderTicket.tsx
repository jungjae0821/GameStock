import { useState } from "react";
import { won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import type { OrderResult } from "../market/types";

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

/** 현재가로 즉시 체결되는 주문. 방향, 수량, 금액, 버튼 네 가지만 있다. */
export function OrderTicket({ code }: { code: string }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [side, setSide] = useState<"buy" | "sell">("buy");
  const [qty, setQty] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<OrderResult | null>(null);

  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const maxQty = api.orderable(code, side);
  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 1;
  const amount = valid ? parsed * quote.price : 0;

  const setRatio = (ratio: number) => {
    const next = Math.min(maxQty, Math.max(1, Math.floor(maxQty * ratio)));
    setQty(String(maxQty > 0 ? next : ""));
    setResult(null);
  };

  const submit = async () => {
    if (pending) return;
    setPending(true);
    try {
      const outcome = await api.placeOrder({ code, side, qty: parsed });
      setResult(outcome);
      if (outcome.ok) setQty("");
    } finally { setPending(false); }
  };

  return (
    <form
      className="ticket"
      onSubmit={(event) => {
        event.preventDefault();
        if (valid && !pending) void submit();
      }}
    >
      <div className="ticket-side" role="group" aria-label="주문 방향">
        {(["buy", "sell"] as const).map((option) => (
          <button
            key={option}
            type="button"
            className={`side-button is-${option}${side === option ? " is-active" : ""}`}
            aria-pressed={side === option}
            onClick={() => {
              setSide(option);
              setQty("");
              setResult(null);
            }}
          >
            {option === "buy" ? "매수" : "매도"}
          </button>
        ))}
      </div>

      <div className="ticket-field">
        <label htmlFor="order-qty">수량 (최대 {maxQty}주)</label>
        <div className="ticket-input">
          <input
            id="order-qty"
            type="number"
            inputMode="numeric"
            min={1}
            step={1}
            value={qty}
            placeholder="0"
            onChange={(event) => {
              setQty(event.target.value);
              setResult(null);
            }}
          />
          <span className="ticket-unit">주</span>
        </div>
        <div className="ticket-ratios">
          {RATIOS.map((item) => (
            <button
              key={item.label}
              type="button"
              className="text-button"
              onClick={() => setRatio(item.ratio)}
              disabled={maxQty === 0}
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>

      <button type="submit" className={`submit-button is-${side}`} disabled={!valid || pending || Boolean(quote.restriction && !quote.restriction.marketOrdersAllowed)}>
        {valid ? `${won(amount)} ${side === "buy" ? "매수" : "매도"}` : side === "buy" ? "매수" : "매도"}
      </button>

      <p className={`ticket-message${result && !result.ok ? " is-error" : ""}`} role="status" aria-live="polite">
        {result?.message ?? ""}
      </p>
    </form>
  );
}
