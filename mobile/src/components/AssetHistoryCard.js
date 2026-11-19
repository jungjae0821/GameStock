import React, { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import Svg, { Line, Path } from 'react-native-svg';
import { request } from '../auth';
import { signedPercent, signedWon, won } from '../format';
import { SPACE, Txt, toneOf, useTheme } from '../theme';
import { Button, Card, CardHead, Segment } from './Kit';

const RANGES = [{ key: 7, label: '7일' }, { key: 30, label: '30일' }, { key: 90, label: '90일' }, { key: 365, label: '1년' }];
const HEIGHT = 140;
const dayLabel = date => { const [, month, day] = String(date).split('-'); return `${Number(month)}.${Number(day)}`; };

/**
 * 하루 마감 순자산을 이은 선. 마지막 점은 지금 평가액이다(웹 AssetHistory와 같은 /api/asset-history).
 * 기간 첫날 값을 점선으로 깔아 그 위아래로 얼마나 움직였는지 읽게 한다.
 */
export function AssetHistoryCard() {
  const theme = useTheme();
  const [days, setDays] = useState(30);
  const [points, setPoints] = useState(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    let active = true;
    setFailed(false);
    request(`/api/asset-history?days=${days}`)
      .then(next => { if (active) setPoints(Array.isArray(next) ? next : []); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [days, attempt]);

  const first = points?.[0];
  const last = points?.[points.length - 1];
  const change = first && last ? last.netAsset - first.netAsset : 0;
  const ratio = first && first.netAsset > 0 ? change / first.netAsset : 0;
  const tone = toneOf(change);

  let line = '';
  let baseY = HEIGHT / 2;
  if (points && points.length > 1 && first && width > 0) {
    const values = points.map(point => point.netAsset);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const pad = Math.max(1, (max - min) * 0.12);
    const y = value => HEIGHT - ((value - (min - pad)) / (max - min + pad * 2)) * HEIGHT;
    line = points.map((point, index) => `${index ? 'L' : 'M'}${((index / (points.length - 1)) * width).toFixed(1)},${y(point.netAsset).toFixed(1)}`).join(' ');
    baseY = y(first.netAsset);
  }

  return (
    <Card>
      <CardHead title="자산 추이" />
      <Segment options={RANGES} value={days} onChange={setDays} label="기간" />
      {failed ? (
        <View style={styles.gapTop}>
          <Txt variant="callout" tone="inkSoft">자산 기록을 불러오지 못했어요.</Txt>
          <Button label="다시 불러오기" small onPress={() => setAttempt(current => current + 1)} style={styles.retry} />
        </View>
      ) : !points ? <Txt variant="callout" tone="inkSoft" style={styles.gapTop}>자산 기록을 불러오고 있어요.</Txt>
        : points.length === 0 ? <Txt variant="callout" tone="inkSoft" style={styles.gapTop}>아직 남은 자산 기록이 없어요. 5분 안에 오늘 값이 기록돼요.</Txt>
        : (
          <>
            {points.length > 1 ? (
              <Txt variant="callout" tone={tone} numeric style={styles.gapTop}>
                {`${signedWon(change)} (${signedPercent(ratio)}) `}
                <Txt variant="caption" tone="inkSoft">{`${dayLabel(first.date)}부터`}</Txt>
              </Txt>
            ) : <Txt variant="callout" tone="inkSoft" style={styles.gapTop}>오늘 처음 기록했어요. 내일부터 선이 이어져요.</Txt>}
            {points.length > 1 ? (
              <View
                style={styles.plot}
                onLayout={event => setWidth(event.nativeEvent.layout.width)}
                accessible
                accessibilityRole="image"
                accessibilityLabel={`${dayLabel(first.date)} ${won(first.netAsset)}에서 ${dayLabel(last.date)} ${won(last.netAsset)}`}
              >
                {width > 0 ? (
                  <Svg width={width} height={HEIGHT}>
                    <Line x1={0} x2={width} y1={baseY} y2={baseY} stroke={theme.edge} strokeWidth={1} strokeDasharray="4 4" />
                    <Path d={line} stroke={theme[tone] ?? theme.ink} strokeWidth={2} fill="none" strokeLinejoin="round" strokeLinecap="round" />
                  </Svg>
                ) : null}
                <View style={styles.axis} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
                  <Txt variant="micro" tone="inkSoft" numeric>{dayLabel(first.date)}</Txt>
                  <Txt variant="micro" tone="inkSoft">오늘</Txt>
                </View>
              </View>
            ) : null}
          </>
        )}
    </Card>
  );
}

const styles = StyleSheet.create({
  gapTop: { marginTop: SPACE.md },
  retry: { alignSelf: 'flex-start', marginTop: SPACE.sm },
  plot: { marginTop: SPACE.md },
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: SPACE.xs },
});
