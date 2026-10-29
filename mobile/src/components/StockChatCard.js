import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useAuth } from '../auth';
import { CHAT_MAX_LENGTH, deleteChat, failure, loadChat, sendChat, serverTime } from '../research';
import { FONT_FAMILY, RADIUS, SPACE, TYPE, Txt, useTheme } from '../theme';
import { Button, Card, CardHead, Empty, FieldError } from './Kit';

// 새 메시지 조회 간격. 서버 이벤트가 없어 after 커서로 따라잡는다(웹 StockChat과 같다).
const POLL_MS = 2_000;
// 지워진 메시지는 after 커서로 오지 않아 최신 목록을 가끔 다시 받아 맞춘다.
const REFRESH_MS = 30_000;
const CATCH_UP_PAGES = 5;
const LIST_HEIGHT = 280;

// 보낸 메시지의 authorId를 기억해 내 메시지를 알아본다. 프로필 응답에는 사용자 ID가 없다.
const authorKey = uid => `gamestock.chat.author.${uid}`;

/** ID 오름차순으로 합친다. 같은 ID는 나중 값(삭제 표시 등)으로 바꾼다. */
function merge(current, incoming) {
  if (!incoming.length) return current;
  const byId = new Map(current.map(entry => [entry.id, entry]));
  for (const entry of incoming) byId.set(entry.id, entry);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

const clock = ms => {
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms);
  return `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
};

/** 종목별 실시간 채팅. 짧은 대화용이라 답글·좋아요 없이 시간순으로만 쌓는다. */
/** showHead가 false면 머리 줄을 뺀다. 위에 이미 종목과 '실시간 채팅'이 보이는 토론 탭에서 쓴다. */
export function StockChatCard({ code, name, onLogin, showHead = true }) {
  const theme = useTheme();
  const { user, profile } = useAuth();
  const uid = user?.uid ?? null;
  const [entries, setEntries] = useState(null);
  const [olderMore, setOlderMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState('');
  // 첫 목록을 못 받았는지. 이때 빈 목록은 '대화 없음'이 아니라 실패라서 안내를 다르게 쓴다.
  const [failed, setFailed] = useState(false);
  const [author, setAuthor] = useState(null);
  const after = useRef(0);
  const list = useRef(null);
  const stick = useRef(true);

  useEffect(() => {
    setAuthor(null);
    if (!uid) return;
    AsyncStorage.getItem(authorKey(uid)).then(value => { const id = Number(value); if (id > 0) setAuthor(id); }).catch(() => {});
  }, [uid]);

  const advance = items => { for (const entry of items) if (entry.id > after.current) after.current = entry.id; };

  const refresh = useCallback(async () => {
    const page = await loadChat(code);
    advance(page.items);
    setEntries(current => merge(current ?? [], page.items));
    return page;
  }, [code]);

  useEffect(() => {
    let stopped = false;
    let polling = false;
    let lastRefresh = Date.now();
    setEntries(null);
    setError('');
    setFailed(false);
    after.current = 0;
    stick.current = true;

    const poll = async () => {
      if (polling || stopped || AppState.currentState !== 'active') return;
      polling = true;
      try {
        if (Date.now() - lastRefresh >= REFRESH_MS) { lastRefresh = Date.now(); await refresh(); }
        for (let count = 0; count < CATCH_UP_PAGES && !stopped; count += 1) {
          const page = await loadChat(code, { after: after.current });
          advance(page.items);
          if (page.items.length) setEntries(current => merge(current ?? [], page.items));
          if (!page.hasMore || page.nextCursor == null) break;
        }
        if (!stopped) { setError(''); setFailed(false); }
      } catch (reason) {
        if (!stopped) setError(failure(reason, '새 메시지를 받지 못했어요. 2초마다 다시 연결해요.'));
      } finally {
        polling = false;
      }
    };

    refresh()
      .then(page => { if (!stopped) { setOlderMore(page.hasMore); setFailed(false); } })
      .catch(reason => { if (!stopped) { setEntries([]); setFailed(true); setError(failure(reason, '채팅을 불러오지 못했어요. 2초마다 다시 연결해요.')); } });
    const timer = setInterval(() => void poll(), POLL_MS);
    // 앱으로 돌아오면 놓친 메시지를 바로 받는다.
    const subscription = AppState.addEventListener('change', next => { if (next === 'active') void poll(); });
    return () => { stopped = true; clearInterval(timer); subscription.remove(); };
  }, [code, refresh]);

  const older = async () => {
    const oldest = entries?.[0];
    if (!oldest || loadingOlder) return;
    setLoadingOlder(true);
    stick.current = false;
    try {
      const page = await loadChat(code, { before: oldest.id });
      setEntries(current => merge(current ?? [], page.items));
      setOlderMore(page.hasMore);
    } catch (reason) {
      setError(failure(reason, '이전 메시지를 불러오지 못했어요.'));
    } finally {
      setLoadingOlder(false);
    }
  };

  const sent = entry => {
    if (uid) { AsyncStorage.setItem(authorKey(uid), String(entry.authorId)).catch(() => {}); setAuthor(entry.authorId); }
    stick.current = true;
    setEntries(current => merge(current ?? [], [entry]));
  };

  const remove = entry => Alert.alert('이 메시지를 지울까요?', '지운 메시지는 되돌릴 수 없어요.', [
    { text: '취소', style: 'cancel' },
    {
      text: '지우기', style: 'destructive', onPress: async () => {
        try {
          await deleteChat(code, entry.id);
          setEntries(current => current && current.map(item => (item.id === entry.id ? { ...item, deleted: true, body: '' } : item)));
        } catch (reason) {
          setError(failure(reason, '메시지를 지우지 못했어요.'));
          void refresh().catch(() => {});
        }
      },
    },
  ]);

  const mine = entry => (author != null ? entry.authorId === author : Boolean(profile) && entry.nickname === profile.nickname);
  const canDelete = entry => Boolean(user) && !entry.deleted && (mine(entry) || Boolean(profile?.admin));

  return (
    <Card padded={false}>
      {showHead ? <CardHead title="실시간 채팅" meta={`${name} 채널`} padded /> : <View style={styles.headSpace} />}
      {error ? <Txt variant="caption" style={styles.inset} accessibilityLiveRegion="polite">{error}</Txt> : null}
      <View style={[styles.window, { backgroundColor: theme.raised }]}>
        {entries == null ? (
          <View style={styles.center}><Empty loading text="채팅을 불러오는 중이에요" /></View>
        ) : (
          <ScrollView
            ref={list}
            nestedScrollEnabled
            accessibilityLabel={`${name} 채팅 메시지`}
            contentContainerStyle={styles.listContent}
            onScroll={event => {
              const { contentOffset, contentSize, layoutMeasurement } = event.nativeEvent;
              stick.current = contentSize.height - contentOffset.y - layoutMeasurement.height < 24;
            }}
            scrollEventThrottle={100}
            onContentSizeChange={() => { if (stick.current) list.current?.scrollToEnd({ animated: false }); }}
          >
            {olderMore ? (
              <Pressable accessibilityRole="button" disabled={loadingOlder} onPress={() => void older()} style={styles.older}>
                <Txt variant="caption" tone="inkSoft" style={styles.underline}>{loadingOlder ? '불러오는 중' : '이전 메시지 보기'}</Txt>
              </Pressable>
            ) : null}
            {entries.length === 0 && failed ? null : entries.length === 0 ? (
              <Txt variant="callout" tone="inkSoft" style={styles.empty}>{`아직 대화가 없어요.${user ? ' 먼저 말을 걸어 보세요.' : ''}`}</Txt>
            ) : entries.map(entry => (
              <Message key={entry.id} entry={entry} own={mine(entry)} onDelete={canDelete(entry) ? () => remove(entry) : null} />
            ))}
          </ScrollView>
        )}
      </View>
      {user ? <Composer code={code} onSent={sent} /> : (
        <View style={styles.signIn}>
          <Txt variant="callout" tone="inkSoft" style={styles.grow}>로그인하면 채팅에 참여할 수 있어요.</Txt>
          <Button label="로그인" small onPress={onLogin} />
        </View>
      )}
    </Card>
  );
}

function Message({ entry, own, onDelete }) {
  const theme = useTheme();
  const at = serverTime(entry.createdAt);
  return (
    // 내 메시지는 핑크 선과 '나' 이름으로 표시한다. 핑크는 '내 것·지금 여기'에만 쓰는 색이다.
    <View style={[styles.message, own && { borderLeftColor: theme.brand }]}>
      <View style={styles.meta}>
        <Txt variant="caption" numberOfLines={1} style={styles.shrink}>{own ? '나' : entry.nickname}</Txt>
        <Txt variant="micro" tone="inkSoft" numeric>{clock(at)}</Txt>
        {onDelete ? (
          <Pressable accessibilityRole="button" accessibilityLabel={`${clock(at)} 메시지 지우기`} onPress={onDelete} hitSlop={{ top: 14, bottom: 14, left: 12, right: 12 }} style={styles.delete}>
            <Txt variant="micro" tone="inkSoft" style={styles.underline}>지우기</Txt>
          </Pressable>
        ) : null}
      </View>
      <Txt variant="callout" tone={entry.deleted ? 'inkSoft' : 'ink'}>{entry.deleted ? '지워진 메시지예요.' : entry.body}</Txt>
    </View>
  );
}

function Composer({ code, onSent }) {
  const theme = useTheme();
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const count = body.trim().length;
  const over = count > CHAT_MAX_LENGTH;

  // 전송은 자동으로 다시 보내지 않는다. 응답이 없어도 글이 이미 저장됐을 수 있다.
  const submit = async () => {
    if (busy || count === 0 || over) return;
    setBusy(true);
    setMessage('');
    try {
      onSent(await sendChat(code, body.trim()));
      setBody('');
    } catch (reason) {
      setMessage(failure(reason, '메시지를 보내지 못했어요. 채팅창에 올라왔는지 확인한 뒤 다시 보내 주세요.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.compose}>
      <View style={styles.composeRow}>
        <TextInput
          value={body}
          onChangeText={setBody}
          placeholder="메시지 입력"
          placeholderTextColor={theme.inkSoft}
          keyboardAppearance={theme.scheme}
          selectionColor={theme.brand}
          multiline
          accessibilityLabel="채팅 메시지"
          style={[styles.input, { color: theme.ink, backgroundColor: theme.raised, borderColor: over ? theme.ink : theme.edge }, over && styles.inputOver]}
        />
        <Button label="보내기" kind="primary" small busy={busy} disabled={count === 0 || over} onPress={() => void submit()} />
      </View>
      {over ? <FieldError text={`${CHAT_MAX_LENGTH.toLocaleString('ko-KR')}자까지 보낼 수 있어요. ${(count - CHAT_MAX_LENGTH).toLocaleString('ko-KR')}자를 줄여 주세요.`} /> : null}
      {count > 0 ? (
        <Txt variant="micro" tone={over ? 'ink' : 'inkSoft'} numeric style={styles.count}>{`${count.toLocaleString('ko-KR')}/${CHAT_MAX_LENGTH.toLocaleString('ko-KR')}자`}</Txt>
      ) : null}
      {message ? <Txt variant="caption" accessibilityLiveRegion="polite">{message}</Txt> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  inset: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.sm },
  headSpace: { height: SPACE.lg + 2 },
  window: { height: LIST_HEIGHT, marginHorizontal: SPACE.lg + 2, borderRadius: RADIUS, overflow: 'hidden' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  listContent: { padding: SPACE.md, gap: SPACE.md, flexGrow: 1, justifyContent: 'flex-end' },
  older: { minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  empty: { textAlign: 'center' },
  message: { gap: 2, paddingLeft: SPACE.sm, borderLeftWidth: 2, borderLeftColor: 'transparent' },
  meta: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  shrink: { flexShrink: 1 },
  grow: { flex: 1 },
  delete: { marginLeft: 'auto' },
  underline: { textDecorationLine: 'underline' },
  signIn: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, padding: SPACE.lg + 2 },
  compose: { padding: SPACE.lg + 2, gap: 6 },
  composeRow: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACE.sm },
  input: { flex: 1, minHeight: 44, maxHeight: 120, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: SPACE.md, paddingTop: 11, paddingBottom: 11, fontFamily: FONT_FAMILY, fontSize: TYPE.callout.fontSize, textAlignVertical: 'top' },
  inputOver: { borderWidth: 2 },
  count: { textAlign: 'right' },
});
