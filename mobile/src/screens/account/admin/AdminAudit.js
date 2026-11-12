import React, { useEffect, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Button, Card, CardHead, Chips, Empty, Field, Notice, Row, Segment, kit, kstShort } from '../../../components/Kit';
import { Txt, useTheme } from '../../../theme';
import { styles, useCursorList } from './shared';

const ACTIONS = {
  TRADE_BAN: '이용 정지', TRADE_BAN_LIFT: '이용 정지 해제', NICKNAME_FORCE: '닉네임 변경', ORDER_CANCEL: '주문 취소',
  SETTLEMENT_SETTLE: '정산 완료 처리', SETTLEMENT_REVERSE: '체결 취소', RUNTIME: '시장 운영 설정', STOCK_CREATE: '종목 등록',
  STOCK_UPDATE: '종목 정보 수정', STOCK_STATE: '종목 정지·재개', CASH_ADJUST: '현금 조정', CASH_BULK: '전체 보상',
  MISSION_RECOVERY: '미션 보상 복구', HOLDING_RESTORE: '보유 주식 복구', PERMISSIONS: '권한 변경', CONTENT_SAVE: '콘텐츠 저장',
  CONTENT_DELETE: '콘텐츠 삭제', MISSION_CONFIG: '미션 설정', REPORT_RESOLVE: '신고 처리', COMMENT_DELETE: '댓글 삭제',
  SEASON_CREATE: '시즌 생성', SEASON_START: '시즌 시작', SEASON_CLOSE: '시즌 종료', SEASON_RESET: '시즌 초기화', SEASON_USER_RESET: '시즌 회원 초기화',
};
const actionLabel = action => ACTIONS[String(action)] ?? String(action ?? '');

const pretty = value => {
  if (value === null || value === undefined || value === '') return '';
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    if (parsed && typeof parsed === 'object' && Object.keys(parsed).length === 0) return '';
    return JSON.stringify(parsed, null, 2);
  } catch {
    return String(value);
  }
};

const ACTION_FILTERS = [{ key: '', label: '전체' }, ...Object.entries(ACTIONS).map(([key, label]) => ({ key, label }))];

export function Audit() {
  const [source, setSource] = useState('audit');
  const [actorId, setActorId] = useState('');
  const [userId, setUserId] = useState('');
  const [action, setAction] = useState('');
  const [applied, setApplied] = useState({});
  const list = useCursorList(source === 'audit' ? '/api/admin/audit' : '/api/admin/audit/legacy');
  const params = () => Object.fromEntries(Object.entries(applied).filter(([, value]) => value));

  // source가 바뀌면 load도 새 경로로 바뀌므로 함께 다시 읽는다.
  useEffect(() => { void list.load(params(), null); }, [applied, list.load]); // eslint-disable-line react-hooks/exhaustive-deps

  const switchSource = next => {
    if (next === source) return;
    setSource(next);
    setActorId('');
    setUserId('');
    setAction('');
    setApplied({});
  };
  const filtered = Object.values(applied).some(Boolean);

  return (
    <>
      <Card>
        <Segment options={[{ key: 'audit', label: '운영 기록' }, { key: 'legacy', label: '이전 기록' }]} value={source} onChange={switchSource} label="기록 종류" />
        <Txt variant="caption" tone="inkSoft">{source === 'audit' ? '사유와 변경 전후 값이 함께 남은 기록이에요.' : '운영 API 이전에 남은 이용 정지·닉네임 변경 기록이에요.'}</Txt>
        {source === 'audit' ? (
          <>
            <Field label="관리자 번호" numeric keyboardType="number-pad" value={actorId} placeholder="전체" onChangeText={text => setActorId(text.replace(/\D/g, ''))} />
            <Chips options={ACTION_FILTERS} value={action} onChange={next => { setAction(next); setApplied({ actorId, action: next }); }} />
          </>
        ) : (
          <Field label="대상 회원 번호" numeric keyboardType="number-pad" value={userId} placeholder="전체" onChangeText={text => setUserId(text.replace(/\D/g, ''))} />
        )}
        <View style={styles.filters}>
          <Button label="조회" kind="primary" style={kit.grow} busy={list.loading} onPress={() => setApplied(source === 'audit' ? { actorId, action } : { userId })} />
          {filtered ? <Button label="조건 지우기" style={kit.grow} onPress={() => { setActorId(''); setUserId(''); setAction(''); setApplied({}); }} /> : null}
        </View>
      </Card>
      {list.error ? <Notice text={list.error} /> : null}
      <Card padded={false}>
        <CardHead padded title="기록" meta={`${list.rows.length.toLocaleString('ko-KR')}건${list.done ? '' : ' 이상'}`} />
        {list.loading && list.rows.length === 0 ? <Empty loading text="기록을 불러오는 중이에요" />
          : list.error && list.rows.length === 0 ? <Empty text="기록을 불러오지 못했어요." action="다시 시도" onAction={() => void list.load(params(), null)} />
          : list.rows.length === 0 ? <Empty text={filtered ? '조건에 맞는 기록이 없어요.' : '아직 남은 기록이 없어요.'} />
          : list.rows.map((row, index) => (source === 'audit'
            ? <AuditEntry key={String(row.id)} row={row} divided={index > 0} />
            : (
              <Row key={String(row.id)} divided={index > 0}>
                <View style={kit.grow}>
                  <Txt variant="callout">{actionLabel(row.action)}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`관리자 #${row.admin_id} → 회원 #${row.target_user_id} · ${kstShort(row.created_at)}`}</Txt>
                  {row.detail ? <Txt variant="caption" tone="inkSoft">{String(row.detail)}</Txt> : null}
                </View>
              </Row>
            )))}
        {!list.done && list.rows.length > 0 ? (
          <View style={styles.more}><Button label="다음 50건" busy={list.loading} onPress={() => void list.load(params(), { before: list.lastId })} /></View>
        ) : null}
      </Card>
    </>
  );
}

function AuditEntry({ row, divided }) {
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const before = pretty(row.before_json);
  const after = pretty(row.after_json);
  const hasDiff = Boolean(before || after);
  return (
    <View style={divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
      <Pressable
        accessibilityRole={hasDiff ? 'button' : undefined}
        accessibilityState={hasDiff ? { expanded: open } : undefined}
        disabled={!hasDiff}
        onPress={() => setOpen(value => !value)}
        style={({ pressed }) => [styles.audit, pressed && { backgroundColor: theme.pressed }]}
      >
        <View style={kit.between}>
          <Txt variant="callout" style={styles.shrink}>{actionLabel(row.action)}</Txt>
          {hasDiff ? <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={18} color={theme.inkSoft} /> : null}
        </View>
        <Txt variant="caption" tone="inkSoft" numeric>{`대상 ${row.target ?? '-'} · 관리자 #${row.admin_id} · ${kstShort(row.created_at)}`}</Txt>
        {row.reason ? <Txt variant="caption">{String(row.reason)}</Txt> : null}
      </Pressable>
      {open ? (
        <View style={[styles.diff, { backgroundColor: theme.raised }]}>
          <Txt variant="caption" tone="inkSoft">변경 전</Txt>
          <Txt variant="micro" numeric selectable>{before || '없음'}</Txt>
          <Txt variant="caption" tone="inkSoft" style={styles.sectionGap}>변경 후</Txt>
          <Txt variant="micro" numeric selectable>{after || '없음'}</Txt>
        </View>
      ) : null}
    </View>
  );
}
