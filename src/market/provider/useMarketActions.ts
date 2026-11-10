import { useMemo } from "react";
import { announceMissionReward } from "../../components/MissionRewardToast";
import { apiFetch } from "../../lib/api";
import { requireSignIn } from "../../lib/auth";
import { firebaseAuth } from "../../lib/firebase";
import type { DailyMissionStatus, MarketSnapshot } from "../types";
import { toFills, toPortfolio, type BackendActiveOrder, type BackendLoanAction, type BackendMissionReward, type BackendPortfolio, type BackendSettlement } from "./backendMapping";
import type { MarketApi } from "./context";
import type { MarketSync } from "./syncRefs";

/** 주문·취소·관심종목·미션 보상·대출처럼 계좌를 바꾸는 동작을 MarketApi로 묶는다. */
export function useMarketActions(snapshot: MarketSnapshot, sync: MarketSync): MarketApi {
  const { setSnapshot, watchRef, watchMutationSeq, watchSettledSeq, activeDetailCodeRef, streamRef, privateRevisionRef, snapshotFillsRef } = sync;

  /**
   * 주문·취소 응답에는 계좌가 없다. 웹소켓 계좌 이벤트가 늦거나 빠지면 다음 30초 조회까지
   * 잔고가 낡으므로, 성공 직후 커밋된 계좌를 한 번 받아 온다. 그사이 웹소켓으로 더 새로운
   * 계좌가 들어왔으면 그 값을 덮어쓰지 않는다.
   */
  const refreshPortfolio = async () => {
    const owner = firebaseAuth.currentUser;
    if (!owner) return;
    const revision = privateRevisionRef.current;
    try {
      const [portfolio, settlements] = await Promise.all([
        apiFetch<BackendPortfolio>("/api/portfolio"),
        apiFetch<BackendSettlement[]>("/api/settlements").catch(() => null),
      ]);
      if (firebaseAuth.currentUser?.uid !== owner.uid || privateRevisionRef.current !== revision) return;
      privateRevisionRef.current++;
      if (settlements) snapshotFillsRef.current = toFills(settlements);
      setSnapshot((current) => ({ ...current, portfolio: { ...toPortfolio(portfolio), fills: snapshotFillsRef.current }, updatedAt: Date.now() }));
    } catch {
      /* 다음 주기 조회나 웹소켓 이벤트가 계좌를 맞춘다. */
    }
  };

  // setSnapshot과 모듈 함수만 사용하므로 렌더마다 새로 만들어도 동작이 같다.
  const claimMissionReward = async (missionId: string) => {
    const claimant = firebaseAuth.currentUser;
    if (!claimant) {
      requireSignIn();
      throw new Error("미션 보상은 로그인 후 받을 수 있습니다.");
    }
    const result = await apiFetch<BackendMissionReward>(`/api/missions/${encodeURIComponent(missionId)}/reward`, { method: "POST" });
    if (firebaseAuth.currentUser?.uid === claimant.uid) {
      setSnapshot((current) => ({
        ...current,
        portfolio: toPortfolio(result.portfolio),
        updatedAt: Date.now(),
      }));
    }
    return { rewardCash: result.rewardCash, awarded: result.awarded, missions: result.missions };
  };

  const loanAction = async (kind: "borrow" | "repay", amount: number) => {
    const borrower = firebaseAuth.currentUser;
    if (!borrower) {
      requireSignIn("/loan");
      throw new Error("대출은 로그인 후 이용할 수 있습니다.");
    }
    const result = await apiFetch<BackendLoanAction>(`/api/loan/${kind}`, { method: "POST", body: JSON.stringify({ amount }) });
    if (firebaseAuth.currentUser?.uid === borrower.uid) {
      privateRevisionRef.current++;
      setSnapshot((current) => ({
        ...current,
        portfolio: { ...toPortfolio(result.portfolio), fills: current.portfolio.fills },
        updatedAt: Date.now(),
      }));
    }
    return { message: result.message, loan: result.loan };
  };

  return useMemo<MarketApi>(() => ({
    orderable: (code, side) => {
      const quote = snapshot.quotes[code];
      if (!quote) return 0;
      if (side === "buy") return Math.floor(snapshot.portfolio.cash / quote.price);
      // 서버가 미정산분과 대기 중인 매도 주문을 뺀 수량을 준다. 예전 서버 응답이면 보유 수량을 쓴다.
      const position = snapshot.portfolio.positions[code];
      return position?.availableQty ?? position?.qty ?? 0;
    },
    placeOrder: async (request) => {
      if (!requireSignIn(`/market/${request.code}`)) return { ok: false, message: "로그인 후 주문할 수 있습니다." };
      try {
        const result = await apiFetch<{ message?: string; price?: number }>("/api/orders", {
          method: "POST",
          body: JSON.stringify({
            stockCode: request.code,
            side: request.side,
            quantity: request.qty,
            orderType: request.orderType ?? "MARKET",
            ...(request.price ? { price: request.price } : {}),
          }),
        });
        void refreshPortfolio();
        return {
          ok: true,
          message: result.message ?? `${request.qty}주 ${request.side === "buy" ? "매수" : "매도"} 체결`,
          fill: { id: Date.now(), at: Date.now(), code: request.code, side: request.side, qty: request.qty, price: result.price ?? snapshot.quotes[request.code]?.price ?? 0 },
        };
      } catch (error) {
        return { ok: false, message: error instanceof Error ? error.message : "주문 처리에 실패했습니다." };
      }
    },
    cancelOpenOrder: async (code) => {
      if (!requireSignIn(`/market/${code}`)) return { ok: false, cancelled: 0, message: "로그인 후 주문을 취소할 수 있습니다." };
      try {
        const orders = await apiFetch<BackendActiveOrder[]>("/api/orders");
        const target = orders.find((order) => order.stockCode === code && order.status === "OPEN");
        if (!target) return { ok: true, cancelled: 0, message: "현재 종목에 취소할 미체결 주문이 없습니다." };
        await apiFetch(`/api/orders/${target.id}`, { method: "DELETE" });
        void refreshPortfolio();
        return { ok: true, cancelled: 1, message: "현재 종목의 미체결 주문 1건을 취소했습니다." };
      } catch (error) {
        return { ok: false, cancelled: 0, message: error instanceof Error ? error.message : "주문 취소에 실패했습니다." };
      }
    },
    cancelAllOpenOrders: async () => {
      if (!requireSignIn()) return { ok: false, cancelled: 0, message: "로그인 후 주문을 취소할 수 있습니다." };
      let cancelled = 0;
      try {
        // /api/orders는 화면용으로 최대 5건만 반환하므로, 남은 주문이 없어질 때까지 반복한다.
        for (let attempt = 0; attempt < 20; attempt += 1) {
          const orders = await apiFetch<BackendActiveOrder[]>("/api/orders");
          if (orders.length === 0) break;
          await Promise.all(orders.map((order) => apiFetch(`/api/orders/${order.id}`, { method: "DELETE" })));
          cancelled += orders.length;
          if (orders.length < 5) break;
        }
        if (cancelled > 0) void refreshPortfolio();
        return {
          ok: true,
          cancelled,
          message: cancelled > 0 ? `미체결 주문 ${cancelled}건을 모두 취소했습니다.` : "취소할 미체결 주문이 없습니다.",
        };
      } catch (error) {
        return { ok: false, cancelled, message: error instanceof Error ? error.message : "전체 주문 취소에 실패했습니다." };
      }
    },
    toggleWatch: (code) => {
      if (!requireSignIn()) return;
      const exists = watchRef.current.includes(code);
      const previous = watchRef.current;
      const optimistic = exists ? previous.filter((item) => item !== code) : [...previous, code];
      watchRef.current = optimistic;
      setSnapshot((current) => ({ ...current, watch: watchRef.current }));
      const seq = ++watchMutationSeq.current;
      void apiFetch(`/api/watchlist/${code}`, { method: exists ? "DELETE" : "PUT" })
        .then(() => {
          if (exists) return;
          // 관심종목 미션은 서버가 등록 여부를 확인한 뒤 지급한다. 오늘 배정되지 않았거나 이미
          // 받은 날에 요청하면 서버가 400으로 거절하므로 배정 여부를 먼저 확인한다.
          return apiFetch<DailyMissionStatus>("/api/missions")
            .then((missions) => {
              if (!missions.availableMissionIds.includes("watch") || missions.completedMissionIds.includes("watch")) return;
              return claimMissionReward("watch")
                .then((result) => { if (result.awarded) announceMissionReward(result.rewardCash); });
            })
            .catch(() => undefined);
        })
        .catch(() => {
          // 실패한 토글은 다음 조회가 서버 값을 따르기 전까지 화면에 머무르지 않게 되돌린다.
          if (watchRef.current === optimistic) {
            watchRef.current = previous;
            setSnapshot((current) => ({ ...current, watch: watchRef.current }));
          }
        })
        .finally(() => {
          if (watchSettledSeq.current < seq) watchSettledSeq.current = seq;
        });
    },
    setActiveDetailCode: (code) => {
      activeDetailCodeRef.current = code;
      streamRef.current?.subscribe(code ? [code] : snapshot.codes, code);
    },
    refreshPortfolio,
    claimMissionReward,
    loanAction,
  }), [snapshot]);
}
