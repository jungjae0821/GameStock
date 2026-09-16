import { useState } from "react";
import { won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import type { OrderResult } from "../market/types";

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
];

export function OrderTicket({ code }: { code: string }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [side, setSide] = useState<"buy" | "sell">("buy");
  const [qty, setQty] = useState("");
  const [result, setResult] = useState<OrderResult | null>(null);

  const quote = snapshot.quotes[code];
  if (!quote) return null;

  const position = snapshot.portfolio.positions[code];
  const maxQty = api.orderable(code, side);
  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 1;
  const amount = valid ? parsed * quote.price : 0;
  const afterCash = side === "buy" ? snapshot.portfolio.cash - amount : snapshot.portfolio.cash + amount;
  const afterQty = side === "buy" ? (position?.qty ?? 0) + (valid ? parsed : 0) : (position?.qty ?? 0) - (valid ? parsed : 0);

  const setRatio = (ratio: number) => {
    const next = Math.min(maxQty, Math.max(1, Math.floor(maxQty * ratio)));
    setQty(String(maxQty > 0 ? next : ""));
    setResult(null);
  };

  const submit = async () => {
    const outcome = await api.placeOrder({ code, side, qty: parsed });
    setResult(outcome);
    if (outcome.ok) setQty("");
  };

  return (
    <form
      className="ticket"
      onSubmit={(event) => {
        event.preventDefault();
        if (valid) void submit();
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
        <label htmlFor="order-qty">수량</label>
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
            <button key={item.label} type="button" className="text-button" onClick={() => setRatio(item.ratio)} disabled={maxQty === 0}>
              {item.label}
            </button>
          ))}
          <button type="button" className="text-button" onClick={() => setRatio(1)} disabled={maxQty === 0}>
            최대
          </button>
        </div>
      </div>

      <dl className="ticket-summary">
        <div>
          <dt>{side === "buy" ? "주문 가능 금액" : "매도 가능 수량"}</dt>
          <dd className="num">{side === "buy" ? won(snapshot.portfolio.cash) : `${maxQty}주`}</dd>
        </div>
        <div>
          <dt>체결 기준가</dt>
          <dd className="num">{won(quote.price)}</dd>
        </div>
        <div>
          <dt>주문 금액</dt>
          <dd className="num">{won(amount)}</dd>
        </div>
        <div>
          <dt>{side === "buy" ? "체결 후 현금" : "체결 후 보유"}</dt>
          <dd className="num">{side === "buy" ? won(afterCash) : `${Math.max(0, afterQty)}주`}</dd>
        </div>
      </dl>

      <p className="ticket-note">현재가로 즉시 체결됩니다.</p>

      <button type="submit" className={`submit-button is-${side}`} disabled={!valid}>
        {side === "buy" ? "매수 주문" : "매도 주문"}
      </button>

      <p className={`ticket-message${result && !result.ok ? " is-error" : ""}`} role="status" aria-live="polite">
        {result?.message ?? ""}
      </p>
    </form>
  );
}
