import React, { useCallback, useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request } from '../../../auth';
import { Button, Card, CardHead, Chips, Empty, Field, Notice, Row, kit, kstShort } from '../../../components/Kit';
import { won } from '../../../format';
import { Txt, useTheme } from '../../../theme';
import { confirm, styles } from './shared';

const BAN_PRESETS = [1, 3, 7, 30];

export function Users() {
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [openId, setOpenId] = useState(null);

  const search = useCallback(async text => {
    setLoading(true);
    setError('');
    try {
      setUsers(await request(`/api/admin/users${text.trim() ? `?q=${encodeURIComponent(text.trim())}` : ''}`));
    } catch (failure) {
      setError(failure.message || '회원 목록을 불러오지 못했어요.');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void search(''); }, [search]);

  const replace = next => setUsers(list => (list ?? []).map(user => (user.id === next.id ? next : user)));

  return (
    <>
      <Card>
        <Field
          label="이메일·닉네임·UID·회원 번호"
          value={query}
          onChangeText={setQuery}
          placeholder="비워 두면 최근 가입 50명"
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
          onSubmitEditing={() => void search(query)}
        />
        <Button label="검색" kind="primary" busy={loading} onPress={() => void search(query)} />
      </Card>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <CardHead padded title="회원" meta={users ? `${users.length}명` : null} />
        {users == null ? (error
          ? <Empty text="회원 목록을 불러오지 못했어요." action="다시 시도" onAction={() => void search(query)} />
          : <Empty loading text="회원을 불러오는 중이에요" />) : users.length === 0 ? <Empty text="검색 결과가 없어요." /> : users.map((user, index) => {
          const open = openId === user.id;
          return (
            <View key={user.id} style={index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
              <Row onPress={() => setOpenId(open ? null : user.id)} accessibilityLabel={`${user.nickname}, 회원 번호 ${user.id}. ${open ? '닫기' : '관리'}`}>
                <View style={kit.grow}>
                  <View style={styles.inline}>
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{user.nickname}</Txt>
                    {user.role === 'ADMIN' ? <Badge text="관리자" /> : null}
                    {/* 운영 봇. 다른 사용자에게는 일반 회원으로 보이고, 관리자에게만 표시한다. */}
                    {user.persona ? <Badge text={`봇 · ${PERSONA_LABELS[user.persona] ?? user.persona}`} /> : null}
                    {user.tradeBannedUntil ? <Badge text="이용 정지" tone="ink" /> : null}
                  </View>
                  <Txt variant="caption" tone="inkSoft" numberOfLines={1} numeric>{`#${user.id} · ${user.email || '이메일 없음'}`}</Txt>
                </View>
                <View style={kit.right}>
                  <Txt variant="caption" numeric>{won(user.cash)}</Txt>
                  <Txt variant="micro" tone="inkSoft" numeric>{`가입 ${kstShort(user.createdAt)}`}</Txt>
                </View>
                <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={18} color={theme.inkSoft} />
              </Row>
              {open ? <UserActions user={user} onChange={replace} /> : null}
            </View>
          );
        })}
      </Card>
    </>
  );
}

// 운영 봇의 성격(서버 users.persona). 일반 회원은 null이다. 웹 AdminUsers의 PERSONA_LABELS와 같다.
const PERSONA_LABELS = { LONG: '장기투자', SCALP: '단타', NEWBIE: '초보' };

function Badge({ text, tone = 'inkSoft' }) {
  const theme = useTheme();
  return (
    <View style={[styles.badge, { borderColor: tone === 'ink' ? theme.ink : theme.edge }]}>
      <Txt variant="micro" tone={tone}>{text}</Txt>
    </View>
  );
}

function UserActions({ user, onChange }) {
  const theme = useTheme();
  const [days, setDays] = useState('7');
  // 사유는 정지당한 사용자에게 그대로 보이므로 미리 채우지 않는다. 관리자가 직접 적어야 정지할 수 있다.
  const [reason, setReason] = useState('');
  const [nickname, setNickname] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const run = async (action, done) => {
    setBusy(true);
    setMessage('');
    try {
      onChange(await action());
      setMessage(done);
    } catch (failure) {
      setMessage(failure.message || '처리하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  const ban = () => {
    const value = Number(days);
    if (!Number.isInteger(value) || value < 1 || value > 365) { setMessage('정지 기간은 1~365일로 입력해 주세요.'); return; }
    confirm(`${user.nickname} 계정을 ${value}일 동안 이용 정지할까요?`, '정지 중에는 로그인과 정지 안내만 볼 수 있고, 미체결 주문은 모두 취소돼요.', '이용 정지', () => void run(
      () => request(`/api/admin/users/${user.id}/trade-ban`, { method: 'PUT', body: { days: value, reason } }),
      `${value}일 이용 정지했어요.`,
    ));
  };
  const lift = () => confirm(`${user.nickname} 계정의 이용 정지를 풀까요?`, '', '정지 해제', () => void run(
    () => request(`/api/admin/users/${user.id}/trade-ban`, { method: 'DELETE' }),
    '이용 정지를 풀었어요.',
  ), false);
  const rename = () => {
    const value = nickname.trim();
    if (value && (value.length < 2 || value.length > 10)) { setMessage('닉네임은 2~10자로 입력해 주세요.'); return; }
    confirm(`"${user.nickname}" 닉네임을 바꿀까요? 새 닉네임: ${value || '자동 생성'}`, '3일 변경 제한을 무시하고 바로 바뀌어요.', '닉네임 변경', () => void run(async () => {
      const next = await request(`/api/admin/users/${user.id}/nickname`, { method: 'PUT', body: { nickname: value } });
      setNickname('');
      return next;
    }, '닉네임을 바꿨어요.'), false);
  };

  return (
    <View style={[styles.actions, { backgroundColor: theme.raised }]}>
      {message ? <Txt variant="callout" accessibilityLiveRegion="polite">{message}</Txt> : null}
      <Txt variant="caption" tone="inkSoft" numeric selectable>{`UID ${user.uid ?? '-'}`}</Txt>

      <Txt variant="headline">이용 정지</Txt>
      <Txt variant="caption" tone="inkSoft">
        {user.tradeBannedUntil
          ? `${kstShort(user.tradeBannedUntil)}까지 정지돼 있어요.${user.tradeBanReason ? ` 사유: ${user.tradeBanReason}` : ''}`
          : '정지 중에는 로그인과 정지 안내만 볼 수 있어요. 미체결 주문은 바로 취소돼요.'}
      </Txt>
      {user.tradeBannedUntil ? <Button label="정지 해제" small busy={busy} onPress={lift} style={styles.alignStart} /> : null}
      <Chips options={BAN_PRESETS.map(preset => ({ key: String(preset), label: `${preset}일` }))} value={days} onChange={setDays} />
      <Field label="기간(일)" numeric unit="일" keyboardType="number-pad" value={days} onChangeText={text => setDays(text.replace(/\D/g, ''))} />
      <Field label="사유 (사용자에게 보여요)" value={reason} onChangeText={setReason} maxLength={200} placeholder="사용자에게 보일 정지 사유" />
      <Button label={user.tradeBannedUntil ? '기간 다시 정하기' : '이용 정지'} kind="danger" busy={busy} disabled={user.role === 'ADMIN' || !reason.trim()} onPress={ban} />
      {user.role === 'ADMIN' ? <Txt variant="caption" tone="inkSoft">관리자 계정은 정지할 수 없어요.</Txt> : null}

      <Txt variant="headline" style={styles.sectionGap}>닉네임 강제 변경</Txt>
      <Txt variant="caption" tone="inkSoft">{`지금: ${user.nickname}. 비워 두면 "슈엔단0000" 형식으로 자동으로 만들어요.`}</Txt>
      <Field label="새 닉네임" value={nickname} onChangeText={setNickname} maxLength={10} placeholder="자동 생성" autoCorrect={false} />
      <Button label="닉네임 변경" kind="primary" busy={busy} onPress={rename} />
      <Txt variant="caption" tone="inkSoft">현금 조정과 미션 보상 복구는 웹 관리자 화면에서 해요.</Txt>
    </View>
  );
}
