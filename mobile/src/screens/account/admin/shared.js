import { useCallback, useRef, useState } from 'react';
import { Alert, StyleSheet } from 'react-native';
import { request } from '../../../auth';
import { SPACE } from '../../../theme';

/* 관리자 화면의 여러 구획이 함께 쓰는 값·도구와 스타일. */
export const PAGE_SIZE = 50;
export const num = value => (typeof value === 'number' ? value : Number(value ?? 0));
export const confirm = (title, body, actionText, onConfirm, destructive = true) => Alert.alert(title, body, [
  { text: '취소', style: 'cancel' },
  { text: actionText, style: destructive ? 'destructive' : 'default', onPress: onConfirm },
]);

/** id 내림차순 목록을 before 커서로 이어 받는다(웹 useCursorList와 같은 규칙). */
export function useCursorList(path) {
  const [state, setState] = useState({ rows: [], loading: false, done: false, error: '' });
  const latest = useRef(0);
  const load = useCallback(async (params, append) => {
    const ticket = ++latest.current;
    setState(current => ({ ...current, loading: true, error: '', rows: append ? current.rows : [] }));
    const query = new URLSearchParams(params);
    query.set('limit', String(PAGE_SIZE));
    if (append?.before) query.set('before', String(append.before));
    try {
      const page = await request(`${path}?${query.toString()}`);
      if (ticket !== latest.current) return;
      setState(current => ({ rows: append ? [...current.rows, ...page] : page, loading: false, done: page.length < PAGE_SIZE, error: '' }));
    } catch (failure) {
      if (ticket !== latest.current) return;
      setState(current => ({ ...current, loading: false, error: failure.message || '목록을 불러오지 못했어요.' }));
    }
  }, [path]);
  const lastId = state.rows.length ? num(state.rows[state.rows.length - 1].id) : 0;
  return { ...state, load, lastId };
}

/** 서버 시각 ↔ "2026-10-21 14:30"(한국시간). 비어 있으면 null. */
export const toKstText = value => {
  if (!value) return '';
  const ms = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  const pad = number => String(number).padStart(2, '0');
  return `${at.getUTCFullYear()}-${pad(at.getUTCMonth() + 1)}-${pad(at.getUTCDate())} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`;
};
export const KST_TEXT = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/;
export const toInstant = text => (text.trim() && KST_TEXT.test(text.trim()) ? new Date(`${text.trim().replace(' ', 'T')}:00+09:00`).toISOString() : null);
export const badTime = text => Boolean(text.trim()) && (!KST_TEXT.test(text.trim()) || Number.isNaN(Date.parse(`${text.trim().replace(' ', 'T')}:00+09:00`)));

export const styles = StyleSheet.create({
  lead: { paddingHorizontal: SPACE.lg + 2, marginTop: -SPACE.sm, marginBottom: SPACE.md },
  sections: { marginBottom: SPACE.md },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  shrink: { flexShrink: 1 },
  badge: { borderRadius: 4, borderWidth: 1, paddingHorizontal: 5, paddingVertical: 0 },
  actions: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.lg, gap: SPACE.sm },
  alignStart: { alignSelf: 'flex-start' },
  sectionGap: { marginTop: SPACE.md },
  filters: { flexDirection: 'row', gap: SPACE.sm },
  more: { padding: SPACE.lg },
  headRow: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg, paddingBottom: SPACE.sm },
  audit: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md, gap: 2 },
  diff: { marginHorizontal: SPACE.lg + 2, marginBottom: SPACE.md, padding: SPACE.md, borderRadius: 8, gap: 2 },
  titleGroup: { gap: SPACE.xs },
  titleOptions: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACE.xs },
  titleOption: { minHeight: 44, borderWidth: 1, borderRadius: 6, paddingHorizontal: SPACE.md, justifyContent: 'center' },
});
