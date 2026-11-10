import { useState } from "react";
import type { ReactNode } from "react";
import { BackendLoadingScreen } from "../components/BackendLoadingScreen";
import { MarketEngine } from "./engine";
import { toSnapshot } from "./provider/backendMapping";
import { ApiContext, SnapshotContext } from "./provider/context";
import { readPublicCache } from "./provider/publicCache";
import { useMarketSync } from "./provider/syncRefs";
import { useMarketActions } from "./provider/useMarketActions";
import { useMarketFeed } from "./provider/useMarketFeed";
import type { MarketSnapshot } from "./types";

/*
 * 시세·계좌 상태의 진입점. 실제 동작은 provider/ 아래로 나눠 두었다.
 * - backendMapping: 백엔드 응답 → MarketSnapshot 변환
 * - publicCache: 새로고침 직후 먼저 그릴 공개 시세 캐시
 * - useMarketFeed: 웹소켓·REST 주기 조회 연결
 * - useMarketActions: 주문·관심종목·미션·대출 동작(MarketApi)
 * - context: useMarket / useMarketApi
 */
export { useMarket, useMarketApi } from "./provider/context";
export type { CancelOrdersResult, MarketApi } from "./provider/context";

const fallbackEngine = new MarketEngine(20260910);

function fallbackSnapshot(): MarketSnapshot {
  return fallbackEngine.getSnapshot();
}

export function MarketProvider({ children }: { children: ReactNode }) {
  const [initialCache] = useState(readPublicCache);
  const [snapshot, setSnapshot] = useState<MarketSnapshot>(() =>
    initialCache ? toSnapshot(initialCache.stocks, initialCache.events, undefined, [], initialCache.market) : fallbackSnapshot());
  const [serverAvailable, setServerAvailable] = useState(Boolean(initialCache));
  const sync = useMarketSync(setSnapshot, Boolean(initialCache));

  useMarketFeed(sync, setServerAvailable);
  const api = useMarketActions(snapshot, sync);

  return (
    <ApiContext.Provider value={api}>
      <SnapshotContext.Provider value={snapshot}>
        {serverAvailable || sync.hasServerSnapshotRef.current ? children : <BackendLoadingScreen />}
      </SnapshotContext.Provider>
    </ApiContext.Provider>
  );
}
