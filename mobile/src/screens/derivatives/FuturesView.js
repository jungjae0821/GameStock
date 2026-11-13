import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useAuth } from '../../auth';
import { CandleChart } from '../../components/CandleChart';
import { Button, Card, CardHead, Empty, Row, Segment, SideTag, kit } from '../../components/Kit';
import { FUTURE_FEE_RATE, futureCost, indexText, liquidationIndex, useDerivatives } from '../../derivatives';
import { api } from '../../market';
import { signedPercent, signedWon, won } from '../../format';
import { RADIUS_SM, SPACE, Txt, toneOf, useTheme } from '../../theme';
import { Fact, OrderNotice, Stepper, TradeList, pnlTone, useDerivativeOrder } from './parts';
import { UnlockNotice } from '../../components/Engagement';
import { useDeskIntro } from '../../components/Mentor';

const MAX_CONTRACTS = 100;
const RANGES = [
  { key: '1h', label: '1분봉' },
  { key: '6h', label: '5분봉' },
  { key: '1d', label: '30분봉' },
  { key: '3d', label: '1시간봉' },
];
const RATIOS = [{ label: '10%', ratio: 0.1 }, { label: '25%', ratio: 0.25 }, { label: '50%', ratio: 0.5 }, { label: '최대', ratio: 1 }];
const signedIndex = value => `${value >= 0 ? '+' : '−'}${indexText(Math.abs(value))}`;

/** 게임주 지수 선물(무기한). 웹 FuturesTerminal과 같은 순서: 시세·차트, 주문, 내 포지션, 상품 정보. */
export function FuturesView({ onLogin, onOpenCareer, contentStyle }) {
  const theme = useTheme();
  useDeskIntro('FUTURES');
  const { profile } = useAuth();
  const { snapshot, account, error, signedIn, setAccount } = useDerivatives();
  const order = useDerivativeOrder({ signedIn, setAccount, onLogin });
  const [range, setRange] = useState('1h');
  const [candles, setCandles] = useState({ range: null, bars: null, error: false });
  const [side, setSide] = useState('LONG');
  const [leverage, setLeverage] = useState(null);
  const [quantity, setQuantity] = useState('1');
  const [tab, setTab] = useState('positions');

  useEffect(() => {
    let cancelled = false;
    setCandles(current => ({ range, bars: current.range === range ? current.bars : null, error: false }));
    const read = () => api(`/api/derivatives/futures/chart?range=${range}`)
      .then(bars => { if (!cancelled) setCandles({ range, bars, error: false }); })
      .catch(() => { if (!cancelled) setCandles(current => ({ ...current, error: true })); });
    void read();
    const timer = setInterval(read, 10_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [range]);

  const future = snapshot?.future;
  if (!future) {
    return (
      <View style={styles.center}>
        {error ? <Txt variant="callout" tone="inkSoft">선물 시세를 불러오지 못했어요. 2초마다 다시 불러와요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}
      </View>
    );
  }

  const lev = leverage ?? (future.leverages.includes(5) ? 5 : future.leverages[0] ?? 1);
  const count = Number(quantity);
  const valid = Number.isInteger(count) && count >= 1 && count <= MAX_CONTRACTS;
  const cost = futureCost(future, lev, valid ? count : 0);
  const unit = futureCost(future, lev, 1);
  const cash = account?.cash ?? null;
  const maxQty = cash === null ? 0 : Math.min(MAX_CONTRACTS, Math.floor(cash / (unit.margin + unit.fee)));
  const affordable = cash === null || cost.margin + cost.fee <= cash;
  const long = side === 'LONG';
  const sideLabel = long ? '롱' : '숏';
  const adminBlocked = Boolean(profile?.admin);
  const positions = account?.positions.filter(position => position.kind === 'FUTURE') ?? [];
  const fills = account?.trades.filter(trade => trade.kind === 'FUTURE') ?? [];
  const ratio = future.changePercent / 100;
  const margin = positions.reduce((sum, position) => sum + position.margin, 0);
  const pnl = positions.reduce((sum, position) => sum + position.unrealizedPnl, 0);
  const maintenance = Math.round(future.maintenanceRate * 100);

  const submitLabel = !signedIn ? '로그인하고 주문하기' : adminBlocked ? '관리자 계정은 거래할 수 없어요'
    : valid ? `${count}계약 ${sideLabel} 열기 · ${won(cost.margin + cost.fee)}` : `${sideLabel} 주문`;

  return (
    <ScrollView contentContainerStyle={contentStyle} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets>
      <View style={styles.hero}>
        <Txt variant="caption" tone="inkSoft" numeric>{`${future.code} · 무기한 · 게임주 지수 추종`}</Txt>
        <Txt variant="title" accessibilityRole="header">{future.name}</Txt>
        <Txt variant="price" numeric style={{ marginTop: SPACE.sm }}>{indexText(future.price)}</Txt>
        <Txt variant="callout" tone={toneOf(ratio)} numeric>{`오늘 ${signedPercent(ratio)} (${signedIndex(future.price - future.prevClose)})`}</Txt>
      </View>
      {error ? <OrderNotice notice={{ ok: false, text: '시세 갱신이 늦어져 이전 시세를 보여 주고 있어요.' }} /> : null}
      <UnlockNotice feature="FUTURES" onOpenCareer={onOpenCareer} />

      <Card>
        <View style={styles.ranges} accessibilityRole="tablist" accessibilityLabel="봉 간격">
          {RANGES.map(item => {
            const selected = range === item.key;
            return (
              <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => setRange(item.key)} style={[styles.range, selected && { backgroundColor: theme.raised }]}>
                <Txt variant="caption" tone={selected ? 'ink' : 'inkSoft'}>{item.label}</Txt>
              </Pressable>
            );
          })}
        </View>
        {candles.bars == null ? (
          <View style={styles.chartLoading}>{candles.error ? <Txt variant="caption" tone="inkSoft">봉 기록을 불러오지 못했어요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}</View>
        ) : <CandleChart candles={candles.bars} reference={future.prevClose} format={indexText} />}
      </Card>

      <Card>
        <CardHead title="주문" meta={`1계약 증거금 ${won(unit.margin)}`} />
        <Segment options={[{ key: 'LONG', label: '롱 · 오르면 이익' }, { key: 'SHORT', label: '숏 · 내리면 이익' }]} value={side} onChange={setSide} label="포지션 방향" />
        <Txt variant="caption" tone="inkSoft">레버리지</Txt>
        <Segment options={future.leverages.map(item => ({ key: String(item), label: `${item}배` }))} value={String(lev)} onChange={value => setLeverage(Number(value))} label="레버리지" />
        <Stepper label="계약 수" value={quantity} onChange={setQuantity} max={MAX_CONTRACTS} unit="계약" />
        {signedIn ? (
          <View style={styles.ratios} accessibilityLabel="현금 대비 계약 수">
            {RATIOS.map(item => (
              <Button key={item.label} label={item.label} small disabled={maxQty === 0} style={kit.grow}
                accessibilityLabel={`현금의 ${item.label}만큼 계약 수 채우기`}
                onPress={() => setQuantity(String(Math.max(1, Math.floor(maxQty * item.ratio))))} />
            ))}
          </View>
        ) : null}
        {valid && !affordable ? <Txt variant="caption" tone="up">{`지금 현금으로는 최대 ${maxQty.toLocaleString('ko-KR')}계약까지 열 수 있어요.`}</Txt> : null}
        <Fact label="주문 가능 현금" value={cash === null ? (signedIn ? '확인 중' : '로그인 후 확인') : won(cash)} />
        <Fact label="계약 명목가" value={won(cost.notional)} />
        <Fact label={`수수료 ${(FUTURE_FEE_RATE * 100).toFixed(2)}%`} value={won(cost.fee)} />
        <Fact label="강제청산 예상 지수" value={valid ? indexText(liquidationIndex(future.price, cost.margin, count, long, future)) : '-'} />
        <Fact label="필요 증거금" value={won(cost.margin)} strong />
        <Button
          label={submitLabel}
          kind={signedIn && !adminBlocked ? (long ? 'buy' : 'sell') : 'primary'}
          busy={order.busy}
          disabled={signedIn && (adminBlocked || !valid || !affordable)}
          onPress={() => order.open({ kind: 'FUTURE', side, symbol: future.code, leverage: lev, quantity: count })}
        />
        <OrderNotice notice={order.notice} />
      </Card>

      <View style={styles.tabs}>
        <Segment options={[{ key: 'positions', label: `보유 포지션${positions.length ? ` ${positions.length}` : ''}` }, { key: 'fills', label: '체결 내역' }]} value={tab} onChange={setTab} label="포지션 내역" />
      </View>
      {tab === 'positions' ? (
        <Card padded={false}>
          {!signedIn ? <Empty text="로그인하면 선물 포지션을 볼 수 있어요." action="로그인" onAction={onLogin} />
            : !account ? <Empty loading text="계좌를 확인하는 중이에요" />
            : positions.length === 0 ? <Empty text="열려 있는 선물 포지션이 없어요." />
            : positions.map((position, index) => {
              const isLong = position.side === 'LONG';
              return (
                <Row key={position.id} divided={index > 0}>
                  <SideTag buy={isLong} label={`${isLong ? '롱' : '숏'} ${position.leverage}배`} />
                  <View style={kit.grow}>
                    <Txt variant="callout" numeric>{`${position.quantity}계약 · 진입 ${indexText(position.entryPrice)}`}</Txt>
                    <Txt variant="caption" tone="inkSoft" numeric>{`강제청산 ${indexText(liquidationIndex(position.entryPrice, position.margin, position.quantity, isLong, future))} · 증거금 ${won(position.margin)}`}</Txt>
                    <Txt variant="caption" tone={pnlTone(position.unrealizedPnl)} numeric>{`${signedWon(position.unrealizedPnl)} (${signedPercent(position.unrealizedPercent / 100)})`}</Txt>
                  </View>
                  <Button label="청산" small busy={order.busy} onPress={() => order.close(position.id, position.quantity)} accessibilityLabel={`${isLong ? '롱' : '숏'} ${position.leverage}배 ${position.quantity}계약 전량 청산`} />
                </Row>
              );
            })}
        </Card>
      ) : !signedIn ? (
        <Card padded={false}><Empty text="로그인하면 체결 내역을 볼 수 있어요." action="로그인" onAction={onLogin} /></Card>
      ) : <TradeList title="선물 체결" trades={fills} emptyText="선물 체결 내역이 아직 없어요." />}

      {signedIn && account ? (
        <Card>
          <CardHead title="선물 계좌" />
          <Fact label="묶인 증거금" value={won(margin)} />
          <Fact label="선물 평가손익" value={signedWon(pnl)} tone={pnlTone(pnl)} />
          <Txt variant="caption" tone="inkSoft" numeric>{`계좌 전체 누적 확정 손익 ${signedWon(account.realizedPnl)}, 수수료 ${won(account.totalFees)}`}</Txt>
        </Card>
      ) : null}

      <Card>
        <CardHead title="상품 정보" />
        <Fact label="기준 지수" value={indexText(future.prevClose)} />
        <Fact label="계약 단위" value={`1pt × ${won(future.multiplier)}`} />
        <Fact label="1계약 명목가" value={won(Math.round(future.price * future.multiplier))} />
        <Fact label="만기" value="없음 · 무기한" />
        <Fact label="레버리지" value={future.leverages.map(item => `${item}배`).join(' · ')} />
        <Fact label="유지증거금" value={`${maintenance}%`} />
        <Txt variant="caption" tone="inkSoft">{`지수가 오를 것 같으면 롱, 내릴 것 같으면 숏을 열어요. 명목가를 레버리지로 나눈 만큼만 현금에서 빠지고, 지수 1포인트는 1계약당 ${won(future.multiplier)}이에요. 만기가 없어 원할 때 청산하고, 평가액이 증거금의 ${maintenance}% 아래로 내려가면 자동으로 청산돼요.`}</Txt>
      </Card>
      <Txt variant="caption" tone="inkSoft" style={styles.disclaimer}>게임주 지수를 따르는 모의 선물이에요. 실제 금융상품이 아니에요.</Txt>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.xl },
  hero: { paddingHorizontal: SPACE.xs, paddingBottom: SPACE.lg, gap: 2 },
  ranges: { flexDirection: 'row', gap: 4 },
  range: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
  chartLoading: { minHeight: 120, alignItems: 'center', justifyContent: 'center' },
  tabs: { marginBottom: SPACE.md },
  ratios: { flexDirection: 'row', gap: SPACE.xs },
  disclaimer: { textAlign: 'center', marginTop: SPACE.sm },
});
