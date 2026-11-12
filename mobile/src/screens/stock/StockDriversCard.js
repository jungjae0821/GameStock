import React from 'react';
import { StyleSheet, View } from 'react-native';
import { SPACE, Txt, useTheme } from '../../theme';
import { Card, amount } from './parts';

/** 움직인 이유. 받은 값이 없을 때만 불러오는 중·실패 문구를 보인다. */
export function StockDriversCard({ drivers, failed }) {
  const theme = useTheme();
  return (
    <Card>
      <Txt variant="headline" accessibilityRole="header">움직인 이유</Txt>
      {drivers ? (
        <>
          <Txt variant="callout" style={{ marginTop: SPACE.sm }}>{drivers.reason}</Txt>
          <View style={[styles.drivers, { borderTopColor: theme.line }]}>
            <Driver label="관련 뉴스" value={drivers.newsCount ? `${drivers.newsCount}건 · 영향 ${drivers.newsImpact > 0 ? '+' : ''}${drivers.newsImpact}` : '없음'} />
            <Driver label="대기 주문" buy={drivers.openBuyVolume} sell={drivers.openSellVolume} />
          </View>
          <Txt variant="micro" tone="inkSoft" style={{ marginTop: SPACE.sm }}>최근 24시간 기준이에요.</Txt>
        </>
      ) : <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.sm }}>{failed ? '가격 근거를 불러오지 못했어요. 30초마다 다시 불러와요.' : '가격 근거를 불러오는 중이에요'}</Txt>}
    </Card>
  );
}

/** 매수·매도 수량을 나란히 적고, 아래에 매수 비중 막대를 둔다. */
function Driver({ label, value, buy, sell }) {
  const theme = useTheme();
  const total = (Number(buy) || 0) + (Number(sell) || 0);
  const share = total ? (Number(buy) || 0) / total : 0.5;
  return (
    <View style={styles.driver}>
      <View style={styles.driverLine}>
        <Txt variant="callout" tone="inkSoft">{label}</Txt>
        {value != null ? <Txt variant="callout" numeric>{value}</Txt> : (
          <Txt variant="callout" numeric>
            <Txt variant="callout" tone="up" numeric>{`매수 ${amount(buy)}`}</Txt>
            {'  '}
            <Txt variant="callout" tone="down" numeric>{`매도 ${amount(sell)}`}</Txt>
          </Txt>
        )}
      </View>
      {value == null && total ? (
        <View style={[styles.shareTrack, { backgroundColor: theme.down }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          <View style={[styles.shareFill, { width: `${Math.round(share * 100)}%`, backgroundColor: theme.up }]} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  drivers: { marginTop: SPACE.md, paddingTop: SPACE.sm, borderTopWidth: StyleSheet.hairlineWidth, gap: SPACE.md },
  driver: { gap: 6 },
  driverLine: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: SPACE.sm, flexWrap: 'wrap' },
  shareTrack: { height: 4, borderRadius: 3, overflow: 'hidden', flexDirection: 'row' },
  shareFill: { height: 4 },
});
