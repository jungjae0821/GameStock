import React, { useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { request, useAuth } from '../auth';
import { Avatar } from '../components/Avatar';
import { won } from '../format';
import { titleColor } from '../titles';
import { RADIUS, RADIUS_SM, RADIUS_XS, SPACE, Txt, mixColor, toneOf, useTheme } from '../theme';

const TABS = [
  { key: 'total', label: '트레이더', path: '/api/ranking' },
  { key: 'weekly', label: '이번 주 수익률', path: '/api/titles/weekly-ranking' },
];
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
// 탭을 오가거나 다시 들어와도 지난 값부터 보여 주고 뒤에서 갱신한다.
const cache = {};

const percentText = value => {
  const percent = Number(value) || 0;
  return `${percent > 0 ? '+' : percent < 0 ? '−' : ''}${Math.abs(percent).toFixed(2)}%`;
};
/** "2026-10-19" → "10.19(월)". 날짜만 다루므로 UTC로 읽는다. */
const weekDay = iso => {
  const date = new Date(`${iso}T00:00:00Z`);
  return `${date.getUTCMonth() + 1}.${date.getUTCDate()}(${WEEKDAYS[date.getUTCDay()]})`;
};
const countdown = (iso, now) => {
  const minutes = Math.floor(Math.max(0, Date.parse(iso) - now) / 60_000);
  return `${Math.floor(minutes / 1440)}일 ${String(Math.floor((minutes % 1440) / 60)).padStart(2, '0')}시간 ${String(minutes % 60).padStart(2, '0')}분`;
};

/** 웹 랭킹과 같이 5초마다 다시 읽는다. 한 번에 한 요청만 보낸다. */
function usePolled(path) {
  const [state, setState] = useState(() => ({ data: cache[path] ?? null, error: false }));
  const reload = useRef(async () => {});
  useEffect(() => {
    let active = true;
    let busy = false;
    setState({ data: cache[path] ?? null, error: false });
    const load = async () => {
      if (!active || busy) return;
      busy = true;
      try {
        const data = await request(path);
        cache[path] = data;
        if (active) setState({ data, error: false });
      } catch {
        if (active) setState(current => ({ ...current, error: true }));
      } finally {
        busy = false;
      }
    };
    reload.current = load;
    void load();
    const timer = setInterval(load, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [path]);
  return { ...state, reload: () => reload.current() };
}

/** 랭킹. 할 일은 비교 하나다: 누가 앞서 있는지, 그리고 나는 어디쯤인지. */
export function RankingScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user, profile } = useAuth();
  const [tab, setTab] = useState('total');
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(Date.now());
  const current = TABS.find(item => item.key === tab);
  const { data, error, reload } = usePolled(current.path);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const weekly = tab === 'weekly';
  const me = weekly ? data?.me ?? null : null;
  const entries = weekly
    ? data?.entries ?? []
    // 서버가 직급 → 직급 수익률 순으로 매겨 보낸다. 운용 자산은 회사 돈이라 다시 정렬하지 않는다.
    : (Array.isArray(data) ? data : []);
  const isMine = entry => (weekly ? Boolean(me) && entry.rank === me.rank : Boolean(profile?.nickname) && entry.nickname === profile.nickname);
  // 주간 표에 내 순위가 없으면 맨 아래에 따로 붙인다.
  const rows = me && !entries.some(entry => entry.rank === me.rank) ? [...entries, { gap: true, rank: -1 }, me] : entries;

  const refresh = async () => {
    setRefreshing(true);
    await reload();
    setRefreshing(false);
  };

  const empty = () => (
    <Txt variant="callout" tone="inkSoft" style={styles.empty}>
      {data == null && !error ? '랭킹을 불러오는 중이에요'
        : data == null ? '랭킹을 불러오지 못했어요. 아래로 당겨 다시 불러오세요.'
        : weekly ? '이번 주에 거래한 사용자가 아직 없어요. 첫 거래로 1위에 올라 보세요.'
        : '아직 랭킹에 참여한 사용자가 없어요.'}
    </Txt>
  );

  const header = (
    <View style={styles.head}>
      <Txt variant="page" accessibilityRole="header">랭킹</Txt>
      <View style={[styles.segment, { backgroundColor: theme.card }]} accessibilityRole="tablist">
        {TABS.map(item => {
          const selected = item.key === tab;
          return (
            <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => setTab(item.key)} style={[styles.segmentItem, selected && { backgroundColor: theme.ink }]}>
              <Txt variant="callout" style={{ color: selected ? theme.card : theme.ink }}>{item.label}</Txt>
            </Pressable>
          );
        })}
      </View>
      {weekly && data?.weekStart ? (
        <View style={styles.week}>
          <Txt variant="caption" tone="inkSoft">{`${weekDay(data.weekStart)} ~ ${weekDay(data.weekEnd)}`}</Txt>
          <Txt variant="caption" numeric>{`주간 정산까지 ${countdown(data.awardAt, now)}`}</Txt>
        </View>
      ) : null}
      {data != null && error ? <Txt variant="caption">새 순위를 받지 못해 마지막으로 받은 값이에요.</Txt> : null}
    </View>
  );

  const footer = data != null ? (
    <Txt variant="caption" tone="inkSoft" style={styles.note}>
      {weekly
        ? `${user && !me ? '이번 주에 한 번 이상 거래하면 순위에 들어가요. ' : ''}이번 주에 1회 이상 거래한 사용자만 집계하고, 출석·미션 보상과 대출금은 수익률에서 빼요.`
        : '높은 직급이 먼저이고, 같은 직급끼리는 직급을 시작할 때 자산 대비 수익률로 매긴 상위 100명이에요. 금액은 회사가 맡긴 운용 자산이에요.'}
    </Txt>
  ) : null;

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <FlatList
        data={rows}
        keyExtractor={(item, index) => (item.gap ? 'gap' : `${item.rank}-${item.nickname}-${index}`)}
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} progressViewOffset={insets.top} />}
        ListHeaderComponent={header}
        ListEmptyComponent={empty}
        ListFooterComponent={footer}
        renderItem={({ item, index }) => {
          const first = index === 0;
          const last = index === rows.length - 1;
          const shape = [styles.rowBox, { backgroundColor: theme.card }, first && styles.first, last && styles.last, !first && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }];
          if (item.gap) {
            return <View style={[...shape, styles.gap]}><Txt variant="caption" tone="inkSoft">내 순위</Txt></View>;
          }
          const mine = isMine(item);
          const percent = weekly ? item.weeklyReturnPercent : item.changePercent;
          const title = item.equippedTitle;
          return (
            <View
              style={[...shape, styles.row, mine && { backgroundColor: theme.brandWash }]}
              accessible
              accessibilityLabel={`${MEDALS[item.rank] ? `${MEDALS[item.rank].label} ` : ''}${item.rank}위 ${title ? `${title} ` : ''}${item.nickname}${mine ? ', 나' : ''}. 운용 자산 ${won(item.totalAsset)}, ${weekly ? '이번 주' : ''} 수익률 ${percentText(percent)}`}
            >
              <Rank rank={item.rank} />
              <Avatar url={item.profileImageUrl} />
              <View style={styles.who}>
                {title ? <Txt variant="micro" numberOfLines={1} style={{ color: titleInk(title, theme) }}>{title}</Txt> : null}
                <View style={styles.nameLine}>
                  <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{item.nickname}</Txt>
                  {mine ? <View style={[styles.meBadge, { backgroundColor: theme.brand }]}><Txt variant="micro" style={{ color: theme.onBrand }}>나</Txt></View> : null}
                </View>
              </View>
              <View style={styles.value}>
                <Txt variant="callout" numeric numberOfLines={1}>{won(item.totalAsset)}</Txt>
                <Txt variant="caption" tone={toneOf(percent)} numeric>{percentText(percent)}</Txt>
              </View>
            </View>
          );
        }}
      />
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
    </View>
  );
}

/**
 * 칭호 색표는 흰 바탕 기준이라 다크에서는 읽히지 않는다. 웹(.has-title-ink)과 같은 비율로
 * 다크는 흰색 쪽으로 55%, 라이트는 잉크 쪽으로 15% 섞는다.
 */
function titleInk(title, theme) {
  const color = titleColor(title);
  if (!color) return theme.inkSoft;
  return theme.scheme === 'dark' ? mixColor(color, '#FFFFFF', 0.45) : mixColor(color, theme.ink, 0.85);
}

// 1~3위 순위 원판. DESIGN.md의 금·은·동 위에 무대색 숫자를 쓴다. 라이트의 흰 카드 위에서는
// 원판과 카드의 대비가 약해 3:1 이상인 테두리로 경계를 잡는다.
const MEDALS = {
  1: { fill: '#F2C94C', ring: '#B8860B', label: '금메달' },
  2: { fill: '#C9CED8', ring: '#8A9099', label: '은메달' },
  3: { fill: '#D79A63', ring: '#A8642E', label: '동메달' },
};

/** 순위. 1~3위는 금·은·동 메달 안에 숫자를 넣고, 나머지는 숫자만 쓴다. */
function Rank({ rank }) {
  const theme = useTheme();
  const medal = MEDALS[rank];
  if (!medal) return <Txt variant="headline" tone="inkSoft" numeric style={styles.rank}>{rank}</Txt>;
  return (
    <View style={styles.rankBox} accessibilityLabel={`${medal.label} ${rank}위`}>
      <View style={[styles.medal, { backgroundColor: medal.fill, borderColor: theme.scheme === 'light' ? medal.ring : medal.fill }]}>
        <Txt variant="callout" numeric style={styles.medalText}>{rank}</Txt>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  rankBox: { width: 28, alignItems: 'center' },
  medal: { width: 28, height: 28, borderRadius: 14, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center' },
  medalText: { color: '#12101C' },
  fill: { flex: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  head: { paddingHorizontal: SPACE.lg, paddingTop: SPACE.sm, paddingBottom: SPACE.md, gap: SPACE.md },
  segment: { flexDirection: 'row', borderRadius: RADIUS, padding: 4, gap: 4 },
  segmentItem: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
  week: { flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap', gap: SPACE.sm },
  rowBox: { marginHorizontal: SPACE.lg, paddingHorizontal: SPACE.lg + 2 },
  first: { borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS },
  last: { borderBottomLeftRadius: RADIUS, borderBottomRightRadius: RADIUS },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 68, paddingVertical: SPACE.md },
  gap: { minHeight: 36, justifyContent: 'center' },
  rank: { width: 28, textAlign: 'center' },
  who: { flex: 1, minWidth: 0, gap: 1 },
  nameLine: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  shrink: { flexShrink: 1 },
  meBadge: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  value: { alignItems: 'flex-end', maxWidth: '45%' },
  note: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.md, lineHeight: 18 },
  empty: { textAlign: 'center', paddingVertical: SPACE.xxl, paddingHorizontal: SPACE.lg },
});
