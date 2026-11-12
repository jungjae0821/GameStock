import React, { useCallback, useEffect, useState } from 'react';
import { Pressable, Switch, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request } from '../../../auth';
import { Button, Card, CardHead, Empty, Field, FieldError, Notice, Row, kit, kstShort } from '../../../components/Kit';
import { won } from '../../../format';
import { Txt, useTheme } from '../../../theme';
import { badTime, num, styles, toInstant, toKstText } from './shared';

const PROMO_EMPTY = { code: '', rewardCash: '', titleIds: [], maxUses: '', startsAt: '', endsAt: '', active: true, memo: '' };
// 특별 칭호가 프로모션 코드의 본래 용도라 맨 앞에 둔다(웹 AdminPromos와 같은 순서).
const PROMO_CATEGORIES = ['특별', '자산', '수익률', '보유', '거래', '활동', '수집'];
const MAX_REWARD_CASH = 100_000_000;
const onlyDigits = value => value.replace(/[^0-9]/g, '');
const splitTitles = value => String(value ?? '').split(',').map(id => id.trim()).filter(Boolean);

function promoState(row, now = Date.now()) {
  if (!row.active) return '꺼짐';
  if (row.starts_at && Date.parse(row.starts_at) > now) return '예약';
  if (row.ends_at && Date.parse(row.ends_at) <= now) return '기간 끝';
  if (row.max_uses != null && num(row.used_count) >= num(row.max_uses)) return '소진';
  return '사용 가능';
}

const promoDraft = row => ({
  code: String(row.code ?? ''),
  rewardCash: num(row.reward_cash) ? String(num(row.reward_cash)) : '',
  titleIds: splitTitles(row.title_ids),
  maxUses: row.max_uses == null ? '' : String(row.max_uses),
  startsAt: toKstText(row.starts_at),
  endsAt: toKstText(row.ends_at),
  active: Boolean(row.active),
  memo: String(row.memo ?? ''),
});

const rewardSummary = (cash, titleIds, names) => {
  const parts = [];
  if (cash > 0) parts.push(`현금 ${won(cash)}`);
  if (titleIds.length) parts.push(`칭호 ${titleIds.map(id => names.get(id) ?? id).join(', ')}`);
  return parts.length ? parts.join(' + ') : '보상 없음';
};

/** 프로모션 코드 관리. 한 계정은 코드 하나를 한 번만 쓸 수 있고, 현금 보상은 랭킹 수익률에서 빠진다. */
export function Promos() {
  const theme = useTheme();
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(null);
  const [draft, setDraft] = useState(PROMO_EMPTY);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [usage, setUsage] = useState(null);

  const load = useCallback(async () => {
    try {
      setData(await request('/api/admin/promos'));
      setError('');
    } catch (failure) {
      setError(failure.message || '프로모션 코드를 불러오지 못했어요.');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    setUsage(null);
    if (!editing) return undefined;
    let alive = true;
    request(`/api/admin/promos/${encodeURIComponent(editing)}/redemptions`)
      .then(rows => { if (alive) setUsage(rows); })
      .catch(() => { if (alive) setUsage([]); });
    return () => { alive = false; };
  }, [editing, data]);

  const rows = data?.codes ?? null;
  const catalog = data?.titles ?? [];
  const names = new Map(catalog.map(title => [title.id, title.name]));
  const groups = PROMO_CATEGORIES.map(category => ({ category, titles: catalog.filter(title => title.category === category) })).filter(group => group.titles.length);

  const set = (key, value) => setDraft(current => ({ ...current, [key]: value }));
  const startNew = () => { setEditing(null); setDraft(PROMO_EMPTY); setReason(''); setMessage(''); };
  const edit = row => { setEditing(String(row.code)); setDraft(promoDraft(row)); setReason(''); setMessage(''); };
  const toggleTitle = id => set('titleIds', draft.titleIds.includes(id) ? draft.titleIds.filter(item => item !== id) : [...draft.titleIds, id]);

  const cash = Number(draft.rewardCash || 0);
  const codeError = !editing && draft.code && !/^[A-Za-z0-9_-]{2,40}$/.test(draft.code) ? '코드는 영문·숫자와 _ - 로 2~40자예요.' : '';
  const cashError = cash > MAX_REWARD_CASH ? '현금 보상은 1억원까지예요.' : '';
  const usesError = draft.maxUses !== '' && (Number(draft.maxUses) < 1 || Number(draft.maxUses) > 1_000_000) ? '인원 제한은 1~1,000,000명이에요.' : '';
  const timeError = badTime(draft.startsAt) || badTime(draft.endsAt) ? '시각은 2026-10-21 14:30 형식(한국시간)으로 적어 주세요.'
    : draft.startsAt && draft.endsAt && toInstant(draft.endsAt) <= toInstant(draft.startsAt) ? '끝나는 시각은 시작 시각보다 뒤여야 해요.' : '';
  const rewardError = cash === 0 && draft.titleIds.length === 0 ? '현금이나 칭호 중 하나는 골라 주세요.' : '';
  const valid = draft.code.trim() && reason.trim() && !codeError && !cashError && !usesError && !timeError && !rewardError;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setMessage('');
    try {
      const saved = await request(editing ? `/api/admin/promos/${encodeURIComponent(editing)}` : '/api/admin/promos', {
        method: editing ? 'PUT' : 'POST',
        body: {
          code: draft.code.trim(), rewardCash: cash, titleIds: draft.titleIds, maxUses: draft.maxUses === '' ? null : Number(draft.maxUses),
          startsAt: toInstant(draft.startsAt), endsAt: toInstant(draft.endsAt), active: draft.active, memo: draft.memo.trim(), reason: reason.trim(),
        },
      });
      setMessage(editing ? '코드를 고쳤어요. 이미 받은 사람의 보상은 그대로예요.' : `${String(saved.code)} 코드를 만들었어요.`);
      setEditing(String(saved.code));
      setDraft(promoDraft(saved));
      setReason('');
      await load();
    } catch (failure) {
      setMessage(failure.message || '코드를 저장하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <View style={[kit.between, styles.headRow]}>
          <Txt variant="headline">프로모션 코드</Txt>
          <Button label="새 코드" small icon="add" onPress={startNew} />
        </View>
        {rows == null ? (error
          ? <Empty text="프로모션 코드를 불러오지 못했어요." action="다시 시도" onAction={() => void load()} />
          : <Empty loading text="프로모션 코드를 불러오는 중이에요" />) : rows.length === 0 ? <Empty text="아직 코드가 없어요. 첫 코드를 만들어 보세요." /> : rows.map((row, index) => {
          const code = String(row.code);
          const selected = editing === code;
          const used = `${num(row.used_count).toLocaleString('ko-KR')}${row.max_uses != null ? ` / ${num(row.max_uses).toLocaleString('ko-KR')}` : ''}명 사용`;
          return (
            <Row key={code} divided={index > 0} onPress={() => edit(row)} accessibilityLabel={`${code}, ${promoState(row)}, ${used}. 누르면 고쳐요`} style={selected && { backgroundColor: theme.raised }}>
              <View style={kit.grow}>
                <Txt variant="callout" numeric numberOfLines={1}>{code}</Txt>
                <Txt variant="caption" tone="inkSoft" numberOfLines={2}>{rewardSummary(num(row.reward_cash), splitTitles(row.title_ids), names)}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{`${promoState(row)} · ${used}`}</Txt>
              </View>
              {selected ? <Txt variant="caption" tone="brandText">고치는 중</Txt> : <Ionicons name="create-outline" size={18} color={theme.inkSoft} />}
            </Row>
          );
        })}
      </Card>

      <Card>
        <CardHead title={editing ? `${editing} 고치기` : '새 코드'} meta={rewardSummary(cash, draft.titleIds, names)} />
        <Field
          label="코드" value={draft.code} onChangeText={value => set('code', value.replace(/\s/g, ''))} maxLength={40} editable={!editing}
          autoCapitalize="none" autoCorrect={false} placeholder="launch2026"
          hint={editing ? '이미 쓴 사람의 기록이 이 코드에 묶여 있어 코드는 바꿀 수 없어요.' : '대소문자는 구분하지 않아요.'} error={codeError}
        />
        <Field
          label="현금 보상" unit="원" numeric value={draft.rewardCash} onChangeText={value => set('rewardCash', onlyDigits(value).slice(0, 10))} placeholder="0"
          hint={`${cash > 0 ? `${won(cash)}을 줘요. ` : '비우면 현금은 주지 않아요. '}받은 현금은 랭킹 수익률에서 빠져요.`} error={cashError}
        />
        <Txt variant="callout">{`칭호 보상 · ${draft.titleIds.length ? `${draft.titleIds.length}개 선택` : '선택 안 함'}`}</Txt>
        {groups.map(group => (
          <View key={group.category} style={styles.titleGroup}>
            <Txt variant="caption" tone="inkSoft">{group.category}</Txt>
            <View style={styles.titleOptions}>
              {group.titles.map(title => {
                const picked = draft.titleIds.includes(title.id);
                return (
                  <Pressable
                    key={title.id}
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: picked }}
                    onPress={() => toggleTitle(title.id)}
                    style={[styles.titleOption, { borderColor: picked ? theme.ink : theme.edge, backgroundColor: picked ? theme.ink : 'transparent' }]}
                  >
                    <Txt variant="caption" style={{ color: picked ? theme.ground : theme.ink }}>{title.name}</Txt>
                  </Pressable>
                );
              })}
            </View>
          </View>
        ))}
        {rewardError && draft.code ? <FieldError text={rewardError} /> : null}
        <Field label="사용 인원 제한" unit="명" numeric value={draft.maxUses} onChangeText={value => set('maxUses', onlyDigits(value).slice(0, 7))} placeholder="비우면 제한 없음" error={usesError} />
        <View style={styles.filters}>
          <Field label="시작 (선택)" style={kit.grow} value={draft.startsAt} onChangeText={value => set('startsAt', value)} placeholder="비우면 바로" autoCorrect={false} />
          <Field label="끝 (선택)" style={kit.grow} value={draft.endsAt} onChangeText={value => set('endsAt', value)} placeholder="비우면 계속" autoCorrect={false} />
        </View>
        {timeError ? <FieldError text={timeError} /> : <Txt variant="caption" tone="inkSoft">시각은 2026-10-21 14:30 형식의 한국시간이에요.</Txt>}
        <View style={kit.between}>
          <Txt variant="callout">코드 켜기</Txt>
          <Switch value={draft.active} onValueChange={value => set('active', value)} trackColor={{ true: theme.ink, false: theme.edge }} thumbColor={theme.card} accessibilityLabel="코드 켜기" />
        </View>
        <Field label="메모 (관리자만 봐요)" value={draft.memo} onChangeText={value => set('memo', value)} maxLength={200} placeholder="10월 방송 시청자용" />
        <Field label="사유 (감사 기록에 남아요)" value={reason} onChangeText={setReason} maxLength={300} placeholder={editing ? '기간 연장' : '오픈 이벤트'} />
        {message ? <Notice text={message} /> : null}
        <Button label={editing ? '변경 저장' : '코드 만들기'} kind="primary" busy={busy} disabled={!valid} onPress={() => void save()} />
      </Card>

      {editing ? (
        <Card padded={false}>
          <CardHead title="최근 사용" padded />
          {usage == null ? <Empty loading text="사용 기록을 불러오는 중이에요" />
            : usage.length === 0 ? <Empty text="아직 쓴 사람이 없어요." />
            : usage.map((entry, index) => (
              <Row key={String(entry.user_id)} divided={index > 0}>
                <Txt variant="callout" style={kit.grow} numberOfLines={1}>{String(entry.nickname ?? '')}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{kstShort(entry.redeemed_at)}</Txt>
              </Row>
            ))}
        </Card>
      ) : null}
    </>
  );
}
