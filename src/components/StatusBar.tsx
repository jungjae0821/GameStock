import { clock, tickLabel, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { totals } from "../market/selectors";

export function StatusBar() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const money = totals(snapshot);
  const positions = Object.keys(snapshot.portfolio.positions).length;

  const handleReset = () => {
    if (window.confirm("보유 종목과 체결 내역을 모두 지우고 시작 자본으로 되돌립니다.")) api.reset();
  };

  return (
    <footer className="statusbar">
      <div className="statusbar-inner">
        <span className="status-note num">{tickLabel(snapshot.tickMs)}마다 갱신</span>
        <p className="status-line num">
          보유 현금 {won(money.cash)}
          {positions > 0 && <> · 보유 {positions}종목</>}
          <span className="status-sep" aria-hidden="true" />
          {clock(snapshot.updatedAt)}
        </p>
        <button type="button" className="text-button" onClick={handleReset}>
          초기화
        </button>
      </div>
    </footer>
  );
}
