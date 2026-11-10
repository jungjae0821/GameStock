import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAccount } from '../account';
import { useAuth } from '../auth';
import { GameIcon } from '../components/GameIcon';
import { cleanTitle, relativeTime, serverTimestamp, signedPercent, won } from '../format';
import { api, newsEffect } from '../market';
import { RADIUS, SPACE, Txt, toneOf, useTheme } from '../theme';

const EXCERPT_PARAGRAPHS = 2;
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const excerptCache = new Map();

/** 한국시간 "10월 21일 (화) 14:32". 기기 시간대와 상관없이 같은 값을 보여 준다. */
function kstDateTime(iso) {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일 (${WEEKDAYS[at.getUTCDay()]}) ${String(at.getUTCHours()).padStart(2, '0')}:${String(at.getUTCMinutes()).padStart(2, '0')}`;
}

const hostOf = url => { try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return null; } };

/** 웹 NewsDetail.readDescription과 같은 규칙: 설명에서 원문 링크를 꺼내고, 제목을 되풀이하는 줄은 문단으로 치지 않는다. */
function readDescription(raw, title) {
  if (!raw?.trim()) return { paragraphs: [], link: null };
  let link = null;
  let source = raw.replace(/출처:\s*(https?:\/\/\S+)/gi, (_, url) => { link ??= url; return ''; });
  const href = source.match(/<a[^>]+href="(https?:\/\/[^"]+)"/i);
  if (!link && href) link = href[1];
  source = source
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&quot;/gi, '"').replace(/&#39;/g, "'");
  const squeeze = value => value.replace(/[\s\-–·]+/g, '');
  const titleKey = squeeze(title).slice(0, 20);
  const paragraphs = source
    .split(/\n\s*\n|\r?\n/)
    .map(line => line.replace(/[ \t]+/g, ' ').trim())
    .filter(line => line.length > 0 && !squeeze(line).startsWith(titleKey))
    .slice(0, EXCERPT_PARAGRAPHS);
  return { paragraphs, link };
}

/** 기사 첫 두 문단. 목록에 미리 실려 왔으면 그대로 쓰고, 아니면 Google 뉴스 링크인 기사만 서버에 묻는다. */
function useExcerpt(item, storedLink) {
  const preloaded = item.articleUrl ? { paragraphs: (item.excerpt ?? []).slice(0, EXCERPT_PARAGRAPHS), url: item.articleUrl } : null;
  const remote = !preloaded && Boolean(storedLink?.startsWith('https://news.google.com/'));
  const [state, setState] = useState(() => ({ link: storedLink, excerpt: storedLink ? excerptCache.get(storedLink) ?? null : null }));
  useEffect(() => {
    if (!remote) return undefined;
    const cached = excerptCache.get(storedLink);
    if (cached) { setState({ link: storedLink, excerpt: cached }); return undefined; }
    let cancelled = false;
    setState({ link: storedLink, excerpt: null });
    api(`/api/news/excerpt?link=${encodeURIComponent(storedLink)}`)
      .then(result => {
        const excerpt = { paragraphs: (result?.paragraphs ?? []).slice(0, EXCERPT_PARAGRAPHS), url: result?.url ?? null };
        excerptCache.set(storedLink, excerpt);
        if (!cancelled) setState({ link: storedLink, excerpt });
      })
      .catch(() => { if (!cancelled) setState({ link: storedLink, excerpt: { paragraphs: [], url: null } }); });
    return () => { cancelled = true; };
  }, [storedLink, remote]);
  if (preloaded) return { excerpt: preloaded, loading: false };
  const current = state.link === storedLink ? state.excerpt : null;
  return { excerpt: current, loading: remote && current === null };
}

/** 뉴스 상세. 할 일은 읽기 하나다: 기사 첫 문단, 원문 링크, 그리고 발행 뒤 가격이 어떻게 움직였는지. */
export function NewsDetailScreen({ item, market, onBack, onOpenStock, onLogin }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user } = useAuth();
  const account = useAccount();
  const [watchError, setWatchError] = useState('');
  const stock = market.stocks.find(entry => entry.code === item?.stockCode);
  const title = cleanTitle(item?.title);
  const stored = useMemo(() => readDescription(item?.description, item?.title ?? ''), [item?.description, item?.title]);
  const { excerpt, loading } = useExcerpt(item ?? {}, stored.link);

  if (!item) {
    return (
      <View style={[styles.fill, styles.center, { backgroundColor: theme.ground, paddingTop: insets.top }]}>
        <Txt variant="callout" tone="inkSoft">이 기사를 찾을 수 없어요. 목록에서 다른 기사를 골라 주세요.</Txt>
        <Pressable accessibilityRole="button" onPress={onBack} style={styles.backLink}><Txt variant="callout" tone="inkSoft">뉴스 목록으로</Txt></Pressable>
      </View>
    );
  }

  const effect = newsEffect(item, stock);
  const paragraphs = excerpt?.paragraphs?.length ? excerpt.paragraphs : loading ? [] : stored.paragraphs;
  const original = excerpt?.url ?? stored.link;
  const host = excerpt?.url ? hostOf(excerpt.url) : null;
  const recent = Date.now() - (serverTimestamp(item.publishedAt) || 0) < 60 * 60_000 ? relativeTime(item.publishedAt) : null;
  const watching = account.watchlist.includes(item.stockCode);

  const toggleWatch = async () => {
    if (!user) { onLogin(); return; }
    setWatchError('');
    try { await account.toggleWatch(item.stockCode); } catch (error) { setWatchError(error.message); }
  };

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <View style={[styles.bar, { paddingTop: insets.top, backgroundColor: theme.ground }]}>
        <Pressable accessibilityRole="button" accessibilityLabel="뉴스 목록으로 돌아가기" onPress={onBack} style={styles.barButton}>
          <Ionicons name="chevron-back" size={26} color={theme.ink} />
        </Pressable>
      </View>
      <ScrollView contentContainerStyle={{ paddingHorizontal: SPACE.lg, paddingBottom: SPACE.xl }}>
        <View style={styles.article}>
          {stock ? (
            <Pressable accessibilityRole="button" accessibilityLabel={`${stock.name} 종목 보기`} onPress={() => onOpenStock(item.stockCode)} style={styles.game}>
              <GameIcon code={item.stockCode} size={28} />
              <Txt variant="callout" style={styles.underline}>{stock.name}</Txt>
              <Txt variant="caption" tone="inkSoft">{item.source}</Txt>
            </Pressable>
          ) : <Txt variant="caption" tone="inkSoft">{item.source}</Txt>}
          <Txt variant="title" accessibilityRole="header" style={styles.title}>{title}</Txt>
          <Txt variant="caption" tone="inkSoft">{recent ? `${kstDateTime(item.publishedAt)} · ${recent}` : kstDateTime(item.publishedAt)}</Txt>
        </View>

        <View style={[styles.card, styles.stats, { backgroundColor: theme.card }]}>
          <Stat label="발행 시점" value={Number(item.priceAtPublish) > 0 ? won(item.priceAtPublish) : '기록 없음'} />
          <Stat label="현재가" value={stock ? won(stock.price) : '기록 없음'} />
          <Stat label="발행 후 등락" value={signedPercent(effect)} tone={toneOf(effect)} />
        </View>

        <View style={[styles.card, styles.excerpt, { backgroundColor: theme.card }]} accessibilityLiveRegion="polite">
          {loading ? (
            <View style={styles.loading}>
              <ActivityIndicator color={theme.inkSoft} />
              <Txt variant="caption" tone="inkSoft">원문에서 첫 문단을 가져오는 중이에요</Txt>
            </View>
          ) : paragraphs.length ? paragraphs.map((paragraph, index) => (
            <Txt key={index} variant="body" style={styles.paragraph}>{paragraph}</Txt>
          )) : <Txt variant="callout" tone="inkSoft">원문에서 본문을 가져오지 못했어요. 아래 링크로 원문을 읽을 수 있어요.</Txt>}
          {original && !loading ? (
            <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(original)} style={styles.origin}>
              <Txt variant="callout" style={styles.underline}>{host ? `${host}에서 전체 기사 읽기` : '원문 기사 열기'}</Txt>
              <Ionicons name="open-outline" size={16} color={theme.ink} />
            </Pressable>
          ) : null}
        </View>
        {watchError ? <Txt variant="caption" style={{ marginBottom: SPACE.sm }}>{watchError}</Txt> : null}
      </ScrollView>

      {stock ? (
        <View style={[styles.actions, { backgroundColor: theme.card, borderTopColor: theme.line }]}>
          <Pressable accessibilityRole="button" accessibilityState={{ selected: watching }} accessibilityLabel={watching ? '관심 종목에서 빼기' : '관심 종목에 넣기'} onPress={() => void toggleWatch()} style={({ pressed }) => [styles.watch, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}>
            <Ionicons name={watching ? 'star' : 'star-outline'} size={22} color={watching ? theme.star : theme.ink} />
          </Pressable>
          <Pressable accessibilityRole="button" onPress={() => onOpenStock(item.stockCode)} style={({ pressed }) => [styles.action, styles.outline, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}>
            <Txt variant="body">종목 보기</Txt>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={() => onOpenStock(item.stockCode, 'buy')} style={({ pressed }) => [styles.action, { backgroundColor: theme.upFill, opacity: pressed ? 0.85 : 1 }]}>
            <Txt variant="body" style={{ color: theme.onFill }}>주문하기</Txt>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

function Stat({ label, value, tone = 'ink' }) {
  return (
    <View style={styles.stat}>
      <Txt variant="caption" tone="inkSoft">{label}</Txt>
      <Txt variant="callout" tone={tone} numeric numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8}>{value}</Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  center: { alignItems: 'center', justifyContent: 'center', gap: SPACE.md, paddingHorizontal: SPACE.xl },
  backLink: { minHeight: 44, justifyContent: 'center' },
  bar: { flexDirection: 'row', paddingHorizontal: SPACE.xs },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  article: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.lg, gap: SPACE.sm },
  game: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 44, alignSelf: 'flex-start' },
  title: { marginTop: SPACE.xs },
  underline: { textDecorationLine: 'underline' },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  stats: { flexDirection: 'row', padding: SPACE.lg + 2, gap: SPACE.md },
  stat: { flex: 1, minWidth: 0, gap: 2 },
  excerpt: { padding: SPACE.lg + 2, gap: SPACE.md },
  loading: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 60 },
  paragraph: { lineHeight: 26 },
  origin: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44 },
  actions: { flexDirection: 'row', gap: SPACE.sm, paddingHorizontal: SPACE.lg, paddingVertical: SPACE.sm, borderTopWidth: StyleSheet.hairlineWidth },
  watch: { width: 52, minHeight: 52, borderRadius: RADIUS, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  action: { flex: 1, minHeight: 52, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
  outline: { borderWidth: 1 },
});
