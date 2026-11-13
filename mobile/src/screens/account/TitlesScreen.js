import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request, useAuth } from '../../auth';
import { AccountFrame, Card, Chips, Empty, Notice, kit } from '../../components/Kit';
import { titleColor } from '../../titles';
import { RADIUS, SPACE, Txt, mixColor, useTheme } from '../../theme';

const CATEGORY_ORDER = ['자산', '수익률', '보유', '거래', '팬덤', '예측', '활동', '수집', '주간', '시즌', '기념', '특별'];
// 서버가 아직 특별 칭호를 내려주지 않아도 목록에는 늘 미획득으로 보이게 한다(웹 withSpecialTitles와 같다).
const SPECIAL = [
  { id: 'special_syuangel', name: '슈엔젤' },
  { id: 'special_misilis_holder', name: '미실리스 대주주' },
  { id: 'special_syuaga', name: '슈아가' },
];

function withSpecial(status) {
  const known = new Set(status.titles.map(title => title.id));
  const missing = SPECIAL.filter(title => !known.has(title.id)).map(title => ({
    ...title, description: '프로모션 코드를 넣으면 받을 수 있어요', category: '특별', weekly: false, owned: false, equipped: false, count: 0, acquiredAt: null, updatedAt: null,
  }));
  return missing.length ? { ...status, titles: [...status.titles, ...missing], totalCount: status.totalCount + missing.length } : status;
}

/** 칭호 색표는 흰 바탕 기준이라 테마에 맞춰 섞는다(랭킹과 같은 비율). */
function inkFor(name, theme) {
  const color = titleColor(name);
  if (!color) return theme.ink;
  return theme.scheme === 'dark' ? mixColor(color, '#FFFFFF', 0.45) : mixColor(color, theme.ink, 0.85);
}

const shortDate = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${String(at.getUTCFullYear()).slice(2)}.${at.getUTCMonth() + 1}.${at.getUTCDate()}`;
};

/** 칭호. 할 일은 받은 칭호를 확인하고 하나를 다는 것이다. 단 칭호는 랭킹에서 닉네임 위에 보인다. */
export function TitlesScreen({ onBack }) {
  const theme = useTheme();
  const { profile } = useAuth();
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState('');
  const [category, setCategory] = useState('all');
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  // 칭호 확인(check)은 조건을 다시 따져 새 칭호를 지급한 뒤 전체 목록을 돌려준다.
  const load = useCallback(async () => {
    try {
      const next = await request('/api/titles/check', { method: 'POST' });
      if (!alive.current) return;
      setStatus(withSpecial(next));
      setError('');
      const fresh = (next.newlyAwarded ?? []).map(title => title.name);
      if (fresh.length) setNotice(`새 칭호를 받았어요: ${fresh.join(', ')}`);
    } catch {
      if (alive.current) setError('칭호 정보를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  // 이미 단 칭호를 다시 누르면 뗀다.
  const toggle = async title => {
    if (pending || !title.owned) return;
    setPending(true);
    setNotice('');
    try {
      const next = await request('/api/titles/equipped', { method: 'PUT', body: { titleId: title.equipped ? '' : title.id } });
      if (alive.current) setStatus(withSpecial(next));
    } catch (failure) {
      if (alive.current) setNotice(failure.message);
    } finally {
      if (alive.current) setPending(false);
    }
  };

  if (!status) {
    return (
      <AccountFrame title="칭호" onBack={onBack} onRefresh={load}>
        <Card><Empty loading={!error} text={error || '칭호를 확인하는 중이에요'} /></Card>
      </AccountFrame>
    );
  }

  const categories = CATEGORY_ORDER.filter(name => status.titles.some(title => title.category === name));
  const visible = status.titles
    .filter(title => category === 'all' || title.category === category)
    .sort((a, b) => Number(b.owned) - Number(a.owned) || CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category));
  const equipped = status.titles.find(title => title.equipped);
  const share = status.ownedCount / Math.max(1, status.totalCount);
  const chips = [
    { key: 'all', label: '전체', count: `${status.ownedCount}/${status.totalCount}` },
    ...categories.map(name => {
      const titles = status.titles.filter(title => title.category === name);
      return { key: name, label: name, count: `${titles.filter(title => title.owned).length}/${titles.length}` };
    }),
  ];

  return (
    <AccountFrame title="칭호" onBack={onBack} onRefresh={load}>
      <View style={kit.hero} accessible accessibilityLabel={`칭호 ${status.totalCount}개 중 ${status.ownedCount}개 수집`}>
        <Txt variant="caption" tone="inkSoft">수집한 칭호</Txt>
        <Txt variant="price" numeric>
          {status.ownedCount}
          <Txt variant="title" tone="inkSoft" numeric>{` / ${status.totalCount}`}</Txt>
        </Txt>
        <View style={[styles.meter, { backgroundColor: theme.raised }]}>
          <View style={{ width: `${Math.round(share * 100)}%`, backgroundColor: theme.ink }} />
        </View>
      </View>

      {/* 랭킹에서 보이는 모습 그대로 미리 보여 준다. */}
      <Card style={styles.plate}>
        <Txt variant="caption" tone="inkSoft">랭킹에서 보이는 모습</Txt>
        <Txt variant="caption" style={{ color: equipped ? inkFor(equipped.name, theme) : theme.inkSoft }}>{equipped ? equipped.name : '단 칭호 없음'}</Txt>
        <Txt variant="headline" numberOfLines={1}>{profile?.nickname ?? '내 닉네임'}</Txt>
      </Card>

      {notice ? <Notice text={notice} /> : null}

      <View style={styles.chips}><Chips options={chips} value={category} onChange={setCategory} /></View>

      <View style={styles.grid}>
        {visible.map(title => {
          const date = title.weekly ? title.updatedAt : title.acquiredAt;
          const state = title.equipped ? '달고 있음' : title.owned ? `받음${date ? ` · ${shortDate(date)}` : ''}` : '아직 못 받음';
          return (
            <Pressable
              key={title.id}
              accessibilityRole={title.owned ? 'button' : undefined}
              accessibilityState={{ selected: title.equipped, disabled: !title.owned || pending }}
              accessibilityLabel={`${title.name}${title.weekly && title.owned ? ` ${title.count}관왕` : ''}. ${title.description}. ${state}${title.owned ? (title.equipped ? '. 누르면 떼요' : '. 누르면 달아요') : ''}`}
              disabled={!title.owned || pending}
              onPress={() => void toggle(title)}
              style={({ pressed }) => [
                styles.title,
                { backgroundColor: title.owned ? theme.card : theme.ground, borderColor: title.equipped ? theme.brand : theme.line },
                title.equipped && styles.titleEquipped,
                pressed && { backgroundColor: theme.pressed },
              ]}
            >
              <View style={styles.titleHead}>
                <Txt variant="callout" numberOfLines={2} style={[styles.shrink, { color: title.owned ? inkFor(title.name, theme) : theme.inkSoft }]}>{title.name}</Txt>
                {title.weekly && title.owned ? <Txt variant="micro" tone="inkSoft" numeric>{`${title.count}관왕`}</Txt> : null}
              </View>
              <Txt variant="caption" tone="inkSoft" numberOfLines={3} style={styles.grow}>{title.description}</Txt>
              <View style={styles.state}>
                {!title.owned ? <Ionicons name="lock-closed-outline" size={12} color={theme.inkSoft} /> : null}
                <Txt variant="micro" tone={title.equipped ? 'brandText' : 'inkSoft'} numeric>{state}</Txt>
              </View>
            </Pressable>
          );
        })}
      </View>
      <Txt variant="caption" tone="inkSoft" style={styles.foot}>주간 수익률 칭호는 매주 월요일에 지급돼요. 특별 칭호는 충전하기 화면에서 프로모션 코드를 넣으면 받을 수 있어요.</Txt>
    </AccountFrame>
  );
}

const styles = StyleSheet.create({
  meter: { height: 6, borderRadius: 3, overflow: 'hidden', flexDirection: 'row', marginTop: SPACE.md },
  plate: { gap: 2 },
  chips: { marginBottom: SPACE.md },
  grid: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', rowGap: SPACE.sm },
  title: { width: '48.8%', minHeight: 132, borderRadius: RADIUS, borderWidth: 1, padding: SPACE.md, gap: 6 },
  titleEquipped: { borderWidth: 2 },
  titleHead: { flexDirection: 'row', alignItems: 'flex-start', gap: 4 },
  shrink: { flex: 1, minWidth: 0 },
  grow: { flex: 1 },
  state: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  foot: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg },
});
