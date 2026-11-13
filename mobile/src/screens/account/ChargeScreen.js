import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAccount } from '../../account';
import { request } from '../../auth';
import { AccountFrame, Button, Card, CardHead, Empty, Field, Notice, Row, kit } from '../../components/Kit';
import { won } from '../../format';
import { MISSION_REWARD, missionLabel } from '../../missions';
import { RADIUS, SPACE, Txt, useTheme } from '../../theme';

/** 충전하기. 모의 자금은 결제가 아니라 매일 바뀌는 투자 미션 보상으로만 들어온다. */
export function ChargeScreen({ onBack, onGo, onNotify }) {
  const theme = useTheme();
  const account = useAccount();
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(null);
  const [message, setMessage] = useState(null);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const load = useCallback(async () => {
    try {
      const next = await request('/api/missions');
      if (!alive.current) return;
      setStatus(next);
      setError('');
    } catch {
      if (alive.current) setError('미션 상태를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.');
    }
  }, []);

  // 한국시간 자정에 미션이 바뀌므로 30초마다 다시 읽는다(웹과 같은 주기).
  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 30_000);
    return () => clearInterval(timer);
  }, [load]);

  const ids = status?.availableMissionIds ?? [];
  const completed = new Set(status?.completedMissionIds ?? []);
  const count = ids.filter(id => completed.has(id)).length;

  /** 보상을 먼저 받고 그 화면으로 이동한다. 관심 종목 미션은 담은 뒤에 자동으로 지급된다. */
  const complete = async id => {
    if (pending || completed.has(id)) return;
    const mission = missionLabel(id);
    setPending(id);
    setMessage(null);
    try {
      const result = await request(`/api/missions/${encodeURIComponent(id)}/reward`, { method: 'POST' });
      if (!alive.current) return;
      setStatus(result.missions);
      void account.reload();
      // 바로 다른 화면으로 옮기므로, 지급 결과는 화면이 바뀌어도 남는 앱 알림으로 보여 준다.
      if (result.awarded) {
        const text = `미션 보상 ${won(result.rewardCash)}을 받았어요.`;
        if (onNotify) onNotify(text); else setMessage({ text, ok: true });
      }
      onGo(mission.href);
    } catch (failure) {
      if (!alive.current) return;
      if (id === 'watch') { onGo(mission.href); return; }
      setMessage({ text: failure.message, ok: false });
    } finally {
      if (alive.current) setPending(null);
    }
  };

  return (
    <AccountFrame title="충전하기" onBack={onBack} onRefresh={load}>
      <View style={kit.hero} accessible accessibilityLabel={`오늘 받은 미션 보상 ${won(count * MISSION_REWARD)}, ${ids.length}개 중 ${count}개 완료`}>
        <Txt variant="caption" tone="inkSoft">오늘 받은 미션 보상</Txt>
        <Txt variant="price" numeric>{won(count * MISSION_REWARD)}</Txt>
        {/* 미션 하나가 칸 하나. 채워진 칸이 끝낸 미션이고, 남은 칸은 바탕 대비 3:1이 넘는 테두리로 그린다. */}
        <View style={styles.steps}>
          {(ids.length ? ids : [0, 1, 2]).map((id, index) => (
            <View key={String(id) + index} style={[styles.step, completed.has(id) ? { backgroundColor: theme.brand, borderColor: theme.brand } : { borderColor: theme.edge }]} />
          ))}
        </View>
        <Txt variant="caption" tone="inkSoft" numeric style={styles.stepNote}>{`하루 최대 ${won((ids.length || 3) * MISSION_REWARD)} · 한국시간 자정에 새 미션`}</Txt>
      </View>

      {error ? <Notice text={error} /> : null}
      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}

      <Card padded={false}>
        <CardHead padded title="오늘의 투자 미션" />
        {!status ? <Empty loading={!error} text={error ? '미션을 불러오지 못했어요.' : '미션을 확인하는 중이에요'} /> : ids.map((id, index) => {
          const mission = missionLabel(id);
          const done = completed.has(id);
          return (
            <Row key={id} divided={index > 0} style={styles.mission}>
              <View style={[styles.check, { borderColor: done ? theme.brand : theme.edge, backgroundColor: done ? theme.brand : 'transparent' }]}>
                {done ? <Ionicons name="checkmark" size={16} color={theme.onBrand} /> : null}
              </View>
              <View style={kit.grow}>
                <Txt variant="callout">{mission.title}</Txt>
                <Txt variant="caption" tone="inkSoft">{mission.description}</Txt>
                <Txt variant="caption" numeric style={{ marginTop: 2 }}>{`+${won(MISSION_REWARD)}`}</Txt>
              </View>
              {done ? <Txt variant="caption" tone="inkSoft">받음</Txt> : (
                <Button label={pending === id ? '지급 중' : mission.action} small kind="primary" busy={pending === id} disabled={pending != null} onPress={() => void complete(id)} />
              )}
            </Row>
          );
        })}
      </Card>

      <PromoCode />
      <Txt variant="micro" tone="inkSoft" style={styles.footer}>실제 돈은 오가지 않아요. 미션 보상은 운용계좌가 아니라 내 지갑으로 들어와요.</Txt>
    </AccountFrame>
  );
}

/** 프로모션 코드를 넣으면 관리자가 정한 현금이나 칭호를 받는다. */
function PromoCode() {
  const [code, setCode] = useState('');
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState(null);
  const submit = async () => {
    const trimmed = code.trim();
    if (!trimmed || pending) return;
    setPending(true);
    setMessage(null);
    try {
      const status = await request('/api/titles/promo', { method: 'POST', body: { code: trimmed } });
      const named = (status?.newlyAwarded ?? []).map(title => title.name);
      const cash = Number(status?.rewardCash) || 0;
      const parts = [cash > 0 ? `내 지갑에 ${cash.toLocaleString('ko-KR')}원이 들어왔어요.` : '', named.length ? `칭호 ${named.join(', ')}을(를) 받았어요.` : ''].filter(Boolean);
      setMessage({ text: parts.length ? parts.join(' ') : '프로모션 코드를 사용했어요.', ok: true });
      setCode('');
    } catch (failure) {
      setMessage({ text: failure.message, ok: false });
    } finally {
      setPending(false);
    }
  };
  return (
    <Card>
      <CardHead title="프로모션 코드" />
      <Field
        value={code}
        onChangeText={setCode}
        placeholder="코드를 넣으면 보상을 받아요"
        autoCapitalize="none"
        autoCorrect={false}
        maxLength={40}
        editable={!pending}
        returnKeyType="done"
        onSubmitEditing={() => void submit()}
        accessibilityLabel="프로모션 코드"
      />
      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}
      <Button label="코드 사용" kind="primary" busy={pending} disabled={!code.trim()} onPress={() => void submit()} />
    </Card>
  );
}

const styles = StyleSheet.create({
  steps: { flexDirection: 'row', gap: 6, marginTop: SPACE.md },
  step: { width: 56, height: 10, borderRadius: 3, borderWidth: 1.5 },
  stepNote: { marginTop: SPACE.sm },
  mission: { alignItems: 'flex-start' },
  check: { width: 24, height: 24, borderRadius: RADIUS - 2, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
});
