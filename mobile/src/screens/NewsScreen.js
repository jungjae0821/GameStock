import React, { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAccount } from '../account';
import { GameIcon } from '../components/GameIcon';
import { Chips } from '../components/Kit';
import { cleanTitle, relativeTime, signedPercent } from '../format';
import { newsEffect, newsWithIds } from '../market';
import { VISIBLE_NEWS_SOURCES, classifyNewsSource } from '../newsSource';
import { RADIUS, SPACE, Txt, toneOf, useTheme } from '../theme';
import { StockPicker } from './stock/StockPicker';

/** 뉴스 목록. 할 일은 읽을 기사 고르기 하나다: 최신순 목록, 그리고 발행 뒤 가격이 어떻게 움직였는지. */
export function NewsScreen({ market, onOpenNews }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [refreshing, setRefreshing] = useState(false);
  const stocks = useMemo(() => Object.fromEntries(market.stocks.map(stock => [stock.code, stock])), [market.stocks]);
  const news = useMemo(() => newsWithIds(market.events), [market.events]);
  const names = useMemo(() => Object.fromEntries(market.stocks.map(stock => [stock.code, stock.name])), [market.stocks]);
  const account = useAccount();
  // 웹 뉴스 화면과 같은 필터: 출처, 종목, 관심 종목만.
  const [source, setSource] = useState('all');
  const [game, setGame] = useState(null);
  const [watchOnly, setWatchOnly] = useState(false);
  const [picking, setPicking] = useState(false);
  const watch = account.watchlist;
  const sources = useMemo(() => new Map(news.map(item => [item.id, classifyNewsSource(item, names)])), [news, names]);
  // 출처 칩의 건수는 종목·관심 조건을 먼저 적용한 뒤 센다. 눌렀을 때 보일 개수와 같아야 한다.
  const scoped = news.filter(item => (!game || item.stockCode === game) && (!watchOnly || watch.includes(item.stockCode)));
  const counts = { all: scoped.length };
  for (const item of scoped) counts[sources.get(item.id)] = (counts[sources.get(item.id)] ?? 0) + 1;
  const items = scoped.filter(item => source === 'all' || sources.get(item.id) === source);
  const filtered = source !== 'all' || Boolean(game) || watchOnly;
  const resetFilters = () => { setSource('all'); setGame(null); setWatchOnly(false); };
  // 종목 고르기에는 기사가 있는 종목만 보인다(웹 종목 선택과 같다).
  const games = useMemo(() => {
    const codes = new Set(news.map(item => item.stockCode));
    return market.stocks.filter(stock => codes.has(stock.code));
  }, [news, market.stocks]);
  const gameName = game ? names[game] ?? game : null;

  const refresh = async () => {
    setRefreshing(true);
    await market.refresh();
    setRefreshing(false);
  };

  const empty = () => (news.length && filtered ? (
    <View style={styles.emptyBox}>
      <Txt variant="callout" tone="inkSoft" style={styles.center}>{source === '공식 발표' ? '이 조건에는 게임사가 직접 올린 공지가 없어요.' : '조건에 맞는 뉴스가 없어요.'}</Txt>
      <Pressable accessibilityRole="button" onPress={resetFilters} hitSlop={10} style={styles.reset}>
        <Txt variant="callout" tone="inkSoft" style={styles.underline}>필터 초기화</Txt>
      </Pressable>
    </View>
  ) : (
    <Txt variant="callout" tone="inkSoft" style={styles.empty}>
      {!market.loaded ? '뉴스를 불러오는 중이에요'
        : market.error ? '뉴스를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.'
        : '아직 수집된 뉴스가 없어요. 새 기사가 들어오면 여기에 보여요.'}
    </Txt>
  ));

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} progressViewOffset={insets.top} />}
        ListHeaderComponent={(
          <View style={styles.head}>
            <Txt variant="page" accessibilityRole="header">뉴스</Txt>
            {news.length ? <Txt variant="caption" tone="inkSoft">오른쪽 숫자는 기사가 나온 뒤의 가격 변화예요.</Txt> : null}
            {news.length ? (
              <View style={styles.filters}>
                <Chips
                  options={['all', ...VISIBLE_NEWS_SOURCES].map(key => ({ key, label: key === 'all' ? '전체' : key, count: counts[key] ?? 0 }))}
                  value={source}
                  onChange={setSource}
                />
                <View style={styles.filterRow}>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={gameName ? `종목 ${gameName}. 다른 종목 고르기` : '종목 고르기, 지금은 모든 종목'}
                    onPress={() => setPicking(true)}
                    style={({ pressed }) => [styles.filterButton, { borderColor: game ? theme.ink : theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}
                  >
                    <Txt variant="caption" numberOfLines={1} style={styles.shrink}>{gameName ?? '모든 종목'}</Txt>
                    <Ionicons name="chevron-down" size={14} color={theme.inkSoft} />
                  </Pressable>
                  {game ? (
                    <Pressable accessibilityRole="button" accessibilityLabel="종목 조건 지우기" onPress={() => setGame(null)} hitSlop={8} style={styles.clear}>
                      <Ionicons name="close-circle" size={20} color={theme.inkSoft} />
                    </Pressable>
                  ) : null}
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: watchOnly }}
                    onPress={() => setWatchOnly(value => !value)}
                    style={({ pressed }) => [styles.filterButton, { borderColor: watchOnly ? theme.ink : theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}
                  >
                    <Ionicons name={watchOnly ? 'checkbox' : 'square-outline'} size={16} color={watchOnly ? theme.ink : theme.inkSoft} />
                    <Txt variant="caption">관심 종목만</Txt>
                  </Pressable>
                </View>
              </View>
            ) : null}
          </View>
        )}
        ListEmptyComponent={empty}
        renderItem={({ item, index }) => {
          const stock = stocks[item.stockCode];
          const effect = newsEffect(item, stock);
          const first = index === 0;
          const last = index === items.length - 1;
          return (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`${cleanTitle(item.title)}. ${stock?.name ?? '시장 전체'}, ${relativeTime(item.publishedAt)}. 발행 뒤 ${signedPercent(effect)}`}
              onPress={() => onOpenNews(item.id)}
              style={({ pressed }) => [
                styles.row,
                { backgroundColor: pressed ? theme.pressed : theme.card },
                first && styles.first,
                last && styles.last,
                !first && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line },
              ]}
            >
              <GameIcon code={item.stockCode} />
              <View style={styles.body}>
                <Txt variant="callout" numberOfLines={2}>{cleanTitle(item.title)}</Txt>
                <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.xs }} numberOfLines={1}>
                  {sources.get(item.id) === '공식 발표' ? <Txt variant="caption" tone="brandText">{'공식 발표 · '}</Txt> : null}
                  {`${stock?.name ?? '시장 전체'} · ${relativeTime(item.publishedAt)}`}
                </Txt>
              </View>
              <Txt variant="caption" tone={toneOf(effect)} numeric style={styles.effect}>{signedPercent(effect)}</Txt>
            </Pressable>
          );
        }}
      />
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
      <StockPicker
        visible={picking}
        stocks={games}
        current={game}
        onPick={code => { setPicking(false); setGame(code); }}
        onClose={() => setPicking(false)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  head: { paddingHorizontal: SPACE.lg, paddingTop: SPACE.sm, paddingBottom: SPACE.md, gap: 2, minHeight: 48 },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, marginHorizontal: SPACE.lg, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md, minHeight: 72 },
  first: { borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS },
  last: { borderBottomLeftRadius: RADIUS, borderBottomRightRadius: RADIUS },
  body: { flex: 1, minWidth: 0 },
  effect: { minWidth: 56, textAlign: 'right' },
  empty: { textAlign: 'center', paddingVertical: SPACE.xxl, paddingHorizontal: SPACE.lg },
  emptyBox: { alignItems: 'center', paddingVertical: SPACE.xxl, paddingHorizontal: SPACE.lg, gap: SPACE.sm },
  center: { textAlign: 'center' },
  reset: { minHeight: 44, justifyContent: 'center' },
  underline: { textDecorationLine: 'underline' },
  filters: { marginTop: SPACE.md, gap: SPACE.sm },
  filterRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  filterButton: { flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 40, paddingHorizontal: SPACE.md, borderRadius: RADIUS, borderWidth: 1, maxWidth: '55%' },
  clear: { width: 32, height: 40, alignItems: 'center', justifyContent: 'center', marginLeft: -SPACE.xs },
  shrink: { flexShrink: 1 },
});
