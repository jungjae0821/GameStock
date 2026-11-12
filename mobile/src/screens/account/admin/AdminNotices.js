import React, { useCallback, useEffect, useState } from 'react';
import { Switch, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request } from '../../../auth';
import { Button, Card, CardHead, Empty, Field, FieldError, Notice, Row, kit, kstShort } from '../../../components/Kit';
import { Txt, useTheme } from '../../../theme';
import { badTime, confirm, num, styles, toInstant, toKstText } from './shared';

/* 공지 본문 끝의 "코드: …" 줄은 공지 창에서 복사 버튼이 붙는 코드다(웹 notice.ts와 같은 형식). */
const CODE_LINE = /\n\s*\n?코드:\s*(\S+)\s*$/;
const splitCode = body => {
  const match = String(body ?? '').match(CODE_LINE);
  return match ? { body: body.slice(0, match.index).trimEnd(), code: match[1] } : { body: String(body ?? ''), code: '' };
};
const joinCode = (body, code) => (code.trim() ? `${body.trim()}\n\n코드: ${code.trim()}` : body.trim());

function noticeState(published, startsAt, endsAt, now = Date.now()) {
  if (!published) return '숨김';
  if (startsAt && Date.parse(startsAt) > now) return '예약';
  if (endsAt && Date.parse(endsAt) <= now) return '종료';
  return '게시 중';
}

const EMPTY_DRAFT = { title: '', body: '', code: '', image: '', actionLabel: '', actionUrl: '', dismissLabel: '', published: true, startsAt: '', endsAt: '' };
// 서버와 같은 규칙: 사이트 안 경로(/...)나 https 주소.
const LINK = /^(https:\/\/\S+|\/(?!\/)\S*)$/i;

export function Notices() {
  const theme = useTheme();
  const [rows, setRows] = useState(null);
  const [error, setError] = useState('');
  const [editingId, setEditingId] = useState(null);
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const load = useCallback(async () => {
    try {
      setRows(await request('/api/admin/content?kind=NOTICE&limit=100'));
      setError('');
    } catch (failure) {
      setError(failure.message || '공지를 불러오지 못했어요.');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const set = (key, value) => setDraft(current => ({ ...current, [key]: value }));
  const startNew = () => { setEditingId(null); setDraft(EMPTY_DRAFT); setReason(''); setMessage(''); };
  const edit = row => {
    const { body, code } = splitCode(row.body);
    setEditingId(num(row.id));
    setDraft({
      title: String(row.title ?? ''), body, code, image: String(row.image_url ?? ''), actionLabel: String(row.action_label ?? ''),
      actionUrl: String(row.action_url ?? ''), dismissLabel: String(row.dismiss_label ?? ''),
      published: Boolean(row.published), startsAt: toKstText(row.starts_at), endsAt: toKstText(row.ends_at),
    });
    setReason('');
    setMessage('');
  };

  const timeError = badTime(draft.startsAt) || badTime(draft.endsAt) ? '시각은 2026-10-21 14:30 형식(한국시간)으로 적어 주세요.'
    : draft.startsAt && draft.endsAt && toInstant(draft.endsAt) <= toInstant(draft.startsAt) ? '끝나는 시각은 시작 시각보다 뒤여야 해요.' : '';
  const image = draft.image.trim(), actionText = draft.actionLabel.trim(), actionUrl = draft.actionUrl.trim();
  const linkError = (image && !LINK.test(image)) || (actionUrl && !LINK.test(actionUrl))
    ? '그림·이동 주소는 /로 시작하는 경로나 https:// 주소여야 해요.'
    : Boolean(actionText) !== Boolean(actionUrl) ? '이동 버튼은 문구와 주소를 함께 적어 주세요.' : '';
  const valid = draft.title.trim() && draft.body.trim() && reason.trim() && !timeError && !linkError;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setMessage('');
    try {
      const saved = await request(editingId ? `/api/admin/content/${editingId}` : '/api/admin/content', {
        method: editingId ? 'PUT' : 'POST',
        body: {
          kind: 'NOTICE', title: draft.title.trim(), body: joinCode(draft.body, draft.code), stockCode: null,
          imageUrl: image || null, actionLabel: actionText || null, actionUrl: actionUrl || null, dismissLabel: draft.dismissLabel.trim() || null,
          published: draft.published, startsAt: toInstant(draft.startsAt), endsAt: toInstant(draft.endsAt), reason: reason.trim(),
        },
      });
      setMessage(editingId ? '공지를 고쳤어요. 이미 확인한 사용자에게도 다시 보여요.' : '공지를 등록했어요.');
      setEditingId(num(saved.id));
      setReason('');
      await load();
    } catch (failure) {
      setMessage(failure.message || '공지를 저장하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  const remove = () => {
    if (!editingId || !reason.trim()) return;
    confirm(`"${draft.title}" 공지를 삭제할까요?`, '삭제한 내용은 감사 기록에만 남아요.', '삭제', async () => {
      setBusy(true);
      setMessage('');
      try {
        await request(`/api/admin/content/${editingId}`, { method: 'DELETE', body: { reason: reason.trim() } });
        startNew();
        setMessage('공지를 삭제했어요.');
        await load();
      } catch (failure) {
        setMessage(failure.message || '공지를 삭제하지 못했어요.');
      } finally {
        setBusy(false);
      }
    });
  };

  return (
    <>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <View style={[kit.between, styles.headRow]}>
          <Txt variant="headline">공지</Txt>
          <Button label="새 공지" small icon="add" onPress={startNew} />
        </View>
        {rows == null ? (error
          ? <Empty text="공지를 불러오지 못했어요." action="다시 시도" onAction={() => void load()} />
          : <Empty loading text="공지를 불러오는 중이에요" />) : rows.length === 0 ? <Empty text="아직 공지가 없어요." /> : rows.map((row, index) => {
          const state = noticeState(Boolean(row.published), row.starts_at, row.ends_at);
          const selected = editingId === num(row.id);
          return (
            <Row key={String(row.id)} divided={index > 0} onPress={() => edit(row)} accessibilityLabel={`${row.title}, ${state}. 누르면 고쳐요`} style={selected && { backgroundColor: theme.raised }}>
              <View style={kit.grow}>
                <Txt variant="callout" numberOfLines={1}>{String(row.title ?? '')}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{`${state} · ${kstShort(row.updated_at ?? row.created_at)}`}</Txt>
              </View>
              {selected ? <Txt variant="caption" tone="brandText">고치는 중</Txt> : <Ionicons name="create-outline" size={18} color={theme.inkSoft} />}
            </Row>
          );
        })}
      </Card>

      <Card>
        <CardHead title={editingId ? '공지 고치기' : '새 공지'} meta={noticeState(draft.published, toInstant(draft.startsAt), toInstant(draft.endsAt))} />
        <Field label="제목" value={draft.title} onChangeText={value => set('title', value)} maxLength={120} />
        <Field label="본문" value={draft.body} onChangeText={value => set('body', value)} multiline />
        <Field label="복사용 코드 (선택)" value={draft.code} onChangeText={value => set('code', value)} autoCapitalize="none" autoCorrect={false} hint="공지 창에서 복사 버튼이 붙어요." />
        <Field label="머리 그림 주소 (선택)" value={draft.image} onChangeText={value => set('image', value)} maxLength={500} autoCapitalize="none" autoCorrect={false} placeholder="/notice/season-2.png" hint="창 위쪽에 16:7 비율로 잘려 보여요(권장 960×420). 비우면 글만 보여요." />
        <Field label="이동 버튼 문구 (선택)" value={draft.actionLabel} onChangeText={value => set('actionLabel', value)} maxLength={40} placeholder="지금 확인하기" />
        <Field label="이동 주소" value={draft.actionUrl} onChangeText={value => set('actionUrl', value)} maxLength={500} autoCapitalize="none" autoCorrect={false} placeholder="/market 또는 https://" />
        <Field label="닫기 버튼 문구 (선택)" value={draft.dismissLabel} onChangeText={value => set('dismissLabel', value)} maxLength={40} placeholder={actionText ? '닫기' : '확인했어요'} hint="이동 버튼이 없으면 닫기 버튼 하나만 진하게 보여요." />
        {linkError ? <FieldError text={linkError} /> : null}
        <View style={kit.between}>
          <Txt variant="callout">게시</Txt>
          <Switch value={draft.published} onValueChange={value => set('published', value)} trackColor={{ true: theme.ink, false: theme.edge }} thumbColor={theme.card} accessibilityLabel="게시" />
        </View>
        <View style={styles.filters}>
          <Field label="시작 (선택)" style={kit.grow} value={draft.startsAt} onChangeText={value => set('startsAt', value)} placeholder="2026-10-21 14:30" autoCorrect={false} numeric={false} />
          <Field label="끝 (선택)" style={kit.grow} value={draft.endsAt} onChangeText={value => set('endsAt', value)} placeholder="비워 두면 계속" autoCorrect={false} />
        </View>
        {timeError ? <FieldError text={timeError} /> : <Txt variant="caption" tone="inkSoft">시각은 한국시간이에요.</Txt>}
        <Field label="사유 (감사 기록에 남아요)" value={reason} onChangeText={setReason} maxLength={300} />
        {message ? <Notice text={message} /> : null}
        <Button label={editingId ? '고친 내용 저장' : '공지 등록'} kind="primary" busy={busy} disabled={!valid} onPress={() => void save()} />
        {editingId ? <Button label="공지 삭제" kind="danger" disabled={busy || !reason.trim()} onPress={remove} /> : null}
      </Card>
    </>
  );
}
