import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import { useAccount } from '../../account';
import { request } from '../../auth';
import { GameIcon } from '../../components/GameIcon';
import { AccountFrame, Button, Card, CardHead, Empty, Figure, Notice, Row, SideTag, kit, kstShort } from '../../components/Kit';
import { ShareResultButton } from '../../components/Engagement';
import { price, signedPercent, signedWon, won } from '../../format';
import { sessionRate } from '../../market';
import { SPACE, Txt, toneOf, useTheme } from '../../theme';

// 웹 INITIAL_CASH와 같은 시작 자금. 손익은 이 값과 출석·미션 보상을 빼고 계산한다(랭킹 수익률과 같은 기준).
const INITIAL_CASH = 1_000_000;
const FILL_COUNT = 20;

/** 서버 계좌 값을 화면에서 쓰는 합계로 바꾼다. 웹 selectors.totals와 같은 식이다. */
function summarize(portfolio, quotes) {
  const positions = (portfolio?.positions ?? []).filter(position => position.quantity > 0);
  const rows = positions.map(position => {
    const quote = quotes[position.stockCode];
    const current = quote?.price ?? position.averagePrice;
    const value = current * position.quantity;
    const cost = position.averagePrice * position.quantity;
    return { code: position.stockCode, name: quote?.name ?? position.stockCode, qty: position.quantity, avg: position.averagePrice, current, value, pnl: value - cost, rate: cost > 0 ? (value - cost) / cost : 0, quote };
  }).sort((a, b) => b.value - a.value);
  const cash = Number(portfolio?.cash) || 0;
  const stockValue = rows.reduce((sum, row) => sum + row.value, 0);
  const total = cash + stockValue;
  const loan = Number(portfolio?.loanBalance) || 0;
  const reward = (Number(portfolio?.attendanceRewardCash) || 0) + (Number(portfolio?.missionRewardCash) || 0);
  // 승진 충전·관리자 지급은 거래 밖에서 들어온 돈이라 손익에서 빼고, 수익률은 맡은 돈 전체에 대해 잰다.
  const outside = Number(portfolio?.adminAdjustmentCash) || 0;
  const net = total - loan;
  const pnl = net - INITIAL_CASH - reward - outside;
  return { rows, cash, stockValue, total, loan, net, pnl, pnlRate: pnl / Math.max(1, INITIAL_CASH + outside), realized: Number(portfolio?.realizedProfitLoss) || 0 };
}

/**
 * 자산 구성 막대. 현금은 핑크, 종목은 잉크에서 무대 쪽으로 내려가는 무채색 다섯 단계다.
 * 색 수를 늘리지 않고 비중만 읽히게 한다(DESIGN.md 자산 구성 막대).
 */
function Allocation({ money }) {
  const theme = useTheme();
  if (money.total <= 0) return null;
  const parts = [
    ...money.rows.map((row, index) => ({ key: row.code, label: row.name, value: row.value, color: theme.alloc[index % 5] })),
    { key: 'cash', label: '현금', value: money.cash, color: theme.brand },
  ].filter(part => part.value > 0);
  const share = value => (value / money.total) * 100;
  return (
    <Card>
      <CardHead title="자산 구성" />
      <View style={styles.allocBar} accessible accessibilityLabel={parts.map(part => `${part.label} ${Math.round(share(part.value))}%`).join(', ')}>
        {parts.map(part => <View key={part.key} style={{ flexGrow: part.value, minWidth: 3, backgroundColor: part.color }} />)}
      </View>
      <View style={styles.legend} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        {parts.map(part => (
          <View key={part.key} style={styles.legendItem}>
            <View style={[styles.legendDot, { backgroundColor: part.color, borderColor: theme.line }]} />
            <Txt variant="caption" tone="inkSoft" numberOfLines={1}>{part.label}</Txt>
            <Txt variant="caption" numeric>{`${share(part.value).toFixed(1)}%`}</Txt>
          </View>
        ))}
      </View>
    </Card>
  );
}

/** 내 계좌. 할 일은 하나다: 내 돈이 어떻게 됐는지 확인하고, 걸어 둔 주문을 정리한다. */
export function MyAccountScreen({ market, onBack, onGo }) {
  const account = useAccount();
  const quotes = useMemo(() => Object.fromEntries(market.stocks.map(stock => [stock.code, stock])), [market.stocks]);
  const money = summarize(account.portfolio, quotes);
  const [orders, setOrders] = useState(null);
  const [ordersFailed, setOrdersFailed] = useState(false);
  const [fills, setFills] = useState(null);
  const [fillsFailed, setFillsFailed] = useState(false);
  const [cancelling, setCancelling] = useState(null);
  const [message, setMessage] = useState(null);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const loadOrders = useCallback(async () => {
    try {
      const next = await request('/api/orders');
      if (!alive.current) return;
      setOrders(next.filter(order => order.status === 'OPEN' || order.status === 'PARTIAL'));
      setOrdersFailed(false);
    } catch {
      if (alive.current) setOrdersFailed(true);
    }
  }, []);
  const loadFills = useCallback(async () => {
    try {
      const next = await request('/api/settlements');
      if (!alive.current) return;
      setFills(next);
      setFillsFailed(false);
    } catch {
      // 받은 내역이 있으면 그대로 두고, 한 번도 못 받았으면 실패로 보인다(빈 내역으로 바꾸지 않는다).
      if (alive.current) setFillsFailed(true);
    }
  }, []);

  // 미체결 주문은 체결·만료로 자주 바뀌어 3초마다, 체결 내역은 15초마다 다시 읽는다.
  useEffect(() => {
    void loadOrders();
    void loadFills();
    const orderTimer = setInterval(() => void loadOrders(), 3000);
    const fillTimer = setInterval(() => void loadFills(), 15_000);
    return () => { clearInterval(orderTimer); clearInterval(fillTimer); };
  }, [loadOrders, loadFills]);

  const cancel = order => {
    const name = quotes[order.stockCode]?.name ?? order.stockCode;
    Alert.alert('주문을 취소할까요?', `${name} ${order.side === 'BUY' ? '매수' : '매도'} ${order.remainingQuantity.toLocaleString('ko-KR')}주`, [
      { text: '그대로 두기', style: 'cancel' },
      {
        text: '주문 취소', style: 'destructive', onPress: async () => {
          setCancelling(order.id);
          setMessage(null);
          try {
            await request(`/api/orders/${order.id}`, { method: 'DELETE' });
            setOrders(current => (current ?? []).filter(item => item.id !== order.id));
            setMessage({ text: '미체결 주문을 취소했어요.', ok: true });
            void account.reload();
          } catch (error) {
            setMessage({ text: error.message, ok: false });
          } finally {
            setCancelling(null);
          }
        },
      },
    ]);
  };

  const refresh = async () => { await Promise.all([account.reload(), loadOrders(), loadFills()]); };

  if (!account.portfolio) {
    return (
      <AccountFrame title="내 계좌" onBack={onBack} onRefresh={refresh}>
        <Card>
          <Empty loading={account.status !== 'error'} text={account.status === 'error' ? '계좌를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.' : '계좌를 불러오는 중이에요'} />
        </Card>
      </AccountFrame>
    );
  }

  return (
    <AccountFrame title="내 계좌" onBack={onBack} onRefresh={refresh}>
      {/* 순자산은 이 화면의 답이라 카드 없이 바탕 위에 크게 둔다(홈의 지수와 같은 자리). */}
      <View style={kit.hero} accessible accessibilityLabel={`${money.loan > 0 ? '순자산' : '총 자산'} ${won(money.net)}, 시작 자금 대비 ${signedWon(money.pnl)}`}>
        <Txt variant="caption" tone="inkSoft">{money.loan > 0 ? '순자산' : '총 자산'}</Txt>
        <Txt variant="price" numeric numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>{won(money.net)}</Txt>
        <Txt variant="callout" tone={toneOf(money.pnl)} numeric>{`${signedWon(money.pnl)} (${signedPercent(money.pnlRate)})`}</Txt>
        <Txt variant="caption" tone="inkSoft">시작 자금과 승진 충전을 더한 금액 대비</Txt>
      </View>
      {/* 위 숫자 묶음은 스크린리더가 한 번에 읽는 덩어리라, 누를 수 있는 공유 버튼은 그 밖에 둔다. */}
      <View style={styles.share}>
        <ShareResultButton pnl={money.pnl} pnlRate={money.pnlRate} stocks={market.stocks}
          best={money.rows.length ? (([top]) => ({ name: top.name, pnlRate: top.rate }))([...money.rows].sort((a, b) => b.pnl - a.pnl)) : null} />
      </View>

      <Card>
        <View style={styles.figures}>
          <Figure label="현금" value={won(money.cash)} />
          <Figure label="주식 평가액" value={won(money.stockValue)} />
          <Figure label="실현 손익" value={signedWon(money.realized)} tone={toneOf(money.realized)} />
        </View>
        {money.loan > 0 ? (
          <View style={kit.between}>
            <Figure label="대출 잔액" value={`−${won(money.loan)}`} />
            <Button label="대출 관리" small onPress={() => onGo('/loan')} />
          </View>
        ) : null}
      </Card>

      <Allocation money={money} />

      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}

      <Card padded={false}>
        <CardHead padded title="보유 종목" meta={`${money.rows.length}종목`} />
        {money.rows.length === 0 ? (
          <Empty text="보유한 종목이 없어요." action="종목 찾기" onAction={() => onGo('/market')} />
        ) : money.rows.map((row, index) => (
          <Row
            key={row.code}
            divided={index > 0}
            onPress={() => onGo(`/market/${row.code}`)}
            accessibilityLabel={`${row.name} ${row.qty}주, 평가액 ${won(row.value)}, 손익 ${signedWon(row.pnl)} ${signedPercent(row.rate)}`}
          >
            <GameIcon code={row.code} size={36} />
            <View style={kit.grow}>
              <Txt variant="callout" numberOfLines={1}>{row.name}</Txt>
              <Txt variant="caption" tone="inkSoft" numeric>{`${row.qty.toLocaleString('ko-KR')}주 · 평균 ${price(row.avg)}원`}</Txt>
            </View>
            <View style={kit.right}>
              <Txt variant="callout" numeric>{won(row.value)}</Txt>
              <Txt variant="caption" tone={toneOf(row.pnl)} numeric>{`${signedWon(row.pnl)} ${signedPercent(row.rate)}`}</Txt>
            </View>
          </Row>
        ))}
      </Card>

      <Card padded={false}>
        <CardHead padded title="미체결 주문" meta={orders ? `${orders.length}건` : ordersFailed ? '연결 끊김' : null} />
        {orders == null ? (
          <Empty loading={!ordersFailed} text={ordersFailed ? '미체결 주문을 불러오지 못했어요. 연결되면 자동으로 다시 불러와요.' : '주문을 불러오는 중이에요'} />
        ) : orders.length === 0 ? <Empty text="걸려 있는 미체결 주문이 없어요." /> : orders.map((order, index) => {
          const buy = order.side === 'BUY';
          const name = quotes[order.stockCode]?.name ?? order.stockCode;
          return (
            <Row key={order.id} divided={index > 0}>
              <View style={kit.grow}>
                <View style={styles.inline}>
                  <SideTag buy={buy} />
                  <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{name}</Txt>
                </View>
                <Txt variant="caption" tone="inkSoft" numeric>
                  {`${order.orderType === 'MARKET' ? '시장가' : `${price(order.price)}원`} · ${order.remainingQuantity.toLocaleString('ko-KR')}/${order.quantity.toLocaleString('ko-KR')}주 남음 · ${kstShort(order.createdAt)}`}
                </Txt>
              </View>
              <Button label="취소" small busy={cancelling === order.id} disabled={cancelling != null} onPress={() => cancel(order)} accessibilityLabel={`${name} 주문 취소`} />
            </Row>
          );
        })}
      </Card>

      <Card padded={false}>
        <CardHead padded title="체결 내역" meta={fills ? `최근 ${Math.min(fills.length, FILL_COUNT)}건` : null} />
        {fills == null ? <Empty loading={!fillsFailed} text={fillsFailed ? '체결 내역을 불러오지 못했어요. 아래로 당겨 다시 불러오세요.' : '체결 내역을 불러오는 중이에요'} />
          : fills.length === 0 ? <Empty text="아직 체결된 주문이 없어요." />
          : fills.slice(0, FILL_COUNT).map((fill, index) => {
            const buy = String(fill.side).toUpperCase() === 'BUY';
            const unit = fill.quantity > 0 ? fill.grossAmount / fill.quantity : 0;
            return (
              <Row key={fill.id} divided={index > 0} accessibilityLabel={`${kstShort(fill.createdAt)} ${quotes[fill.stockCode]?.name ?? fill.stockCode} ${buy ? '매수' : '매도'} ${fill.quantity}주, ${won(fill.grossAmount)}`}>
                <View style={kit.grow}>
                  <View style={styles.inline}>
                    <SideTag buy={buy} />
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{quotes[fill.stockCode]?.name ?? fill.stockCode}</Txt>
                  </View>
                  <Txt variant="caption" tone="inkSoft" numeric>{`${fill.quantity.toLocaleString('ko-KR')}주 · ${price(unit)}원 · ${kstShort(fill.createdAt)}`}</Txt>
                </View>
                <Txt variant="callout" numeric>{won(fill.grossAmount)}</Txt>
              </Row>
            );
          })}
      </Card>

      <Watchlist quotes={quotes} account={account} onGo={onGo} />
      <Txt variant="micro" tone="inkSoft" style={styles.footer}>모의 시장이에요. 실제 주식이나 현금을 거래하지 않아요.</Txt>
    </AccountFrame>
  );
}

function Watchlist({ quotes, account, onGo }) {
  const [error, setError] = useState('');
  const codes = account.watchlist.filter(code => quotes[code]);
  return (
    <Card padded={false}>
      <CardHead padded title="관심 종목" meta={`${codes.length}종목`} />
      {error ? <View style={styles.inset}><Notice text={error} /></View> : null}
      {codes.length === 0 ? <Empty text="종목 상세에서 별을 누르면 여기에 모여요." action="종목 찾기" onAction={() => onGo('/market')} /> : codes.map((code, index) => {
        const stock = quotes[code];
        const rate = sessionRate(stock);
        return (
          <Row key={code} divided={index > 0} onPress={() => onGo(`/market/${code}`)} accessibilityLabel={`${stock.name} ${price(stock.price)}원, 오늘 ${signedPercent(rate)}`}>
            <GameIcon code={code} size={36} />
            <Txt variant="callout" numberOfLines={1} style={kit.grow}>{stock.name}</Txt>
            <View style={kit.right}>
              <Txt variant="callout" numeric>{price(stock.price)}</Txt>
              <Txt variant="caption" tone={toneOf(rate)} numeric>{signedPercent(rate)}</Txt>
            </View>
            <Button label="빼기" small onPress={() => { setError(''); account.toggleWatch(code).catch(failure => setError(failure.message)); }} accessibilityLabel={`${stock.name} 관심 종목에서 빼기`} />
          </Row>
        );
      })}
    </Card>
  );
}

const styles = StyleSheet.create({
  share: { paddingHorizontal: SPACE.lg + 2, marginTop: -SPACE.md, marginBottom: SPACE.lg },
  figures: { flexDirection: 'row', gap: SPACE.md },
  allocBar: { flexDirection: 'row', gap: 2, height: 12, borderRadius: 6, overflow: 'hidden' },
  legend: { flexDirection: 'row', flexWrap: 'wrap', columnGap: SPACE.lg, rowGap: 6 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6, maxWidth: '100%' },
  legendDot: { width: 8, height: 8, borderRadius: 3, borderWidth: StyleSheet.hairlineWidth },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  shrink: { flexShrink: 1 },
  inset: { paddingHorizontal: SPACE.lg + 2 },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
});
