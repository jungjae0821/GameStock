import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '../auth';
import { GameIcon } from '../components/GameIcon';
import { Button, Card, Empty, FieldError, Segment } from '../components/Kit';
import { StockChatCard } from '../components/StockChatCard';
import { price, signedPercent } from '../format';
import { sessionRate } from '../market';
import { deleteTalk, failure, likeTalk, loadTalk, serverTime, writeTalk } from '../research';
import { FONT_FAMILY, RADIUS, RADIUS_XS, SPACE, TYPE, Txt, toneOf, useTheme } from '../theme';
import { StockPicker } from './StockScreen';

const MAX_LENGTH = 300;
const VIEWS = [{ key: 'board', label: '글' }, { key: 'chat', label: '실시간 채팅' }];

/** 서버 시각 기준 "3분 전". 서버 시각에 시간대가 빠져 있어도 UTC로 읽는다. */
function since(iso) {
  const at = serverTime(iso);
  if (!Number.isFinite(at)) return '';
  const minutes = Math.max(0, Math.floor((Date.now() - at) / 60_000));
  if (minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}시간 전` : `${Math.floor(hours / 24)}일 전`;
}

/** 종목토론 탭. 위에서 종목을 고르고, 글과 실시간 채팅을 오가며 본다. 웹 종목토론 화면과 같은 일을 한다. */
export function TalkScreen({ code, market, onSwitch, onOpenStock, onLogin }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [view, setView] = useState('board');
  const [picking, setPicking] = useState(false);
  const stocks = market.stocks;
  const current = stocks.find(item => item.code === code) ?? stocks[0] ?? null;

  return (
    <View style={styles.fill}>
      <ScrollView
        style={{ backgroundColor: theme.ground }}
        automaticallyAdjustKeyboardInsets
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl, paddingHorizontal: SPACE.lg }}
      >
        <Txt variant="page" accessibilityRole="header" style={styles.title}>종목토론</Txt>
        <Txt variant="caption" tone="inkSoft" style={styles.sub}>글은 첫 글이 올라온 지 7일 뒤 답글과 함께 사라져요.</Txt>
        {!current ? (
          <Card><Empty loading={!market.loaded} text={market.loaded ? '상장된 종목이 아직 없어요. 종목이 등록되면 여기서 토론할 수 있어요.' : '종목을 불러오는 중이에요'} /></Card>
        ) : (
          <>
            <StockRow stock={current} onPick={() => setPicking(true)} onOpenStock={() => onOpenStock(current.code)} />
            <View style={styles.segment}>
              <Segment options={VIEWS} value={view} onChange={setView} label="보기" />
            </View>
            {view === 'board'
              ? <TalkBoard key={current.code} code={current.code} name={current.name} onLogin={onLogin} />
              : <StockChatCard key={`chat-${current.code}`} code={current.code} name={current.name} onLogin={onLogin} showHead={false} />}
          </>
        )}
      </ScrollView>
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
      {current ? (
        <StockPicker
          visible={picking}
          stocks={stocks}
          current={current.code}
          onPick={next => { setPicking(false); onSwitch(next); }}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </View>
  );
}

/** 지금 보는 종목. 누르면 종목을 바꾸고, 오른쪽 버튼은 그 종목 거래 화면을 연다. */
function StockRow({ stock, onPick, onOpenStock }) {
  const theme = useTheme();
  const rate = sessionRate(stock);
  return (
    <Card padded={false} style={styles.stockCard}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${stock.name}, ${price(stock.price)}원, 오늘 ${signedPercent(rate)}. 다른 종목 고르기`}
        onPress={onPick}
        style={({ pressed }) => [styles.stockRow, pressed && { backgroundColor: theme.pressed }]}
      >
        <GameIcon code={stock.code} size={36} />
        <View style={styles.grow}>
          <View style={styles.nameLine}>
            <Txt variant="headline" numberOfLines={1} style={styles.shrink}>{stock.name}</Txt>
            <Ionicons name="chevron-down" size={18} color={theme.inkSoft} />
          </View>
          <Txt variant="caption" numeric>
            {`${price(stock.price)}원  `}
            <Txt variant="caption" tone={toneOf(rate)} numeric>{signedPercent(rate)}</Txt>
          </Txt>
        </View>
      </Pressable>
      <Button label="거래" small onPress={onOpenStock} accessibilityLabel={`${stock.name} 거래 화면 열기`} style={styles.tradeButton} />
    </Card>
  );
}

/** 글 목록. 최신 글부터 보이고, 답글은 한 단계까지만 단다. */
function TalkBoard({ code, name, onLogin }) {
  const { user, ready } = useAuth();
  const [page, setPage] = useState(null);
  const [error, setError] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const uid = user?.uid ?? null;

  const reload = useCallback(() => {
    let active = true;
    setError('');
    loadTalk(code)
      .then(next => { if (active) setPage(next); })
      .catch(reason => { if (active) setError(failure(reason, '토론 글을 불러오지 못했어요.')); });
    return () => { active = false; };
  }, [code]);

  // 로그인 상태가 바뀌면 내 글·좋아요 표시가 달라지므로 다시 받는다.
  useEffect(() => {
    if (!ready) return undefined;
    setPage(null);
    return reload();
  }, [reload, ready, uid]);

  const more = async () => {
    const last = page?.posts[page.posts.length - 1];
    if (!last) return;
    setLoadingMore(true);
    try {
      const next = await loadTalk(code, last.id);
      setPage(current => current && { ...next, posts: [...current.posts, ...next.posts.filter(post => !current.posts.some(item => item.id === post.id))] });
    } catch (reason) {
      setError(failure(reason, '글을 더 불러오지 못했어요.'));
    } finally {
      setLoadingMore(false);
    }
  };

  const update = (id, change) => setPage(current => current && {
    ...current,
    posts: current.posts.flatMap(post => {
      if (post.id === id) { const next = change(post); return next ? [next] : []; }
      const replies = post.replies.flatMap(reply => { if (reply.id !== id) return [reply]; const next = change(reply); return next ? [next] : []; });
      return [{ ...post, replies }];
    }),
  });

  const posted = (post, parentId) => setPage(current => current && {
    ...current,
    posts: parentId == null ? [post, ...current.posts] : current.posts.map(item => (item.id === parentId ? { ...item, replies: [...item.replies, post] } : item)),
  });

  return (
    <Card padded={false}>
      <View style={styles.boardTop}>
        {user ? <Composer code={code} parentId={null} placeholder={`${name}에 대한 생각을 남겨 주세요`} onPosted={posted} /> : (
          <View style={styles.signIn}>
            <Txt variant="callout" tone="inkSoft" style={styles.grow}>로그인하고 글을 남겨 보세요.</Txt>
            <Button label="로그인" small onPress={onLogin} />
          </View>
        )}
      </View>
      {error ? (
        <View style={styles.inset}>
          <FieldError text={error} />
          <Button label="다시 불러오기" small onPress={() => reload()} style={styles.retry} />
        </View>
      ) : null}
      {!page ? (error ? null : <Empty loading text="토론 글을 불러오는 중이에요" />)
        : page.posts.length === 0 ? <Empty text={user ? '아직 글이 없어요. 첫 글을 남겨 보세요.' : '아직 글이 없어요.'} />
        : page.posts.map((post, index) => (
          <Post key={post.id} code={code} post={post} signedIn={Boolean(user)} divided={index > 0} onChange={update} onPosted={posted} onLogin={onLogin} />
        ))}
      {page?.hasMore ? (
        <Pressable accessibilityRole="button" disabled={loadingMore} onPress={() => void more()} style={styles.more}>
          {loadingMore ? <ActivityIndicator /> : <Txt variant="callout" tone="inkSoft" style={styles.underline}>이전 글 더 보기</Txt>}
        </Pressable>
      ) : null}
    </Card>
  );
}

function Composer({ code, parentId, placeholder, onPosted, onCancel }) {
  const theme = useTheme();
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const count = [...body.trim()].length;
  const over = count > MAX_LENGTH;

  const submit = async () => {
    if (busy || count === 0 || over) return;
    setBusy(true);
    setMessage('');
    try {
      onPosted(await writeTalk(code, body.trim(), parentId), parentId);
      setBody('');
      onCancel?.();
    } catch (reason) {
      setMessage(failure(reason, '글을 올리지 못했어요. 잠시 후 다시 올려 주세요.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.compose}>
      <TextInput
        value={body}
        onChangeText={setBody}
        placeholder={placeholder}
        placeholderTextColor={theme.inkSoft}
        keyboardAppearance={theme.scheme}
        selectionColor={theme.brand}
        multiline
        autoFocus={parentId != null}
        accessibilityLabel={parentId != null ? '답글' : '새 글'}
        style={[styles.input, parentId != null && styles.inputReply, { color: theme.ink, backgroundColor: theme.raised, borderColor: over ? theme.ink : theme.edge }, over && styles.inputOver]}
      />
      {over ? <FieldError text={`${MAX_LENGTH}자까지 올릴 수 있어요. ${count - MAX_LENGTH}자를 줄여 주세요.`} /> : null}
      <View style={styles.composeFoot}>
        <Txt variant="micro" tone="inkSoft" numeric style={styles.grow}>{`${count}/${MAX_LENGTH}자`}</Txt>
        {onCancel ? <Button label="그만두기" small onPress={onCancel} disabled={busy} /> : null}
        <Button label={parentId != null ? '답글 올리기' : '글 올리기'} kind="primary" small busy={busy} disabled={count === 0 || over} onPress={() => void submit()} />
      </View>
      {message ? <FieldError text={message} /> : null}
    </View>
  );
}

function Post({ code, post, signedIn, divided, reply = false, onChange, onPosted, onLogin }) {
  const theme = useTheme();
  const [replying, setReplying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const like = async () => {
    if (!signedIn) { onLogin(); return; }
    setBusy(true);
    setMessage('');
    try {
      const result = await likeTalk(post.id);
      onChange(post.id, current => ({ ...current, likes: result.likes, liked: result.liked }));
    } catch (reason) {
      setMessage(failure(reason, '좋아요를 반영하지 못했어요.'));
    } finally {
      setBusy(false);
    }
  };

  const remove = () => Alert.alert(
    '이 글을 지울까요?',
    !reply && post.replies.length > 0 ? '달린 답글도 함께 지워져요. 지운 글은 되돌릴 수 없어요.' : '지운 글은 되돌릴 수 없어요.',
    [
      { text: '취소', style: 'cancel' },
      {
        text: '지우기', style: 'destructive', onPress: async () => {
          setBusy(true);
          setMessage('');
          try {
            await deleteTalk(post.id);
            onChange(post.id, () => null);
          } catch (reason) {
            setMessage(failure(reason, '글을 지우지 못했어요.'));
            setBusy(false);
          }
        },
      },
    ],
  );

  return (
    <View style={[!reply && styles.post, reply && styles.reply, divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }]}>
      <View style={styles.meta}>
        <Txt variant="caption" numberOfLines={1} style={styles.shrink}>{post.nickname}</Txt>
        {post.mine ? <View style={[styles.mine, { backgroundColor: theme.raised }]}><Txt variant="micro" tone="inkSoft">내 글</Txt></View> : null}
        <Txt variant="micro" tone="inkSoft">{since(post.createdAt)}</Txt>
      </View>
      <Txt variant="body" style={styles.body}>{post.body}</Txt>
      <View style={styles.actions}>
        <Action
          icon={post.liked ? 'heart' : 'heart-outline'}
          label={`좋아요 ${post.likes}`}
          active={post.liked}
          disabled={busy}
          accessibilityState={{ selected: post.liked, disabled: busy }}
          onPress={() => void like()}
        />
        {!reply ? (
          <Action
            icon="chatbubble-outline"
            label={post.replies.length ? `답글 ${post.replies.length}` : '답글'}
            accessibilityState={{ expanded: replying }}
            onPress={() => (signedIn ? setReplying(open => !open) : onLogin())}
          />
        ) : null}
        {post.mine ? <Action icon="trash-outline" label="지우기" disabled={busy} onPress={remove} /> : null}
      </View>
      {message ? <FieldError text={message} /> : null}
      {!reply && (post.replies.length > 0 || replying) ? (
        <View style={styles.replies}>
          {post.replies.map(item => (
            <Post key={item.id} code={code} post={item} signedIn={signedIn} reply onChange={onChange} onPosted={onPosted} onLogin={onLogin} />
          ))}
          {replying ? <Composer code={code} parentId={post.id} placeholder={`${post.nickname}님에게 답글`} onPosted={onPosted} onCancel={() => setReplying(false)} /> : null}
        </View>
      ) : null}
    </View>
  );
}

function Action({ icon, label, active, disabled, accessibilityState, onPress }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={accessibilityState ?? { disabled: Boolean(disabled) }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.action, pressed && { backgroundColor: theme.pressed }]}
    >
      <Ionicons name={icon} size={16} color={active ? theme.ink : theme.inkSoft} />
      <Txt variant="caption" tone={active ? 'ink' : 'inkSoft'} numeric>{label}</Txt>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  grow: { flex: 1, minWidth: 0 },
  shrink: { flexShrink: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  title: { minHeight: 48, textAlignVertical: 'center', paddingTop: SPACE.sm },
  sub: { marginBottom: SPACE.lg },
  stockCard: { flexDirection: 'row', alignItems: 'center', paddingRight: SPACE.lg + 2 },
  stockRow: { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 68, paddingLeft: SPACE.lg + 2, paddingRight: SPACE.sm, paddingVertical: SPACE.md },
  nameLine: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  tradeButton: { minWidth: 64 },
  segment: { marginBottom: SPACE.md },
  boardTop: { padding: SPACE.lg + 2 },
  signIn: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  inset: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.md, gap: SPACE.sm, alignItems: 'flex-start' },
  retry: { alignSelf: 'flex-start' },
  compose: { gap: SPACE.sm },
  input: { minHeight: 88, maxHeight: 200, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: SPACE.md, paddingTop: SPACE.md, paddingBottom: SPACE.md, fontFamily: FONT_FAMILY, fontSize: TYPE.callout.fontSize, textAlignVertical: 'top' },
  inputReply: { minHeight: 64 },
  inputOver: { borderWidth: 2 },
  composeFoot: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  post: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg, paddingBottom: SPACE.sm },
  reply: { paddingTop: SPACE.md },
  meta: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  mine: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  body: { marginTop: SPACE.xs },
  actions: { flexDirection: 'row', flexWrap: 'wrap', marginLeft: -SPACE.sm },
  action: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44, paddingHorizontal: SPACE.sm, borderRadius: RADIUS },
  replies: { paddingLeft: SPACE.lg, gap: SPACE.xs, paddingBottom: SPACE.sm },
  more: { minHeight: 52, alignItems: 'center', justifyContent: 'center' },
  underline: { textDecorationLine: 'underline' },
});
