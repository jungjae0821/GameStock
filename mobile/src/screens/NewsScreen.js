import React, { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GameIcon } from '../components/GameIcon';
import { cleanTitle, relativeTime, signedPercent } from '../format';
import { newsEffect, newsWithIds } from '../market';
import { classifyNewsSource } from '../newsSource';
import { RADIUS, SPACE, Txt, toneOf, useTheme } from '../theme';

/** 뉴스 목록. 할 일은 읽을 기사 고르기 하나다: 최신순 목록, 그리고 발행 뒤 가격이 어떻게 움직였는지. */
export function NewsScreen({ market, onOpenNews }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [refreshing, setRefreshing] = useState(false);
  const stocks = useMemo(() => Object.fromEntries(market.stocks.map(stock => [stock.code, stock])), [market.stocks]);
  const news = useMemo(() => newsWithIds(market.events), [market.events]);
  const names = useMemo(() => Object.fromEntries(market.stocks.map(stock => [stock.code, stock.name])), [market.stocks]);

  const refresh = async () => {
    setRefreshing(true);
    await market.refresh();
    setRefreshing(false);
  };

  const empty = () => (
    <Txt variant="callout" tone="inkSoft" style={styles.empty}>
      {!market.loaded ? '뉴스를 불러오는 중이에요'
        : market.error ? '뉴스를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.'
        : '아직 수집된 뉴스가 없어요. 새 기사가 들어오면 여기에 보여요.'}
    </Txt>
  );

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <FlatList
        data={news}
        keyExtractor={item => String(item.id)}
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} progressViewOffset={insets.top} />}
        ListHeaderComponent={(
          <View style={styles.head}>
            <Txt variant="page" accessibilityRole="header">뉴스</Txt>
            {news.length ? <Txt variant="caption" tone="inkSoft">오른쪽 숫자는 기사가 나온 뒤의 가격 변화예요.</Txt> : null}
          </View>
        )}
        ListEmptyComponent={empty}
        renderItem={({ item, index }) => {
          const stock = stocks[item.stockCode];
          const effect = newsEffect(item, stock);
          const first = index === 0;
          const last = index === news.length - 1;
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
                  {classifyNewsSource(item, names) === '업데이트 노트' ? <Txt variant="caption" tone="brandText">{'업데이트 노트 · '}</Txt> : null}
                  {`${stock?.name ?? '시장 전체'} · ${relativeTime(item.publishedAt)}`}
                </Txt>
              </View>
              <Txt variant="caption" tone={toneOf(effect)} numeric style={styles.effect}>{signedPercent(effect)}</Txt>
            </Pressable>
          );
        }}
      />
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
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
});
