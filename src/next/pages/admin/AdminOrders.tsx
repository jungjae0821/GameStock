import { useEffect, useState } from "react";
import { LISTINGS, LISTING_BY_CODE } from "../../../market/universe";
import { won } from "../../../market/format";
import { Panel } from "../../../components/Panel";
import { ORDER_STATUS, adminTime, count, num, useCursorList, type Row } from "./adminShared";

type Filters = { userId: string; stockCode: string; status: string };

const EMPTY: Filters = { userId: "", stockCode: "", status: "" };

/** 회원 상세의 "전체 주문 보기"는 ?userId=로 들어온다. */
const initialFilters = (): Filters => ({ ...EMPTY, userId: new URLSearchParams(window.location.search).get("userId")?.replace(/\D/g, "") ?? "" });

function query(filters: Filters) {
  const params = new URLSearchParams();
  if (filters.userId.trim()) params.set("userId", filters.userId.trim());
  if (filters.stockCode.trim()) params.set("stockCode", filters.stockCode.trim().toUpperCase());
  if (filters.status) params.set("status", filters.status);
  return params;
}

/** 전체 회원의 주문을 회원·종목·상태로 좁혀 본다. 최신 주문부터 50건씩 읽는다. */
export function AdminOrders() {
  const [draft, setDraft] = useState<Filters>(initialFilters);
  const [applied, setApplied] = useState<Filters>(initialFilters);
  const { rows, loading, done, error, load, lastId } = useCursorList("/api/admin/orders");

  useEffect(() => { void load(query(applied), false); }, [applied, load]);

  const apply = (next: Filters) => {
    setDraft(next);
    setApplied(next);
  };
  const filtered = applied.userId || applied.stockCode || applied.status;

  return (
    <Panel id="admin-orders" title="주문 조회" meta={loading ? "불러오는 중" : `${count(rows.length)}건${done ? "" : " 이상"}`} level={2}>
      <form className="account-form admin-filters" onSubmit={(event) => { event.preventDefault(); apply(draft); }}>
        <div className="account-field">
          <label htmlFor="orders-user">회원 번호</label>
          <input id="orders-user" inputMode="numeric" pattern="[0-9]*" value={draft.userId} placeholder="전체"
            onChange={(event) => setDraft({ ...draft, userId: event.target.value.replace(/\D/g, "") })} />
        </div>
        <div className="account-field">
          <label htmlFor="orders-stock">종목 코드</label>
          <input id="orders-stock" list="orders-stock-list" value={draft.stockCode} placeholder="전체" autoCapitalize="characters"
            onChange={(event) => setDraft({ ...draft, stockCode: event.target.value.toUpperCase() })} />
          <datalist id="orders-stock-list">
            {LISTINGS.map((listing) => <option key={listing.code} value={listing.code}>{listing.name}</option>)}
          </datalist>
        </div>
        <div className="account-field">
          <label htmlFor="orders-status">상태</label>
          <select id="orders-status" value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value })}>
            <option value="">전체</option>
            {Object.entries(ORDER_STATUS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        <div className="admin-filter-actions">
          <button type="submit" className="account-button is-primary" disabled={loading}>조회</button>
          {filtered && <button type="button" className="text-button" onClick={() => apply(EMPTY)}>조건 지우기</button>}
        </div>
      </form>

      {error && <p className="account-message" role="alert">{error}</p>}

      <div className="table-scroll admin-table-scroll">
        <table className="table admin-table admin-orders-table">
          <caption className="vh">주문 목록</caption>
          <thead>
            <tr>
              <th scope="col" className="cell-num">번호</th>
              <th scope="col">주문 시각</th>
              <th scope="col">회원</th>
              <th scope="col">종목</th>
              <th scope="col">구분</th>
              <th scope="col" className="cell-num">주문가</th>
              <th scope="col" className="cell-num">체결 / 수량</th>
              <th scope="col">상태</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => <OrderRow key={String(row.id)} row={row} onUser={(userId) => apply({ ...applied, userId })} />)}
            {!loading && !error && rows.length === 0 && (
              <tr><td colSpan={8} className="empty">{filtered ? "조건에 맞는 주문이 없습니다." : "아직 주문이 없습니다."}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {!done && rows.length > 0 && (
        <button type="button" className="account-button admin-more" disabled={loading} onClick={() => void load(query(applied), true, lastId)}>
          {loading ? "불러오는 중" : "다음 50건"}
        </button>
      )}
    </Panel>
  );
}

function OrderRow({ row, onUser }: { row: Row; onUser: (userId: string) => void }) {
  const code = String(row.stock_code ?? "");
  const quantity = num(row.quantity);
  const filled = quantity - num(row.remaining_quantity);
  const side = row.side === "BUY";
  const status = String(row.status ?? "");
  const userId = String(row.user_id ?? "");

  return (
    <tr>
      <td className="cell-num num">{String(row.id)}</td>
      <td className="num">{adminTime(row.created_at)}</td>
      <td>
        <button type="button" className="text-button" title="이 회원의 주문만 보기" onClick={() => onUser(userId)}>
          {String(row.nickname ?? "")}
        </button>
        <small className="admin-uid">#{userId}</small>
      </td>
      <td>
        {LISTING_BY_CODE[code]?.name ?? code}
        {LISTING_BY_CODE[code] && <small className="admin-uid">{code}</small>}
      </td>
      <td className={side ? "is-up" : "is-down"}>
        {side ? "매수" : "매도"}
        <small className="admin-uid">{row.order_type === "MARKET" ? "시장가" : "지정가"}</small>
      </td>
      <td className="cell-num num">{row.price ? won(num(row.price)) : "-"}</td>
      <td className="cell-num num">{count(filled)} / {count(quantity)}주</td>
      <td className={`admin-status is-${status.toLowerCase()}`}>{ORDER_STATUS[status] ?? status}</td>
    </tr>
  );
}
