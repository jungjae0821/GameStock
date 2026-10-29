/*
 * 차트 위에서 바로 계산하는 기술 지표. 서버 TechnicalIndicators·ResearchChartService와 같은 식이다.
 * 캔들이 체결마다 자라므로 서버를 기다리지 않고 화면이 가진 봉으로 다시 계산한다. 값이 모자라면 null이다.
 */

export type IndicatorType = "SMA" | "EMA" | "RSI" | "MACD" | "BBANDS" | "ATR";
export type IndicatorSpec = { id: string; type: IndicatorType; period: number };
export type Bar = { high: number; low: number; close: number };
export type Series = Array<number | null>;
export type IndicatorResult = { pane: "overlay" | "oscillator"; values: Record<string, Series> };

const blank = (length: number): Series => Array.from({ length }, () => null);

export function sma(values: number[], period: number): Series {
  const out = blank(values.length);
  let sum = 0;
  values.forEach((value, index) => {
    sum += value;
    if (index >= period) sum -= values[index - period];
    if (index >= period - 1) out[index] = sum / period;
  });
  return out;
}

/** 첫 값은 앞 period개의 단순 평균으로 시작한다. 가운데 빈 값이 있으면 거기서 다시 시작한다. */
export function ema(values: Series, period: number): Series {
  const out = blank(values.length);
  let sum = 0;
  let count = 0;
  let previous = 0;
  values.forEach((value, index) => {
    if (value === null) { sum = 0; count = 0; return; }
    if (count < period) {
      sum += value;
      count += 1;
      if (count === period) previous = sum / period; else return;
    } else {
      previous += (value - previous) * 2 / (period + 1);
    }
    out[index] = previous;
  });
  return out;
}

/** 와일더 평균. 오르내림이 모두 0이면 50이다. */
export function rsi(values: number[], period: number): Series {
  const out = blank(values.length);
  let gain = 0;
  let loss = 0;
  let count = 0;
  for (let index = 1; index < values.length; index += 1) {
    const diff = values[index] - values[index - 1];
    const up = Math.max(0, diff);
    const down = Math.max(0, -diff);
    if (count < period) {
      gain += up; loss += down; count += 1;
      if (count === period) { gain /= period; loss /= period; }
    } else {
      gain = (gain * (period - 1) + up) / period;
      loss = (loss * (period - 1) + down) / period;
    }
    if (count >= period) out[index] = loss === 0 ? (gain === 0 ? 50 : 100) : 100 - 100 / (1 + gain / loss);
  }
  return out;
}

/** 모표준편차(나누기 period). */
function stddev(values: number[], period: number): Series {
  const mean = sma(values, period);
  return values.map((_, index) => {
    const center = mean[index];
    if (center === null) return null;
    let total = 0;
    for (let offset = index - period + 1; offset <= index; offset += 1) total += (values[offset] - center) ** 2;
    return Math.sqrt(total / period);
  });
}

function atr(bars: Bar[], period: number): Series {
  const out = blank(bars.length);
  let sum = 0;
  let previous = 0;
  bars.forEach((bar, index) => {
    let range = bar.high - bar.low;
    if (index > 0) range = Math.max(range, Math.abs(bar.high - bars[index - 1].close), Math.abs(bar.low - bars[index - 1].close));
    if (index < period) {
      sum += range;
      if (index === period - 1) out[index] = previous = sum / period;
    } else {
      out[index] = previous = (previous * (period - 1) + range) / period;
    }
  });
  return out;
}

export function computeIndicator(spec: IndicatorSpec, bars: Bar[]): IndicatorResult {
  const close = bars.map((bar) => bar.close);
  switch (spec.type) {
    case "SMA": return { pane: "overlay", values: { value: sma(close, spec.period) } };
    case "EMA": return { pane: "overlay", values: { value: ema(close, spec.period) } };
    case "RSI": return { pane: "oscillator", values: { value: rsi(close, spec.period) } };
    case "ATR": return { pane: "oscillator", values: { value: atr(bars, spec.period) } };
    case "BBANDS": {
      const middle = sma(close, spec.period);
      const deviation = stddev(close, spec.period);
      return {
        pane: "overlay",
        values: {
          upper: middle.map((value, index) => value === null || deviation[index] === null ? null : value + 2 * deviation[index]!),
          middle,
          lower: middle.map((value, index) => value === null || deviation[index] === null ? null : value - 2 * deviation[index]!),
        },
      };
    }
    case "MACD": {
      const fast = ema(close, 12);
      const slow = ema(close, 26);
      const line = fast.map((value, index) => value === null || slow[index] === null ? null : value - slow[index]!);
      const signal = ema(line, 9);
      return {
        pane: "oscillator",
        values: { macd: line, signal, histogram: line.map((value, index) => value === null || signal[index] === null ? null : value - signal[index]!) },
      };
    }
  }
}
