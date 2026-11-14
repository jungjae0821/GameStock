import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAccount, heldQuantity } from '../account';
import { request, useAuth } from '../auth';
import { GameIcon } from '../components/GameIcon';
import { useCareer } from '../engagement';
import { PickerSheet } from '../components/StudyChart';
import { price, signedPercent } from '../format';
import { api, sessionRate } from '../market';
import { RADIUS, RADIUS_SM, SPACE, Txt, toneOf, useTheme } from '../theme';
import { OrderSheet } from './OrderSheet';
import { StockChartCard, useStockChart } from './stock/StockChartCard';
import { StockDriversCard } from './stock/StockDriversCard';
import { StockNewsCard } from './stock/StockNewsCard';
import { StockOrderBookCard, StockTradesCard } from './stock/StockOrderBook';
import { StockPicker } from './stock/StockPicker';
import { Card, amount, styles as shared } from './stock/parts';

/** 종목 상세. 할 일은 판단 하나다: 가격과 흐름, 움직인 이유, 지금 호가, 그리고 주문. */
export function StockScreen({ code, market, onBack, onLogin, onSwitch, onOpenTalk, initialOrder }) {
  const [picking, setPicking] = useState(false);
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user } = useAuth();
  const account = useAccount();
  const stock = market.stocks.find(item => item.code === code);
  const chart = useStockChart(code);
  const [drivers, setDrivers] = useState(null);
  // 수습 OJT의 "종목 화면에서 호가·뉴스 보기"는 웹 종목 화면처럼 이 화면을 연 것으로 끝난다.
  // 수습이 아니거나 이미 끝낸 단계면 보내지 않는다(종목을 열 때마다 서버를 부르지 않게).
  const career = useCareer();
  const marketPending = Boolean(career && !career.legacy && career.grade === 'TRAINEE'
    && career.promotion?.some(step => step.id === 'MARKET' && !step.done));
  useEffect(() => {
    if (user && code && marketPending) request('/api/career/ojt/MARKET', { method: 'POST' }).catch(() => {});
  }, [user, code, marketPending]);
  const [news, setNews] = useState(null);
  // 근거·뉴스를 한 번도 못 받았을 때만 실패로 보인다. 받은 값이 있으면 다음 갱신까지 그 값을 둔다.
  const [driversFailed, setDriversFailed] = useState(false);
  const [newsFailed, setNewsFailed] = useState(false);
  const [order, setOrder] = useState(null);
  const [watchError, setWatchError] = useState('');

  // 뉴스 상세의 '주문하기'로 들어오면 주문 시트를 바로 연다.
  useEffect(() => {
    if (!initialOrder) return;
    if (user) setOrder({ side: initialOrder, limitPrice: null }); else onLogin();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 호가와 최근 체결은 소켓으로 실시간 갱신된다.
  useEffect(() => {
    market.watchDetail(code);
    return () => market.watchDetail(null);
  }, [code]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let cancelled = false;
    const read = () => {
      api(`/api/stocks/${code}/price-drivers`)
        .then(value => { if (!cancelled) { setDrivers(value); setDriversFailed(false); } })
        .catch(() => { if (!cancelled) setDriversFailed(true); });
      api(`/api/stocks/${code}/news`)
        .then(value => { if (!cancelled) { setNews(value); setNewsFailed(false); } })
        .catch(() => { if (!cancelled) setNewsFailed(true); });
    };
    read();
    const timer = setInterval(read, 30_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [code]);

  const detail = market.detail.code === code ? market.detail : { orderbook: null, trades: null };
  const sortedNews = useMemo(() => [...(news ?? [])].sort((a, b) => (Date.parse(b.publishedAt) || 0) - (Date.parse(a.publishedAt) || 0)), [news]);

  if (!stock) {
    return (
      <View style={[styles.fill, styles.center, { backgroundColor: theme.ground, paddingTop: insets.top }]}>
        {market.loaded ? <Txt variant="callout" tone="inkSoft">이 종목을 찾을 수 없어요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}
        <Pressable accessibilityRole="button" onPress={onBack} style={styles.backLink}><Txt variant="callout" tone="inkSoft">시장으로 돌아가기</Txt></Pressable>
      </View>
    );
  }

  const rate = sessionRate(stock);
  const tone = toneOf(rate);
  const watching = account.watchlist.includes(code);
  const held = heldQuantity(account.portfolio, code);
  const restriction = stock.restriction;

  const toggleWatch = async () => {
    if (!user) { onLogin(); return; }
    setWatchError('');
    try { await account.toggleWatch(code); } catch (error) { setWatchError(error.message); }
  };
  const startOrder = (side, limitPrice) => {
    if (!user) { onLogin(); return; }
    setOrder({ side, limitPrice: limitPrice ?? null });
  };

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <View style={[styles.bar, { paddingTop: insets.top, backgroundColor: theme.ground }]}>
        <Pressable accessibilityRole="button" accessibilityLabel="시장으로 돌아가기" onPress={onBack} style={shared.barButton}>
          <Ionicons name="chevron-back" size={26} color={theme.ink} />
        </Pressable>
        {onSwitch ? (
          <Pressable accessibilityRole="button" accessibilityLabel="다른 종목 고르기" onPress={() => setPicking(true)} style={({ pressed }) => [styles.switcher, pressed && { backgroundColor: theme.pressed }]}>
            <Txt variant="callout" numberOfLines={1} style={shared.shrink}>{stock.name}</Txt>
            <Ionicons name="chevron-down" size={18} color={theme.inkSoft} />
          </Pressable>
        ) : <View style={shared.shrink} />}
        <Pressable accessibilityRole="button" accessibilityLabel={watching ? '관심 종목에서 빼기' : '관심 종목에 넣기'} accessibilityState={{ selected: watching }} onPress={() => void toggleWatch()} style={shared.barButton}>
          <Ionicons name={watching ? 'star' : 'star-outline'} size={24} color={watching ? theme.star : theme.ink} />
        </Pressable>
      </View>

      {/* 채팅 입력칸이 키보드에 가리지 않도록 키보드 높이만큼 스크롤 여백을 늘린다. */}
      <ScrollView
        automaticallyAdjustKeyboardInsets
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ paddingHorizontal: SPACE.lg, paddingBottom: SPACE.xl }}
      >
        <View style={styles.hero}>
          <View style={styles.heroName}>
            <GameIcon code={code} size={36} />
            <View style={shared.shrink}>
              <Txt variant="headline" numberOfLines={1} accessibilityRole="header">{stock.name}</Txt>
              <Txt variant="caption" tone="inkSoft">{`${code} · ${stock.genre}`}</Txt>
            </View>
          </View>
          <Txt variant="price" numeric style={{ marginTop: SPACE.md }}>{`${price(stock.price)}원`}</Txt>
          <Txt variant="callout" tone={tone} numeric>{`오늘 ${signedPercent(rate)} (${rate >= 0 ? '+' : '−'}${price(Math.abs(stock.price - (stock.referencePrice || stock.price)))}원)`}</Txt>
          {held > 0 ? <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.xs }}>{`${amount(held)} 보유 중`}</Txt> : null}
          {watchError ? <Txt variant="caption" style={{ marginTop: SPACE.xs }}>{watchError}</Txt> : null}
        </View>

        {restriction ? (
          <View style={[styles.notice, { backgroundColor: theme.guardWash, borderColor: theme.guard }]}>
            <Ionicons name="shield-half" size={18} color={theme.guardText} />
            <View style={shared.shrink}>
              <Txt variant="callout" style={{ color: theme.guardText }}>{restriction.label}</Txt>
              <Txt variant="caption" style={{ color: theme.guardText }}>{restriction.effect || restriction.reason}</Txt>
            </View>
          </View>
        ) : null}

        <StockChartCard chart={chart} stock={stock} tone={tone} newsTimes={sortedNews.map(item => item.publishedAt)} />
        <StockDriversCard drivers={drivers} failed={driversFailed} />
        <StockOrderBookCard stock={stock} tone={tone} orderbook={detail.orderbook} onOrder={startOrder} />
        <StockTradesCard trades={detail.trades} />
        <StockNewsCard news={news} sortedNews={sortedNews} failed={newsFailed} />
        {/* 글과 채팅은 토론 탭에만 둔다. 여기서는 그 종목 토론방으로 가는 길만 낸다. */}
        {onOpenTalk ? (
          <Card>
            <Txt variant="headline" accessibilityRole="header">종목토론</Txt>
            <Txt variant="callout" tone="inkSoft" style={{ marginTop: SPACE.xs }}>글과 실시간 채팅은 토론 탭에 모여 있어요.</Txt>
            <Pressable accessibilityRole="button" onPress={() => onOpenTalk(code)} style={({ pressed }) => [styles.talkButton, { borderColor: theme.edge }, pressed && { backgroundColor: theme.pressed }]}>
              <Ionicons name="chatbubbles-outline" size={18} color={theme.ink} />
              <Txt variant="callout">토론방 열기</Txt>
            </Pressable>
          </Card>
        ) : null}
        <Txt variant="micro" tone="inkSoft" style={styles.footer}>모의 시장이에요. 실제 주식이나 현금을 거래하지 않아요.</Txt>
      </ScrollView>

      <View style={[styles.actions, { paddingBottom: SPACE.sm, backgroundColor: theme.card, borderTopColor: theme.line }]}>
        <Pressable accessibilityRole="button" onPress={() => startOrder('sell')} style={({ pressed }) => [styles.action, { backgroundColor: theme.downFill, opacity: pressed ? 0.85 : 1 }]}>
          <Txt variant="body" style={{ color: theme.onFill }}>매도</Txt>
        </Pressable>
        <Pressable accessibilityRole="button" onPress={() => startOrder('buy')} style={({ pressed }) => [styles.action, { backgroundColor: theme.upFill, opacity: pressed ? 0.85 : 1 }]}>
          <Txt variant="body" style={{ color: theme.onFill }}>매수</Txt>
        </Pressable>
      </View>

      <PickerSheet visible={chart.pickingStudy} count={chart.studies.indicators.length} oscillators={chart.studies.oscillators} onAdd={chart.studies.add} onClose={() => chart.setPickingStudy(false)} />
      <OrderSheet
        visible={Boolean(order)}
        stock={stock}
        orderbook={detail.orderbook}
        initialSide={order?.side ?? 'buy'}
        initialLimitPrice={order?.limitPrice ?? null}
        onClose={() => setOrder(null)}
      />
      {onSwitch ? (
        <StockPicker
          visible={picking}
          stocks={market.stocks}
          current={code}
          onPick={next => { setPicking(false); if (next !== code) onSwitch(next); }}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  center: { alignItems: 'center', justifyContent: 'center', gap: SPACE.md },
  backLink: { minHeight: 44, justifyContent: 'center' },
  bar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: SPACE.xs },
  switcher: { flex: 1, minWidth: 0, minHeight: 44, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, borderRadius: RADIUS_SM, paddingHorizontal: SPACE.sm },
  hero: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.lg },
  heroName: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  notice: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, borderWidth: 1, padding: SPACE.md, marginBottom: SPACE.md },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
  talkButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm, minHeight: 48, marginTop: SPACE.md, borderWidth: 1, borderRadius: RADIUS },
  actions: { flexDirection: 'row', gap: SPACE.sm, paddingHorizontal: SPACE.lg, paddingTop: SPACE.sm, borderTopWidth: StyleSheet.hairlineWidth },
  action: { flex: 1, minHeight: 52, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
});
