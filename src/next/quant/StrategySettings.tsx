import type { Project } from "../../lib/research";
import { LISTING_BY_CODE } from "../../market/universe";
import { STATUS_LABEL, digits, type Draft } from "./draft";

/** 이름·종목·캔들·주문 한도. */
export function StrategySettings({ formId, project, draft, update, stockCodes, ranges }: {
  formId: string; project: Project | null; draft: Draft; update: (patch: Partial<Draft>) => void;
  stockCodes: string[]; ranges: Array<{ value: string; label: string }>;
}) {
  return (
    <section className="nx-panel qx-settings" aria-labelledby={`${formId}-settings`}>
      <header className="nx-panel-head">
        <h2 id={`${formId}-settings`} className="nx-panel-title">{project ? "전략 편집" : "새 전략"}</h2>
        {project && <span className={`qx-status is-${project.status.toLowerCase()}`}>{STATUS_LABEL[project.status]} · r{project.revision}</span>}
      </header>
      <div className="qx-fields">
        <div className="nx-field qx-field-name">
          <label htmlFor={`${formId}-name`}>이름</label>
          <input id={`${formId}-name`} className="nx-input" value={draft.name} maxLength={80} onChange={(event) => update({ name: event.target.value })} />
        </div>
        <div className="nx-field">
          <label htmlFor={`${formId}-stock`}>종목</label>
          <select id={`${formId}-stock`} className="nx-select" value={draft.stockCode} onChange={(event) => update({ stockCode: event.target.value })}>
            {stockCodes.map((stock) => <option key={stock} value={stock}>{LISTING_BY_CODE[stock]?.name ?? stock} · {stock}</option>)}
          </select>
        </div>
        <div className="nx-field">
          <label htmlFor={`${formId}-range`}>캔들</label>
          <select id={`${formId}-range`} className="nx-select" value={draft.range} onChange={(event) => update({ range: event.target.value })}>
            {ranges.map((item) => <option key={item.value} value={item.value}>{item.label}봉</option>)}
          </select>
        </div>
        <div className="nx-field">
          <label htmlFor={`${formId}-orders`}>주문 횟수 한도</label>
          <div className="nx-stepper is-plain">
            <input id={`${formId}-orders`} className="nx-num" inputMode="numeric" value={draft.maxOrders} onChange={(event) => update({ maxOrders: digits(event.target.value).slice(0, 3) })} />
            <span className="nx-stepper-unit">회</span>
          </div>
        </div>
        <div className="nx-field">
          <label htmlFor={`${formId}-notional`}>주문 금액 한도</label>
          <div className="nx-stepper is-plain">
            <input id={`${formId}-notional`} className="nx-num" inputMode="numeric" value={draft.maxNotional ? Number(draft.maxNotional).toLocaleString("ko-KR") : ""}
              onChange={(event) => update({ maxNotional: digits(event.target.value).slice(0, 13) })} />
            <span className="nx-stepper-unit">원</span>
          </div>
        </div>
      </div>
      <p className="nx-field-note">한도는 매수·매도 주문을 낸 횟수와 「지정가 × 수량」을 모두 더해 세요. 실패하거나 체결되지 않아도 줄어들지 않고, 중지·편집해도 초기화되지 않아요.</p>
    </section>
  );
}
