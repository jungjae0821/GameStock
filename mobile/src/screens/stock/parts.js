import React from 'react';
import { StyleSheet, View } from 'react-native';
import { RADIUS, SPACE, useTheme } from '../../theme';

/* 종목 상세 화면의 여러 카드가 함께 쓰는 부품과 스타일. */
export const amount = value => `${Math.round(Number(value) || 0).toLocaleString('ko-KR')}주`;

export function Card({ children, padded = true }) {
  const theme = useTheme();
  return <View style={[styles.card, { backgroundColor: theme.card }, padded && styles.cardPadded]}>{children}</View>;
}

export const styles = StyleSheet.create({
  shrink: { flex: 1, minWidth: 0 },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  cardPadded: { padding: SPACE.lg + 2 },
  cardHead: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg + 2, paddingBottom: SPACE.sm, gap: SPACE.sm },
  chartLoading: { minHeight: 120, alignItems: 'center', justifyContent: 'center' },
  bookEmpty: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
});
