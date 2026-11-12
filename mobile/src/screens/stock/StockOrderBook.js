import React, { useMemo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { clockTime, price, signedPercent } from '../../format';
import { sessionRate } from '../../market';
import { SPACE, Txt, useTheme } from '../../theme';
import { Card, styles as shared } from './parts';

const BOOK_DEPTH = 5;
const TRADE_COUNT = 10;

/** 호가 5단계와 판정선. 가격을 누르면 그 가격으로 반대쪽 주문을 연다. */
export function StockOrderBookCard({ stock, tone, orderbook, onOrder }) {
  const theme = useTheme();
  const asks = useMemo(() => [...(orderbook?.asks ?? [])].sort((a, b) => a.price - b.price).slice(0, BOOK_DEPTH).reverse(), [orderbook]);
  const bids = useMemo(() => [...(orderbook?.bids ?? [])].sort((a, b) => b.price - a.price).slice(0, BOOK_DEPTH), [orderbook]);
  const maxLevel = Math.max(1, ...asks.map(level => level.quantity), ...bids.map(level => level.quantity));
  return (
    <Card padded={false}>
      <View style={shared.cardHead}>
        <Txt variant="headline" accessibilityRole="header">호가</Txt>
        <Txt variant="caption" tone="inkSoft">가격을 누르면 그 가격으로 주문해요</Txt>
      </View>
      {orderbook ? (
        <View>
          {asks.length ? asks.map(level => <BookRow key={`a${level.price}`} level={level} side="sell" max={maxLevel} onPress={() => onOrder('buy', level.price)} />)
            : <Txt variant="caption" tone="inkSoft" style={shared.bookEmpty}>매도 대기 주문이 없어요</Txt>}
          <JudgeLine stock={stock} tone={tone} />
          {bids.length ? bids.map(level => <BookRow key={`b${level.price}`} level={level} side="buy" max={maxLevel} onPress={() => onOrder('sell', level.price)} />)
            : <Txt variant="caption" tone="inkSoft" style={shared.bookEmpty}>매수 대기 주문이 없어요</Txt>}
        </View>
      ) : <View style={shared.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>}
    </Card>
  );
}

export function StockTradesCard({ trades }) {
  const theme = useTheme();
  return (
    <Card padded={false}>
      <View style={shared.cardHead}><Txt variant="headline" accessibilityRole="header">최근 체결</Txt></View>
      {trades ? (trades.length ? trades.slice(0, TRADE_COUNT).map((trade, index) => {
        const buy = String(trade.side).toUpperCase() === 'BUY';
        return (
          <View key={`${trade.createdAt}-${index}`} style={styles.trade} accessible accessibilityLabel={`${clockTime(trade.createdAt)} ${price(trade.price)}원 ${trade.quantity}주 ${buy ? '매수' : '매도'}`}>
            <Txt variant="caption" tone="inkSoft" numeric style={styles.tradeTime}>{clockTime(trade.createdAt)}</Txt>
            <Txt variant="callout" numeric style={styles.tradePrice}>{price(trade.price)}</Txt>
            <Txt variant="callout" numeric tone={buy ? 'up' : 'down'} style={styles.tradeQty}>{`${trade.quantity}주 ${buy ? '매수' : '매도'}`}</Txt>
          </View>
        );
      }) : <Txt variant="caption" tone="inkSoft" style={shared.bookEmpty}>아직 체결이 없어요</Txt>)
        : <View style={shared.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>}
    </Card>
  );
}

/**
 * 판정선. 매도 호가와 매수 호가 사이에 현재가를 꽂는 줄이다(DESIGN.md의 서명 부품).
 * 위에 2px 핑크 선, 바탕은 brand-wash, 현재가는 굵은 고정폭 숫자로 쓴다.
 */
function JudgeLine({ stock, tone }) {
  const theme = useTheme();
  const rate = sessionRate(stock);
  return (
    <View
      style={[styles.judge, { backgroundColor: theme.brandWash, borderTopColor: theme.brand }]}
      accessible
      accessibilityLabel={`현재가 ${price(stock.price)}원, 오늘 ${signedPercent(rate)}`}
    >
      <Txt variant="caption" tone="inkSoft" style={styles.judgeSide}>현재가</Txt>
      <Txt variant="title" numeric>{price(stock.price)}</Txt>
      <Txt variant="caption" tone={tone} numeric style={[styles.judgeSide, styles.judgeRate]}>{signedPercent(rate)}</Txt>
    </View>
  );
}

/** 호가 한 줄. 매도 잔량은 왼쪽, 매수 잔량은 오른쪽에 막대로 그리고 가격은 가운데에 둔다. */
function BookRow({ level, side, max, onPress }) {
  const theme = useTheme();
  const bar = `${Math.max(4, Math.round((level.quantity / max) * 100))}%`;
  const wash = side === 'sell' ? theme.downWash : theme.upWash;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${side === 'sell' ? '매도' : '매수'} 호가 ${price(level.price)}원, ${level.quantity}주. 이 가격으로 ${side === 'sell' ? '매수' : '매도'} 주문`}
      onPress={onPress}
      style={({ pressed }) => [styles.book, pressed && { backgroundColor: theme.pressed }]}
    >
      <View style={styles.bookSide}>
        {side === 'sell' ? (
          <>
            <View style={[styles.bookBar, styles.bookBarRight, { width: bar, backgroundColor: wash }]} />
            <Txt variant="caption" numeric style={styles.bookQtyRight}>{`${level.quantity.toLocaleString('ko-KR')}주`}</Txt>
          </>
        ) : null}
      </View>
      <View style={styles.bookPrice}>
        <Txt variant="callout" numeric tone={side === 'sell' ? 'down' : 'up'}>{price(level.price)}</Txt>
      </View>
      <View style={styles.bookSide}>
        {side === 'buy' ? (
          <>
            <View style={[styles.bookBar, { width: bar, backgroundColor: wash }]} />
            <Txt variant="caption" numeric style={styles.bookQtyLeft}>{`${level.quantity.toLocaleString('ko-KR')}주`}</Txt>
          </>
        ) : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  book: { flexDirection: 'row', alignItems: 'center', minHeight: 44, paddingHorizontal: SPACE.lg + 2 },
  bookSide: { flex: 1, height: 32, justifyContent: 'center' },
  bookBar: { position: 'absolute', top: 4, bottom: 4, left: 0, borderRadius: 3 },
  bookBarRight: { left: undefined, right: 0 },
  bookQtyRight: { textAlign: 'right', paddingRight: SPACE.sm },
  bookQtyLeft: { paddingLeft: SPACE.sm },
  bookPrice: { minWidth: 96, minHeight: 32, alignItems: 'center', justifyContent: 'center' },
  judge: { flexDirection: 'row', alignItems: 'center', minHeight: 52, paddingHorizontal: SPACE.lg + 2, marginVertical: 2, borderTopWidth: 2 },
  judgeSide: { flex: 1 },
  judgeRate: { textAlign: 'right' },
  trade: { flexDirection: 'row', alignItems: 'center', minHeight: 36, paddingHorizontal: SPACE.lg + 2 },
  tradeTime: { width: 72 },
  tradePrice: { flex: 1, textAlign: 'right' },
  tradeQty: { width: 104, textAlign: 'right' },
});
