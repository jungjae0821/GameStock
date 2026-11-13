import React, { useEffect, useRef, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { request, useAuth } from '../auth';
import { Avatar } from '../components/Avatar';
import { PublicProfileModal } from '../components/Engagement';
import { GameIcon } from '../components/GameIcon';
import { useEngagement, usePublicNicknames } from '../engagement';
import { won } from '../format';
import { titleColor } from '../titles';
import { RADIUS, RADIUS_SM, RADIUS_XS, SPACE, Txt, mixColor, toneOf, useTheme } from '../theme';

const TABS = [
  { key: 'total', label: '트레이더', path: '/api/ranking' },
  { key: 'wealth', label: '부자', path: '/api/ranking/wealth' },
  { key: 'weekly', label: '이번 주', path: '/api/titles/weekly-ranking' },
  { key: 'season', label: '시즌', path: '/api/engagement/season', interval: 10_000 },
  { key: 'fandom', label: '팬덤전', path: '/api/engagement/fandoms', interval: 10_000 },
];
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
// 탭을 오가거나 다시 들어와도 지난 값부터 보여 주고 뒤에서 갱신한다.
const cache = {};
// 마지막에 본 탭. 홈의 "팬덤 대항전 > 전체 순위"처럼 다른 화면이 열 탭을 미리 정할 수도 있다.
let preferredTab = 'total';
export const preferRankingTab = key => { if (TABS.some(tab => tab.key === key)) preferredTab = key; };

const percentText = value => {
  const percent = Number(value) || 0;
  return `${percent > 0 ? '+' : percent < 0 ? '−' : ''}${Math.abs(percent).toFixed(2)}%`;
};
/** "2026-10-19" → "10.19(월)". 날짜만 다루므로 UTC로 읽는다. */
const weekDay = iso => {
  const date = new Date(`${iso}T00:00:00Z`);
  return `${date.getUTCMonth() + 1}.${date.getUTCDate()}(${WEEKDAYS[date.getUTCDay()]})`;
};
/** 시각(ISO)을 한국시간 "11.14"로. */
const kstDay = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${at.getUTCMonth() + 1}.${at.getUTCDate()}`;
};
const countdown = (iso, now) => {
  const minutes = Math.floor(Math.max(0, Date.parse(iso) - now) / 60_000);
  return `${Math.floor(minutes / 1440)}일 ${String(Math.floor((minutes % 1440) / 60)).padStart(2, '0')}시간 ${String(minutes % 60).padStart(2, '0')}분`;
};

/**
 * 웹 랭킹과 같이 5초(시즌·팬덤은 10초)마다 다시 읽는다. 한 번에 한 요청만 보낸다.
 * loaded는 이 주소에서 한 번이라도 답을 받았는지(시즌이 없으면 서버가 빈 답을 준다), missing은 서버가 아직 모르는 기능(404).
 */
function usePolled(path, interval = 5000) {
  const initial = () => ({ data: cache[path] ?? null, loaded: path in cache, error: false, missing: false });
  const [state, setState] = useState(initial);
  const reload = useRef(async () => {});
  useEffect(() => {
    let active = true;
    let busy = false;
    setState(initial());
    const load = async () => {
      if (!active || busy) return;
      busy = true;
      try {
        const data = await request(path);
        cache[path] = data;
        if (active) setState({ data, loaded: true, error: false, missing: false });
      } catch (failure) {
        if (active) setState(current => ({ ...current, error: true, missing: failure?.status === 404 }));
      } finally {
        busy = false;
      }
    };
    reload.current = load;
    void load();
    const timer = setInterval(load, interval);
    return () => { active = false; clearInterval(timer); };
  }, [path, interval]); // eslint-disable-line react-hooks/exhaustive-deps
  return { ...state, reload: () => reload.current() };
}

/** 랭킹. 할 일은 비교 하나다: 누가 앞서 있는지, 그리고 나는 어디쯤인지. */
export function RankingScreen({ market }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user, profile } = useAuth();
  const engagement = useEngagement();
  const publicNames = usePublicNicknames();
  const [tab, setTabState] = useState(preferredTab);
  const setTab = key => { preferredTab = key; setTabState(key); };
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [viewing, setViewing] = useState(null);
  const current = TABS.find(item => item.key === tab);
  const { data, loaded, error, missing, reload } = usePolled(current.path, current.interval);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const weekly = tab === 'weekly';
  const season = tab === 'season';
  const fandom = tab === 'fandom';
  const wealth = tab === 'wealth';
  // 주간·시즌은 { entries, me }, 나머지는 배열이다.
  const ranked = weekly || season;
  const me = ranked ? data?.me ?? null : null;
  const entries = ranked
    ? data?.entries ?? []
    // 서버가 직급 → 직급 수익률 순으로 매겨 보낸다. 운용 자산은 회사 돈이라 다시 정렬하지 않는다.
    : (Array.isArray(data) ? data : []);
  const myFandom = engagement?.fandomCode ?? null;
  const isMine = entry => (fandom ? entry.stockCode === myFandom
    : ranked ? Boolean(me) && entry.rank === me.rank
    : Boolean(profile?.nickname) && entry.nickname === profile.nickname);
  // 주간·시즌 표에 내 순위가 없으면 맨 아래에 따로 붙인다.
  const rows = me && !entries.some(entry => entry.rank === me.rank) ? [...entries, { gap: true, rank: -1 }, me] : entries;
  const mostFans = fandom ? Math.max(1, ...entries.map(entry => entry.fans ?? 0)) : 1;

  const refresh = async () => {
    setRefreshing(true);
    await reload();
    setRefreshing(false);
  };

  const emptyText = (season && (missing || (loaded && !data)))
    ? '아직 열린 시즌이 없어요. 시즌이 시작되면 그날 자산을 기준으로 수익률 순위를 매겨요.'
    : fandom && missing ? '팬덤 대항전은 곧 열려요.'
    : !loaded && !error ? '랭킹을 불러오는 중이에요'
    : !loaded ? '랭킹을 불러오지 못했어요. 아래로 당겨 다시 불러오세요.'
    : weekly ? '이번 주에 거래한 사용자가 아직 없어요. 첫 거래로 1위에 올라 보세요.'
    : wealth ? '아직 지갑에 돈이 쌓인 사람이 없어요. 성과급과 미션 보상이 쌓이면 여기에 올라와요.'
    : season ? '아직 순위에 오른 사람이 없어요.'
    : '아직 랭킹에 참여한 사용자가 없어요.';
  const empty = () => <Txt variant="callout" tone="inkSoft" style={styles.empty}>{emptyText}</Txt>;

  const header = (
    <View style={styles.head}>
      <Txt variant="page" accessibilityRole="header">랭킹</Txt>
      <View style={[styles.segment, { backgroundColor: theme.card }]} accessibilityRole="tablist">
        {TABS.map(item => {
          const selected = item.key === tab;
          return (
            <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => setTab(item.key)} style={[styles.segmentItem, selected && { backgroundColor: theme.ink }]}>
              <Txt variant="caption" numberOfLines={1} style={{ color: selected ? theme.card : theme.ink }}>{item.label}</Txt>
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
      {season && data?.name ? (
        <View style={styles.week}>
          <Txt variant="caption">{data.name}</Txt>
          <Txt variant="caption" tone="inkSoft" numeric>
            {`${data.status === 'ACTIVE' ? `${kstDay(data.startedAt)} 시작` : `${kstDay(data.startedAt)} ~ ${kstDay(data.endedAt)}`} · ${Number(data.participants || 0).toLocaleString('ko-KR')}명 참여`}
          </Txt>
        </View>
      ) : null}
      {loaded && error && !missing ? <Txt variant="caption">새 순위를 받지 못해 마지막으로 받은 값이에요.</Txt> : null}
    </View>
  );

  const note = weekly
    ? `${user && !me ? '이번 주에 한 번 이상 거래하면 순위에 들어가요. ' : ''}이번 주에 1회 이상 거래한 사용자만 집계하고, 출석·미션 보상과 대출금은 수익률에서 빼요.`
    : wealth ? '성과급, 출석·미션·예측 보상처럼 내 지갑에 들어온 돈만 세요. 회사가 맡긴 운용계좌는 넣지 않아요.'
    : season ? `${data?.status === 'ACTIVE' ? '시즌이 끝나는 순간의 순위로 1~3위에게 시즌 칭호를 줘요. ' : '지난 시즌 최종 순위예요. '}자산은 그대로 두고, 시즌 시작 때 자산과 비교해 수익률을 매겨요. 관리자 지급과 승진 때 회사가 채워 준 운용한도는 빼고 계산해요.`
    : fandom ? `팬은 설정에서 주력 게임으로 고른 사람, 주주는 지금 그 주식을 1주 이상 가진 사람이에요.${engagement && !myFandom ? ' 설정에서 주력 게임을 고르면 내 팬덤 순위에 힘을 보탤 수 있어요.' : ''}`
    : '높은 직급이 먼저이고, 같은 직급끼리는 직급을 시작할 때 자산 대비 수익률로 매긴 상위 100명이에요. 금액은 회사가 맡긴 운용 자산이에요.';
  const footer = rows.length ? (
    <>
      <Txt variant="caption" tone="inkSoft" style={styles.note}>{note}</Txt>
      {!fandom && rows.some(row => !row.gap && (season ? row.publicProfile : publicNames.has(row.nickname))) ? <Txt variant="caption" tone="inkSoft" style={styles.noteSmall}>밑줄 친 닉네임은 포트폴리오를 공개한 사람이에요. 누르면 보유 종목을 볼 수 있어요.</Txt> : null}
    </>
  ) : null;

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <FlatList
        data={rows}
        keyExtractor={(item, index) => (item.gap ? 'gap' : `${item.rank}-${item.nickname ?? item.stockCode}-${index}`)}
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
          if (fandom) return <FandomRow item={item} mine={mine} most={mostFans} shape={shape} />;
          const percent = weekly ? item.weeklyReturnPercent : season ? item.returnPercent : item.changePercent;
          const value = wealth ? item.personalCash : season ? item.currentAsset : item.totalAsset;
          const title = item.equippedTitle;
          const open = season ? Boolean(item.publicProfile) : publicNames.has(item.nickname);
          const label = `${MEDALS[item.rank] ? `${MEDALS[item.rank].label} ` : ''}${item.rank}위 ${title ? `${title} ` : ''}${item.nickname}${mine ? ', 나' : ''}. `
            + (wealth ? `내 지갑 ${won(value)}, ${item.gradeLabel ?? '이전 대기'}` : `${season ? '자산' : '운용 자산'} ${won(value)}, ${weekly ? '이번 주 ' : season ? '시즌 ' : ''}수익률 ${percentText(percent)}`)
            + (open ? '. 누르면 공개 포트폴리오를 봐요' : '');
          const body = (
            <>
              <Rank rank={item.rank} />
              <Avatar url={item.profileImageUrl} />
              <View style={styles.who}>
                {title ? <Txt variant="micro" numberOfLines={1} style={{ color: titleInk(title, theme) }}>{title}</Txt> : null}
                <View style={styles.nameLine}>
                  <Txt variant="callout" numberOfLines={1} style={[styles.shrink, open && styles.link]}>{item.nickname}</Txt>
                  {mine ? <View style={[styles.meBadge, { backgroundColor: theme.brand }]}><Txt variant="micro" style={{ color: theme.onBrand }}>나</Txt></View> : null}
                </View>
              </View>
              <View style={styles.value}>
                <Txt variant="callout" numeric numberOfLines={1}>{won(value)}</Txt>
                {wealth
                  ? <Txt variant="caption" tone="inkSoft" numberOfLines={1}>{item.gradeLabel ?? '이전 대기'}</Txt>
                  : <Txt variant="caption" tone={toneOf(percent)} numeric>{percentText(percent)}</Txt>}
              </View>
            </>
          );
          const rowStyle = [...shape, styles.row, mine && { backgroundColor: theme.brandWash }];
          if (!open) return <View style={rowStyle} accessible accessibilityLabel={label}>{body}</View>;
          return (
            <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={() => setViewing(item.nickname)}
              style={({ pressed }) => [...rowStyle, pressed && !mine && { backgroundColor: theme.pressed }]}>
              {body}
            </Pressable>
          );
        }}
      />
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
      <PublicProfileModal nickname={viewing} onClose={() => setViewing(null)} stocks={market?.stocks ?? []} />
    </View>
  );
}

/** 팬덤 대항전 한 줄. 팬 수가 많은 순이고, 이번 주 수익률은 그 팬들의 평균이다. */
function FandomRow({ item, mine, most, shape }) {
  const theme = useTheme();
  const weeklyReturn = item.weeklyReturnPercent;
  return (
    <View
      style={[...shape, styles.row, mine && { backgroundColor: theme.brandWash }]}
      accessible
      accessibilityLabel={`${item.rank}위 ${item.name}${mine ? ', 내 팬덤' : ''}. 팬 ${item.fans.toLocaleString('ko-KR')}명, 주주 ${item.holders.toLocaleString('ko-KR')}명, 팬 주간 수익률 ${weeklyReturn == null ? '집계 전' : percentText(weeklyReturn)}`}
    >
      <Rank rank={item.rank} />
      <GameIcon code={item.stockCode} size={36} />
      <View style={styles.who}>
        <View style={styles.nameLine}>
          <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{item.name}</Txt>
          {mine ? <View style={[styles.meBadge, { backgroundColor: theme.brand }]}><Txt variant="micro" style={{ color: theme.onBrand }}>내 팬덤</Txt></View> : null}
        </View>
        <View style={[styles.fanBar, { backgroundColor: theme.raised }]}>
          <View style={{ width: `${Math.max(2, (item.fans / most) * 100)}%`, height: '100%', borderRadius: 3, backgroundColor: mine ? theme.ink : theme.edge }} />
        </View>
        <Txt variant="micro" tone="inkSoft" numeric>{`팬 ${item.fans.toLocaleString('ko-KR')}명 · 주주 ${item.holders.toLocaleString('ko-KR')}명`}</Txt>
      </View>
      <View style={styles.value}>
        <Txt variant="micro" tone="inkSoft">팬 주간 수익률</Txt>
        {weeklyReturn == null
          ? <Txt variant="caption" tone="inkSoft">집계 전</Txt>
          : <Txt variant="callout" tone={toneOf(weeklyReturn)} numeric>{percentText(weeklyReturn)}</Txt>}
      </View>
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
  segmentItem: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 2 },
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
  link: { textDecorationLine: 'underline' },
  meBadge: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  value: { alignItems: 'flex-end', maxWidth: '45%' },
  fanBar: { height: 6, borderRadius: 3, marginVertical: 4, overflow: 'hidden' },
  note: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.md, lineHeight: 18 },
  noteSmall: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.xs, lineHeight: 18 },
  empty: { textAlign: 'center', paddingVertical: SPACE.xxl, paddingHorizontal: SPACE.lg },
});
