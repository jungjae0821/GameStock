import { useMarketApi } from "../market/MarketProvider";

export function StatusBar() {
  const api = useMarketApi();

  const handleReset = () => {
    if (window.confirm("보유 종목과 체결 내역을 모두 지우고 시작 자본으로 되돌립니다.")) api.reset();
  };

  return (
    <footer className="statusbar">
      <p>모의 시세입니다. 실제 시장 데이터가 아닙니다.</p>
      <button type="button" className="text-button" onClick={handleReset}>
        계좌 초기화
      </button>
    </footer>
  );
}
