import { firebaseAuth } from "../lib/firebase";
import { onAuthStateChanged } from "firebase/auth";
import { currentApiBaseUrl } from "../lib/api";

export type StreamMessage = { type: string; payload?: unknown };

/** Subscription transport only; REST remains the initial snapshot and reconnect fallback. */
export function openMarketStream(receive: (message: StreamMessage) => void, status: (ready: boolean) => void) {
  let socket: WebSocket | null = null;
  let stopped = false;
  let retry = 0;
  let reconnect: ReturnType<typeof setTimeout> | undefined;
  let symbols: string[] = [];
  let detailSymbols: string[] = [];
  let lastMessage = Date.now();
  const send = (message: unknown) => { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); };
  const authenticate = async () => {
    const user = firebaseAuth.currentUser;
    const target = socket;
    if (!user) return;
    try {
      const token = await user.getIdToken();
      if (firebaseAuth.currentUser === user && socket === target) send({ type: "AUTH", token });
    } catch { status(false); }
  };
  const connect = () => {
    if (stopped) return;
    const url = new URL("/ws/stream", currentApiBaseUrl());
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    const socketUid = firebaseAuth.currentUser?.uid;
    let authenticated = !socketUid;
    const target = new WebSocket(url);
    socket = target;
    target.onopen = () => {
      lastMessage = Date.now();
      send({ type: "SUBSCRIBE", symbols, detailSymbols });
      void authenticate();
    };
    target.onmessage = (event) => {
      if (socket !== target) return;
      try {
        const message = JSON.parse(event.data) as StreamMessage;
        if (message.type === "ERROR") { status(false); return; }
        if (message.type === "AUTHENTICATED" && socketUid === firebaseAuth.currentUser?.uid) authenticated = true;
        lastMessage = Date.now(); retry = 0; status(authenticated);
        const publicMessage = ["MARKET_SYMBOL", "PONG"].includes(message.type);
        if (publicMessage || socketUid === firebaseAuth.currentUser?.uid) receive(message);
      } catch { status(false); }
    };
    target.onclose = () => {
      if (socket !== target) return;
      status(false);
      if (!stopped) reconnect = setTimeout(connect, Math.min(15_000, 500 * 2 ** Math.min(retry++, 5)));
    };
    target.onerror = () => target.close();
  };
  const restart = () => {
    clearTimeout(reconnect);
    if (socket) { socket.onclose = null; socket.close(); }
    status(false); connect();
  };
  const unsubscribe = onAuthStateChanged(firebaseAuth, restart);
  window.addEventListener("gamestock:api-base", restart);
  const heartbeat = setInterval(() => {
    if (socket?.readyState === WebSocket.OPEN && Date.now() - lastMessage > 45_000) socket.close();
    else send({ type: "PING" });
  }, 15_000);
  const renew = setInterval(() => void authenticate(), 240_000);
  return {
    subscribe(next: string[], detail?: string | null) {
      const details = detail ? [detail] : [];
      if (JSON.stringify([symbols, detailSymbols]) === JSON.stringify([next, details])) return;
      symbols = next; detailSymbols = details; send({ type: "SUBSCRIBE", symbols, detailSymbols });
    },
    close() {
      stopped = true; unsubscribe(); clearTimeout(reconnect); clearInterval(heartbeat); clearInterval(renew);
      window.removeEventListener("gamestock:api-base", restart); socket?.close(); status(false);
    },
  };
}
