import { useState } from "react";
import { AdvancedOrderTicket } from "./AdvancedOrderTicket";
import { OrderBook } from "./OrderBook";
import { SeriesChart } from "./SeriesChart";
import { TradePrints } from "./TradePrints";
import { RestrictionBadge } from "./RestrictionBadge";

/** Existing exchange tools remain available without replacing reference UI. */
export function TradingTools({ code }: { code: string }) {
  const [open, setOpen] = useState(false);
  const [price, setPrice] = useState<number | null>(null);
  return (
    <details className="integration-tools" onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>호가 · 지정가 주문 · 상세 차트</summary>
      {open && <div className="integration-trading">
        <RestrictionBadge code={code} />
        <SeriesChart code={code} />
        <OrderBook code={code} onPriceSelect={setPrice} />
        <AdvancedOrderTicket code={code} selectedLimitPrice={price} />
        <TradePrints code={code} />
      </div>}
    </details>
  );
}
