import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Linking, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAccount, heldQuantity } from '../account';
import { useAuth } from '../auth';
import { GameIcon } from '../components/GameIcon';
import { IndicatorButton, PickerSheet, StudyChart, useIndicatorSettings } from '../components/StudyChart';
import { LineChart } from '../components/LineChart';
import { cleanTitle, clockTime, newsSourceUrl, newsSummary, price, relativeTime, signedPercent } from '../format';
import { api, sessionRate } from '../market';
import { RADIUS, RADIUS_SM, SPACE, Txt, toneOf, useTheme } from '../theme';
import { OrderSheet } from './OrderSheet';

const RANGES = [
  { key: '1h', label: '1시간' },
  { key: '6h', label: '6시간' },
  { key: '24h', label: '하루' },
  { key: '7d', label: '일주일' },
];
// 캔들은 봉 간격으로 고른다. 키는 /api/stocks/{code}/chart의 range 값이고, 이름은 웹 SeriesChart와 같다.
const CANDLE_RANGES = [
  { key: '1h', label: '1분' },
  { key: '6h', label: '5분' },
  { key: '1d', label: '30분' },
  { key: '3d', label: '1시간' },
];
const MODES = [{ key: 'line', label: '선' }, { key: 'candle', label: '캔들' }];
const BOOK_DEPTH = 5;
const TRADE_COUNT = 10;
const amount = value => `${Math.round(Number(value) || 0).toLocaleString('ko-KR')}주`;

/** 종목 상세. 할 일은 판단 하나다: 가격과 흐름, 움직인 이유, 지금 호가, 그리고 주문. */
export function StockScreen({ code, market, onBack, onLogin, onSwitch, onOpenTalk, initialOrder }) {
  const [picking, setPicking] = useState(false);
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user } = useAuth();
  const account = useAccount();
  const stock = market.stocks.find(item => item.code === code);
  const [range, setRange] = useState('24h');
  const [history, setHistory] = useState({ range: null, points: null, error: false });
  const [mode, setMode] = useState('line');
  const [candleRange, setCandleRange] = useState('1h');
  const [candles, setCandles] = useState({ range: null, bars: null, error: false });
  const studies = useIndicatorSettings();
  const [pickingStudy, setPickingStudy] = useState(false);
  const [drivers, setDrivers] = useState(null);
  const [news, setNews] = useState(null);
  // 근거·뉴스를 한 번도 못 받았을 때만 실패로 보인다. 받은 값이 있으면 다음 갱신까지 그 값을 둔다.
  const [driversFailed, setDriversFailed] = useState(false);
  const [newsFailed, setNewsFailed] = useState(false);
  const [openNews, setOpenNews] = useState(null);
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
    setHistory(current => ({ range, points: current.range === range ? current.points : null, error: false }));
    const read = () => api(`/api/stocks/${code}/history?range=${range}`)
      .then(points => { if (!cancelled) setHistory({ range, points, error: false }); })
      .catch(() => { if (!cancelled) setHistory(current => ({ ...current, error: true })); });
    void read();
    const timer = setInterval(read, 60_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [code, range]);

  // 캔들은 캔들 보기를 고른 동안만 받는다. 마지막 봉이 계속 자라므로 10초마다 다시 읽는다.
  useEffect(() => {
    if (mode !== 'candle') return undefined;
    let cancelled = false;
    setCandles(current => ({ range: candleRange, bars: current.range === candleRange ? current.bars : null, error: false }));
    const read = () => api(`/api/stocks/${code}/chart?range=${candleRange}`)
      .then(bars => { if (!cancelled) setCandles({ range: candleRange, bars, error: false }); })
      .catch(() => { if (!cancelled) setCandles(current => ({ ...current, error: true })); });
    void read();
    const timer = setInterval(read, 10_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [code, mode, candleRange]);

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
  const asks = useMemo(() => [...(detail.orderbook?.asks ?? [])].sort((a, b) => a.price - b.price).slice(0, BOOK_DEPTH).reverse(), [detail.orderbook]);
  const bids = useMemo(() => [...(detail.orderbook?.bids ?? [])].sort((a, b) => b.price - a.price).slice(0, BOOK_DEPTH), [detail.orderbook]);
  const maxLevel = Math.max(1, ...asks.map(level => level.quantity), ...bids.map(level => level.quantity));
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
        <Pressable accessibilityRole="button" accessibilityLabel="시장으로 돌아가기" onPress={onBack} style={styles.barButton}>
          <Ionicons name="chevron-back" size={26} color={theme.ink} />
        </Pressable>
        {onSwitch ? (
          <Pressable accessibilityRole="button" accessibilityLabel="다른 종목 고르기" onPress={() => setPicking(true)} style={({ pressed }) => [styles.switcher, pressed && { backgroundColor: theme.pressed }]}>
            <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{stock.name}</Txt>
            <Ionicons name="chevron-down" size={18} color={theme.inkSoft} />
          </Pressable>
        ) : <View style={styles.shrink} />}
        <Pressable accessibilityRole="button" accessibilityLabel={watching ? '관심 종목에서 빼기' : '관심 종목에 넣기'} accessibilityState={{ selected: watching }} onPress={() => void toggleWatch()} style={styles.barButton}>
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
            <View style={styles.shrink}>
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
            <View style={styles.shrink}>
              <Txt variant="callout" style={{ color: theme.guardText }}>{restriction.label}</Txt>
              <Txt variant="caption" style={{ color: theme.guardText }}>{restriction.effect || restriction.reason}</Txt>
            </View>
          </View>
        ) : null}

        <Card>
          <View style={styles.chartBar}>
            <View style={[styles.ranges, styles.shrink]} accessibilityRole="tablist" accessibilityLabel="차트 구간">
              {(mode === 'candle' ? CANDLE_RANGES : RANGES).map(item => {
                const selected = (mode === 'candle' ? candleRange : range) === item.key;
                return (
                  <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => (mode === 'candle' ? setCandleRange : setRange)(item.key)} style={[styles.range, selected && { backgroundColor: theme.raised }]}>
                    <Txt variant="caption" tone={selected ? 'ink' : 'inkSoft'}>{item.label}</Txt>
                  </Pressable>
                );
              })}
            </View>
            <View style={[styles.modes, { borderColor: theme.edge }]} accessibilityRole="tablist" accessibilityLabel="차트 모양">
              {MODES.map(item => {
                const selected = mode === item.key;
                return (
                  <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => setMode(item.key)} style={[styles.mode, selected && { backgroundColor: theme.ink }]}>
                    <Txt variant="caption" style={{ color: selected ? theme.ground : theme.ink }}>{item.label}</Txt>
                  </Pressable>
                );
              })}
            </View>
            {/* 지표는 봉으로 계산하므로 선 보기에서 누르면 캔들로 바꾼 뒤 연다. */}
            <IndicatorButton count={studies.indicators.length} onPress={() => { setMode('candle'); setPickingStudy(true); }} />
          </View>
          {mode === 'candle' ? (
            candles.bars == null ? (
              <View style={styles.chartLoading}>{candles.error ? <Txt variant="caption" tone="inkSoft">봉 기록을 불러오지 못했어요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}</View>
            ) : <StudyChart candles={candles.bars} indicators={studies.indicators} reference={stock.referencePrice} onRemove={studies.remove} />
          ) : history.points == null && !history.error ? (
            <View style={styles.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>
          ) : history.error && history.points == null ? (
            <View style={styles.chartLoading}><Txt variant="caption" tone="inkSoft">가격 기록을 불러오지 못했어요.</Txt></View>
          ) : (
            <LineChart
              points={history.points}
              current={stock.price}
              reference={range === '7d' ? null : stock.referencePrice}
              newsTimes={sortedNews.map(item => item.publishedAt)}
              tone={tone}
            />
          )}
          <Txt variant="micro" tone="inkSoft" style={{ marginTop: SPACE.sm }}>{mode === 'candle' ? '최근 60개 봉이에요. 회색 점선은 오늘 기준가, 핑크 점선은 마지막 종가예요. 막대는 거래량이 아니라 체결 건수예요.' : range === '7d' ? '아래 점과 세로선은 관련 뉴스가 나온 시점이에요.' : '아래 점과 세로선은 관련 뉴스가 나온 시점, 점선은 오늘 기준가예요.'}</Txt>
        </Card>

        <Card>
          <Txt variant="headline" accessibilityRole="header">움직인 이유</Txt>
          {drivers ? (
            <>
              <Txt variant="callout" style={{ marginTop: SPACE.sm }}>{drivers.reason}</Txt>
              <View style={[styles.drivers, { borderTopColor: theme.line }]}>
                <Driver label="관련 뉴스" value={drivers.newsCount ? `${drivers.newsCount}건 · 영향 ${drivers.newsImpact > 0 ? '+' : ''}${drivers.newsImpact}` : '없음'} />
                <Driver label="대기 주문" buy={drivers.openBuyVolume} sell={drivers.openSellVolume} />
              </View>
              <Txt variant="micro" tone="inkSoft" style={{ marginTop: SPACE.sm }}>최근 24시간 기준이에요.</Txt>
            </>
          ) : <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.sm }}>{driversFailed ? '가격 근거를 불러오지 못했어요. 30초마다 다시 불러와요.' : '가격 근거를 불러오는 중이에요'}</Txt>}
        </Card>

        <Card padded={false}>
          <View style={styles.cardHead}>
            <Txt variant="headline" accessibilityRole="header">호가</Txt>
            <Txt variant="caption" tone="inkSoft">가격을 누르면 그 가격으로 주문해요</Txt>
          </View>
          {detail.orderbook ? (
            <View>
              {asks.length ? asks.map(level => <BookRow key={`a${level.price}`} level={level} side="sell" max={maxLevel} onPress={() => startOrder('buy', level.price)} />)
                : <Txt variant="caption" tone="inkSoft" style={styles.bookEmpty}>매도 대기 주문이 없어요</Txt>}
              <JudgeLine stock={stock} tone={tone} />
              {bids.length ? bids.map(level => <BookRow key={`b${level.price}`} level={level} side="buy" max={maxLevel} onPress={() => startOrder('sell', level.price)} />)
                : <Txt variant="caption" tone="inkSoft" style={styles.bookEmpty}>매수 대기 주문이 없어요</Txt>}
            </View>
          ) : <View style={styles.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>}
        </Card>

        <Card padded={false}>
          <View style={styles.cardHead}><Txt variant="headline" accessibilityRole="header">최근 체결</Txt></View>
          {detail.trades ? (detail.trades.length ? detail.trades.slice(0, TRADE_COUNT).map((trade, index) => {
            const buy = String(trade.side).toUpperCase() === 'BUY';
            return (
              <View key={`${trade.createdAt}-${index}`} style={styles.trade} accessible accessibilityLabel={`${clockTime(trade.createdAt)} ${price(trade.price)}원 ${trade.quantity}주 ${buy ? '매수' : '매도'}`}>
                <Txt variant="caption" tone="inkSoft" numeric style={styles.tradeTime}>{clockTime(trade.createdAt)}</Txt>
                <Txt variant="callout" numeric style={styles.tradePrice}>{price(trade.price)}</Txt>
                <Txt variant="callout" numeric tone={buy ? 'up' : 'down'} style={styles.tradeQty}>{`${trade.quantity}주 ${buy ? '매수' : '매도'}`}</Txt>
              </View>
            );
          }) : <Txt variant="caption" tone="inkSoft" style={styles.bookEmpty}>아직 체결이 없어요</Txt>)
            : <View style={styles.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>}
        </Card>

        <Card padded={false}>
          <View style={styles.cardHead}><Txt variant="headline" accessibilityRole="header">관련 뉴스</Txt></View>
          {news == null ? (newsFailed
            ? <Txt variant="caption" tone="inkSoft" style={styles.bookEmpty}>관련 뉴스를 불러오지 못했어요. 30초마다 다시 불러와요.</Txt>
            : <View style={styles.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>)
            : sortedNews.length ? sortedNews.slice(0, 5).map((item, index) => {
              const key = `${item.publishedAt}-${index}`;
              const expanded = openNews === key;
              const summary = newsSummary(item.description);
              const source = newsSourceUrl(item);
              return (
                <View key={key} style={index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
                  <Pressable accessibilityRole="button" accessibilityState={{ expanded }} onPress={() => setOpenNews(expanded ? null : key)} style={({ pressed }) => [styles.news, pressed && { backgroundColor: theme.pressed }]}>
                    <Txt variant="callout" numberOfLines={expanded ? undefined : 2}>{cleanTitle(item.title)}</Txt>
                    <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.xs }}>
                      {`${relativeTime(item.publishedAt)}${Number.isFinite(Number(item.priceChangePercent)) ? ` · 발행 뒤 ${Number(item.priceChangePercent) >= 0 ? '+' : ''}${Number(item.priceChangePercent).toFixed(2)}%` : ''}`}
                    </Txt>
                  </Pressable>
                  {expanded ? (
                    <View style={styles.newsBody}>
                      {summary && summary !== cleanTitle(item.title) ? <Txt variant="callout" tone="inkSoft">{summary}</Txt> : null}
                      {source ? (
                        <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(source)} style={styles.sourceLink}>
                          <Txt variant="callout" style={styles.underline}>원문 기사 열기</Txt>
                          <Ionicons name="open-outline" size={16} color={theme.ink} />
                        </Pressable>
                      ) : null}
                    </View>
                  ) : null}
                </View>
              );
            }) : <Txt variant="caption" tone="inkSoft" style={styles.bookEmpty}>이 종목의 관련 뉴스가 아직 없어요</Txt>}
        </Card>
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

      <PickerSheet visible={pickingStudy} count={studies.indicators.length} oscillators={studies.oscillators} onAdd={studies.add} onClose={() => setPickingStudy(false)} />
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

/** 목록으로 돌아가지 않고 다른 종목으로 바꾸는 시트. 웹 거래 화면의 종목 선택과 같은 일을 한다. */
export function StockPicker({ visible, stocks, current, onPick, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={[StyleSheet.absoluteFill, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="종목 고르기 닫기" />
      <View style={[styles.picker, { backgroundColor: theme.card, paddingBottom: insets.bottom + SPACE.md }]}>
        <View style={styles.pickerHead}>
          <Txt variant="headline" accessibilityRole="header">종목 고르기</Txt>
          <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={onClose} hitSlop={8} style={styles.barButton}>
            <Ionicons name="close" size={24} color={theme.ink} />
          </Pressable>
        </View>
        <ScrollView>
          {stocks.map((item, index) => {
            const selected = item.code === current;
            const rate = sessionRate(item);
            return (
              <Pressable
                key={item.code}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={`${item.name} ${price(item.price)}원, 오늘 ${signedPercent(rate)}`}
                onPress={() => onPick(item.code)}
                style={({ pressed }) => [styles.pickerRow, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, (pressed || selected) && { backgroundColor: theme.raised }]}
              >
                <GameIcon code={item.code} />
                <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{item.name}</Txt>
                <View style={styles.pickerPrice}>
                  <Txt variant="callout" numeric>{price(item.price)}</Txt>
                  <Txt variant="caption" tone={toneOf(rate)} numeric>{signedPercent(rate)}</Txt>
                </View>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>
    </Modal>
  );
}

function Card({ children, padded = true }) {
  const theme = useTheme();
  return <View style={[styles.card, { backgroundColor: theme.card }, padded && styles.cardPadded]}>{children}</View>;
}

/** 매수·매도 수량을 나란히 적고, 아래에 매수 비중 막대를 둔다. */
function Driver({ label, value, buy, sell }) {
  const theme = useTheme();
  const total = (Number(buy) || 0) + (Number(sell) || 0);
  const share = total ? (Number(buy) || 0) / total : 0.5;
  return (
    <View style={styles.driver}>
      <View style={styles.driverLine}>
        <Txt variant="callout" tone="inkSoft">{label}</Txt>
        {value != null ? <Txt variant="callout" numeric>{value}</Txt> : (
          <Txt variant="callout" numeric>
            <Txt variant="callout" tone="up" numeric>{`매수 ${amount(buy)}`}</Txt>
            {'  '}
            <Txt variant="callout" tone="down" numeric>{`매도 ${amount(sell)}`}</Txt>
          </Txt>
        )}
      </View>
      {value == null && total ? (
        <View style={[styles.shareTrack, { backgroundColor: theme.down }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          <View style={[styles.shareFill, { width: `${Math.round(share * 100)}%`, backgroundColor: theme.up }]} />
        </View>
      ) : null}
    </View>
  );
}

/**
 * 판정선. 매도 호가와 매수 호가 사이에 현재가를 꽂는 줄이다(DESIGN.md의 서명 부품).
 * 위에 2px 핑크 선, 바탕은 brand-wash, 현재가는 굵은 고정폭 숫자로 쓴다.
 */
function JudgeLine({ stock, tone }) {
  const theme = useTheme();
  const rate = sessionRate(stock);
  return (
    <View
      style={[styles.judge, { backgroundColor: theme.brandWash, borderTopColor: theme.brand }]}
      accessible
      accessibilityLabel={`현재가 ${price(stock.price)}원, 오늘 ${signedPercent(rate)}`}
    >
      <Txt variant="caption" tone="inkSoft" style={styles.judgeSide}>현재가</Txt>
      <Txt variant="title" numeric>{price(stock.price)}</Txt>
      <Txt variant="caption" tone={tone} numeric style={[styles.judgeSide, styles.judgeRate]}>{signedPercent(rate)}</Txt>
    </View>
  );
}

/** 호가 한 줄. 매도 잔량은 왼쪽, 매수 잔량은 오른쪽에 막대로 그리고 가격은 가운데에 둔다. */
function BookRow({ level, side, max, onPress }) {
  const theme = useTheme();
  const bar = `${Math.max(4, Math.round((level.quantity / max) * 100))}%`;
  const wash = side === 'sell' ? theme.downWash : theme.upWash;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${side === 'sell' ? '매도' : '매수'} 호가 ${price(level.price)}원, ${level.quantity}주. 이 가격으로 ${side === 'sell' ? '매수' : '매도'} 주문`}
      onPress={onPress}
      style={({ pressed }) => [styles.book, pressed && { backgroundColor: theme.pressed }]}
    >
      <View style={styles.bookSide}>
        {side === 'sell' ? (
          <>
            <View style={[styles.bookBar, styles.bookBarRight, { width: bar, backgroundColor: wash }]} />
            <Txt variant="caption" numeric style={styles.bookQtyRight}>{`${level.quantity.toLocaleString('ko-KR')}주`}</Txt>
          </>
        ) : null}
      </View>
      <View style={styles.bookPrice}>
        <Txt variant="callout" numeric tone={side === 'sell' ? 'down' : 'up'}>{price(level.price)}</Txt>
      </View>
      <View style={styles.bookSide}>
        {side === 'buy' ? (
          <>
            <View style={[styles.bookBar, { width: bar, backgroundColor: wash }]} />
            <Txt variant="caption" numeric style={styles.bookQtyLeft}>{`${level.quantity.toLocaleString('ko-KR')}주`}</Txt>
          </>
        ) : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  center: { alignItems: 'center', justifyContent: 'center', gap: SPACE.md },
  backLink: { minHeight: 44, justifyContent: 'center' },
  shrink: { flex: 1, minWidth: 0 },
  bar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: SPACE.xs },
  switcher: { flex: 1, minWidth: 0, minHeight: 44, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, borderRadius: RADIUS_SM, paddingHorizontal: SPACE.sm },
  picker: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '75%', borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS },
  pickerHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingLeft: SPACE.lg + 2, paddingRight: SPACE.xs, paddingTop: SPACE.xs },
  pickerRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 60, paddingHorizontal: SPACE.lg + 2 },
  pickerPrice: { alignItems: 'flex-end' },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  hero: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.lg },
  heroName: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  notice: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, borderWidth: 1, padding: SPACE.md, marginBottom: SPACE.md },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  cardPadded: { padding: SPACE.lg + 2 },
  cardHead: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg + 2, paddingBottom: SPACE.sm, gap: SPACE.sm },
  chartBar: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, marginBottom: SPACE.md },
  ranges: { flexDirection: 'row', gap: 4 },
  modes: { flexDirection: 'row', borderWidth: 1, borderRadius: RADIUS_SM, overflow: 'hidden' },
  mode: { minHeight: 44, minWidth: 44, paddingHorizontal: SPACE.sm, alignItems: 'center', justifyContent: 'center' },
  range: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
  chartLoading: { minHeight: 120, alignItems: 'center', justifyContent: 'center' },
  drivers: { marginTop: SPACE.md, paddingTop: SPACE.sm, borderTopWidth: StyleSheet.hairlineWidth, gap: SPACE.md },
  driver: { gap: 6 },
  driverLine: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: SPACE.sm, flexWrap: 'wrap' },
  shareTrack: { height: 4, borderRadius: 3, overflow: 'hidden', flexDirection: 'row' },
  shareFill: { height: 4 },
  book: { flexDirection: 'row', alignItems: 'center', minHeight: 44, paddingHorizontal: SPACE.lg + 2 },
  bookSide: { flex: 1, height: 32, justifyContent: 'center' },
  bookBar: { position: 'absolute', top: 4, bottom: 4, left: 0, borderRadius: 3 },
  bookBarRight: { left: undefined, right: 0 },
  bookQtyRight: { textAlign: 'right', paddingRight: SPACE.sm },
  bookQtyLeft: { paddingLeft: SPACE.sm },
  bookPrice: { minWidth: 96, minHeight: 32, alignItems: 'center', justifyContent: 'center' },
  judge: { flexDirection: 'row', alignItems: 'center', minHeight: 52, paddingHorizontal: SPACE.lg + 2, marginVertical: 2, borderTopWidth: 2 },
  judgeSide: { flex: 1 },
  judgeRate: { textAlign: 'right' },
  bookEmpty: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  trade: { flexDirection: 'row', alignItems: 'center', minHeight: 36, paddingHorizontal: SPACE.lg + 2 },
  tradeTime: { width: 72 },
  tradePrice: { flex: 1, textAlign: 'right' },
  tradeQty: { width: 104, textAlign: 'right' },
  news: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  newsBody: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.md, gap: SPACE.sm },
  sourceLink: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44 },
  underline: { textDecorationLine: 'underline' },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
  talkButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm, minHeight: 48, marginTop: SPACE.md, borderWidth: 1, borderRadius: RADIUS },
  actions: { flexDirection: 'row', gap: SPACE.sm, paddingHorizontal: SPACE.lg, paddingTop: SPACE.sm, borderTopWidth: StyleSheet.hairlineWidth },
  action: { flex: 1, minHeight: 52, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
});
