import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Card, CardHead, Empty, Row, Segment, SideTag, kit } from '../../components/Kit';
import { countdown, impliedVolatility, indexText, legExitValue, optionDelta, optionFee, useDerivatives, useNow } from '../../derivatives';
import { api } from '../../market';
import { signedPercent, signedWon, won } from '../../format';
import { RADIUS_SM, SPACE, Txt, toneOf, useTheme } from '../../theme';
import { Fact, OrderNotice, Stepper, TradeList, pnlTone, useDerivativeOrder } from './parts';
import { UnlockNotice } from '../../components/Engagement';
import { useDeskIntro } from '../../components/Mentor';

// 만기 손익을 보여 줄 지수 위치. 지금 지수에서 ±3%까지 1%씩.
const MOVES = [-0.03, -0.02, -0.01, 0, 0.01, 0.02, 0.03];
const FLOW_POLL_MS = 15_000;
const expiryText = epoch => {
  const at = new Date(epoch * 1000 + 9 * 60 * 60_000);
  const pad = value => String(value).padStart(2, '0');
  return `${at.getUTCMonth() + 1}.${at.getUTCDate()} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`;
};

/**
 * 10분·1시간·일요일 18:00 만기 옵션. 웹 OptionsPanel처럼 만기 → 콜/풋 → 행사가 순으로 고르고,
 * 고른 계약(또는 보유 계약)의 만기 손익을 지수 위치별로 미리 본다.
 */
export function OptionsView({ onLogin, onOpenCareer, contentStyle }) {
  const theme = useTheme();
  useDeskIntro('OPTIONS');
  const { snapshot, account, error, signedIn, setAccount } = useDerivatives();
  const order = useDerivativeOrder({ signedIn, setAccount, onLogin });
  const now = useNow(1000);
  const [cycleId, setCycleId] = useState('10M');
  const [side, setSide] = useState('CALL');
  const [strikeChoice, setStrikeChoice] = useState(null);
  const [quantity, setQuantity] = useState('1');
  const [focusId, setFocusId] = useState(null);
  const flow = useOptionFlow();
  const volatility = useMemo(() => (snapshot ? impliedVolatility(snapshot) : 0.8), [snapshot]);

  if (!snapshot) {
    return (
      <View style={styles.center}>
        {error ? <Txt variant="callout" tone="inkSoft">옵션 시세를 불러오지 못했어요. 2초마다 다시 불러와요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}
      </View>
    );
  }

  const spot = snapshot.indexValue;
  const cycle = snapshot.cycles.find(item => item.id === cycleId) ?? snapshot.cycles[0];
  const call = side === 'CALL';
  const chain = cycle ? snapshot.options.filter(contract => contract.cycleId === cycle.id && contract.type === side).sort((a, b) => a.strike - b.strike) : [];
  const atTheMoney = chain.reduce((best, contract) => (best === null || Math.abs(contract.strike - spot) < Math.abs(best - spot) ? contract.strike : best), null);
  // 고른 행사가가 새 회차에 없으면 등가격으로 돌아간다. 늘 한 계약을 고른 상태로 둔다.
  const selected = chain.find(contract => contract.strike === strikeChoice) ?? chain.find(contract => contract.strike === atTheMoney);
  const spotIndex = chain.findIndex(contract => contract.strike > spot);
  const positions = account?.positions.filter(position => position.kind === 'OPTION') ?? [];
  const trades = account?.trades.filter(trade => trade.kind === 'OPTION') ?? [];
  const focused = positions.find(position => position.id === focusId) ?? null;
  const count = Number(quantity);
  const valid = Number.isInteger(count) && count >= 1 && count <= 100;
  const premium = selected ? selected.premium * (valid ? count : 0) : 0;
  const fee = optionFee(premium);

  const leg = focused?.expiryAt
    ? { call: focused.optionType === 'CALL', strike: focused.strike, expiryEpoch: Date.parse(focused.expiryAt) / 1000, quantity: focused.quantity, cost: focused.entryValue, label: `보유 · ${focused.name}` }
    : selected ? { call, strike: selected.strike, expiryEpoch: selected.expiryEpoch, quantity: valid ? count : 1, cost: (selected.premium * (valid ? count : 1)) + optionFee(selected.premium * (valid ? count : 1)), label: '주문 전 미리 보기' } : null;

  return (
    <ScrollView contentContainerStyle={contentStyle} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets>
      <View style={styles.hero}>
        <Txt variant="caption" tone="inkSoft">게임주 지수</Txt>
        <Txt variant="price" numeric>{indexText(spot)}</Txt>
        <Txt variant="callout" tone={toneOf(snapshot.indexChangePercent)} numeric>{`오늘 ${signedPercent(snapshot.indexChangePercent / 100)}`}</Txt>
      </View>
      {error ? <OrderNotice notice={{ ok: false, text: '시세 갱신이 늦어져 이전 시세를 보여 주고 있어요.' }} /> : null}
      <UnlockNotice feature="OPTIONS" onOpenCareer={onOpenCareer} />

      <Card>
        <CardHead title="만기" meta={cycle ? `${countdown(cycle.expiryEpoch, now)} 남음` : null} />
        <Segment options={snapshot.cycles.map(item => ({ key: item.id, label: item.label }))} value={cycle?.id} onChange={key => { setCycleId(key); setFocusId(null); }} label="옵션 만기 회차" />
        {cycle ? <Txt variant="caption" tone="inkSoft" numeric>{`${cycle.tag} · ${expiryText(cycle.expiryEpoch)} 만기(한국시간)`}</Txt> : null}
      </Card>

      <View style={styles.sides}>
        <Segment options={[{ key: 'CALL', label: '콜 · 오르면 이익' }, { key: 'PUT', label: '풋 · 내리면 이익' }]} value={side} onChange={key => { setSide(key); setFocusId(null); }} label="옵션 종류" />
      </View>

      <Card padded={false}>
        <CardHead title="옵션 체인" meta="행사가 · 손익분기까지 · 프리미엄" padded />
        {chain.length === 0 ? <Empty text="이 회차에 거래할 수 있는 계약이 없어요." /> : chain.map((contract, index) => {
          const inTheMoney = call ? contract.strike < spot : contract.strike > spot;
          const active = !focused && contract.strike === selected?.strike;
          const delta = optionDelta(spot, contract.strike, contract.secondsLeft, volatility, call);
          const distance = contract.breakeven / spot - 1;
          return (
            <React.Fragment key={contract.id}>
              {index === spotIndex ? <SpotLine spot={spot} /> : null}
              <Row
                divided={index > 0 && index !== spotIndex}
                onPress={() => { setStrikeChoice(contract.strike); setFocusId(null); }}
                accessibilityLabel={`행사가 ${indexText(contract.strike)}${contract.strike === atTheMoney ? ', 등가격' : inTheMoney ? ', 내가격' : ''}. 손익분기까지 ${signedPercent(distance)}, 델타 ${delta.toFixed(2)}, 프리미엄 ${won(contract.premium)}${active ? ', 고름' : ''}`}
                style={active && { backgroundColor: theme.raised }}
              >
                <View style={kit.grow}>
                  <View style={styles.strikeLine}>
                    <Txt variant="callout" numeric>{indexText(contract.strike)}</Txt>
                    {contract.strike === atTheMoney ? <Txt variant="micro" tone="inkSoft">등가격</Txt> : inTheMoney ? <Txt variant="micro" tone="inkSoft">내가격</Txt> : null}
                  </View>
                  <Txt variant="caption" tone="inkSoft" numeric>{`손익분기까지 ${signedPercent(distance)} · 델타 ${delta.toFixed(2)}`}</Txt>
                </View>
                <View style={kit.right}>
                  <Txt variant="callout" numeric>{won(contract.premium)}</Txt>
                  <Txt variant="micro" tone="inkSoft" numeric>{`${contract.mark.toFixed(2)}p`}</Txt>
                </View>
              </Row>
            </React.Fragment>
          );
        })}
        {chain.length > 0 && spotIndex === -1 ? <SpotLine spot={spot} /> : null}
        <Txt variant="caption" tone="inkSoft" style={styles.note}>프리미엄은 1계약(지수 1포인트 × 1,000원) 기준이에요. 손익분기까지는 만기에 본전이 되려면 지수가 지금보다 얼마나 움직여야 하는지예요.</Txt>
      </Card>

      {selected ? (
        <Card>
          <CardHead title={`${call ? '콜' : '풋'} ${indexText(selected.strike)}`} meta={cycle?.label} />
          <Fact label="1계약 프리미엄" value={won(selected.premium)} />
          <Fact label="만기 손익분기 지수" value={indexText(selected.breakeven)} />
          <Stepper label="계약 수" value={quantity} onChange={setQuantity} unit="계약" />
          <Fact label="프리미엄 합계" value={won(premium)} />
          <Fact label="수수료 0.3%" value={won(fee)} />
          <Fact label="바로 빠질 현금(최대 손실)" value={won(premium + fee)} strong />
          <Button
            label={!signedIn ? '로그인하고 주문하기' : `${call ? '콜' : '풋'} ${valid ? count : ''}계약 매수`}
            kind={signedIn ? (call ? 'buy' : 'sell') : 'primary'}
            busy={order.busy}
            disabled={signedIn && !valid}
            onPress={() => order.open({ kind: 'OPTION', side, quantity: count, cycleId: selected.cycleId, strike: selected.strike, expiryEpoch: selected.expiryEpoch })}
          />
          <OrderNotice notice={order.notice} />
          <Txt variant="caption" tone="inkSoft">만기에는 내재가치로 현금 결제되고, 그 전에는 지금 프리미엄으로 청산할 수 있어요.</Txt>
        </Card>
      ) : null}

      {leg ? <Payoff leg={leg} spot={spot} volatility={volatility} onReset={focused ? () => setFocusId(null) : null} /> : null}

      <Flow flow={flow} />

      <Card padded={false}>
        <CardHead title="보유 옵션" meta={positions.length ? `${positions.length}건` : null} padded />
        {!signedIn ? <Empty text="로그인하면 보유 옵션을 볼 수 있어요." action="로그인" onAction={onLogin} />
          : !account ? <Empty loading text="계좌를 확인하는 중이에요" />
          : positions.length === 0 ? <Empty text="보유 중인 옵션이 없어요." />
          : positions.map((position, index) => (
            <Row key={position.id} divided={index > 0} style={position.id === focusId && { backgroundColor: theme.raised }}>
              <SideTag buy={position.optionType === 'CALL'} label={position.optionType === 'CALL' ? '콜' : '풋'} />
              <View style={kit.grow}>
                <Txt variant="callout" numberOfLines={1}>{position.name}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{`${position.quantity}계약 · ${position.entryPrice.toFixed(2)}p → ${position.markPrice.toFixed(2)}p${position.expiryAt ? ` · ${countdown(Date.parse(position.expiryAt) / 1000, now)} 남음` : ''}`}</Txt>
                <Txt variant="caption" tone={pnlTone(position.unrealizedPnl)} numeric>{`${signedWon(position.unrealizedPnl)} (${signedPercent(position.unrealizedPercent / 100)})`}</Txt>
              </View>
              <View style={styles.positionActions}>
                <Button label="계산" small disabled={!position.expiryAt} onPress={() => setFocusId(position.id)} accessibilityLabel={`${position.name} 만기 손익 보기`} />
                <Button label="청산" small busy={order.busy} onPress={() => order.close(position.id, position.quantity)} accessibilityLabel={`${position.name} ${position.quantity}계약 전량 청산`} />
              </View>
            </Row>
          ))}
      </Card>

      {trades.length ? <TradeList title="최근 옵션 체결" trades={trades} emptyText="" /> : null}
      <Txt variant="caption" tone="inkSoft" style={styles.disclaimer}>게임주 지수를 따르는 모의 옵션이에요. 실제 금융상품이 아니에요.</Txt>
    </ScrollView>
  );
}

function SpotLine({ spot }) {
  const theme = useTheme();
  return (
    <View style={[styles.spot, { backgroundColor: theme.brandWash }]} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
      <Txt variant="micro" style={{ color: theme.brandText }} numeric>{`현재 지수 ${indexText(spot)}`}</Txt>
    </View>
  );
}

/** 만기에 지수가 어디서 끝나면 얼마를 벌고 잃는지. 웹 수익 계산기의 만기 열과 같은 값이다. */
function Payoff({ leg, spot, volatility, onReset }) {
  const rows = MOVES.map(move => {
    const level = spot * (1 + move);
    const profit = legExitValue(leg, level, leg.expiryEpoch, volatility) - leg.cost;
    return { move, level, profit };
  });
  return (
    <Card padded={false}>
      <CardHead title="만기 손익 미리 보기" meta={`${leg.call ? '콜' : '풋'} ${indexText(leg.strike)} × ${leg.quantity}`} padded />
      <View style={styles.payoffHead}>
        <Txt variant="caption" tone="inkSoft" style={kit.grow}>{leg.label}</Txt>
        {onReset ? <Button label="고른 계약으로" small onPress={onReset} /> : null}
      </View>
      {rows.map((row, index) => (
        <Row key={row.move} divided={index > 0} accessibilityLabel={`만기 지수 ${indexText(row.level)}(${signedPercent(row.move)})이면 ${signedWon(row.profit)}`}>
          <View style={kit.grow}>
            <Txt variant="callout" numeric>{indexText(row.level)}</Txt>
            <Txt variant="caption" tone="inkSoft" numeric>{row.move === 0 ? '지금 지수 그대로' : `지금보다 ${signedPercent(row.move)}`}</Txt>
          </View>
          <Txt variant="callout" tone={pnlTone(row.profit)} numeric>{signedWon(row.profit)}</Txt>
        </Row>
      ))}
    </Card>
  );
}

/** 최근 1시간 옵션 거래 흐름. 서버가 5초씩 묶어 주므로 15초마다 다시 받는다. */
function useOptionFlow() {
  const [flow, setFlow] = useState(null);
  useEffect(() => {
    let alive = true;
    const read = () => api('/api/derivatives/options/flow?range=1h&interval=1m')
      .then(next => { if (alive) setFlow(next); })
      .catch(() => { if (alive) setFlow(current => current ?? false); });
    void read();
    const timer = setInterval(read, FLOW_POLL_MS);
    return () => { alive = false; clearInterval(timer); };
  }, []);
  return flow;
}

function Flow({ flow }) {
  const theme = useTheme();
  const total = flow ? flow.callVolume + flow.putVolume : 0;
  const callShare = total > 0 ? flow.callVolume / total : 0.5;
  return (
    <Card>
      <CardHead title="최근 1시간 거래 흐름" />
      {flow === null ? <Txt variant="caption" tone="inkSoft">거래 흐름을 불러오는 중이에요</Txt> : !flow ? <Txt variant="caption" tone="inkSoft">거래 흐름을 불러오지 못했어요. 15초마다 다시 불러와요.</Txt> : total === 0 ? <Txt variant="caption" tone="inkSoft">최근 1시간 동안 옵션 거래가 없었어요.</Txt> : (
        <>
          <View style={styles.flowBar} accessible accessibilityLabel={`콜 ${flow.callVolume}계약, 풋 ${flow.putVolume}계약`}>
            <View style={{ flex: callShare, backgroundColor: theme.upFill }} />
            <View style={{ flex: 1 - callShare, backgroundColor: theme.downFill }} />
          </View>
          <Fact label="콜 거래량" value={`${flow.callVolume.toLocaleString('ko-KR')}계약 · ${won(flow.callPremium)}`} />
          <Fact label="풋 거래량" value={`${flow.putVolume.toLocaleString('ko-KR')}계약 · ${won(flow.putPremium)}`} />
          <Fact label="순 프리미엄(콜 − 풋)" value={signedWon(flow.netPremium)} tone={pnlTone(flow.netPremium)} />
        </>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.xl },
  hero: { paddingHorizontal: SPACE.xs, paddingBottom: SPACE.lg, gap: 2 },
  sides: { marginBottom: SPACE.md },
  strikeLine: { flexDirection: 'row', alignItems: 'baseline', gap: SPACE.sm },
  spot: { paddingHorizontal: SPACE.lg + 2, paddingVertical: 4 },
  note: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  payoffHead: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.sm },
  positionActions: { gap: SPACE.xs },
  flowBar: { flexDirection: 'row', height: 12, borderRadius: RADIUS_SM, overflow: 'hidden' },
  disclaimer: { textAlign: 'center', marginTop: SPACE.sm },
});
