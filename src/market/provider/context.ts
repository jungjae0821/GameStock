import { createContext, useContext } from "react";
import type { DailyMissionStatus, LoanStatus, MarketSnapshot, OrderRequest, OrderResult } from "../types";

/* 화면이 쓰는 시세 스냅샷과 주문·계좌 API의 컨텍스트, 그리고 그것을 꺼내는 훅. */

export interface MarketApi {
  placeOrder: (request: OrderRequest) => Promise<OrderResult>;
  cancelOpenOrder: (code: string) => Promise<CancelOrdersResult>;
  cancelAllOpenOrders: () => Promise<CancelOrdersResult>;
  orderable: (code: string, side: "buy" | "sell") => number;
  toggleWatch: (code: string) => void;
  setActiveDetailCode: (code: string | null) => void;
  /** 파생 거래 뒤 주식 계좌 현금을 즉시 맞춘다. */
  refreshPortfolio: () => Promise<void>;
  claimMissionReward: (missionId: string) => Promise<{ rewardCash: number; awarded: boolean; missions: DailyMissionStatus }>;
  /** 대출을 받거나 갚고, 바뀐 계좌를 바로 화면에 반영한다. */
  loanAction: (kind: "borrow" | "repay", amount: number) => Promise<{ message: string; loan: LoanStatus }>;
}

export type CancelOrdersResult = {
  ok: boolean;
  cancelled: number;
  message: string;
};

export const SnapshotContext = createContext<MarketSnapshot | null>(null);
export const ApiContext = createContext<MarketApi | null>(null);

export function useMarket(): MarketSnapshot {
  const snapshot = useContext(SnapshotContext);
  if (!snapshot) throw new Error("MarketProvider 안에서만 사용할 수 있습니다.");
  return snapshot;
}

export function useMarketApi(): MarketApi {
  const api = useContext(ApiContext);
  if (!api) throw new Error("MarketProvider 안에서만 사용할 수 있습니다.");
  return api;
}
