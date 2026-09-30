import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { LISTING_BY_CODE } from "../market/universe";
import { OrderTicket } from "./OrderTicket";

/** Native modal keeps background controls inert and keyboard focus inside the order form. */
export function OrderDialog({ code, initialSide, selectedLimitPrice, onClose }: {
  code: string;
  initialSide: "buy" | "sell";
  selectedLimitPrice: number | null;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    return () => {
      element.close();
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus({ preventScroll: true });
    };
  }, []);

  return createPortal(
    <dialog ref={dialog} className="stock-order-dialog" aria-labelledby="stock-order-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose();
      }}>
      <div className="stock-order-dialog-content">
        <header className="stock-order-dialog-head">
          <div><h2 id="stock-order-title">{LISTING_BY_CODE[code]?.name ?? code} 주문</h2><p>시장가·호가 지정가</p></div>
          <button type="button" className="stock-order-close" aria-label="주문창 닫기" onClick={onClose} autoFocus>×</button>
        </header>
        <div className="stock-order-dialog-body"><OrderTicket code={code} initialSide={initialSide} selectedLimitPrice={selectedLimitPrice} /></div>
      </div>
    </dialog>, document.body,
  );
}
