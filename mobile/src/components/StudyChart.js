import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Svg, { Line, Path, Rect } from 'react-native-svg';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { computeIndicator } from '../indicators';
import { serverTime } from '../research';
import { RADIUS, RADIUS_XS, SPACE, Txt, useTheme } from '../theme';
import { Button, Field } from './Kit';

// 종목 상세 차트의 지표. 웹 ChartStudies와 같은 지표·기본값·제한을 쓴다.
const TYPES = [
  { key: 'SMA', label: '단순 이동평균', period: 20, note: '종가 평균', pane: 'overlay' },
  { key: 'EMA', label: '지수 이동평균', period: 20, note: '최근 값에 가중', pane: 'overlay' },
  { key: 'BBANDS', label: '볼린저 밴드', period: 20, note: '평균 ± 2표준편차', pane: 'overlay' },
  { key: 'RSI', label: 'RSI', period: 14, note: '70 과열, 30 침체', pane: 'oscillator' },
  { key: 'MACD', label: 'MACD', period: 26, note: '12/26/9 고정', pane: 'oscillator' },
  { key: 'ATR', label: 'ATR', period: 14, note: '평균 변동폭', pane: 'oscillator' },
];
const PANE_OF = Object.fromEntries(TYPES.map(item => [item.key, item.pane]));
const DEFAULTS = [
  { id: 'sma5', type: 'SMA', period: 5 },
  { id: 'ema20', type: 'EMA', period: 20 },
  { id: 'rsi14', type: 'RSI', period: 14 },
];
const MAX_INDICATORS = 12;
// 아래 칸으로 그리는 지표 수. 셋 이상이면 차트가 호가를 화면 밖으로 밀어낸다.
const MAX_OSCILLATORS = 2;
const STORAGE_KEY = 'gamestock.indicators';
// 폰 폭에서 캔들이 1px로 뭉개지지 않도록 최근 캔들만 그린다. 지표는 전체 봉으로 계산한 뒤 같은 칸만큼 자른다.
const MAX_BARS = 60;
// 오른쪽 가격축 폭. 마지막 종가 꼬리표가 이 칸에 선다.
const AXIS = 56;

const whole = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 });
const compact = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 });
const toText = (format, value) => (value == null || !Number.isFinite(value) ? '-' : format.format(value));
const nameOf = spec => (spec.type === 'MACD' ? 'MACD 12 26 9' : `${spec.type === 'BBANDS' ? 'BB' : spec.type} ${spec.period}`);

/** id는 [A-Za-z][A-Za-z0-9_]{0,31}이고 겹치면 안 된다. */
function nextId(type, period, taken) {
  const base = `${type.toLowerCase()}${period}`;
  let id = base;
  for (let suffix = 2; taken.some(item => item.id === id); suffix += 1) id = `${base}_${suffix}`;
  return id;
}

/** 켜 둔 지표 목록. 앱을 다시 켜도 남는다. 저장소를 읽기 전에는 기본값을 쓴다. */
export function useIndicatorSettings() {
  const [indicators, setIndicators] = useState(DEFAULTS);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(STORAGE_KEY)
      .then(raw => {
        const saved = raw ? JSON.parse(raw) : null;
        if (active && saved && Array.isArray(saved.indicators)) {
          setIndicators(saved.indicators.filter(item => PANE_OF[item.type] && Number.isInteger(item.period)).slice(0, MAX_INDICATORS));
        }
      })
      .catch(() => {})
      .finally(() => { if (active) setLoaded(true); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    if (loaded) AsyncStorage.setItem(STORAGE_KEY, JSON.stringify({ indicators })).catch(() => {});
  }, [indicators, loaded]);
  const add = useCallback((type, period) => setIndicators(current => (current.length >= MAX_INDICATORS ? current
    : [...current, { id: nextId(type, period, current), type, period }])), []);
  const remove = useCallback(id => setIndicators(current => current.filter(item => item.id !== id)), []);
  const oscillators = indicators.filter(item => PANE_OF[item.type] === 'oscillator').length;
  return { indicators, add, remove, oscillators };
}

/** 차트 머리의 지표 버튼. 지금 켜 둔 수를 함께 보인다. */
export function IndicatorButton({ count, onPress }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`지표 추가, 지금 ${count}개 켜짐`}
      onPress={onPress}
      style={({ pressed }) => [styles.pick, { borderColor: theme.edge }, pressed && { backgroundColor: theme.pressed }]}
    >
      <Txt variant="caption" style={styles.fx}>fx</Txt>
      <Txt variant="caption">지표</Txt>
      <Txt variant="caption" tone="inkSoft" numeric>{count}</Txt>
    </Pressable>
  );
}

/** 지표 고르기 시트. 종류를 고르고 기간을 정해 추가한다. */
export function PickerSheet({ visible, count, oscillators, onAdd, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [type, setType] = useState('SMA');
  const [period, setPeriod] = useState('20');
  const fixed = type === 'MACD';
  const value = fixed ? 26 : Number(period);
  const periodError = !fixed && period !== '' && (!Number.isInteger(value) || value < 2 || value > 500) ? '기간은 2에서 500 사이 정수로 넣어 주세요.' : '';
  const full = count >= MAX_INDICATORS;
  const panesFull = PANE_OF[type] === 'oscillator' && oscillators >= MAX_OSCILLATORS;
  const blocked = full || panesFull || Boolean(periodError) || (!fixed && period === '');
  const note = periodError
    || (full ? `지표는 ${MAX_INDICATORS}개까지 켤 수 있어요. 범례의 ×로 하나를 끈 뒤 추가해 주세요.` : '')
    || (panesFull ? `RSI·MACD·ATR은 아래 칸으로 ${MAX_OSCILLATORS}개까지 그려요. 하나를 끈 뒤 추가해 주세요.` : '')
    || `${count}/${MAX_INDICATORS}개 켜짐`;
  const pick = next => { setType(next); setPeriod(String(TYPES.find(item => item.key === next)?.period ?? 20)); };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={[StyleSheet.absoluteFill, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="지표 고르기 닫기" />
      <View style={[styles.sheet, { backgroundColor: theme.card, paddingBottom: insets.bottom + SPACE.md }]}>
        <View style={styles.sheetHead}>
          <Txt variant="headline" accessibilityRole="header">지표 추가</Txt>
          <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={onClose} style={styles.close}>
            <Ionicons name="close" size={24} color={theme.ink} />
          </Pressable>
        </View>
        <View accessibilityRole="radiogroup" accessibilityLabel="지표 종류">
          {TYPES.map(item => {
            const selected = item.key === type;
            return (
              <Pressable
                key={item.key}
                accessibilityRole="radio"
                accessibilityState={{ checked: selected }}
                onPress={() => pick(item.key)}
                style={({ pressed }) => [styles.type, (selected || pressed) && { backgroundColor: theme.raised }]}
              >
                <Txt variant="callout">{item.label}</Txt>
                <Txt variant="caption" tone="inkSoft">{item.note}</Txt>
              </Pressable>
            );
          })}
        </View>
        <View style={[styles.addRow, { borderTopColor: theme.line }]}>
          <Field
            label="기간(캔들 수)"
            value={fixed ? '12·26·9' : period}
            onChangeText={text => setPeriod(text.replace(/[^0-9]/g, ''))}
            editable={!fixed}
            keyboardType="number-pad"
            maxLength={3}
            numeric
            unit={fixed ? null : '개'}
            style={styles.grow}
          />
          <Button label="추가" kind="primary" small disabled={blocked} onPress={() => { onAdd(type, value); onClose(); }} style={styles.addButton} />
        </View>
        <Txt variant="caption" tone={periodError ? 'ink' : 'inkSoft'} style={styles.sheetNote} accessibilityLiveRegion="polite">{note}</Txt>
      </View>
    </Modal>
  );
}

const lastValue = values => {
  for (let index = values.length - 1; index >= 0; index -= 1) if (values[index] != null && Number.isFinite(values[index])) return values[index];
  return null;
};

/**
 * 캔들과 지표를 칸으로 나눠 그린다. 가격 칸에 이동평균·볼린저 밴드, 그 아래 체결 건수와 RSI·MACD·ATR 칸.
 * candles는 /api/stocks/{code}/chart의 봉 그대로다. reference는 오늘 기준가(점선).
 */
export function StudyChart({ candles, indicators, reference, onRemove }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);

  const view = useMemo(() => {
    const sorted = (candles || [])
      .map(bar => ({ ...bar, at: serverTime(bar.recordedAt) }))
      .filter(bar => Number.isFinite(bar.at) && bar.highPrice > 0 && bar.lowPrice > 0)
      .sort((a, b) => a.at - b.at);
    const bars = sorted.map(bar => ({ high: bar.highPrice, low: bar.lowPrice, close: bar.closePrice }));
    const from = Math.max(0, sorted.length - MAX_BARS);
    const cut = values => values.slice(from);
    const shown = sorted.slice(from);
    const overlay = [];
    const oscillators = [];
    let tone = 0;
    for (const spec of indicators) {
      const result = computeIndicator(spec, bars);
      const lines = Object.entries(result.values).map(([key, values]) => ({
        key: `${spec.id}.${key}`,
        values: cut(values),
        tone: spec.type === 'MACD' ? (key === 'macd' ? 0 : key === 'signal' ? 1 : 5) : result.pane === 'overlay' ? tone : 2,
        style: key === 'histogram' ? 'bar' : key === 'upper' || key === 'lower' ? 'dash' : 'line',
      }));
      const row = { id: spec.id, label: nameOf(spec), lines };
      if (result.pane === 'overlay') {
        overlay.push(row);
        tone = (tone + 1) % 5;
      } else {
        oscillators.push({
          id: spec.id, height: 80, rows: [row], series: lines,
          guides: spec.type === 'RSI' ? [30, 70] : spec.type === 'MACD' ? [0] : [],
          domain: spec.type === 'RSI' ? [0, 100] : null,
          format: spec.type === 'ATR' ? whole : decimal,
        });
      }
    }
    return {
      candles: shown,
      first: shown[0]?.at,
      last: shown[shown.length - 1]?.at,
      panes: [
        { id: 'price', height: 176, candles: shown, series: overlay.flatMap(row => row.lines), rows: overlay, guides: [], format: whole, reference },
        { id: 'count', title: '체결 건수', height: 44, series: [{ key: 'count', values: shown.map(bar => bar.volume ?? null), style: 'bar', tone: 5 }], rows: [], guides: [], format: compact },
        ...oscillators,
      ],
    };
  }, [candles, indicators, reference]);

  if (view.candles.length < 2) {
    return <View style={styles.empty}><Txt variant="caption" tone="inkSoft">이 구간의 봉 기록이 아직 없어요</Txt></View>;
  }
  const plot = Math.max(0, width - AXIS);
  const slot = plot > 0 ? plot / view.candles.length : 0;
  const longRange = view.last - view.first > 26 * 60 * 60_000;
  return (
    <View onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))}>
      {width > 0 ? view.panes.map((pane, index) => (
        <Pane key={pane.id} pane={pane} width={width} plot={plot} slot={slot} theme={theme} divided={index > 1} onRemove={onRemove} />
      )) : null}
      <View style={[styles.axis, { width: plot }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        <Txt variant="micro" tone="inkSoft" numeric>{shortTime(view.first, longRange)}</Txt>
        <Txt variant="micro" tone="inkSoft" numeric>{shortTime(view.last, longRange)}</Txt>
      </View>
    </View>
  );
}

const shortTime = (ms, longRange) => {
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms);
  const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return longRange ? `${at.getMonth() + 1}.${at.getDate()} ${hm}` : hm;
};

function Pane({ pane, width, plot, slot, theme, divided, onRemove }) {
  const { height } = pane;
  const scale = useMemo(() => {
    let low = Number.POSITIVE_INFINITY;
    let high = Number.NEGATIVE_INFINITY;
    const take = value => { if (value != null && Number.isFinite(value)) { low = Math.min(low, value); high = Math.max(high, value); } };
    for (const candle of pane.candles ?? []) { take(candle.lowPrice); take(candle.highPrice); }
    for (const series of pane.series) { series.values.forEach(take); if (series.style === 'bar') take(0); }
    pane.guides.forEach(take);
    if (pane.reference > 0) take(pane.reference);
    if (pane.domain) [low, high] = pane.domain;
    if (!Number.isFinite(low)) return null;
    if (low === high) { low -= 1; high += 1; }
    const pad = pane.domain ? 0 : (high - low) * 0.06;
    return { low: low - pad, high: high + pad, top: high, bottom: low };
  }, [pane]);

  const y = value => (scale ? 4 + (1 - (value - scale.low) / (scale.high - scale.low)) * (height - 8) : height / 2);
  const x = index => slot * index + slot / 2;
  const body = Math.max(1, Math.min(8, slot * 0.6));
  const color = tone => theme.study[tone] ?? theme.inkSoft;
  const path = values => {
    let d = '';
    let open = false;
    values.forEach((value, index) => {
      if (value == null || !Number.isFinite(value)) { open = false; return; }
      d += `${open ? 'L' : 'M'}${x(index).toFixed(1)},${y(value).toFixed(1)}`;
      open = true;
    });
    return d;
  };

  const candles = pane.candles ?? [];
  const lastCandle = candles[candles.length - 1];
  const lastY = lastCandle ? y(lastCandle.closePrice) : null;

  return (
    <View style={[styles.pane, divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }]}>
      {pane.title ? (
        <View style={styles.legendRow} accessible accessibilityLabel={`${pane.title} ${toText(pane.format, lastValue(pane.series[0]?.values ?? []))}`}>
          <Txt variant="micro" tone="inkSoft">{pane.title}</Txt>
          <Txt variant="micro" numeric>{toText(pane.format, lastValue(pane.series[0]?.values ?? []))}</Txt>
        </View>
      ) : null}
      {/* 범례: 지표마다 값과 끄기 버튼. 값 글자는 잉크색, 선 색은 앞의 작은 칸으로만 보인다(밝은 테마의 몇몇 선 색은 글자 대비 4.5:1에 못 미친다). */}
      {pane.rows.map(row => (
        <View key={row.id} style={styles.legendRow}>
          <Txt variant="micro" tone="inkSoft">{row.label}</Txt>
          {row.lines.map(line => (
            <View key={line.key} style={styles.value}>
              <View style={[styles.swatch, { backgroundColor: color(line.tone) }]} />
              <Txt variant="micro" numeric>{toText(pane.format, lastValue(line.values))}</Txt>
            </View>
          ))}
          <Pressable accessibilityRole="button" accessibilityLabel={`${row.label} 끄기`} onPress={() => onRemove(row.id)} hitSlop={12} style={styles.remove}>
            <Ionicons name="close" size={14} color={theme.inkSoft} />
          </Pressable>
        </View>
      ))}
      <View style={{ height }}>
        <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          {pane.reference > 0 ? <Line x1={0} x2={plot} y1={y(pane.reference)} y2={y(pane.reference)} stroke={theme.edge} strokeWidth={1} strokeDasharray="3 4" /> : null}
          {pane.guides.map(value => (
            <Line key={`g${value}`} x1={0} x2={plot} y1={y(value)} y2={y(value)} stroke={theme.edge} strokeWidth={1} strokeDasharray="3 4" />
          ))}
          {pane.series.filter(series => series.style === 'bar').map(series => series.values.map((value, index) => {
            if (value == null || !Number.isFinite(value)) return null;
            const top = Math.min(y(value), y(0));
            return <Rect key={`${series.key}${index}`} x={x(index) - body / 2} y={top} width={body} height={Math.max(1, Math.abs(y(value) - y(0)))} fill={color(series.tone)} />;
          }))}
          {candles.map((candle, index) => {
            const stroke = candle.closePrice >= candle.openPrice ? theme.up : theme.down;
            const upper = y(Math.max(candle.openPrice, candle.closePrice));
            return (
              <React.Fragment key={candle.recordedAt}>
                <Line x1={x(index)} x2={x(index)} y1={y(candle.highPrice)} y2={y(candle.lowPrice)} stroke={stroke} strokeWidth={1} />
                <Rect x={x(index) - body / 2} y={upper} width={body} height={Math.max(1, y(Math.min(candle.openPrice, candle.closePrice)) - upper)} fill={stroke} />
              </React.Fragment>
            );
          })}
          {pane.series.filter(series => series.style !== 'bar').map(series => (
            <Path key={series.key} d={path(series.values)} stroke={color(series.tone)} strokeWidth={1.5} fill="none" strokeDasharray={series.style === 'dash' ? '4 3' : undefined} />
          ))}
          {lastY != null ? <Line x1={0} x2={plot} y1={lastY} y2={lastY} stroke={theme.brand} strokeWidth={1} strokeDasharray="2 3" /> : null}
        </Svg>
        {/* 오른쪽 가격축: 위아래 범위와 마지막 종가 꼬리표(웹 주 차트의 현재가 꼬리표와 같은 판정색). */}
        {scale && pane.id !== 'count' ? (
          <>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.bound, { top: 0, width: AXIS }]}>{toText(pane.format, pane.domain ? pane.domain[1] : scale.top)}</Txt>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.bound, { bottom: 0, width: AXIS }]}>{toText(pane.format, pane.domain ? pane.domain[0] : scale.bottom)}</Txt>
          </>
        ) : null}
        {lastY != null ? (
          <View style={[styles.lastTag, { top: Math.min(height - 18, Math.max(0, lastY - 9)), width: AXIS - 2, backgroundColor: theme.brand }]}>
            <Txt variant="micro" numeric style={{ color: theme.onBrand }}>{whole.format(lastCandle.closePrice)}</Txt>
          </View>
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  grow: { flex: 1, minWidth: 0 },
  pick: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44, minWidth: 44, paddingHorizontal: SPACE.sm, borderWidth: 1, borderRadius: RADIUS, justifyContent: 'center' },
  fx: { fontStyle: 'italic' },
  empty: { minHeight: 168, alignItems: 'center', justifyContent: 'center' },
  pane: { paddingTop: SPACE.xs },
  legendRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 20 },
  value: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  swatch: { width: 8, height: 8, borderRadius: 2 },
  remove: { width: 20, height: 20, alignItems: 'center', justifyContent: 'center' },
  bound: { position: 'absolute', right: 0, textAlign: 'right' },
  lastTag: { position: 'absolute', right: 0, height: 18, borderRadius: RADIUS_XS, alignItems: 'center', justifyContent: 'center' },
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  sheet: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '85%', borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS, paddingHorizontal: SPACE.sm },
  sheetHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingLeft: SPACE.md, paddingTop: SPACE.xs },
  close: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  type: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACE.md, minHeight: 52, paddingHorizontal: SPACE.md, borderRadius: RADIUS },
  addRow: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACE.sm, marginTop: SPACE.sm, paddingTop: SPACE.md, paddingHorizontal: SPACE.md, borderTopWidth: StyleSheet.hairlineWidth },
  addButton: { minWidth: 88, minHeight: 52 },
  sheetNote: { paddingHorizontal: SPACE.md, paddingTop: SPACE.sm },
});
