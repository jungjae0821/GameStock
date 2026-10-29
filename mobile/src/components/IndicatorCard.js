import React, { useEffect, useMemo, useState } from 'react';
import { AppState, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Svg, { Line, Path, Rect } from 'react-native-svg';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { analyzeChart, CHART_RANGES, failure, serverTime } from '../research';
import { RADIUS, RADIUS_XS, SPACE, Txt, useTheme } from '../theme';
import { Button, Card, CardHead, Empty, Field } from './Kit';

// 웹 IndicatorChart와 같은 지표와 기본 기간. MACD는 12/26/9로 고정이라 기간을 받지 않는다.
const TYPES = [
  { key: 'SMA', label: '단순 이동평균', period: 20, note: '종가 평균' },
  { key: 'EMA', label: '지수 이동평균', period: 20, note: '최근 값에 가중' },
  { key: 'BBANDS', label: '볼린저 밴드', period: 20, note: '평균 ± 2표준편차' },
  { key: 'RSI', label: 'RSI', period: 14, note: '70 과열, 30 침체' },
  { key: 'MACD', label: 'MACD', period: 26, note: '12/26/9 고정' },
  { key: 'ATR', label: 'ATR', period: 14, note: '평균 변동폭' },
];
const LABEL = Object.fromEntries(TYPES.map(item => [item.key, item.label]));
const DEFAULTS = [
  { id: 'sma5', type: 'SMA', period: 5 },
  { id: 'ema20', type: 'EMA', period: 20 },
  { id: 'rsi14', type: 'RSI', period: 14 },
];
const MAX_INDICATORS = 12;
const REFRESH_MS = 15_000;
const STORAGE_KEY = 'gamestock.indicators';
// 폰 폭에서 캔들이 1px로 뭉개지지 않도록 최근 캔들만 그린다. 지표 값도 같은 칸만큼 잘라 맞춘다.
const MAX_BARS = 60;
// 오른쪽 가격축 폭. 마지막 종가 꼬리표가 이 칸에 선다.
const AXIS = 56;

const whole = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 });
const compact = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 });
const toText = (format, value) => (value == null || !Number.isFinite(value) ? '-' : format.format(value));

/** id는 [A-Za-z][A-Za-z0-9_]{0,31}이고 겹치면 안 된다. */
function nextId(type, period, taken) {
  const base = `${type.toLowerCase()}${period}`;
  let id = base;
  for (let suffix = 2; taken.some(item => item.id === id); suffix += 1) id = `${base}_${suffix}`;
  return id;
}

const nameOf = spec => (spec.type === 'MACD' ? 'MACD 12 26 9' : `${spec.type === 'BBANDS' ? 'BB' : spec.type} ${spec.period}`);

function seriesFor(spec, values, tone) {
  if (spec.type === 'BBANDS') return [
    { key: `${spec.id}.upper`, values: values.upper ?? [], tone, style: 'dash' },
    { key: `${spec.id}.middle`, values: values.middle ?? [], tone },
    { key: `${spec.id}.lower`, values: values.lower ?? [], tone, style: 'dash' },
  ];
  if (spec.type === 'MACD') return [
    { key: `${spec.id}.macd`, values: values.macd ?? [], tone: 0 },
    { key: `${spec.id}.signal`, values: values.signal ?? [], tone: 1 },
    { key: `${spec.id}.histogram`, values: values.histogram ?? [], tone: 5, style: 'bar' },
  ];
  return [{ key: `${spec.id}.value`, values: values.value ?? [], tone }];
}

/** 종목 상세의 지표 분석. 웹과 같이 간격 탭과 지표 버튼 한 줄, 범례 속 끄기, 지표 고르기 시트로 이뤄진다. */
export function IndicatorCard({ code, name }) {
  const [settings, setSettings] = useState(null);
  const [chart, setChart] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [picking, setPicking] = useState(false);

  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(STORAGE_KEY)
      .then(raw => {
        const saved = raw ? JSON.parse(raw) : null;
        const valid = saved && CHART_RANGES.some(item => item.key === saved.range) && Array.isArray(saved.indicators);
        if (active) setSettings(valid
          ? { range: saved.range, indicators: saved.indicators.filter(item => LABEL[item.type] && Number.isInteger(item.period)).slice(0, MAX_INDICATORS) }
          : { range: '1h', indicators: DEFAULTS });
      })
      .catch(() => { if (active) setSettings({ range: '1h', indicators: DEFAULTS }); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (settings) AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(settings)).catch(() => {});
  }, [settings]);

  const key = settings ? `${code}|${settings.range}|${settings.indicators.map(item => `${item.id}:${item.period}`).join(',')}` : null;

  // 설정이 바뀌면 잠깐 기다렸다 부른다. 마지막 캔들이 계속 자라므로 열려 있는 동안 15초마다 다시 받는다.
  useEffect(() => {
    if (!settings) return undefined;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const next = await analyzeChart(code, settings.range, settings.indicators);
        if (!cancelled) { setChart(next); setError(''); }
      } catch (reason) {
        if (!cancelled) setError(failure(reason, '지표를 계산하지 못했어요. 15초 뒤 다시 계산해요.'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    const debounce = setTimeout(() => void load(), 250);
    const timer = setInterval(() => { if (AppState.currentState === 'active') void load(); }, REFRESH_MS);
    return () => { cancelled = true; clearTimeout(debounce); clearInterval(timer); };
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!settings) {
    return (
      <Card>
        <CardHead title="지표 분석" />
        <Empty loading text="지표 설정을 불러오는 중이에요" />
      </Card>
    );
  }
  const { range, indicators } = settings;
  const longest = Math.max(0, ...indicators.map(item => (item.type === 'MACD' ? 35 : item.period)));
  const remove = id => setSettings(current => ({ ...current, indicators: current.indicators.filter(item => item.id !== id) }));
  const add = (type, period) => setSettings(current => (current.indicators.length >= MAX_INDICATORS ? current
    : { ...current, indicators: [...current.indicators, { id: nextId(type, period, current.indicators), type, period }] }));

  return (
    <Card>
      <CardHead title="지표 분석" meta={loading ? '계산 중' : null} />
      <Toolbar range={range} count={indicators.length} onRange={next => setSettings(current => ({ ...current, range: next }))} onPick={() => setPicking(true)} />
      {error ? <Txt variant="caption" accessibilityLiveRegion="polite">{error}</Txt> : null}
      {!chart ? (
        error ? null : <Empty loading text="지표를 계산하는 중이에요" />
      ) : chart.candles.length === 0 ? (
        <Empty text="이 간격으로 쌓인 캔들이 아직 없어요. 더 긴 간격을 골라 보세요." />
      ) : <StudyChart chart={chart} indicators={indicators} name={name} onRemove={remove} />}
      {chart?.candles.length ? (
        <Txt variant="micro" tone="inkSoft">
          {indicators.length === 0 ? '지표를 모두 껐어요. 오른쪽 위 지표 버튼으로 다시 켤 수 있어요. ' : ''}
          {`최근 캔들 ${Math.min(MAX_BARS, chart.candles.length)}개. 마지막 캔들은 아직 진행 중일 수 있어요. 막대는 거래량이 아니라 체결 건수예요.`}
          {longest > chart.candles.length ? ' 캔들 수가 지표 기간보다 적으면 값이 비어 있어요.' : ''}
        </Txt>
      ) : null}
      <PickerSheet visible={picking} count={indicators.length} onAdd={add} onClose={() => setPicking(false)} />
    </Card>
  );
}

/** 간격 글자 탭과 지표 버튼 한 줄. 상자 없이 아래 괘선 하나로 차트와 나눈다. */
function Toolbar({ range, count, onRange, onPick }) {
  const theme = useTheme();
  return (
    <View style={[styles.toolbar, { borderBottomColor: theme.line }]}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.grow} accessibilityRole="tablist" accessibilityLabel="캔들 간격">
        {CHART_RANGES.map(item => {
          const selected = item.key === range;
          return (
            <Pressable key={item.key} accessibilityRole="tab" accessibilityState={{ selected }} onPress={() => onRange(item.key)} style={styles.tab}>
              <Txt variant="caption" tone={selected ? 'ink' : 'inkSoft'}>{item.label}</Txt>
              <View style={[styles.tabLine, selected && { backgroundColor: theme.ink }]} />
            </Pressable>
          );
        })}
      </ScrollView>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`지표 추가, 지금 ${count}개 켜짐`}
        onPress={onPick}
        style={({ pressed }) => [styles.pick, pressed && { backgroundColor: theme.raised }]}
      >
        <Txt variant="caption" style={styles.fx}>fx</Txt>
        <Txt variant="caption">지표</Txt>
        <Txt variant="caption" tone="inkSoft" numeric>{count}</Txt>
      </Pressable>
    </View>
  );
}

/** 지표 고르기 시트. 종류를 고르고 기간을 정해 추가한다. */
function PickerSheet({ visible, count, onAdd, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [type, setType] = useState('SMA');
  const [period, setPeriod] = useState('20');
  const fixed = type === 'MACD';
  const value = fixed ? 26 : Number(period);
  const periodError = !fixed && period !== '' && (!Number.isInteger(value) || value < 2 || value > 500) ? '기간은 2에서 500 사이 정수로 넣어 주세요.' : '';
  const full = count >= MAX_INDICATORS;
  const blocked = full || Boolean(periodError) || (!fixed && period === '');
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
        <Txt variant="caption" tone={periodError ? 'ink' : 'inkSoft'} style={styles.sheetNote} accessibilityLiveRegion="polite">
          {periodError || (full ? `지표는 ${MAX_INDICATORS}개까지 켤 수 있어요. 차트 범례의 ×로 하나를 끈 뒤 추가해 주세요.` : `${count}/${MAX_INDICATORS}개 켜짐`)}
        </Txt>
      </View>
    </Modal>
  );
}

/** 칸마다 세로 범위가 따로이고 시간 축은 모든 칸이 같이 쓴다. 값이 null인 곳은 선을 끊는다. */
function StudyChart({ chart, indicators, name, onRemove }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);

  const panes = useMemo(() => {
    const from = Math.max(0, chart.candles.length - MAX_BARS);
    const candles = chart.candles.slice(from);
    const cut = values => values.slice(from);
    const specs = new Map(indicators.map(item => [item.id, item]));
    const overlay = [];
    const overlayRows = [];
    const oscillators = [];
    let tone = 0;
    for (const series of chart.indicators) {
      const spec = specs.get(series.id);
      if (!spec) continue;
      const lines = seriesFor(spec, series.values, series.pane === 'overlay' ? tone : 2).map(item => ({ ...item, values: cut(item.values) }));
      const row = { id: spec.id, label: nameOf(spec), lines };
      if (series.pane === 'overlay') {
        overlay.push(...lines);
        overlayRows.push(row);
        tone = (tone + 1) % 5;
      } else {
        oscillators.push({
          id: spec.id,
          height: 88,
          series: lines,
          rows: [row],
          guides: spec.type === 'RSI' ? [30, 70] : spec.type === 'MACD' ? [0] : [],
          domain: spec.type === 'RSI' ? [0, 100] : null,
          format: spec.type === 'ATR' ? whole : decimal,
        });
      }
    }
    return {
      candles,
      first: serverTime(candles[0]?.recordedAt),
      last: serverTime(candles[candles.length - 1]?.recordedAt),
      list: [
        { id: 'price', title: name, height: 188, candles, series: overlay, rows: overlayRows, guides: [], format: whole, lastPrice: true },
        { id: 'count', title: '체결 건수', height: 44, series: [{ key: 'count', values: candles.map(item => item.volume), style: 'bar', tone: 5 }], rows: [], guides: [], format: compact },
        ...oscillators,
      ],
    };
  }, [chart, indicators, name]);

  const plot = Math.max(0, width - AXIS);
  const slot = plot > 0 ? plot / panes.candles.length : 0;
  const longRange = panes.last - panes.first > 26 * 60 * 60_000;
  return (
    <View onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))}>
      {width > 0 ? panes.list.map((pane, index) => (
        <Pane key={pane.id} pane={pane} width={width} plot={plot} slot={slot} theme={theme} divided={index > 0} onRemove={onRemove} />
      )) : null}
      <View style={[styles.axis, { width: plot }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        <Txt variant="micro" tone="inkSoft" numeric>{shortTime(panes.first, longRange)}</Txt>
        <Txt variant="micro" tone="inkSoft" numeric>{shortTime(panes.last, longRange)}</Txt>
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

const lastValue = values => {
  for (let index = values.length - 1; index >= 0; index -= 1) if (values[index] != null && Number.isFinite(values[index])) return values[index];
  return null;
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
  const previous = candles[candles.length - 2]?.closePrice;
  const change = lastCandle && previous ? (lastCandle.closePrice - previous) / previous : null;
  const lastY = pane.lastPrice && lastCandle ? y(lastCandle.closePrice) : null;

  return (
    <View style={[styles.pane, divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }]}>
      {/* 범례: 칸 이름 줄(캔들·건수 칸만), 그 아래 지표마다 값과 끄기 버튼. */}
      {pane.title ? (
        <View style={styles.legendRow} accessible accessibilityLabel={lastCandle
          ? `${pane.title}. 마지막 캔들 시가 ${whole.format(lastCandle.openPrice)}, 고가 ${whole.format(lastCandle.highPrice)}, 저가 ${whole.format(lastCandle.lowPrice)}, 종가 ${whole.format(lastCandle.closePrice)}`
          : `${pane.title} ${toText(pane.format, lastValue(pane.series[0]?.values ?? []))}`}
        >
          <Txt variant="micro" numberOfLines={1} style={styles.shrink}>{pane.title}</Txt>
          {lastCandle ? (
            <Txt variant="micro" tone={!change ? 'flat' : change > 0 ? 'up' : 'down'} numeric numberOfLines={1}>
              {`종 ${whole.format(lastCandle.closePrice)}${change != null ? ` ${change >= 0 ? '+' : ''}${(change * 100).toFixed(2)}%` : ''}`}
            </Txt>
          ) : <Txt variant="micro" numeric>{toText(pane.format, lastValue(pane.series[0]?.values ?? []))}</Txt>}
        </View>
      ) : null}
      {pane.rows.map(row => (
        <View key={row.id} style={styles.legendRow}>
          <Txt variant="micro" tone="inkSoft">{row.label}</Txt>
          {/* 값 글자는 잉크색, 선 색은 앞의 작은 칸으로만 보인다. 밝은 테마의 몇몇 선 색은 글자 대비 4.5:1에 못 미친다. */}
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
          {pane.guides.map(value => (
            <Line key={`g${value}`} x1={0} x2={plot} y1={y(value)} y2={y(value)} stroke={theme.edge} strokeWidth={1} strokeDasharray="3 4" />
          ))}
          {pane.series.filter(series => series.style === 'bar').map(series => series.values.map((value, index) => {
            if (value == null || !Number.isFinite(value)) return null;
            const top = Math.min(y(value), y(0));
            return <Rect key={`${series.key}${index}`} x={x(index) - body / 2} y={top} width={body} height={Math.max(1, Math.abs(y(value) - y(0)))} fill={color(series.tone)} />;
          }))}
          {candles.map((candle, index) => {
            const up = candle.closePrice >= candle.openPrice;
            const stroke = up ? theme.up : theme.down;
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
        {/* 오른쪽 가격축: 위아래 범위와 마지막 종가 꼬리표(웹·주 차트의 현재가 꼬리표와 같은 판정색). */}
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
  shrink: { flexShrink: 1 },
  toolbar: { flexDirection: 'row', alignItems: 'center', gap: SPACE.xs, borderBottomWidth: StyleSheet.hairlineWidth },
  tab: { minWidth: 44, height: 44, paddingHorizontal: SPACE.sm, alignItems: 'center', justifyContent: 'center' },
  tabLine: { position: 'absolute', left: SPACE.sm, right: SPACE.sm, bottom: 0, height: 2 },
  pick: { flexDirection: 'row', alignItems: 'center', gap: 4, height: 44, paddingHorizontal: SPACE.sm, borderRadius: RADIUS },
  fx: { fontStyle: 'italic' },
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
