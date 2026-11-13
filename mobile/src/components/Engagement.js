import React, { useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, Share, StyleSheet, Switch, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '../auth';
import {
  featureLock, fetchFandoms, fetchFeed, fetchPublicProfile, usePolledValue, useEngagement, useEngagementState,
  withSubject, withTopic,
} from '../engagement';
import { signedPercent, signedWon, won } from '../format';
import { RADIUS, RADIUS_XS, SPACE, Txt, toneOf, useTheme } from '../theme';
import { Button, Card, CardHead, Chips, Empty, Notice } from './Kit';
import { GameIcon } from './GameIcon';

// 웹 src/next/engagement/*와 같은 기능을 앱 화면에 맞춰 그린 부품들.

const PREDICTION_REWARD = 10_000;
const percentText = percent => `${percent >= 0 ? '+' : '−'}${Math.abs(Number(percent) || 0).toFixed(2)}%`;
const kstHour = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const hour = new Date(ms + 9 * 60 * 60_000).getUTCHours();
  return `${hour < 12 ? '오전' : '오후'} ${hour % 12 || 12}시`;
};
const kstDay = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일`;
};
/** 3천만원 → "3,000만원", 1억 2천만원 → "1.2억원". 웹 compactWon과 같은 자릿수다. */
export const compactWon = value => {
  const amount = Math.round(Number(value) || 0);
  if (Math.abs(amount) >= 100_000_000) return `${(amount / 100_000_000).toFixed(1).replace(/\.0$/, '')}억원`;
  if (Math.abs(amount) >= 10_000) return `${Math.round(amount / 10_000).toLocaleString('ko-KR')}만원`;
  return won(amount);
};

/** 홈 카드 머리. 오른쪽은 다른 화면으로 가는 짧은 링크. */
function SectionHead({ title, action, onAction, meta }) {
  const theme = useTheme();
  return (
    <View style={styles.sectionHead}>
      <Txt variant="headline" accessibilityRole="header">{title}</Txt>
      {action ? (
        <Pressable accessibilityRole="button" onPress={onAction} hitSlop={12} style={styles.sectionAction}>
          <Txt variant="caption" tone="inkSoft">{action}</Txt>
          <Ionicons name="chevron-forward" size={14} color={theme.inkSoft} />
        </Pressable>
      ) : meta ? <Txt variant="caption" tone="inkSoft">{meta}</Txt> : null}
    </View>
  );
}

/** 막대 하나. 내 팬덤만 잉크, 나머지는 옅은 잉크다(웹 .nx-fandom-bar). */
function FanBar({ share, mine }) {
  const theme = useTheme();
  return (
    <View style={[styles.bar, { backgroundColor: theme.raised }]} importantForAccessibility="no" accessibilityElementsHidden>
      <View style={{ width: `${Math.max(2, share * 100)}%`, height: '100%', borderRadius: 3, backgroundColor: mine ? theme.ink : theme.edge }} />
    </View>
  );
}

/** 홈의 팬덤 대항전 상위 5개. */
export function HomeFandoms({ onOpenRanking, onOpenSettings }) {
  const theme = useTheme();
  const engagement = useEngagement();
  const { data, state } = usePolledValue(fetchFandoms, 60_000);
  if (state === 'missing') return null;
  const top = (data ?? []).slice(0, 5);
  const most = Math.max(1, ...top.map(fandom => fandom.fans));
  const mine = engagement?.fandomCode ?? null;
  return (
    <Card padded={false}>
      <SectionHead title="팬덤 대항전" action="전체 순위" onAction={onOpenRanking} />
      {state === 'loading' ? <Empty loading text="팬덤 순위를 불러오는 중이에요" />
        : !data ? <Txt variant="callout" tone="inkSoft" style={styles.pad}>팬덤 순위를 불러오지 못했어요. 1분 뒤에 다시 불러와요.</Txt>
        : top.every(fandom => fandom.fans === 0) ? <Txt variant="callout" tone="inkSoft" style={styles.pad}>아직 주력 게임을 고른 사람이 없어요. 설정에서 가장 먼저 골라 보세요.</Txt>
        : top.map((fandom, index) => {
          const isMine = fandom.stockCode === mine;
          return (
            <View
              key={fandom.stockCode}
              accessible
              accessibilityLabel={`${fandom.rank}위 ${fandom.name}, 팬 ${fandom.fans.toLocaleString('ko-KR')}명${isMine ? ', 내 팬덤' : ''}`}
              style={[styles.fandomRow, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }]}
            >
              <Txt variant="callout" tone="inkSoft" numeric style={styles.rank}>{fandom.rank}</Txt>
              <GameIcon code={fandom.stockCode} size={32} />
              <View style={styles.grow}>
                <View style={styles.inline}>
                  <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{fandom.name}</Txt>
                  {isMine ? <MineBadge text="내 팬덤" /> : null}
                </View>
                <FanBar share={fandom.fans / most} mine={isMine} />
              </View>
              <Txt variant="caption" numeric>{`${fandom.fans.toLocaleString('ko-KR')}명`}</Txt>
            </View>
          );
        })}
      {engagement && !mine ? (
        <Pressable accessibilityRole="button" onPress={onOpenSettings} style={({ pressed }) => [styles.cta, { borderTopColor: theme.line }, pressed && { backgroundColor: theme.pressed }]}>
          <Txt variant="caption" style={styles.grow}>주력 게임을 고르면 내 게임 순위를 올릴 수 있어요</Txt>
          <Ionicons name="chevron-forward" size={14} color={theme.inkSoft} />
        </Pressable>
      ) : null}
    </Card>
  );
}

const ago = (iso, now) => {
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  return seconds < 60 ? '방금' : `${Math.floor(seconds / 60)}분 전`;
};

/** 홈의 실시간 체결 소식. 3천만원 넘는 체결(고래)과 포트폴리오를 공개한 사람의 체결만 온다. */
export function HomeLiveFeed({ onOpenStock }) {
  const theme = useTheme();
  const { data, state } = usePolledValue(fetchFeed, 15_000);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(timer); }, []);
  if (state === 'missing') return null;
  const items = (data ?? []).slice(0, 6);
  return (
    <Card padded={false}>
      <SectionHead title="실시간 체결 소식" meta="최근 30분" />
      {state === 'loading' ? <Empty loading text="체결 소식을 불러오는 중이에요" />
        : !data ? <Txt variant="callout" tone="inkSoft" style={styles.pad}>체결 소식을 불러오지 못했어요. 15초 뒤에 다시 불러와요.</Txt>
        : items.length === 0 ? <Txt variant="callout" tone="inkSoft" style={styles.pad}>아직 조용해요. 3천만원이 넘는 체결이나, 포트폴리오를 공개한 사람의 체결이 여기에 떠요.</Txt>
        : items.map((item, index) => {
          const buy = item.side === 'BUY';
          const who = item.nickname ?? '익명';
          return (
            <Pressable
              key={`${item.id}-${item.side}`}
              accessibilityRole="button"
              accessibilityLabel={`${item.whale ? '고래, ' : ''}${who}님이 ${item.name} ${item.quantity.toLocaleString('ko-KR')}주를 ${buy ? '샀어요' : '팔았어요'}. ${compactWon(item.amount)}, ${ago(item.at, now)}`}
              onPress={() => onOpenStock?.(item.stockCode)}
              style={({ pressed }) => [styles.feedRow, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, pressed && { backgroundColor: theme.pressed }]}
            >
              <GameIcon code={item.stockCode} size={32} />
              <View style={styles.grow}>
                <Txt variant="callout" numberOfLines={2}>
                  {`${who}님이 ${item.name} `}
                  <Txt variant="callout" numeric>{`${item.quantity.toLocaleString('ko-KR')}주`}</Txt>
                  {'를 '}
                  <Txt variant="callout" tone={buy ? 'up' : 'down'}>{buy ? '샀어요' : '팔았어요'}</Txt>
                </Txt>
                <View style={styles.inline}>
                  <Txt variant="caption" tone="inkSoft">{ago(item.at, now)}</Txt>
                  {item.whale ? <OutlineBadge text="고래" /> : null}
                </View>
              </View>
              <Txt variant="caption" numeric>{compactWon(item.amount)}</Txt>
            </Pressable>
          );
        })}
    </Card>
  );
}

function OutlineBadge({ text }) {
  const theme = useTheme();
  return <View style={[styles.badge, { borderColor: theme.edge }]}><Txt variant="micro">{text}</Txt></View>;
}

function MineBadge({ text = '나' }) {
  const theme = useTheme();
  return <View style={[styles.mine, { backgroundColor: theme.brand }]}><Txt variant="micro" style={{ color: theme.onBrand }}>{text}</Txt></View>;
}

/** 하루 한 종목. 고른 뒤 그날 마지막 체결가가 고를 때보다 높으면 적중이고 내 지갑에 보상이 들어온다. */
export function PredictionCard({ stocks }) {
  const { engagement, submitPrediction } = useEngagementState();
  const [code, setCode] = useState(null);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState(null);
  if (!engagement?.prediction) return null;
  const prediction = engagement.prediction;
  const nameOf = stockCode => stocks.find(stock => stock.code === stockCode)?.name ?? stockCode;
  const picked = prediction.stockCode;
  const chosen = code ?? stocks[0]?.code ?? null;

  const submit = async () => {
    if (!chosen || pending) return;
    setPending(true);
    setMessage(null);
    try {
      await submitPrediction(chosen);
      setMessage({ ok: true, text: '골랐어요. 자정에 결과가 나와요.' });
    } catch (failure) {
      setMessage({ ok: false, text: failure.message || '잠시 후 다시 시도해 주세요.' });
    } finally {
      setPending(false);
    }
  };

  const previous = prediction.previous?.result ? prediction.previous : null;
  return (
    <Card>
      <CardHead title="오늘 오를 종목 고르기" />
      <Txt variant="caption" tone="inkSoft">{`고를 때 가격보다 오늘 마지막 가격이 높으면 내 지갑에 ${won(PREDICTION_REWARD)}이 들어와요.${prediction.closesAt ? ` ${kstHour(prediction.closesAt)}까지 고를 수 있어요.` : ''}`}</Txt>
      {picked ? (
        <View style={styles.inlineGap}>
          <GameIcon code={picked} size={40} />
          <View style={styles.grow}>
            <Txt variant="callout">{nameOf(picked)}</Txt>
            <Txt variant="caption" tone="inkSoft" numeric>{`고른 가격 ${won(prediction.basePrice)} · 자정에 결과가 나와요`}</Txt>
          </View>
        </View>
      ) : prediction.open ? (
        <>
          <Chips options={stocks.map(stock => ({ key: stock.code, label: stock.name }))} value={chosen} onChange={setCode} />
          <Button label={pending ? '고르는 중' : chosen ? '이 종목으로 고르기' : '종목을 불러오는 중이에요'} kind="primary" busy={pending} disabled={!chosen} onPress={() => void submit()} />
        </>
      ) : (
        <Txt variant="callout" tone="inkSoft">오늘 예측은 마감했어요. 내일 0시부터 다시 고를 수 있어요.</Txt>
      )}
      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}
      {previous ? (
        <Txt variant="caption" tone={previous.result === 'HIT' ? 'ink' : 'inkSoft'} numeric>
          {`어제 고른 ${nameOf(previous.stockCode)}: ${won(previous.basePrice)} → ${won(previous.closePrice ?? 0)} · `}
          {previous.result === 'HIT' ? `적중, ${won(previous.reward)} 받았어요` : '이번엔 빗나갔어요'}
        </Txt>
      ) : null}
    </Card>
  );
}

/** 주력 게임 하나. 프로필과 랭킹에 함께 보이고 팬덤 대항전의 소속이 된다. 고르면 일주일 동안 바꿀 수 없다. */
export function FandomSettings({ stocks }) {
  const theme = useTheme();
  const { engagement, setFandom } = useEngagementState();
  const [pending, setPending] = useState(null);
  const [message, setMessage] = useState(null);
  if (!engagement) return null;
  const current = engagement.fandomCode;
  const lockedUntil = engagement.fandomChangeAvailableAt;

  const choose = async stock => {
    if (stock.code === current || pending) return;
    setPending(stock.code);
    setMessage(null);
    try {
      await setFandom(stock.code);
      setMessage({ ok: true, text: `${stock.name} 팬덤에 들어갔어요.` });
    } catch (failure) {
      setMessage({ ok: false, text: failure.message || '잠시 후 다시 시도해 주세요.' });
    } finally {
      setPending(null);
    }
  };

  return (
    <Card>
      <CardHead title="주력 게임" />
      <Txt variant="caption" tone="inkSoft">
        {current ? '팬덤 대항전에서 이 게임 편으로 집계돼요.' : '응원하는 게임을 하나 골라 주세요. 처음 고를 때는 바로 정해져요.'}
        {lockedUntil ? ` 다음 변경은 ${kstDay(lockedUntil)}부터 할 수 있어요.` : ' 고르면 일주일 동안 바꿀 수 없어요.'}
      </Txt>
      <View style={styles.fandomGrid} accessibilityRole="radiogroup" accessibilityLabel="주력 게임">
        {stocks.map(stock => {
          const selected = stock.code === current;
          const blocked = pending !== null || (Boolean(lockedUntil) && !selected);
          return (
            <Pressable
              key={stock.code}
              accessibilityRole="radio"
              accessibilityState={{ checked: selected, disabled: blocked, busy: pending === stock.code }}
              accessibilityLabel={stock.name}
              disabled={blocked}
              onPress={() => void choose(stock)}
              style={({ pressed }) => [
                styles.fandomOption,
                { borderColor: selected ? theme.brandText : 'transparent', backgroundColor: pressed || selected ? theme.raised : 'transparent' },
                blocked && !selected && { opacity: 0.45 },
              ]}
            >
              <GameIcon code={stock.code} size={44} />
              <Txt variant="micro" tone={selected ? 'ink' : 'inkSoft'} numberOfLines={2} style={styles.center}>{stock.name}</Txt>
            </Pressable>
          );
        })}
      </View>
      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}
    </Card>
  );
}

/** 랭킹에서 내 보유 종목을 보여 주고, 체결 소식에 닉네임을 띄울지 정한다. 기본은 비공개. */
export function PublicProfileSettings() {
  const theme = useTheme();
  const { engagement, setPublicProfile } = useEngagementState();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  if (!engagement) return null;
  const open = Boolean(engagement.publicProfile);
  const toggle = async next => {
    setPending(true);
    setError('');
    try { await setPublicProfile(next); } catch (failure) { setError(failure.message || '잠시 후 다시 시도해 주세요.'); } finally { setPending(false); }
  };
  return (
    <Card>
      <View style={styles.inlineGap}>
        <View style={styles.grow}>
          <Txt variant="headline" accessibilityRole="header">포트폴리오 공개</Txt>
          <Txt variant="caption" tone="inkSoft">켜면 랭킹에서 다른 사람이 내 보유 종목을 볼 수 있고, 실시간 체결 소식에 내 닉네임이 보여요.</Txt>
        </View>
        <Switch
          value={open}
          disabled={pending}
          onValueChange={next => void toggle(next)}
          accessibilityLabel={`포트폴리오 공개, 지금 ${open ? '공개' : '비공개'}`}
          trackColor={{ false: theme.edge, true: theme.ink }}
          thumbColor={theme.card}
          ios_backgroundColor={theme.edge}
        />
      </View>
      {error ? <Notice text={error} /> : null}
    </Card>
  );
}

/** 잠긴 데스크 위 안내. 화면은 둘러볼 수 있게 두고, 주문만 서버에서 막는다. */
export function UnlockNotice({ feature, onOpenCareer }) {
  const engagement = useEngagement();
  const locked = featureLock(engagement, feature);
  if (!locked) return null;
  return (
    <Notice icon="lock-closed" text={`${withTopic(locked.name)} 아직 잠겨 있어요`}>
      <Txt variant="caption" tone="inkSoft" style={styles.noticeBody}>{`${withSubject(locked.gradeLabel)} 되면 열려요. 시세는 지금도 볼 수 있어요.`}</Txt>
      {onOpenCareer ? <Button label="승진 조건 보기" small onPress={onOpenCareer} style={styles.noticeButton} /> : null}
    </Notice>
  );
}

/** 공개 포트폴리오 창. 랭킹에서 공개한 사람의 닉네임을 누르면 열린다. */
export function PublicProfileModal({ nickname, onClose, stocks = [] }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!nickname) return undefined;
    let active = true;
    setProfile(null);
    setError('');
    fetchPublicProfile(nickname)
      .then(next => { if (active) setProfile(next); })
      .catch(failure => { if (active) setError(failure.message || '프로필을 불러오지 못했어요.'); });
    return () => { active = false; };
  }, [nickname]);

  return (
    <Modal visible={Boolean(nickname)} transparent animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      <Pressable style={[styles.scrim, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="닫기" accessibilityRole="button" />
      <View style={[styles.sheet, { backgroundColor: theme.card, paddingBottom: insets.bottom + SPACE.lg }]} accessibilityViewIsModal>
        <View style={styles.sheetHead}>
          <View style={styles.grow}>
            {profile?.equippedTitle ? <Txt variant="micro" tone="inkSoft">{profile.equippedTitle}</Txt> : null}
            <Txt variant="title" accessibilityRole="header" numberOfLines={1}>{nickname}</Txt>
            {profile ? (
              <Txt variant="caption" tone="inkSoft" numeric>{`운용 자산 ${won(profile.totalAsset)}${profile.fandomCode ? ` · ${stocks.find(stock => stock.code === profile.fandomCode)?.name ?? profile.fandomCode} 팬` : ''}`}</Txt>
            ) : null}
          </View>
          <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={onClose} hitSlop={8} style={styles.close}>
            <Ionicons name="close" size={24} color={theme.ink} />
          </Pressable>
        </View>
        <ScrollView style={styles.sheetBody}>
          {error ? <Empty text={error} />
            : !profile ? <Empty loading text="포트폴리오를 불러오는 중이에요" />
            : profile.holdings.length === 0 ? <Empty text="지금은 들고 있는 주식이 없어요." />
            : profile.holdings.map((holding, index) => (
              <View
                key={holding.stockCode}
                accessible
                accessibilityLabel={`${holding.name} ${holding.quantity.toLocaleString('ko-KR')}주, 평가금액 ${won(holding.value)}, 수익률 ${percentText(holding.returnPercent)}`}
                style={[styles.holding, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }]}
              >
                <GameIcon code={holding.stockCode} size={32} />
                <View style={styles.grow}>
                  <Txt variant="callout" numberOfLines={1}>{holding.name}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`${holding.quantity.toLocaleString('ko-KR')}주`}</Txt>
                </View>
                <View style={styles.right}>
                  <Txt variant="callout" numeric>{won(holding.value)}</Txt>
                  <Txt variant="caption" tone={toneOf(holding.returnPercent)} numeric>{percentText(holding.returnPercent)}</Txt>
                </View>
              </View>
            ))}
        </ScrollView>
      </View>
    </Modal>
  );
}

/** 수익률 구간마다 붙는 한 줄. 웹 ShareCard의 caption과 같다. */
function caption(ratio) {
  if (ratio >= 1) return '두 배 달성. 이제 전설이에요';
  if (ratio >= 0.3) return '떡상 인증';
  if (ratio > 0) return '꾸준히 오르는 중';
  if (ratio === 0) return '본전도 실력이에요';
  if (ratio > -0.3) return '잠깐 쉬어 가는 중';
  if (ratio > -0.5) return '구조대 대기 중';
  return '한강 수온 체크';
}

/**
 * 수익 인증. 웹은 정사각형 이미지를 만들지만, 앱은 이미지 캡처 모듈 없이 휴대폰 공유 창으로 같은 내용을 글로 보낸다.
 */
export function ShareResultButton({ pnl, pnlRate, best, stocks }) {
  const { profile } = useAuth();
  const engagement = useEngagement();
  const [message, setMessage] = useState('');
  const share = async () => {
    setMessage('');
    const fandom = engagement?.fandomCode ? stocks.find(stock => stock.code === engagement.fandomCode)?.name ?? null : null;
    const lines = [
      `[슈엔증권 수익 인증] ${profile?.nickname ?? '익명 투자자'}${fandom ? ` · ${fandom} 팬` : ''}`,
      `수익률 ${signedPercent(pnlRate)} (${signedWon(pnl)})`,
      caption(pnlRate),
      best ? `가장 많이 번 종목: ${best.name} ${signedPercent(best.pnlRate)}` : null,
      '모의투자 결과예요. 실제 돈은 오가지 않아요.',
    ].filter(Boolean);
    try {
      await Share.share({ message: lines.join('\n') });
    } catch (failure) {
      setMessage(failure.message || '공유 창을 열지 못했어요.');
    }
  };
  return (
    <View style={styles.share}>
      <Button label="수익 인증하기" small icon="share-outline" onPress={() => void share()} style={styles.shareButton} />
      {message ? <Txt variant="caption" tone="inkSoft">{message}</Txt> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  grow: { flex: 1, minWidth: 0 },
  shrink: { flexShrink: 1 },
  center: { textAlign: 'center' },
  right: { alignItems: 'flex-end' },
  pad: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.lg },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  inlineGap: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg + 2, paddingBottom: SPACE.sm },
  sectionAction: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 32 },
  rank: { width: 20, textAlign: 'center' },
  fandomRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  bar: { height: 6, borderRadius: 3, marginTop: 6, overflow: 'hidden' },
  cta: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 48, paddingHorizontal: SPACE.lg + 2, borderTopWidth: StyleSheet.hairlineWidth },
  feedRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  badge: { borderRadius: RADIUS_XS, borderWidth: 1, paddingHorizontal: 6, paddingVertical: 1 },
  mine: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  fandomGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACE.sm },
  fandomOption: { width: 76, minHeight: 88, alignItems: 'center', gap: 4, paddingVertical: SPACE.sm, paddingHorizontal: 2, borderRadius: RADIUS, borderWidth: 2 },
  noticeBody: { marginTop: 2 },
  noticeButton: { alignSelf: 'flex-start', marginTop: SPACE.sm },
  scrim: { ...StyleSheet.absoluteFillObject },
  sheet: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '80%', borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS, paddingTop: SPACE.lg },
  sheetHead: { flexDirection: 'row', alignItems: 'flex-start', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.md },
  sheetBody: { flexGrow: 0 },
  close: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  holding: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  share: { marginTop: SPACE.sm, gap: 4, alignItems: 'flex-start' },
  shareButton: { alignSelf: 'flex-start' },
});
