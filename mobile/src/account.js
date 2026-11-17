import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { request, useAuth } from './auth';
import { won } from './format';
import { announce } from './rewards';

const AccountContext = createContext(null);
export const useAccount = () => useContext(AccountContext);

/**
 * 로그인 사용자의 계좌와 관심 종목. 시장·주문·내 계좌 화면이 같은 값을 보도록 한 곳에서 읽는다.
 * status: idle(로그인 전) · loading · ready · error. 주문이나 관심 변경 뒤에는 reload로 바로 다시 읽는다.
 */
export function AccountProvider({ children }) {
  const { user } = useAuth();
  const [state, setState] = useState({ portfolio: null, watchlist: [], status: 'idle' });
  const session = useRef(0);

  const reload = useCallback(async () => {
    const current = session.current;
    try {
      const [portfolio, watchlist] = await Promise.all([request('/api/portfolio'), request('/api/watchlist').catch(() => null)]);
      if (current !== session.current) return;
      setState(previous => ({
        portfolio,
        watchlist: watchlist ? watchlist.map(entry => entry.stockCode) : previous.watchlist,
        status: 'ready',
      }));
    } catch {
      if (current === session.current) setState(previous => ({ ...previous, status: 'error' }));
    }
  }, []);

  useEffect(() => {
    session.current += 1;
    if (!user) { setState({ portfolio: null, watchlist: [], status: 'idle' }); return undefined; }
    setState({ portfolio: null, watchlist: [], status: 'loading' });
    void reload();
    const timer = setInterval(() => void reload(), 15_000);
    return () => clearInterval(timer);
  }, [user, reload]);

  /** 관심 종목 켜고 끄기. 화면을 먼저 바꾸고, 서버가 거절하면 되돌린다. */
  const toggleWatch = useCallback(async code => {
    const watching = state.watchlist.includes(code);
    setState(previous => ({ ...previous, watchlist: watching ? previous.watchlist.filter(item => item !== code) : [...previous.watchlist, code] }));
    try {
      await request(`/api/watchlist/${code}`, { method: watching ? 'DELETE' : 'PUT' });
      // 관심 종목 담기 미션은 서버가 등록 여부를 확인한 뒤 지급한다(웹 MarketProvider와 같다).
      // 오늘 배정되지 않았거나 이미 받은 날에는 서버가 거절하므로 배정 여부를 먼저 확인한다.
      if (!watching) {
        void request('/api/missions')
          .then(missions => {
            if (!missions?.availableMissionIds?.includes('watch') || missions.completedMissionIds?.includes('watch')) return null;
            return request('/api/missions/watch/reward', { method: 'POST' }).then(result => {
              if (result?.awarded) announce(`미션 보상 ${won(result.rewardCash)}을 받았어요.`, 'checkmark-circle');
              return reload();
            });
          })
          .catch(() => {});
      }
    } catch (error) {
      setState(previous => ({ ...previous, watchlist: watching ? [...previous.watchlist, code] : previous.watchlist.filter(item => item !== code) }));
      throw error;
    }
  }, [state.watchlist, reload]);

  const value = useMemo(() => ({ ...state, reload, toggleWatch }), [state, reload, toggleWatch]);
  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
}

/** 보유 수량. */
export const heldQuantity = (portfolio, code) => portfolio?.positions?.find(position => position.stockCode === code)?.quantity ?? 0;
/** 계좌에서 그 종목의 보유 정보. 매도 가능 수량은 trading.js sellableQuantity로 계산한다. */
export const positionOf = (portfolio, code) => portfolio?.positions?.find(position => position.stockCode === code) ?? null;
