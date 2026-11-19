import React, { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { request } from '../../auth';
import { Button, Field, Notice, Segment, kstShort } from '../../components/Kit';
import { won } from '../../format';
import { serverTime } from '../../research';
import { SPACE, Txt, useTheme } from '../../theme';
import { Card, amount } from './parts';

const KINDS = [{ key: 'STOP_LOSS', label: '손절' }, { key: 'TAKE_PROFIT', label: '익절' }];
const KIND_LABEL = { STOP_LOSS: '손절', TAKE_PROFIT: '익절' };
const STATUS_LABEL = { ACTIVE: '감시 중', FIRING: '주문 중', TRIGGERED: '실행됨', FAILED: '실행 못 함', CANCELLED: '취소됨' };
const digits = text => String(text).replace(/\D/g, '');

/**
 * 손절·익절 자동 매도(웹 StopOrders와 같은 /api/stop-orders). 현재가가 감시 가격에 닿으면 서버가 시장가 매도를 낸다.
 * 잔고가 있는 종목에서만 걸 수 있고, 목록은 지금 보고 있는 종목 것만 보인다.
 */
export function StockStopOrdersCard({ code, current, held }) {
  const theme = useTheme();
  const [orders, setOrders] = useState(null);
  const [kind, setKind] = useState('STOP_LOSS');
  const [trigger, setTrigger] = useState('');
  const [quantity, setQuantity] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const next = await request('/api/stop-orders');
        if (active) setOrders(Array.isArray(next) ? next : []);
      } catch (failure) {
        if (active) setNote({ text: failure.message || '자동 매도 목록을 불러오지 못했어요.', error: true });
      }
    };
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => { active = false; clearInterval(timer); };
  }, [revision]);

  useEffect(() => { setTrigger(''); setQuantity(''); setNote(null); }, [code]);

  const submit = async () => {
    setBusy(true);
    setNote(null);
    try {
      await request('/api/stop-orders', { method: 'POST', body: { stockCode: code, kind, triggerPrice: Number(trigger), quantity: Number(quantity) } });
      setNote({ text: `${KIND_LABEL[kind]} ${Number(quantity).toLocaleString('ko-KR')}주를 ${won(Number(trigger))}에 걸었어요.`, error: false });
      setTrigger('');
      setQuantity('');
      setRevision(value => value + 1);
    } catch (failure) {
      setNote({ text: failure.message || '자동 매도를 걸지 못했어요.', error: true });
    } finally {
      setBusy(false);
    }
  };

  const cancel = async id => {
    setBusy(true);
    try {
      await request(`/api/stop-orders/${id}`, { method: 'DELETE' });
      setNote({ text: '자동 매도를 취소했어요.', error: false });
      setRevision(value => value + 1);
    } catch (failure) {
      setNote({ text: failure.message || '취소하지 못했어요.', error: true });
    } finally {
      setBusy(false);
    }
  };

  const mine = (orders ?? []).filter(order => order.stockCode === code);
  const hint = kind === 'STOP_LOSS'
    ? `현재가 ${won(current)}보다 낮게 정하면, 그 가격 이하로 내려갈 때 시장가로 팔아요.`
    : `현재가 ${won(current)}보다 높게 정하면, 그 가격 이상으로 오를 때 시장가로 팔아요.`;

  return (
    <Card>
      <Txt variant="headline" accessibilityRole="header">자동 매도</Txt>
      {held > 0 ? (
        <View style={styles.form}>
          <Segment options={KINDS} value={kind} onChange={setKind} label="자동 매도 종류" />
          <Field label="감시 가격" numeric unit="원" keyboardType="number-pad" value={trigger} placeholder={String(current)} onChangeText={text => setTrigger(digits(text))} editable={!busy} />
          <Field label={`수량 · 보유 ${amount(held)}`} numeric unit="주" keyboardType="number-pad" value={quantity} placeholder={String(held)} onChangeText={text => setQuantity(digits(text))} editable={!busy} />
          <Txt variant="caption" tone="inkSoft">{`${hint} 체결가는 감시 가격과 다를 수 있어요.`}</Txt>
          <Button label={`${KIND_LABEL[kind]} 걸기`} kind="primary" busy={busy} disabled={!trigger || !quantity} onPress={() => void submit()} />
        </View>
      ) : (
        <Txt variant="callout" tone="inkSoft" style={styles.gap}>이 종목을 갖고 있을 때 손절·익절 가격을 걸어 둘 수 있어요.</Txt>
      )}

      {note ? <View style={styles.gap}><Notice text={note.text} tone={note.error ? 'neutral' : 'ok'} /></View> : null}
      {orders === null && !note?.error ? <Txt variant="caption" tone="inkSoft" style={styles.gap}>걸어 둔 자동 매도를 불러오고 있어요.</Txt> : null}

      {mine.map((order, index) => (
        <View
          key={order.id}
          style={[styles.order, { borderTopColor: theme.line }, index === 0 && styles.firstOrder]}
          accessible={order.status !== 'ACTIVE'}
          accessibilityLabel={`${KIND_LABEL[order.kind]} ${won(order.triggerPrice)}, ${amount(order.quantity)}, ${STATUS_LABEL[order.status] ?? order.status}`}
        >
          <View style={styles.orderText}>
            <Txt variant="callout" numeric>{`${KIND_LABEL[order.kind]} · ${won(order.triggerPrice)} · ${amount(order.quantity)}`}</Txt>
            <Txt variant="caption" tone="inkSoft">
              {`${STATUS_LABEL[order.status] ?? order.status}${order.triggeredAt ? ` ${kstShort(serverTime(order.triggeredAt))}` : ''}`}
            </Txt>
            {order.status !== 'ACTIVE' && order.message ? <Txt variant="caption" tone="inkSoft">{order.message}</Txt> : null}
          </View>
          {order.status === 'ACTIVE' ? (
            <Button label="취소" small disabled={busy} accessibilityLabel={`${KIND_LABEL[order.kind]} ${won(order.triggerPrice)} 자동 매도 취소`} onPress={() => void cancel(order.id)} />
          ) : null}
        </View>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  form: { gap: SPACE.sm, marginTop: SPACE.md },
  gap: { marginTop: SPACE.md },
  order: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingVertical: SPACE.md, borderTopWidth: StyleSheet.hairlineWidth },
  firstOrder: { marginTop: SPACE.md },
  orderText: { flex: 1, minWidth: 0, gap: 2 },
});
