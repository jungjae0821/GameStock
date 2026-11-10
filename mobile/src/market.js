import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { API_BASE_URL } from './config';
import { serverTimestamp } from './format';

const STREAM_URL = API_BASE_URL.replace(/^http/, 'ws') + '/ws/stream';

export async function api(path, token) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    const error = new Error(`${path} ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

/**
 * 공개 시세. REST로 첫 화면을 채우고, /ws/stream 구독으로 종목 가격을 실시간 갱신한다.
 * 소켓이 끊겨 있으면 5초마다, 연결돼 있으면 30초마다 REST로 뉴스와 시장 상태를 다시 읽는다.
 */
export function useMarket() {
  const [state, setState] = useState({ stocks: [], events: [], status: null, loaded: false, error: null });
  const [live, setLive] = useState(false);
  const socketRef = useRef(null);
  const liveRef = useRef(false);
  const codesRef = useRef([]);
  // 상세 화면이 보고 있는 종목. 소켓에 detailSymbols로 구독하면 호가와 최근 체결도 함께 온다.
  const detailRef = useRef(null);
  const [detail, setDetail] = useState({ code: null, orderbook: null, trades: null });

  const load = useCallback(async () => {
    try {
      const [stocks, events, status] = await Promise.all([
        api('/api/stocks'), api('/api/market-events'), api('/api/market-status'),
      ]);
      codesRef.current = stocks.map(stock => stock.code);
      socketRef.current?.subscribe();
      setState({ stocks, events, status, loaded: true, error: null });
    } catch (error) {
      setState(current => ({ ...current, loaded: true, error }));
    }
  }, []);

  useEffect(() => {
    let stopped = false;
    let socket = null;
    let retry = 0;
    let reconnect;
    let lastMessage = Date.now();
    const setConnected = value => { liveRef.current = value; setLive(value); };
    const send = message => { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); };

    const connect = () => {
      if (stopped) return;
      const target = new WebSocket(STREAM_URL);
      socket = target;
      target.onopen = () => {
        lastMessage = Date.now();
        send({ type: 'SUBSCRIBE', symbols: codesRef.current, detailSymbols: detailRef.current ? [detailRef.current] : [] });
      };
      target.onmessage = event => {
        if (socket !== target) return;
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        lastMessage = Date.now();
        retry = 0;
        if (message.type === 'ERROR') { setConnected(false); return; }
        setConnected(true);
        if (message.type !== 'MARKET_SYMBOL') return;
        const stock = message.payload?.stock;
        if (!stock) return;
        const { symbol, orderbook, trades } = message.payload;
        if (symbol === detailRef.current && (orderbook || trades)) {
          setDetail(current => (current.code === symbol
            ? { code: symbol, orderbook: orderbook ?? current.orderbook, trades: trades?.length ? trades : current.trades }
            : current));
        }
        setState(current => ({
          ...current,
          stocks: current.stocks.map(old => (old.code === message.payload.symbol ? { ...old, ...stock } : old)),
        }));
      };
      target.onclose = () => {
        if (socket !== target) return;
        setConnected(false);
        if (!stopped) reconnect = setTimeout(connect, Math.min(15_000, 500 * 2 ** Math.min(retry++, 5)));
      };
      target.onerror = () => target.close();
    };

    socketRef.current = {
      subscribe: () => send({ type: 'SUBSCRIBE', symbols: codesRef.current, detailSymbols: detailRef.current ? [detailRef.current] : [] }),
    };
    const heartbeat = setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN && Date.now() - lastMessage > 45_000) socket.close();
      else send({ type: 'PING' });
    }, 15_000);
    let lastRest = Date.now();
    const poll = setInterval(() => {
      const due = liveRef.current ? 30_000 : 5_000;
      if (Date.now() - lastRest < due) return;
      lastRest = Date.now();
      void load();
    }, 2_500);

    // 백그라운드에서는 소켓을 닫고, 앱으로 돌아오면 다시 연결하며 한 번 새로 읽는다.
    const appState = AppState.addEventListener('change', next => {
      if (next === 'active') {
        if (!socket || socket.readyState > WebSocket.OPEN) { retry = 0; connect(); }
        lastRest = Date.now();
        void load();
      } else if (next === 'background') {
        clearTimeout(reconnect);
        const closing = socket;
        socket = null;
        closing?.close();
        setConnected(false);
      }
    });

    void load().then(connect);
    return () => {
      stopped = true;
      clearTimeout(reconnect);
      clearInterval(heartbeat);
      clearInterval(poll);
      appState.remove();
      socket?.close();
    };
  }, [load]);

  /** 상세 화면이 열리고 닫힐 때 부른다. 처음 값은 REST로 채우고, 이후로는 소켓이 갱신한다. */
  const watchDetail = useCallback(code => {
    detailRef.current = code;
    setDetail({ code, orderbook: null, trades: null });
    socketRef.current?.subscribe();
    if (!code) return;
    Promise.all([api(`/api/stocks/${code}/orderbook`), api(`/api/stocks/${code}/trades`)])
      .then(([orderbook, trades]) => setDetail(current => (current.code === code
        ? { code, orderbook: current.orderbook ?? orderbook, trades: current.trades ?? trades }
        : current)))
      .catch(() => {});
  }, []);

  return { ...state, live, refresh: load, detail, watchDetail };
}

/** 종목별 24시간 가격 기록. 홈의 추이선에만 쓰므로 5분마다 다시 읽는다. */
export function usePriceHistory(codes) {
  const [history, setHistory] = useState({});
  const key = codes.join(',');
  useEffect(() => {
    if (!key) return undefined;
    let cancelled = false;
    const read = async () => {
      const entries = await Promise.all(key.split(',').map(async code => {
        try { return [code, await api(`/api/stocks/${code}/history?range=24h`)]; } catch { return [code, null]; }
      }));
      if (cancelled) return;
      setHistory(current => {
        const next = { ...current };
        for (const [code, points] of entries) if (points) next[code] = points;
        return next;
      });
    };
    void read();
    const timer = setInterval(read, 5 * 60_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [key]);
  return history;
}

/**
 * 오늘 0시(한국시간)부터의 게임주 지수 10분 봉 종가. 서버가 저장한 지수 기록이라 날이 바뀌어도 이어진다.
 * 아직 받지 못했거나 기록이 없으면 null이다. 1분마다 다시 읽는다.
 */
export function useIndexToday() {
  const [points, setPoints] = useState(null);
  useEffect(() => {
    let alive = true;
    const read = () => api('/api/derivatives/index/today')
      .then(candles => { if (alive) setPoints(candles.length ? candles.map(candle => ({ recordedAt: candle.recordedAt, price: candle.closePrice })) : null); })
      .catch(() => {});
    void read();
    const timer = setInterval(read, 60_000);
    return () => { alive = false; clearInterval(timer); };
  }, []);
  return points;
}

/** 기준가 대비 등락률(비율). 서버의 changePercent와 같은 값을 가격에서 다시 계산한다. */
export const sessionRate = stock => {
  const reference = Number(stock.referencePrice) || 0;
  if (reference > 0) return (stock.price - reference) / reference;
  return (Number(stock.changePercent) || 0) / 100;
};

/**
 * 웹 MarketProvider의 stableNewsIds와 같은 해시로 기사 id를 붙이고 최신순으로 정렬한다.
 * id는 받은 순서대로 붙여야 웹과 같아지므로 정렬은 id를 붙인 뒤에 한다.
 */
export function newsWithIds(events) {
  const used = new Set();
  return events
    .map(event => {
      const key = `${event.stockCode}|${event.publishedAt ?? ''}|${event.title}`;
      let hash = 0x811c9dc5;
      for (let i = 0; i < key.length; i++) hash = Math.imul(hash ^ key.charCodeAt(i), 0x01000193);
      let id = (hash >>> 0) % 2_000_000_000 + 1;
      while (used.has(id)) id += 1;
      used.add(id);
      // 시각은 웹처럼 시간대가 없으면 UTC로 읽는다. 시각이 없는 글은 웹과 달리 맨 뒤로 보낸다.
      return { ...event, id, at: serverTimestamp(event.publishedAt) || 0 };
    })
    .sort((a, b) => b.at - a.at);
}

/** 발행 시점 가격 대비 지금 가격의 변화(비율). 웹 selectors.newsEffect와 같다. 시세가 없으면 서버 값을 쓴다. */
export function newsEffect(item, stock) {
  const atPublish = Number(item.priceAtPublish) || 0;
  if (stock && atPublish > 0) return (stock.price - atPublish) / atPublish;
  const percent = Number(item.priceChangePercent);
  return Number.isFinite(percent) ? percent / 100 : 0;
}
