import { useEffect, useState } from 'react';
import { serverTimestamp } from './format';
import { api } from './market';

/*
 * 웹 src/lib/billboard.ts와 같은 전광판. 홈 맨 위에 투자자가 모의 자금으로 올린 한 줄 문구를 보인다.
 * 1분마다 다시 받고, 가장 먼저 끝나는 문구의 끝에도 한 번 더 받는다.
 */
const POLL_MS = 60_000;

export function useBillboard() {
  const [state, setState] = useState({ board: null, failed: false });
  useEffect(() => {
    let alive = true;
    let endTimer = null;
    const load = async () => {
      try {
        const board = await api('/api/billboard');
        if (!alive) return;
        setState({ board, failed: false });
        clearTimeout(endTimer);
        // 끝 시각에 시간대가 빠져 있으면 웹처럼 UTC로 읽는다. 기기 시간으로 읽으면 9시간 어긋난다.
        const end = board?.live?.length ? Math.min(...board.live.map(item => serverTimestamp(item.endsAt))) : NaN;
        if (Number.isFinite(end)) endTimer = setTimeout(load, Math.max(0, end - Date.now()) + 500);
      } catch {
        if (alive) setState(current => ({ ...current, failed: true }));
      }
    };
    load();
    const timer = setInterval(load, POLL_MS);
    return () => { alive = false; clearInterval(timer); clearTimeout(endTimer); };
  }, []);
  return state;
}
