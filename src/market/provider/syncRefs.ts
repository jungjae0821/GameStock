import { useRef } from "react";
import type { Dispatch, RefObject, SetStateAction } from "react";
import type { openMarketStream } from "../stream";
import type { MarketSnapshot, Portfolio } from "../types";

/** 시세 연결(useMarketFeed)과 주문·계좌 동작(useMarketActions)이 함께 보는 값들. 렌더를 일으키지 않도록 ref로 둔다. */
export type MarketSync = {
  setSnapshot: Dispatch<SetStateAction<MarketSnapshot>>;
  watchRef: RefObject<string[]>;
  /* 토글 요청과 2초 watchlist 조회의 경쟁 상태를 가리는 번호.
     mutation은 토글 때마다, settled은 그 요청이 끝날 때 따라 잡는다. */
  watchMutationSeq: RefObject<number>;
  watchSettledSeq: RefObject<number>;
  hasServerSnapshotRef: RefObject<boolean>;
  activeDetailCodeRef: RefObject<string | null>;
  streamRef: RefObject<ReturnType<typeof openMarketStream> | null>;
  /** 웹소켓이나 직접 조회로 계좌가 바뀔 때마다 오른다. 그보다 먼저 시작한 조회는 계좌를 덮어쓰지 않는다. */
  privateRevisionRef: RefObject<number>;
  snapshotFillsRef: RefObject<Portfolio["fills"]>;
};

export function useMarketSync(setSnapshot: Dispatch<SetStateAction<MarketSnapshot>>, hasInitialCache: boolean): MarketSync {
  const watchRef = useRef<string[]>([]);
  const watchMutationSeq = useRef(0);
  const watchSettledSeq = useRef(0);
  const hasServerSnapshotRef = useRef(hasInitialCache);
  const activeDetailCodeRef = useRef<string | null>(null);
  const streamRef = useRef<ReturnType<typeof openMarketStream> | null>(null);
  const privateRevisionRef = useRef(0);
  const snapshotFillsRef = useRef<Portfolio["fills"]>([]);
  return { setSnapshot, watchRef, watchMutationSeq, watchSettledSeq, hasServerSnapshotRef, activeDetailCodeRef, streamRef, privateRevisionRef, snapshotFillsRef };
}
