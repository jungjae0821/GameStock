import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { request, useAuth } from './auth';

// 팬덤·시즌·공개 프로필·체결 피드·일일 예측·기능 해금, 그리고 프랍 커리어. 웹 src/lib/engagement.ts·career.ts와
// 같은 서버 /api/engagement/*, /api/career를 읽는다. 로그인한 동안 1분마다, 앱으로 돌아올 때 다시 읽는다.

const EngagementContext = createContext({ engagement: null, career: null, refresh: async () => {} });

/** { engagement, career, refresh, setFandom, setPublicProfile, submitPrediction } */
export const useEngagementState = () => useContext(EngagementContext);
export const useEngagement = () => useContext(EngagementContext).engagement;
export const useCareer = () => useContext(EngagementContext).career;

export function EngagementProvider({ children }) {
  const { user } = useAuth();
  const [state, setState] = useState({ engagement: null, career: null });
  const session = useRef(0);

  const load = useCallback(async () => {
    const current = session.current;
    // 아직 이 기능이 없는 서버이거나 일시 오류면 그 값만 두고 넘어간다. 서버가 주문을 최종 판정한다.
    const [engagement, career] = await Promise.all([
      request('/api/engagement/me').catch(() => undefined),
      request('/api/career').catch(() => undefined),
    ]);
    if (current !== session.current) return;
    setState(previous => ({
      engagement: engagement === undefined ? previous.engagement : engagement,
      career: career === undefined ? previous.career : career,
    }));
  }, []);

  useEffect(() => {
    session.current += 1;
    setState({ engagement: null, career: null });
    if (!user) return undefined;
    void load();
    const timer = setInterval(() => { if (AppState.currentState === 'active') void load(); }, 60_000);
    const subscription = AppState.addEventListener('change', next => { if (next === 'active') void load(); });
    return () => { clearInterval(timer); subscription.remove(); };
  }, [user, load]);

  const accept = useCallback(engagement => {
    setState(previous => ({ ...previous, engagement }));
    return engagement;
  }, []);

  const value = useMemo(() => ({
    ...state,
    refresh: load,
    // 주력 게임 고르기는 OJT 첫 단계라 커리어도 다시 읽는다.
    setFandom: stockCode => request('/api/engagement/fandom', { method: 'PUT', body: { stockCode } }).then(next => { void load(); return accept(next); }),
    setPublicProfile: publicProfile => request('/api/engagement/public', { method: 'PUT', body: { publicProfile } }).then(accept),
    submitPrediction: stockCode => request('/api/engagement/prediction', { method: 'POST', body: { stockCode } }).then(accept),
  }), [state, load, accept]);

  return <EngagementContext.Provider value={value}>{children}</EngagementContext.Provider>;
}

export const fetchFandoms = () => request('/api/engagement/fandoms', { auth: false });
export const fetchSeason = () => request('/api/engagement/season');
export const fetchPublicProfile = nickname => request(`/api/engagement/profiles/${encodeURIComponent(nickname)}`, { auth: false });
export const fetchFeed = () => request('/api/engagement/feed', { auth: false });

/** 로그인하지 않았거나 서버가 아직 해금 정보를 주지 않으면 잠그지 않는다(서버가 최종 판정). */
export function featureLock(engagement, id) {
  const item = engagement?.unlocks?.features?.find(feature => feature.id === id);
  return item && !item.unlocked ? item : null;
}

/*
 * 포트폴리오를 공개한 닉네임. 랭킹 줄을 누를 수 있는지 정할 때 쓴다. 여러 줄이 30초에 한 번 받은 값을 나눠 쓴다.
 */
let publicNames = new Set();
let publicLoadedAt = 0;
let publicLoading = false;
const publicListeners = new Set();

function loadPublicNames() {
  if (publicLoading || Date.now() - publicLoadedAt < 30_000) return;
  publicLoading = true;
  request('/api/engagement/public-nicknames', { auth: false })
    .then(names => {
      publicNames = new Set(Array.isArray(names) ? names : []);
      for (const listener of publicListeners) listener(publicNames);
    })
    .catch(() => { /* 기능이 없는 서버면 아무 이름도 누를 수 없게 둔다. */ })
    .finally(() => { publicLoadedAt = Date.now(); publicLoading = false; });
}

export function usePublicNicknames() {
  const [names, setNames] = useState(publicNames);
  useEffect(() => {
    publicListeners.add(setNames);
    loadPublicNames();
    const timer = setInterval(loadPublicNames, 30_000);
    return () => { publicListeners.delete(setNames); clearInterval(timer); };
  }, []);
  return names;
}

/**
 * 주기적으로 다시 읽는 값. 한 번 받은 뒤에는 오류가 나도 마지막 값을 둔다.
 * state: loading · ready · error · missing(서버가 아직 이 기능을 모름, 404)
 */
export function usePolledValue(load, intervalMs, deps = []) {
  const [result, setResult] = useState({ data: null, state: 'loading' });
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    let active = true;
    let busy = false;
    setResult({ data: null, state: 'loading' });
    const run = async () => {
      if (!active || busy || AppState.currentState !== 'active') return;
      busy = true;
      try {
        const data = await loadRef.current();
        if (active) setResult({ data, state: 'ready' });
      } catch (error) {
        if (active) setResult(current => (current.state === 'ready' ? current : { data: null, state: error?.status === 404 ? 'missing' : 'error' }));
      } finally {
        busy = false;
      }
    };
    void run();
    const timer = setInterval(run, intervalMs);
    return () => { active = false; clearInterval(timer); };
  }, [intervalMs, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  return result;
}

/* 한국어 조사. 받침 유무에 따라 이/가, 은/는, 이에요/예요가 갈린다(웹 src/lib/korean.ts와 같다). */
function hasFinalConsonant(word) {
  const text = String(word ?? '').trim();
  const code = text.charCodeAt(text.length - 1);
  if (Number.isNaN(code) || code < 0xac00 || code > 0xd7a3) return false;
  return (code - 0xac00) % 28 !== 0;
}
export const withSubject = word => `${word}${hasFinalConsonant(word) ? '이' : '가'}`;
export const withTopic = word => `${word}${hasFinalConsonant(word) ? '은' : '는'}`;
export const withCopula = word => `${word}${hasFinalConsonant(word) ? '이에요' : '예요'}`;
export const withObject = word => `${word}${hasFinalConsonant(word) ? '을' : '를'}`;
