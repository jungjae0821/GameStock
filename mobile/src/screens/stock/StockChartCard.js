import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { IndicatorButton, StudyChart, useIndicatorSettings } from '../../components/StudyChart';
import { LineChart } from '../../components/LineChart';
import { api } from '../../market';
import { RADIUS_SM, SPACE, Txt, useTheme } from '../../theme';
import { Card, styles as shared } from './parts';

const RANGES = [
  { key: '1h', label: '1시간' },
  { key: '6h', label: '6시간' },
  { key: '24h', label: '하루' },
  { key: '7d', label: '일주일' },
];
// 캔들은 봉 간격으로 고른다. 키는 /api/stocks/{code}/chart의 range 값이고, 이름은 웹 SeriesChart와 같다.
const CANDLE_RANGES = [
  { key: '1h', label: '1분' },
  { key: '6h', label: '5분' },
  { key: '1d', label: '30분' },
  { key: '3d', label: '1시간' },
];
const MODES = [{ key: 'line', label: '선' }, { key: 'candle', label: '캔들' }];

/**
 * 차트 구간·모양·지표 상태와 가격·봉 기록 읽기. 종목 정보가 오기 전부터 기록을 받도록
 * 화면이 맨 위에서 부르고, 지표 선택 시트도 화면이 이 상태로 연다.
 */
export function useStockChart(code) {
  const [range, setRange] = useState('24h');
  const [history, setHistory] = useState({ range: null, points: null, error: false });
  const [mode, setMode] = useState('line');
  const [candleRange, setCandleRange] = useState('1h');
  const [candles, setCandles] = useState({ range: null, bars: null, error: false });
  const studies = useIndicatorSettings();
  const [pickingStudy, setPickingStudy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setHistory(current => ({ range, points: current.range === range ? current.points : null, error: false }));
    const read = () => api(`/api/stocks/${code}/history?range=${range}`)
      .then(points => { if (!cancelled) setHistory({ range, points, error: false }); })
      .catch(() => { if (!cancelled) setHistory(current => ({ ...current, error: true })); });
    void read();
    const timer = setInterval(read, 60_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [code, range]);

  // 캔들은 캔들 보기를 고른 동안만 받는다. 마지막 봉이 계속 자라므로 10초마다 다시 읽는다.
  useEffect(() => {
    if (mode !== 'candle') return undefined;
    let cancelled = false;
    setCandles(current => ({ range: candleRange, bars: current.range === candleRange ? current.bars : null, error: false }));
    const read = () => api(`/api/stocks/${code}/chart?range=${candleRange}`)
      .then(bars => { if (!cancelled) setCandles({ range: candleRange, bars, error: false }); })
      .catch(() => { if (!cancelled) setCandles(current => ({ ...current, error: true })); });
    void read();
    const timer = setInterval(read, 10_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [code, mode, candleRange]);

  return { range, setRange, history, mode, setMode, candleRange, setCandleRange, candles, studies, pickingStudy, setPickingStudy };
}

export function StockChartCard({ chart, stock, tone, newsTimes }) {
  const theme = useTheme();
  const { range, setRange, history, mode, setMode, candleRange, setCandleRange, candles, studies, setPickingStudy } = chart;
  return (
    <Card>
      <View style={styles.chartBar}>
        <View style={[styles.ranges, shared.shrink]} accessibilityRole="tablist" accessibilityLabel="차트 구간">
          {(mode === 'candle' ? CANDLE_RANGES : RANGES).map(item => {
            const selected = (mode === 'candle' ? candleRange : range) === item.key;
            return (
              <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => (mode === 'candle' ? setCandleRange : setRange)(item.key)} style={[styles.range, selected && { backgroundColor: theme.raised }]}>
                <Txt variant="caption" tone={selected ? 'ink' : 'inkSoft'}>{item.label}</Txt>
              </Pressable>
            );
          })}
        </View>
        <View style={[styles.modes, { borderColor: theme.edge }]} accessibilityRole="tablist" accessibilityLabel="차트 모양">
          {MODES.map(item => {
            const selected = mode === item.key;
            return (
              <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => setMode(item.key)} style={[styles.mode, selected && { backgroundColor: theme.ink }]}>
                <Txt variant="caption" style={{ color: selected ? theme.ground : theme.ink }}>{item.label}</Txt>
              </Pressable>
            );
          })}
        </View>
        {/* 지표는 봉으로 계산하므로 선 보기에서 누르면 캔들로 바꾼 뒤 연다. */}
        <IndicatorButton count={studies.indicators.length} onPress={() => { setMode('candle'); setPickingStudy(true); }} />
      </View>
      {mode === 'candle' ? (
        candles.bars == null ? (
          <View style={shared.chartLoading}>{candles.error ? <Txt variant="caption" tone="inkSoft">봉 기록을 불러오지 못했어요.</Txt> : <ActivityIndicator color={theme.inkSoft} />}</View>
        ) : <StudyChart candles={candles.bars} indicators={studies.indicators} reference={stock.referencePrice} onRemove={studies.remove} />
      ) : history.points == null && !history.error ? (
        <View style={shared.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>
      ) : history.error && history.points == null ? (
        <View style={shared.chartLoading}><Txt variant="caption" tone="inkSoft">가격 기록을 불러오지 못했어요.</Txt></View>
      ) : (
        <LineChart
          points={history.points}
          current={stock.price}
          reference={range === '7d' ? null : stock.referencePrice}
          newsTimes={newsTimes}
          tone={tone}
        />
      )}
      <Txt variant="micro" tone="inkSoft" style={{ marginTop: SPACE.sm }}>{mode === 'candle' ? '최근 60개 봉이에요. 회색 점선은 오늘 기준가, 핑크 점선은 마지막 종가예요. 막대는 거래량이 아니라 체결 건수예요.' : range === '7d' ? '아래 점과 세로선은 관련 뉴스가 나온 시점이에요.' : '아래 점과 세로선은 관련 뉴스가 나온 시점, 점선은 오늘 기준가예요.'}</Txt>
    </Card>
  );
}

const styles = StyleSheet.create({
  chartBar: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, marginBottom: SPACE.md },
  ranges: { flexDirection: 'row', gap: 4 },
  modes: { flexDirection: 'row', borderWidth: 1, borderRadius: RADIUS_SM, overflow: 'hidden' },
  mode: { minHeight: 44, minWidth: 44, paddingHorizontal: SPACE.sm, alignItems: 'center', justifyContent: 'center' },
  range: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
});
