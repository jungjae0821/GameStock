import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Platform, Text } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

// 글은 웹과 같은 경기천년제목 하나로 쓴다. 배포본이 Medium 한 굵기뿐이라
// fontWeight를 주지 않고, 위계는 크기와 색으로 만든다.
export const FONT_FAMILY = 'GyeonggiTitle';
export const FONTS = { [FONT_FAMILY]: require('../assets/fonts/GyeonggiTitleM.ttf') };
// 가격·수량은 폭이 고정된 숫자로 쓴다(PRODUCT.md, DESIGN.md 고정폭 숫자 규칙). 경기천년제목은 숫자 폭이
// 제각각이라 호가 열이 흔들린다. 웹의 Pretendard 자리에 OS 기본 산세리프(SF Pro, Roboto)의 tnum을 쓴다.
export const NUMBER_FONT = Platform.select({ ios: 'System', default: 'sans-serif' });

// DESIGN.md(판정선) 토큰. 글자는 4.5:1, 눌러야 하는 테두리는 3:1 이상이다.
// 핑크는 "지금 여기"(현재가·내 순위)에만, 노랑은 거래 보호(VI·서킷브레이커)에만 쓴다.
const dark = {
  scheme: 'dark',
  ground: '#12101C',
  card: '#1A1726',
  raised: '#241F34',
  pressed: '#241F34',
  line: '#2E2942',
  ink: '#F1EEF8',
  inkSoft: '#AAA4BE',
  up: '#FF6B79',
  upFill: '#E0263C',
  upWash: '#3A1823',
  down: '#8AA8FF',
  downFill: '#2F5FE0',
  downWash: '#1B2445',
  flat: '#AAA4BE',
  brand: '#FD5886',
  brandText: '#FF7AA2',
  brandWash: '#3A1D2C',
  // 핑크 면 위 글자. 흰색은 3.0:1이라 무대색을 쓴다(6.2:1).
  onBrand: '#12101C',
  onFill: '#FFFFFF',
  guard: '#FFC83D',
  guardWash: '#3A3014',
  guardText: '#FFD66E',
  // 관심 종목 별. 그래픽이라 바탕·카드 대비 3:1 이상이면 된다.
  star: '#FFC83D',
  // 기준가 점선과 입력칸·버튼 테두리. 패널 대비 3:1 이상(3.4:1).
  edge: '#6F6986',
  scrim: 'rgba(5, 4, 10, 0.6)',
  // 자산 구성 막대의 종목 칸. 잉크에서 무대 쪽으로 내려가는 무채색 다섯 단계(현금만 핑크).
  // 그래픽 대비 3:1을 지키도록 가장 어두운 칸도 카드 대비 3.9:1에서 멈춘다(DESIGN.md 값보다 한 단계 밝다).
  alloc: ['#F1EEF8', '#CFC9DF', '#ADA6C2', '#918AA8', '#7A7391'],
  // 담보비율 게이지의 반대매매·경고 구간. 연한 바탕색은 트랙과 구분되지 않아(1.0~1.2:1) 진한 면을 쓴다.
  gaugeDanger: '#FF6B79',
  gaugeWarn: '#FFC83D',
  // 지표 선 색. 상승 적·하락 청과 겹치지 않는 색만 쓴다(웹 research.css의 --study-tone-1~6). 마지막은 막대용 무채색.
  study: ['#F5A524', '#3CCFB4', '#B48CFF', '#E5E07A', '#FF8FC7', '#6F6986'],
};

const light = {
  scheme: 'light',
  ground: '#EFEDF5',
  card: '#FFFFFF',
  raised: '#F3F1F8',
  pressed: '#F3F1F8',
  line: '#DEDAE8',
  ink: '#1B1726',
  inkSoft: '#5C566D',
  up: '#C01F34',
  upFill: '#E0263C',
  upWash: '#FDEBEE',
  down: '#1F3F9E',
  downFill: '#2453C9',
  downWash: '#E8EEFC',
  flat: '#5C566D',
  brand: '#FD5886',
  brandText: '#C8285A',
  brandWash: '#FEEEF3',
  onBrand: '#12101C',
  onFill: '#FFFFFF',
  guard: '#E0A400',
  guardWash: '#FFF4D6',
  guardText: '#6B4C00',
  // 보호 노랑(#E0A400)은 바탕 대비 1.9:1이라 별에는 한 단계 진한 금색을 쓴다(바탕 3.9:1, 카드 4.5:1).
  star: '#9A6F00',
  // 바탕(#EFEDF5) 위에서도 3:1을 넘는 값(3.2:1).
  edge: '#87828F',
  scrim: 'rgba(27, 23, 38, 0.4)',
  alloc: ['#1B1726', '#3A3449', '#554F66', '#6F6986', '#857F99'],
  gaugeDanger: '#C01F34',
  gaugeWarn: '#9A6D00',
  // 막대용 무채색은 웹 값(#B9B4C7, 카드 대비 2.0:1)이 그래픽 기준 3:1에 못 미쳐 자산 막대의 한 단계(3.8:1)를 쓴다.
  study: ['#C87400', '#0F8F7A', '#7A4FD6', '#8A8300', '#C2408A', '#857F99'],
};

export const palettes = { dark, light };

// 웹 src/next/uiMode.ts와 같은 저장 키와 같은 기본값(다크).
export const THEME_KEY = 'shuen.theme';
export const DEFAULT_SCHEME = 'dark';

const ThemeContext = createContext(dark);
const ThemeModeContext = createContext({ scheme: DEFAULT_SCHEME, setScheme: () => {}, ready: false });
export const useTheme = () => useContext(ThemeContext);
/** 지금 테마(scheme)와 바꾸는 함수. ready는 저장된 선택을 읽었는지. */
export const useThemeMode = () => useContext(ThemeModeContext);

/** 고른 밝기를 기억하고 앱 전체에 내려 준다. 저장소를 못 읽으면 다크로 시작한다. */
export function ThemeProvider({ children }) {
  const [scheme, setSchemeState] = useState(DEFAULT_SCHEME);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(THEME_KEY)
      .then(stored => { if (active && (stored === 'dark' || stored === 'light')) setSchemeState(stored); })
      .catch(() => {})
      .finally(() => { if (active) setReady(true); });
    return () => { active = false; };
  }, []);
  const setScheme = useCallback(next => {
    if (next !== 'dark' && next !== 'light') return;
    setSchemeState(next);
    AsyncStorage.setItem(THEME_KEY, next).catch(() => {});
  }, []);
  const mode = useMemo(() => ({ scheme, setScheme, ready }), [scheme, setScheme, ready]);
  return (
    <ThemeModeContext.Provider value={mode}>
      <ThemeContext.Provider value={palettes[scheme]}>{children}</ThemeContext.Provider>
    </ThemeModeContext.Provider>
  );
}

// 글자 크기 단계. DESIGN.md의 크기(10~64px) 안에서만 고른다. 픽셀 값을 화면마다 따로 적지 않는다.
export const TYPE = {
  hero: { fontSize: 48, lineHeight: 54 },
  price: { fontSize: 36, lineHeight: 42 },
  page: { fontSize: 26, lineHeight: 32 },
  title: { fontSize: 22, lineHeight: 28 },
  headline: { fontSize: 18, lineHeight: 24 },
  body: { fontSize: 16, lineHeight: 22 },
  callout: { fontSize: 15, lineHeight: 20 },
  caption: { fontSize: 13, lineHeight: 18 },
  micro: { fontSize: 12, lineHeight: 16 },
};
// 숫자 서체는 굵기가 여럿이라 큰 가격만 굵게 쓴다(DESIGN.md Price·Hero 700).
const NUMBER_WEIGHT = { hero: '700', price: '700', page: '600', title: '600' };

export const SPACE = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };
// 모서리는 DESIGN.md의 네 단계만 쓴다: 8(카드·버튼·행·입력), 6(작은 선택 버튼), 4(배지·태그), 3(막대).
export const RADIUS = 8;
export const RADIUS_SM = 6;
export const RADIUS_XS = 4;

/** 모든 텍스트는 이 컴포넌트로 그린다. 시스템 글자 크기 설정은 1.4배까지 따른다. */
export function Txt({ variant = 'body', tone = 'ink', style, numeric, ...props }) {
  const theme = useTheme();
  return (
    <Text
      maxFontSizeMultiplier={1.4}
      {...props}
      style={[
        { fontFamily: FONT_FAMILY, color: theme[tone] ?? tone },
        TYPE[variant],
        numeric && { fontFamily: NUMBER_FONT, fontVariant: ['tabular-nums'], fontWeight: NUMBER_WEIGHT[variant] ?? '500', letterSpacing: -0.2 },
        style,
      ]}
    />
  );
}

export const toneOf = value => (value > 0 ? 'up' : value < 0 ? 'down' : 'flat');

/** 두 색을 섞는다(amount는 a의 비율). 흰 바탕 기준으로 정한 칭호 색을 테마에 맞출 때 쓴다. */
export function mixColor(a, b, amount) {
  const parse = hex => [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16));
  const [x, y] = [parse(a), parse(b)];
  return `#${x.map((value, index) => Math.round(value * amount + y[index] * (1 - amount)).toString(16).padStart(2, '0')).join('')}`;
}
