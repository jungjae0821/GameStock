import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { heldQuantity, positionOf, useAccount } from '../account';
import { ApiError, request } from '../auth';
import { createOrderKeys } from '../orderKeys';
import { price, won } from '../format';
import { MAX_ORDER_QUANTITY, affordableQuantity, nextTickPrice, orderFee, outsidePriceBand, previousTickPrice, roundToTick, sellableQuantity } from '../trading';
import { NUMBER_FONT, RADIUS, RADIUS_SM, SPACE, TYPE, Txt, useTheme } from '../theme';

const RATIOS = [
  { label: '10%', ratio: 0.1 },
  { label: '25%', ratio: 0.25 },
  { label: '50%', ratio: 0.5 },
  { label: '최대', ratio: 1 },
];

/**
 * 주문 시트. 웹 AdvancedOrderTicket과 같은 규칙으로 검사한다: 1~1,000주, 호가 단위, 거래 제한,
 * 수수료 포함 매수 가능 금액, 보유 수량. 서버가 최종 판단하고, 그 응답 문구를 그대로 보여 준다.
 */
export function OrderSheet({ visible, stock, orderbook, initialSide, initialLimitPrice, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const account = useAccount();
  const [side, setSide] = useState(initialSide);
  const [type, setType] = useState('MARKET');
  const [limitPrice, setLimitPrice] = useState('');
  const [qty, setQty] = useState('');
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const orderKeys = useRef(null);
  if (!orderKeys.current) orderKeys.current = createOrderKeys();
  const [result, setResult] = useState(null);

  const restriction = stock?.restriction;
  const marketAllowed = !restriction || restriction.marketOrdersAllowed;
  const limitAllowed = !restriction || restriction.limitOrdersAllowed;

  useEffect(() => {
    if (!visible || !stock) return;
    const wantsLimit = initialLimitPrice != null || !marketAllowed;
    setSide(initialSide);
    setType(wantsLimit && limitAllowed ? 'LIMIT' : 'MARKET');
    setLimitPrice(String(initialLimitPrice ?? stock.price));
    setQty('');
    setResult(null);
    void account.reload();
  }, [visible]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!stock) return null;

  const bestAsk = Math.min(...(orderbook?.asks ?? []).map(level => level.price), Infinity);
  const bestBid = Math.max(...(orderbook?.bids ?? []).map(level => level.price), -Infinity);
  const parsedLimit = Number(limitPrice);
  const validLimit = Number.isInteger(parsedLimit) && parsedLimit > 0 && roundToTick(parsedLimit) === parsedLimit;
  const outsideBand = validLimit && outsidePriceBand(parsedLimit, stock.limitDown, stock.limitUp);
  // 시장가 매수는 최우선 매도 호가로 체결된다고 보고 금액을 어림한다. 실제 체결가는 서버가 정한다.
  const unitPrice = type === 'LIMIT' ? (validLimit ? parsedLimit : stock.price) : side === 'buy' && Number.isFinite(bestAsk) ? bestAsk : side === 'sell' && Number.isFinite(bestBid) ? bestBid : stock.price;
  const cash = Number(account.portfolio?.cash) || 0;
  const held = heldQuantity(account.portfolio, stock.code);
  // 대기 중인 매도 주문과 미정산분은 팔 수 없다. 서버가 거절할 매도를 화면에서 먼저 막는다(웹과 같은 규칙).
  const sellable = sellableQuantity(positionOf(account.portfolio, stock.code));
  const maxQty = account.portfolio ? (side === 'buy' ? affordableQuantity(cash, unitPrice) : sellable) : 0;
  const parsedQty = Number(qty);
  const validQty = Number.isInteger(parsedQty) && parsedQty >= 1 && parsedQty <= MAX_ORDER_QUANTITY;
  const gross = validQty ? parsedQty * unitPrice : 0;
  const fee = orderFee(gross);
  const blocked = type === 'MARKET' ? !marketAllowed : !limitAllowed;
  const sideLabel = side === 'buy' ? '매수' : '매도';
  const sideColor = side === 'buy' ? theme.upFill : theme.downFill;

  const problem = account.status === 'error' && !account.portfolio ? '계좌를 불러오지 못했어요. 시트를 닫았다가 다시 열어 주세요.'
    : blocked ? `${restriction?.label ?? '거래 제한'} 중이라 ${type === 'MARKET' ? '시장가' : '지정가'} 주문을 받지 않아요.`
    : type === 'LIMIT' && !validLimit ? '단가를 호가 단위에 맞춰 입력해 주세요.'
    : outsideBand ? `오늘 주문할 수 있는 가격은 ${price(stock.limitDown)}~${price(stock.limitUp)}원이에요.`
    : qty !== '' && !validQty ? '수량은 1~1,000주 사이의 정수로 입력해 주세요.'
    : validQty && account.portfolio && parsedQty > maxQty ? (side === 'buy' ? `수수료를 포함해 최대 ${maxQty.toLocaleString('ko-KR')}주까지 살 수 있어요.` : `지금 팔 수 있는 수량은 ${sellable.toLocaleString('ko-KR')}주예요.`)
    : null;
  const ready = !problem && validQty && Boolean(account.portfolio) && !busy;

  const step = direction => {
    const base = validLimit ? parsedLimit : stock.price;
    setLimitPrice(String(direction > 0 ? nextTickPrice(base) : previousTickPrice(base)));
  };
  const applyRatio = ratio => setQty(String(Math.max(0, Math.floor(maxQty * ratio))));

  const submit = async () => {
    // busy state는 다음 렌더에야 반영되므로, 같은 렌더 안의 두 번째 탭은 ref로 막는다.
    if (!ready || submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setResult(null);
    const order = { code: stock.code, side: side.toUpperCase(), qty: parsedQty, orderType: type, price: type === 'LIMIT' ? parsedLimit : undefined };
    try {
      const response = await request('/api/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': orderKeys.current.keyFor(order) },
        body: { stockCode: stock.code, side: side.toUpperCase(), quantity: parsedQty, orderType: type, ...(type === 'LIMIT' ? { price: parsedLimit } : {}) },
      });
      orderKeys.current.settle();
      setResult({ ok: true, message: response?.message || `${parsedQty.toLocaleString('ko-KR')}주 ${sideLabel} 주문을 접수했어요.` });
      setQty('');
      void account.reload();
    } catch (error) {
      // 서버가 응답했다면 결과가 확정됐다. 시간 초과·연결 끊김이면 체결됐을 수도 있어 같은 키를 남긴다.
      if (error instanceof ApiError) orderKeys.current.settle();
      setResult({ ok: false, message: error.message });
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={[styles.scrim, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="주문 닫기" />
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.sheetWrap} pointerEvents="box-none">
        <View style={[styles.sheet, { backgroundColor: theme.card, paddingBottom: insets.bottom + SPACE.md }]}>
          <View style={[styles.grabber, { backgroundColor: theme.line }]} />
          <View style={styles.head}>
            <View style={styles.shrink}>
              <Txt variant="headline" numberOfLines={1} accessibilityRole="header">{`${stock.name} 주문`}</Txt>
              <Txt variant="caption" tone="inkSoft" numeric>{`현재가 ${price(stock.price)}원`}</Txt>
            </View>
            <Pressable accessibilityRole="button" accessibilityLabel="주문 닫기" onPress={onClose} style={styles.close}>
              <Ionicons name="close" size={24} color={theme.ink} />
            </Pressable>
          </View>
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.body}>
            <Segment
              options={[{ key: 'buy', label: '매수' }, { key: 'sell', label: '매도' }]}
              value={side}
              onChange={next => { setSide(next); setResult(null); }}
              activeColor={next => (next === 'buy' ? theme.upFill : theme.downFill)}
              activeInk={() => theme.onFill}
            />
            <Segment
              options={[{ key: 'MARKET', label: '시장가', disabled: !marketAllowed }, { key: 'LIMIT', label: '지정가', disabled: !limitAllowed }]}
              value={type}
              onChange={next => { setType(next); setResult(null); }}
              activeColor={() => theme.ink}
              activeInk={() => theme.ground}
            />

            {type === 'LIMIT' ? (
              <View style={styles.field}>
                <Txt variant="caption" tone="inkSoft">단가</Txt>
                <View style={[styles.stepper, { borderColor: theme.edge, backgroundColor: theme.raised }]}>
                  <Pressable accessibilityRole="button" accessibilityLabel="한 호가 내리기" onPress={() => step(-1)} style={styles.stepButton}><Ionicons name="remove" size={22} color={theme.ink} /></Pressable>
                  <TextInput value={limitPrice} onChangeText={text => setLimitPrice(text.replace(/[^0-9]/g, ''))} keyboardType="number-pad" keyboardAppearance={theme.scheme} selectionColor={theme.brand} style={[styles.stepInput, { color: theme.ink }]} accessibilityLabel="지정가 단가(원)" />
                  <Txt variant="callout" tone="inkSoft">원</Txt>
                  <Pressable accessibilityRole="button" accessibilityLabel="한 호가 올리기" onPress={() => step(1)} style={styles.stepButton}><Ionicons name="add" size={22} color={theme.ink} /></Pressable>
                </View>
              </View>
            ) : (
              <Txt variant="caption" tone="inkSoft" style={styles.marketNote}>{`시장가는 지금 있는 ${side === 'buy' ? '매도' : '매수'} 호가부터 바로 체결돼요.`}</Txt>
            )}

            <View style={styles.field}>
              <View style={styles.fieldHead}>
                <Txt variant="caption" tone="inkSoft">수량</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>
                  {account.portfolio ? (side === 'buy' ? `최대 ${maxQty.toLocaleString('ko-KR')}주 · 주문 가능 ${won(cash)}` : sellable < held ? `매도 가능 ${sellable.toLocaleString('ko-KR')}주 · 보유 ${held.toLocaleString('ko-KR')}주` : `보유 ${held.toLocaleString('ko-KR')}주`) : '계좌를 불러오는 중이에요'}
                </Txt>
              </View>
              <View style={[styles.stepper, { borderColor: theme.edge, backgroundColor: theme.raised }]}>
                <TextInput value={qty} onChangeText={text => setQty(text.replace(/[^0-9]/g, ''))} keyboardType="number-pad" keyboardAppearance={theme.scheme} selectionColor={theme.brand} placeholder="0" placeholderTextColor={theme.inkSoft} style={[styles.stepInput, styles.qtyInput, { color: theme.ink }]} accessibilityLabel="수량(주)" />
                <Txt variant="callout" tone="inkSoft" style={styles.unit}>주</Txt>
              </View>
              <View style={styles.ratios}>
                {RATIOS.map(item => (
                  <Pressable key={item.label} accessibilityRole="button" accessibilityLabel={`${item.label === '최대' ? '최대 수량' : `가능 수량의 ${item.label}`}`} onPress={() => applyRatio(item.ratio)} disabled={!maxQty} style={[styles.ratio, { borderColor: theme.edge, opacity: maxQty ? 1 : 0.5 }]}>
                    <Txt variant="caption">{item.label}</Txt>
                  </Pressable>
                ))}
              </View>
            </View>

            <View style={[styles.summary, { backgroundColor: theme.raised }]}>
              <SummaryRow label={type === 'MARKET' ? '예상 금액' : '주문 금액'} value={won(gross)} />
              <SummaryRow label="수수료 0.1%" value={won(fee)} />
              <SummaryRow label={side === 'buy' ? '필요한 금액' : '받을 금액'} value={won(side === 'buy' ? gross + fee : Math.max(0, gross - fee))} strong />
            </View>

            {problem ? <Message tone="warn" text={problem} /> : null}
            {result ? <Message tone={result.ok ? 'ok' : 'warn'} text={result.message} /> : null}
          </ScrollView>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: !ready }}
            onPress={() => void submit()}
            disabled={!ready}
            style={({ pressed }) => [styles.submit, { backgroundColor: sideColor, opacity: !ready ? 0.45 : pressed ? 0.85 : 1 }]}
          >
            {busy ? <ActivityIndicator color={theme.onFill} /> : null}
            <Txt variant="body" style={{ color: theme.onFill }}>{validQty ? `${parsedQty.toLocaleString('ko-KR')}주 ${sideLabel}하기` : `${sideLabel}할 수량을 입력하세요`}</Txt>
          </Pressable>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

function Segment({ options, value, onChange, activeColor, activeInk }) {
  const theme = useTheme();
  return (
    <View style={[styles.segment, { backgroundColor: theme.raised }]} accessibilityRole="tablist">
      {options.map(option => {
        const selected = option.key === value;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="tab"
            accessibilityState={{ selected, disabled: option.disabled }}
            disabled={option.disabled}
            onPress={() => onChange(option.key)}
            style={[styles.segmentItem, selected && { backgroundColor: activeColor(option.key) }, option.disabled && { opacity: 0.4 }]}
          >
            <Txt variant="callout" style={{ color: selected ? activeInk(option.key) : theme.ink }}>{option.label}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

function SummaryRow({ label, value, strong }) {
  return (
    <View style={styles.summaryRow}>
      <Txt variant={strong ? 'callout' : 'caption'} tone={strong ? 'ink' : 'inkSoft'}>{label}</Txt>
      <Txt variant={strong ? 'headline' : 'callout'} numeric>{value}</Txt>
    </View>
  );
}

function Message({ tone, text }) {
  const theme = useTheme();
  const warn = tone === 'warn';
  // 노랑은 거래 보호 전용이라, 확인이 필요한 문구는 무채색 면에 잉크 글자와 아이콘 모양으로 구분한다.
  return (
    <View style={[styles.message, { backgroundColor: theme.raised }]} accessibilityLiveRegion="polite">
      <Ionicons name={warn ? 'alert-circle' : 'checkmark-circle'} size={18} color={warn ? theme.ink : theme.inkSoft} />
      <Txt variant="callout" style={{ flex: 1 }}>{text}</Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  scrim: { ...StyleSheet.absoluteFillObject },
  sheetWrap: { flex: 1, justifyContent: 'flex-end' },
  sheet: { borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS, maxHeight: '92%', paddingHorizontal: SPACE.lg },
  grabber: { alignSelf: 'center', width: 40, height: 5, borderRadius: 3, marginTop: SPACE.sm },
  head: { flexDirection: 'row', alignItems: 'center', paddingTop: SPACE.sm },
  shrink: { flex: 1, minWidth: 0 },
  close: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  body: { gap: SPACE.md, paddingVertical: SPACE.md },
  segment: { flexDirection: 'row', borderRadius: RADIUS, padding: 4, gap: 4 },
  segmentItem: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
  field: { gap: 6 },
  fieldHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: SPACE.sm, flexWrap: 'wrap' },
  stepper: { flexDirection: 'row', alignItems: 'center', minHeight: 52, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: 4 },
  stepButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  stepInput: { flex: 1, minHeight: 48, textAlign: 'center', fontFamily: NUMBER_FONT, fontWeight: '600', fontSize: TYPE.headline.fontSize, fontVariant: ['tabular-nums'] },
  qtyInput: { textAlign: 'right', paddingRight: SPACE.sm },
  unit: { paddingRight: SPACE.md },
  marketNote: { paddingHorizontal: 2 },
  ratios: { flexDirection: 'row', gap: 6 },
  ratio: { flex: 1, minHeight: 44, borderWidth: 1, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
  summary: { borderRadius: RADIUS, padding: SPACE.md, gap: 6 },
  summaryRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  message: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, padding: SPACE.md },
  submit: { minHeight: 54, borderRadius: RADIUS, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm, marginTop: SPACE.xs },
});
