import { AccountSettings } from "./AccountSettings";
import { useState } from "react";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { HoldingsTable } from "../components/HoldingsTable";
import { Link } from "../components/Link";
import { Panel } from "../components/Panel";
import { clock as hm, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { totals } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";

export function MyPage() {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const snapshot = useMarket();
  const money = totals(snapshot);
  const fills = snapshot.portfolio.fills;
  const holdingCount = Object.keys(snapshot.portfolio.positions).length;

  return (
    <div className="page-stack">
      <h1 className="page-title">내 계좌</h1>

      <section className="head" aria-label="계좌 요약">
        <div>
          <p className="head-label">총 자산</p>
          <p className="head-value">{won(money.total)}</p>
          <p className="head-sub">
            <Delta change={money.pnl} ratio={money.pnlRate} />
          </p>
        </div>
        <dl className="head-me kv">
          <div>
            <dt>현금</dt>
            <dd className="num">{won(money.cash)}</dd>
          </div>
          <div>
            <dt>주식</dt>
            <dd className="num">{won(money.stockValue)}</dd>
          </div>
        </dl>
      </section>

      <Panel id="mypage-holdings" title="보유 종목" action={{ label: "종목 찾기", to: "/market" }}>
        {holdingCount > 0 ? (
          <HoldingsTable caption="내 보유 종목과 평가 손익" />
        ) : (
          <p className="empty">보유한 종목이 없습니다.</p>
        )}
      </Panel>

      <Panel id="mypage-fills" title="체결 내역">
        {fills.length > 0 ? (
          <div className="table-scroll">
            <table className="table">
              <caption className="vh">최근 체결 내역</caption>
              <thead>
                <tr>
                  <th scope="col" className="cell-name">
                    게임
                  </th>
                  <th scope="col">구분</th>
                  <th scope="col" className="cell-num">
                    수량
                  </th>
                  <th scope="col" className="cell-num">
                    금액
                  </th>
                  <th scope="col" className="cell-num">
                    시각
                  </th>
                </tr>
              </thead>
              <tbody>
                {fills.map((fill) => (
                  <tr key={fill.id}>
                    <th scope="row" className="cell-name">
                      <Link className="name-link" to={`/market/${fill.code}`}>
                        <GameIcon code={fill.code} />
                        <span className="name-text">{LISTING_BY_CODE[fill.code]?.name ?? fill.code}</span>
                      </Link>
                    </th>
                    <td className={fill.side === "buy" ? "is-up" : "is-down"}>{fill.side === "buy" ? "매수" : "매도"}</td>
                    <td className="cell-num num">{fill.qty}주</td>
                    <td className="cell-num num">{won(fill.price * fill.qty)}</td>
                    <td className="cell-num num">{hm(fill.at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="empty">체결 내역이 없습니다.</p>
        )}
      </Panel>
      <details className="account-tools" onToggle={(event) => setSettingsOpen(event.currentTarget.open)}>
        <summary className="account-tools-summary">
          <span>계정 관리 · 미체결 주문 · 가격 알림</span>
          <span className="account-tools-toggle" aria-hidden="true">{settingsOpen ? "접기 −" : "펼치기 +"}</span>
        </summary>
        {settingsOpen && <AccountSettings />}
      </details>
    </div>
  );
}
