import { useEffect, useRef } from 'react';
import { AppState } from 'react-native';
import { compactWon } from './components/Engagement';
import { fetchFeed, withObject } from './engagement';
import { cleanTitle, signedPercent } from './format';
import { newsEffect, newsWithIds } from './market';
import { announce } from './rewards';

const BIG_NEWS = 0.03;
const FEED_INTERVAL_MS = 20_000;
const MAX_AT_ONCE = 3;

/**
 * 화면 어디에 있든 탭바 위에 짧게 뜨는 시장 알림(웹 MarketAlerts와 같은 기준).
 * 3천만원 넘는 사람 체결(고래)과, 발행 뒤 시세가 3% 넘게 움직인 뉴스만 띄운다. 앱을 처음 켤 때 이미 있던 건 알리지 않는다.
 */
export function useMarketAlerts(market) {
  const seen = useRef(new Set());
  const primed = useRef({ feed: false, news: false });

  const push = (alerts, kind) => {
    const fresh = alerts.filter(alert => !seen.current.has(alert.key));
    fresh.forEach(alert => seen.current.add(alert.key));
    if (!primed.current[kind]) { primed.current[kind] = true; return; }
    for (const alert of fresh.slice(-MAX_AT_ONCE)) announce(alert.text, alert.icon);
  };
  const pushRef = useRef(push);
  pushRef.current = push;

  useEffect(() => {
    let active = true;
    const run = () => {
      if (AppState.currentState !== 'active') return;
      fetchFeed()
        .then(items => {
          if (!active || !Array.isArray(items)) return;
          pushRef.current(items.filter(item => item.whale).map(item => ({
            key: `whale:${item.id}:${item.side}`,
            icon: 'flash',
            text: `고래 등장 · ${item.nickname ?? '누군가'}님이 ${withObject(item.name)} ${compactWon(item.amount)}어치 ${item.side === 'BUY' ? '샀어요' : '팔았어요'}`,
          })), 'feed');
        })
        .catch(() => { /* 기능이 없는 서버거나 일시 오류다. 다음 주기에 다시 읽는다. */ });
    };
    run();
    const timer = setInterval(run, FEED_INTERVAL_MS);
    return () => { active = false; clearInterval(timer); };
  }, []);

  const { events, stocks } = market;
  useEffect(() => {
    if (!events?.length) return;
    const quotes = Object.fromEntries(stocks.map(stock => [stock.code, stock]));
    pushRef.current(newsWithIds(events)
      .filter(item => Math.abs(newsEffect(item, quotes[item.stockCode])) >= BIG_NEWS)
      .map(item => {
        const ratio = newsEffect(item, quotes[item.stockCode]);
        return {
          key: `news:${item.id}`,
          icon: 'newspaper',
          text: `${quotes[item.stockCode]?.name ?? item.stockCode} ${signedPercent(ratio)} · ${cleanTitle(item.title)}`,
        };
      }), 'news');
    // 시세가 바뀔 때마다가 아니라 새 뉴스가 들어올 때만 본다. 이미 본 기사는 다시 알리지 않는다.
  }, [events]); // eslint-disable-line react-hooks/exhaustive-deps
}
