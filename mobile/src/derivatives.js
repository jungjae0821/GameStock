import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { request, useAuth } from './auth';
import { api } from './market';

/*
 * ETF·선물·옵션 시세와 파생 계좌. 웹 src/market/derivatives.ts와 같은 API를 같은 2초 주기로 읽는다.
 * 미리 보기 식은 react-native 없이 테스트하려고 derivativeMath.js로 옮겼고, 화면은 그대로 여기서 가져간다.
 */
export {
  CONTRACT_MULTIPLIER, ETF_FEE_RATE, FUTURE_FEE_RATE, OPTION_FEE_RATE,
  countdown, futureCost, impliedVolatility, indexText, intrinsic, legExitValue, liquidationIndex, optionDelta, optionFee,
} from './derivativeMath';

const POLL_MS = 2000;

/** 화면이 열려 있고 enabled인 동안만 읽는다. 앱이 백그라운드로 가면 멈춘다. */
export function useDerivatives(includeAccount = true, enabled = true) {
  const { user } = useAuth();
  const [state, setState] = useState({ snapshot: null, account: null, error: false });
  const userRef = useRef(user);
  userRef.current = user;

  const load = useCallback(async () => {
    try {
      const signedIn = Boolean(userRef.current) && includeAccount;
      const [snapshot, account] = await Promise.all([
        api('/api/derivatives'),
        signedIn ? request('/api/derivatives/account') : Promise.resolve(null),
      ]);
      setState({ snapshot, account, error: false });
    } catch {
      setState(current => ({ ...current, error: true }));
    }
  }, [includeAccount]);

  useEffect(() => {
    setState(current => ({ ...current, account: null }));
    if (!enabled) return undefined;
    void load();
    const timer = setInterval(() => { if (AppState.currentState === 'active') void load(); }, POLL_MS);
    return () => clearInterval(timer);
  }, [user, load, enabled]);

  const setAccount = useCallback(account => setState(current => ({ ...current, account })), []);
  return { ...state, signedIn: Boolean(user), setAccount, reload: load };
}

export const openPosition = body => request('/api/derivatives/positions', { method: 'POST', body });
export const closePosition = (id, quantity) => request(`/api/derivatives/positions/${id}/close`, { method: 'POST', body: { quantity } });

/** 1초마다 갱신되는 현재 시각. 만기 카운트다운에만 쓴다. */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
