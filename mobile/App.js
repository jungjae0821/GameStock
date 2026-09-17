import React, { useEffect, useMemo, useState } from 'react';
import * as WebBrowser from 'expo-web-browser';
import * as Google from 'expo-auth-session/providers/google';
import { initializeApp } from 'firebase/app';
import { GoogleAuthProvider, getAuth, getReactNativePersistence, initializeAuth, onAuthStateChanged, signInWithCredential, signOut } from 'firebase/auth';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Alert, Image, Linking, Modal, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View, useColorScheme } from 'react-native';
import { API_BASE_URL, MARKET_SOCKET_URL, FIREBASE_CONFIG, GOOGLE_WEB_CLIENT_ID } from './src/config';

// These bundled PNGs are the same source files used by the web client.
const GAME_ICONS = {
  UMA: require('./assets/game-icons/UMA.png'),
  BA: require('./assets/game-icons/BA.png'),
  GOV: require('./assets/game-icons/GOV.png')
};
const CHART_RANGES = [
  ['5m', '5분'], ['10m', '10분'], ['30m', '30분'], ['1h', '1시간'],
  ['6h', '6시간'], ['12h', '12시간'], ['24h', '24시간'], ['7d', '일주일']
];

WebBrowser.maybeCompleteAuthSession();
const firebaseApp = initializeApp(FIREBASE_CONFIG);
let firebaseAuth;
try { firebaseAuth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) }); }
catch { firebaseAuth = getAuth(firebaseApp); }
const LANGUAGE_KEY = 'gamestock-language';
const THEME_KEY = 'gamestock-theme';
const LOCALES = { ko: 'ko-KR', ja: 'ja-JP', en: 'en-US' };
const TEXT = {
  ja: {
    '서버 연결 중': 'サーバー接続中', '실시간 연결됨': 'リアルタイム接続済み', '실시간 연결 실패': 'リアルタイム接続失敗', 'API 주소를 확인하세요': 'APIアドレスを確認してください',
    '메뉴': 'メニュー', '내 계정': 'マイアカウント', 'Google 로그인': 'Googleでログイン', '마이페이지': 'マイページ', '랭킹': 'ランキング', '로그아웃': 'ログアウト',
    '다크 모드': 'ダークモード', '라이트 모드': 'ライトモード', '언어': '言語', '총 자산': '総資産', '현금': '現金',
    '시장은 누구나 볼 수 있습니다. 거래하려면 Google 로그인하세요.': '市場は誰でも閲覧できます。取引するにはGoogleでログインしてください。',
    '오늘의 게임 종목': '今日のゲーム銘柄', '거래량': '出来高', '탭하여 거래': 'タップして取引', '가격 결정 근거 · 최근 24시간': '価格決定の根拠 · 直近24時間',
    '마지막 뉴스': '最終ニュース', '마지막 체결': '最終約定', '없음': 'なし', '뉴스 흐름': 'ニュース動向', '상승 방향': '上昇方向', '하락 방향': '下落方向', '혼합·보합': '混合・横ばい',
    '이용자 거래': 'ユーザー取引', '매수 우세': '買い優勢', '매도 우세': '売り優勢', '현재 호가': '現在の板', '매수': '買い', '매도': '売り',
    '가격 추이': '価格推移', '가격 데이터 없음': '価格データなし', '5분': '5分', '10분': '10分', '30분': '30分', '1시간': '1時間', '6시간': '6時間', '12시간': '12時間', '24시간': '24時間', '일주일': '1週間', '일별 시세 (시가 · 종가 · 거래량)': '日別相場（始値・終値・出来高）', '시가': '始値', '종가': '終値',
    '아직 집계된 일별 체결이 없습니다.': '集計された日別約定はまだありません。', '호가 깊이': '板の厚み', '매도 대기 없음': '売り注文なし', '매수 대기 없음': '買い注文なし',
    '주문': '注文', '시장가': '成行', '지정가': '指値', '수량': '数量', '시장가 예상 체결금액': '成行の予想約定金額', '주문 제출': '注文する',
    '최근 전체 체결 10건': '直近10件の全約定', '관련 뉴스': '関連ニュース', '관련 뉴스 없음': '関連ニュースなし', '시장 전체': '市場全体', '시장 이벤트': '市場イベント',
    '아직 수집된 뉴스가 없습니다.': '収集されたニュースはまだありません。', '인생 리셋': '人生リセット', '닫기': '閉じる', '뉴스 상세': 'ニュース詳細', '원문 기사 열기 ↗': '元記事を開く ↗',
    '투자 랭킹': '投資ランキング', '봇을 제외한 사용자만 표시됩니다.': 'ボットを除くユーザーのみ表示されます。', '아직 랭킹에 참여한 사용자가 없습니다.': 'ランキング参加者はまだいません。',
    '한강 수온(선유) 조회 불가': '漢江水温（仙遊）取得不可', '보유 종목': '保有銘柄', '미체결 주문': '未約定注文', '체결 완료': '約定完了', '프로필 저장': 'プロフィール保存',
    '닉네임 설정': 'ニックネーム設定', '첫 로그인 기념으로 닉네임을 정해 주세요.': '初回ログインのニックネームを設定してください。', '프로필 정보를 관리하세요.': 'プロフィール情報を管理します。'
  },
  en: {
    '서버 연결 중': 'Connecting to server', '실시간 연결됨': 'Live', '실시간 연결 실패': 'Live connection failed', 'API 주소를 확인하세요': 'Check the API address',
    '메뉴': 'Menu', '내 계정': 'My account', 'Google 로그인': 'Sign in with Google', '마이페이지': 'My page', '랭킹': 'Ranking', '로그아웃': 'Sign out',
    '다크 모드': 'Dark mode', '라이트 모드': 'Light mode', '언어': 'Language', '총 자산': 'Total assets', '현금': 'Cash',
    '시장은 누구나 볼 수 있습니다. 거래하려면 Google 로그인하세요.': 'Anyone can view the market. Sign in with Google to trade.',
    '오늘의 게임 종목': "Today's game stocks", '거래량': 'Volume', '탭하여 거래': 'Tap to trade', '가격 결정 근거 · 최근 24시간': 'Price drivers · Last 24 hours',
    '마지막 뉴스': 'Last news', '마지막 체결': 'Last execution', '없음': 'None', '뉴스 흐름': 'News flow', '상승 방향': 'Upward', '하락 방향': 'Downward', '혼합·보합': 'Mixed or flat',
    '이용자 거래': 'User trading', '매수 우세': 'Buy-dominant', '매도 우세': 'Sell-dominant', '현재 호가': 'Current book', '매수': 'Buy', '매도': 'Sell',
    '가격 추이': 'Price history', '가격 데이터 없음': 'No price data', '5분': '5 min', '10분': '10 min', '30분': '30 min', '1시간': '1 hour', '6시간': '6 hours', '12시간': '12 hours', '24시간': '24 hours', '일주일': '1 week', '일별 시세 (시가 · 종가 · 거래량)': 'Daily prices (open · close · volume)', '시가': 'Open', '종가': 'Close',
    '아직 집계된 일별 체결이 없습니다.': 'There are no aggregated daily executions yet.', '호가 깊이': 'Order book depth', '매도 대기 없음': 'No sell orders', '매수 대기 없음': 'No buy orders',
    '주문': 'Order', '시장가': 'Market', '지정가': 'Limit', '수량': 'Quantity', '시장가 예상 체결금액': 'Estimated market fill', '주문 제출': 'Place order',
    '최근 전체 체결 10건': 'Latest 10 executions', '관련 뉴스': 'Related news', '관련 뉴스 없음': 'No related news', '시장 전체': 'Whole market', '시장 이벤트': 'Market events',
    '아직 수집된 뉴스가 없습니다.': 'No news has been collected yet.', '인생 리셋': 'Account reset', '닫기': 'Close', '뉴스 상세': 'News details', '원문 기사 열기 ↗': 'Open source article ↗',
    '투자 랭킹': 'Investment ranking', '봇을 제외한 사용자만 표시됩니다.': 'Only non-bot users are shown.', '아직 랭킹에 참여한 사용자가 없습니다.': 'No users have joined the ranking yet.',
    '한강 수온(선유) 조회 불가': 'Han River temperature (Seonyu) unavailable', '보유 종목': 'Holdings', '미체결 주문': 'Open orders', '체결 완료': 'Executed', '프로필 저장': 'Save profile',
    '닉네임 설정': 'Set nickname', '첫 로그인 기념으로 닉네임을 정해 주세요.': 'Choose a nickname for your first login.', '프로필 정보를 관리하세요.': 'Manage your profile.'
  }
};
const detectLanguage = () => {
  const code = String(Intl.DateTimeFormat().resolvedOptions().locale || 'ko').toLowerCase().split('-')[0];
  return ['ko', 'ja', 'en'].includes(code) ? code : 'ko';
};
const translate = (language, source) => TEXT[language]?.[source] || source;
const money = (value, language = 'ko') => {
  const rankingValue = value && typeof value === 'object';
  const amount = rankingValue ? value.amount : value;
  const formatted = new Intl.NumberFormat(LOCALES[language], { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(Number(amount || 0));
  if (!rankingValue || value.changePercent == null) return formatted;
  const change = Number(value.changePercent);
  return `${formatted} (${change >= 0 ? '+' : ''}${change.toFixed(2)}%)`;
};
const signedMoney = (value, language) => { const amount = Number(value || 0); return `${amount > 0 ? '+' : amount < 0 ? '-' : ''}${money(Math.abs(amount), language)}`; };
const describePriceReason = (event, t) => event?.priceReason || (Number(event?.priceChangePercent || 0) > 0 ? t('최근 거래 흐름을 따라 가격이 상승하고 있습니다.') : Number(event?.priceChangePercent || 0) < 0 ? t('최근 거래 흐름을 따라 가격이 하락하고 있습니다.') : t('최근 가격은 보합 상태입니다.'));
const describePriceLabel = event => { const change = Number(event?.priceChangePercent || 0); return change > 0 ? `▲ ${change.toFixed(2)}%` : change < 0 ? `▼ ${Math.abs(change).toFixed(2)}%` : '— 0.00%'; };
const summarizeNews = description => { const summary = String(description || '').replace(/\s*출처:\s*https?:\/\/\S+\s*$/i, '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&quot;/gi, '"').replace(/&#39;/g, "'").replace(/\s+/g, ' ').trim(); return summary || '제공된 뉴스 요약이 없습니다.'; };
const newsSourceUrl = description => { const match = String(description || '').match(/출처:\s*(https?:\/\/[^\s<]+)/i); return match ? match[1] : null; };

function NewsModal({ news, stocks, onClose, styles, t }) {
  const liveStock = stocks?.find(stock => stock.code === news?.stockCode);
  const change = liveStock ? Number(liveStock.changePercent || 0) : Number(news?.priceChangePercent || 0);
  const priceStyle = change > 0 ? styles.up : change < 0 ? styles.down : styles.eventNeutral;
  const sourceUrl = newsSourceUrl(news?.description);
  return <Modal transparent visible={Boolean(news)} animationType="fade" onRequestClose={onClose}><View style={styles.modalBackdrop}><View style={styles.modalCard}><Text style={styles.eyebrow}>NEWS DETAIL</Text><Text style={styles.modalHelp}>{news?.stockCode || t('시장 전체')}</Text><Text style={styles.detailTitle}>{news?.title || t('뉴스 상세')}</Text><View style={styles.newsModalPriceRow}><Text style={priceStyle}>{describePriceLabel(news)}</Text></View><Text style={styles.newsModalReason}>{describePriceReason(news, t)}</Text><Text style={styles.newsModalSummary}>{summarizeNews(news?.description)}</Text>{sourceUrl && <TouchableOpacity style={styles.newsModalSource} onPress={() => Linking.openURL(sourceUrl)}><Text style={styles.newsModalSourceText}>{t('원문 기사 열기 ↗')}</Text></TouchableOpacity>}<TouchableOpacity style={styles.modalClose} onPress={onClose}><Text style={styles.text}>{t('닫기')}</Text></TouchableOpacity></View></View></Modal>;
}

export default function App() {
  const systemScheme = useColorScheme();
  const [language, setLanguageState] = useState(detectLanguage);
  const [theme, setThemeState] = useState(systemScheme === 'dark' ? 'dark' : 'light');
  const isDark = theme === 'dark';
  const styles = useMemo(() => createStyles(isDark), [isDark]);
  const t = source => translate(language, source);
  const locale = LOCALES[language] || LOCALES.ko;
  const formatMoney = value => money(value, language);
  const [market, setMarket] = useState({ stocks: [], portfolio: null, events: [] });
  const [selected, setSelected] = useState(null);
  const [book, setBook] = useState({ bids: [], asks: [] });
  const [trades, setTrades] = useState([]);
  const [history, setHistory] = useState([]);
  const [chartHistory, setChartHistory] = useState([]);
  const [chartRange, setChartRange] = useState('24h');
  const [daily, setDaily] = useState([]);
  const [news, setNews] = useState([]);
  const [priceDrivers, setPriceDrivers] = useState(null);
  const [side, setSide] = useState('BUY');
  const [orderType, setOrderType] = useState('MARKET');
  const [quantity, setQuantity] = useState('1');
  const [limitPrice, setLimitPrice] = useState('');
  const [user, setUser] = useState(null);
  const [account, setAccount] = useState(null);
  const [profile, setProfile] = useState(null);
  const [openOrders, setOpenOrders] = useState([]);
  const [settlements, setSettlements] = useState([]);
  const [menuOpen, setMenuOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [rankingOpen, setRankingOpen] = useState(false);
  const [ranking, setRanking] = useState([]);
  const [nickname, setNickname] = useState('');
  const [profileMessage, setProfileMessage] = useState('');
  const [status, setStatus] = useState('서버 연결 중');
  const [riverTemperature, setRiverTemperature] = useState(null);
  const [message, setMessage] = useState('');
  const [selectedNews, setSelectedNews] = useState(null);
  const [request, response, promptAsync] = Google.useAuthRequest({ webClientId: GOOGLE_WEB_CLIENT_ID });

  useEffect(() => {
    AsyncStorage.multiGet([LANGUAGE_KEY, THEME_KEY]).then(([[, storedLanguage], [, storedTheme]]) => {
      if (['ko', 'ja', 'en'].includes(storedLanguage)) setLanguageState(storedLanguage);
      if (storedTheme === 'light' || storedTheme === 'dark') setThemeState(storedTheme);
    }).catch(() => {});
  }, []);
  const setLanguage = next => {
    setLanguageState(next);
    AsyncStorage.setItem(LANGUAGE_KEY, next).catch(() => {});
  };
  const toggleTheme = () => {
    const next = isDark ? 'light' : 'dark';
    setThemeState(next);
    AsyncStorage.setItem(THEME_KEY, next).catch(() => {});
  };

  const api = async (path, options = {}) => {
    const token = firebaseAuth.currentUser ? await firebaseAuth.currentUser.getIdToken() : null;
    const result = await fetch(`${API_BASE_URL}${path}`, { ...options, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers || {}) } });
    const payload = result.status === 204 ? null : await result.json();
    if (!result.ok) throw new Error(payload.message || '요청 처리에 실패했습니다.');
    return payload;
  };
  const loadRiverTemperature = async () => { try { setRiverTemperature(await api('/api/han-river-temperature')); } catch { setRiverTemperature({ available: false, message: '조회 불가' }); } };

  const refreshPortfolio = async () => {
    if (!firebaseAuth.currentUser) return;
    const portfolio = await api('/api/portfolio');
    setMarket(previous => ({ ...previous, portfolio }));
    if (profileOpen) setSettlements(await api('/api/settlements'));
  };
  const openProfile = async () => { setMenuOpen(false); setProfileOpen(true); setProfileMessage('불러오는 중입니다…'); try { const [nextProfile, nextOrders, nextSettlements] = await Promise.all([api('/api/profile'), api('/api/orders'), api('/api/settlements')]); setProfile(nextProfile); setOpenOrders(nextOrders); setSettlements(nextSettlements); setNickname(nextProfile.nickname || ''); setProfileMessage(''); } catch (error) { setProfileMessage(error.message); } };
  const saveProfile = async () => { try { setProfileMessage('저장 중입니다…'); const nextProfile = await api('/api/profile', { method: 'PATCH', body: JSON.stringify({ nickname: nickname.trim() }) }); setProfile(nextProfile); setAccount(previous => ({ ...previous, nickname: nextProfile.nickname, requiresNickname: false })); setProfileMessage('프로필이 저장되었습니다.'); setTimeout(() => setProfileOpen(false), 500); } catch (error) { setProfileMessage(error.message); } };
  const performAccountReset = async () => { try { setProfileMessage('계정을 초기화하는 중입니다…'); await api('/api/account/reset', { method: 'DELETE' }); setAccount(previous => ({ ...previous, nickname: '', requiresNickname: true })); setNickname(''); await refreshPortfolio(); setProfile(await api('/api/profile')); setProfileMessage('계정이 초기화되었습니다. 새 닉네임을 정하면 다시 시작할 수 있습니다.'); } catch (error) { setProfileMessage(error.message); } };
  const resetAccount = () => { if (!user) return; Alert.alert('인생 리셋', '보유 주식, 주문, 출석 기록이 모두 초기화됩니다. Google 계정당 한 번만 사용할 수 있습니다.', [{ text: '취소', style: 'cancel' }, { text: '초기화', style: 'destructive', onPress: performAccountReset }]); };
  const cancelOpenOrder = async orderId => { try { setProfileMessage('주문을 취소하는 중입니다…'); await api(`/api/orders/${orderId}`, { method: 'DELETE' }); setOpenOrders(previous => previous.filter(order => order.id !== orderId)); setProfileMessage('주문이 취소되었습니다. 예약된 자산이 반환되었습니다.'); await refreshPortfolio(); } catch (error) { setProfileMessage(error.message); } };
  const openRanking = async () => { setMenuOpen(false); setRankingOpen(true); try { const rows = await api('/api/ranking'); setRanking(rows.map(entry => ({ ...entry, totalAsset: { amount: entry.totalAsset, changePercent: entry.changePercent } }))); } catch (error) { setMessage(error.message); } };
  const loadDetails = async code => { setSelected(code); setPriceDrivers(null); try { const [nextBook, nextTrades, nextHistory, nextNews, nextDaily, nextDrivers] = await Promise.all([api(`/api/orderbook/${code}`), api(`/api/stocks/${code}/trades`), api(`/api/stocks/${code}/history`), api(`/api/stocks/${code}/news`), api(`/api/stocks/${code}/daily`), api(`/api/stocks/${code}/price-drivers`)]); setBook(nextBook); setTrades(nextTrades); setHistory(nextHistory); setNews(nextNews); setDaily(nextDaily); setPriceDrivers(nextDrivers); } catch (error) { setMessage(error.message); } };
  const priceReason = event => describePriceReason(event, t);
  const loadChartHistory = async (code, range = chartRange) => { try { const next = await api(`/api/stocks/${code}/history?range=${range}`); if (code === selected) setChartHistory(next); } catch { if (code === selected) setChartHistory([]); } };

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(firebaseAuth, async current => { setUser(current); if (current) { try { const serverUser = await api('/api/auth/google', { method: 'POST' }); setAccount(serverUser); await refreshPortfolio(); } catch (error) { setMessage(error.message); } } else { setAccount(null); setProfile(null); setMarket(previous => ({ ...previous, portfolio: null })); } });
    Promise.all([fetch(`${API_BASE_URL}/api/stocks`).then(result => result.json()), fetch(`${API_BASE_URL}/api/market-events`).then(result => result.json())]).then(([stocks, events]) => { setMarket(previous => ({ ...previous, stocks, events })); setStatus('실시간 연결됨'); }).catch(() => setStatus('API 주소를 확인하세요'));
    const socket = new WebSocket(MARKET_SOCKET_URL); socket.onopen = () => setStatus('실시간 연결됨'); socket.onmessage = event => { const data = JSON.parse(event.data); if (data.type === 'MARKET_UPDATED') { setMarket(previous => ({ ...previous, stocks: data.payload.stocks, events: data.payload.events })); if (firebaseAuth.currentUser) refreshPortfolio().catch(() => {}); } }; socket.onerror = () => setStatus('실시간 연결 실패');
    return () => { unsubscribe(); socket.close(); };
  }, []);

  useEffect(() => { if (!selected) return; const timer = setInterval(() => loadDetails(selected), 10000); return () => clearInterval(timer); }, [selected]);
  useEffect(() => { if (!selected) return; loadChartHistory(selected, chartRange); const timer = setInterval(() => loadChartHistory(selected, chartRange), 10000); return () => clearInterval(timer); }, [selected, chartRange]);
  useEffect(() => { loadRiverTemperature(); const timer = setInterval(loadRiverTemperature, 30 * 60 * 1000); return () => clearInterval(timer); }, []);
  useEffect(() => { if (response?.type === 'success') { const credential = GoogleAuthProvider.credential(response.params.id_token); signInWithCredential(firebaseAuth, credential).catch(error => setMessage(error.message)); } }, [response]);

  const selectedStock = useMemo(() => market.stocks.find(stock => stock.code === selected), [market.stocks, selected]);
  const maxDepth = Math.max(1, ...book.bids.map(level => level.quantity), ...book.asks.map(level => level.quantity));
  const chartPoints = useMemo(() => { if (chartHistory.length <= 60) return chartHistory; const step = (chartHistory.length - 1) / 59; return Array.from({ length: 60 }, (_, index) => chartHistory[Math.round(index * step)]); }, [chartHistory]);
  const chartPrices = chartPoints.map(point => Number(point.price));
  const chartMin = chartPrices.length ? Math.min(...chartPrices) : 0;
  const chartMax = chartPrices.length ? Math.max(...chartPrices) : 1;
  const marketEstimate = useMemo(() => {
    if (!selectedStock || orderType !== 'MARKET') return null;
    const requested = Math.max(1, Number(quantity) || 1);
    const levels = side === 'BUY' ? book.asks : book.bids;
    let remaining = requested;
    let filled = 0;
    let total = 0;
    for (const level of levels) {
      const levelQuantity = Math.max(0, Number(level.quantity || 0));
      const matched = Math.min(remaining, levelQuantity);
      total += matched * Number(level.price || 0);
      filled += matched;
      remaining -= matched;
      if (remaining <= 0) break;
    }
    const gross = filled > 0 ? Math.round(total) : Number(selectedStock.price || 0) * requested;
    const fee = gross > 0 ? Math.max(1, Math.round(gross * 0.001)) : 0;
    return { gross, fee, net: side === 'BUY' ? gross + fee : Math.max(0, gross - fee), filled, remaining };
  }, [book, orderType, quantity, selectedStock, side]);
  const submitOrder = async () => {
    if (!user) { setMessage('거래하려면 먼저 Google 로그인이 필요합니다.'); return; }
    try { const payload = { stockCode: selected, side, quantity: Number(quantity), orderType }; if (orderType === 'LIMIT') payload.price = Number(limitPrice); const result = await api('/api/orders', { method: 'POST', body: JSON.stringify(payload) }); const feeText = result.fee ? ` · 수수료 ${formatMoney(result.fee)}` : ''; const settlementText = result.settlementStatus === 'SETTLED' ? ' · 즉시 결제 완료' : ''; setMessage(`${result.message}${feeText}${settlementText}`); await refreshPortfolio(); await loadDetails(selected); } catch (error) { setMessage(error.message); }
  };

  return <SafeAreaView style={styles.safe}><NewsModal news={selectedNews} stocks={market.stocks} onClose={() => setSelectedNews(null)} styles={styles} t={t} /><ScrollView contentContainerStyle={styles.page}>
    <View style={styles.header}><View><Text style={styles.brand}>씹덕주식</Text><Text style={styles.status}>{t(status)}</Text></View><TouchableOpacity style={styles.loginButton} onPress={() => setMenuOpen(previous => !previous)}><Text style={styles.loginText}>{user ? `${account?.nickname || t('내 계정')} ▾` : `☰ ${t('메뉴')}`}</Text></TouchableOpacity></View>
    {menuOpen && <View style={styles.menuPanel}>{!user && <TouchableOpacity style={styles.menuItem} onPress={() => { setMenuOpen(false); promptAsync(); }} disabled={!request}><Text style={styles.menuLoginText}>{t('Google 로그인')}</Text></TouchableOpacity>}{user && <TouchableOpacity style={styles.menuItem} onPress={openProfile}><Text style={styles.text}>{t('마이페이지')}</Text></TouchableOpacity>}<TouchableOpacity style={styles.menuItem} onPress={openRanking}><Text style={styles.text}>{t('랭킹')}</Text></TouchableOpacity><View style={styles.settingsDivider}><TouchableOpacity style={styles.menuItem} onPress={toggleTheme}><Text style={styles.text}>{isDark ? `☀️ ${t('라이트 모드')}` : `🌙 ${t('다크 모드')}`}</Text></TouchableOpacity><Text style={styles.languageLabel}>{t('언어')}</Text><View style={styles.languageRow}>{[['ko','한국어'],['ja','日本語'],['en','English']].map(([code,label]) => <TouchableOpacity key={code} style={[styles.languageButton, language === code && styles.languageButtonActive]} onPress={() => setLanguage(code)}><Text style={[styles.languageText, language === code && styles.languageTextActive]}>{label}</Text></TouchableOpacity>)}</View></View><Text style={styles.riverMenu}>{riverTemperature?.available && riverTemperature.temperature != null ? `한강 수온(선유) ${Number(riverTemperature.temperature).toFixed(1)}℃` : t('한강 수온(선유) 조회 불가')}</Text>{user && <TouchableOpacity style={styles.menuItem} onPress={() => { setMenuOpen(false); signOut(firebaseAuth); }}><Text style={styles.text}>{t('로그아웃')}</Text></TouchableOpacity>}</View>}
    {market.portfolio ? <View style={styles.asset}><Text style={styles.label}>{t('총 자산')}</Text><Text style={styles.assetValue}>{formatMoney(market.portfolio.totalAsset)}</Text><Text style={styles.cash}>{t('현금')} {formatMoney(market.portfolio.cash)}</Text></View> : <View style={styles.guest}><Text style={styles.guestText}>{t('시장은 누구나 볼 수 있습니다. 거래하려면 Google 로그인하세요.')}</Text></View>}
    <Text style={styles.title}>{t('오늘의 게임 종목')}</Text>
    {market.stocks.map(stock => <TouchableOpacity style={[styles.card, selected === stock.code && styles.selectedCard]} key={stock.code} onPress={() => loadDetails(stock.code)}><View style={styles.cardTop}><Text style={styles.code}>{stock.code} · {stock.genre}</Text><Text style={stock.changePercent >= 0 ? styles.up : styles.down}>{stock.changePercent >= 0 ? '▲' : '▼'} {Math.abs(stock.changePercent).toFixed(2)}%</Text></View><View style={styles.gameNameRow}><Image style={styles.gameIcon} source={GAME_ICONS[stock.code]} accessibilityLabel={`${stock.name} 아이콘`} /><Text style={styles.name}>{stock.name}</Text></View><Text style={styles.price}>{formatMoney(stock.price)}</Text><Text style={styles.volume}>{t('거래량')} {Number(stock.volume).toLocaleString(locale)}</Text></TouchableOpacity>)}
    {selectedStock && <View style={styles.detail}><View style={styles.gameNameRow}><Image style={styles.detailGameIcon} source={GAME_ICONS[selectedStock.code]} accessibilityLabel={`${selectedStock.name} 아이콘`} /><Text style={styles.detailTitle}>{selectedStock.name}</Text></View><Text style={styles.detailPrice}>{formatMoney(selectedStock.price)}</Text>{priceDrivers && <View style={styles.priceDrivers}><Text style={styles.sectionLabel}>{t('가격 결정 근거 · 최근 24시간')}</Text><Text style={styles.holdingMeta}>{priceDrivers.reason}</Text><Text style={styles.priceDriversUpdated}>{t('마지막 뉴스')} {priceDrivers.latestNewsAt ? new Date(priceDrivers.latestNewsAt).toLocaleString(locale) : t('없음')} · {t('마지막 체결')} {priceDrivers.latestTradeAt ? new Date(priceDrivers.latestTradeAt).toLocaleString(locale) : t('없음')}</Text><View style={styles.driverGrid}><View style={styles.driverCard}><Text style={styles.driverLabel}>{t('뉴스 흐름')}</Text><Text style={styles.driverValue}>{priceDrivers.newsImpact > 0.2 ? t('상승 방향') : priceDrivers.newsImpact < -0.2 ? t('하락 방향') : t('혼합·보합')} · {Number(priceDrivers.newsCount || 0).toLocaleString(locale)}</Text></View><View style={styles.driverCard}><Text style={styles.driverLabel}>{t('이용자 거래')}</Text><Text style={styles.driverValue}>{Number(priceDrivers.userBuyVolume || 0) >= Number(priceDrivers.userSellVolume || 0) ? t('매수 우세') : t('매도 우세')} · {t('매수')} {Number(priceDrivers.userBuyVolume || 0).toLocaleString(locale)} / {t('매도')} {Number(priceDrivers.userSellVolume || 0).toLocaleString(locale)}</Text></View><View style={styles.driverCard}><Text style={styles.driverLabel}>{t('현재 호가')}</Text><Text style={styles.driverValue}>{t('매수')} {Number(priceDrivers.openBuyVolume || 0).toLocaleString(locale)} / {t('매도')} {Number(priceDrivers.openSellVolume || 0).toLocaleString(locale)}</Text></View></View></View>}<Text style={styles.sectionLabel}>{t('가격 추이')}</Text><View style={styles.chartRangeRow}>{CHART_RANGES.map(([value, label]) => <TouchableOpacity key={value} style={[styles.chartRangeButton, chartRange === value && styles.chartRangeActive]} onPress={() => setChartRange(value)}><Text style={[styles.chartRangeText, chartRange === value && styles.chartRangeActiveText]}>{t(label)}</Text></TouchableOpacity>)}</View><View style={styles.chart}>{chartPrices.length ? chartPrices.map((price, index) => <View key={`${price}-${index}`} style={[styles.chartBar, { height: `${Math.max(8, ((price - chartMin) / Math.max(1, chartMax - chartMin)) * 88 + 12)}%` }]} />) : <Text style={styles.empty}>{t('가격 데이터 없음')}</Text>}</View><Text style={styles.sectionLabel}>{t('일별 시세 (시가 · 종가 · 거래량)')}</Text>{daily.length ? daily.slice(0, 7).map(row => <View style={styles.tradeRow} key={row.tradingDate}><Text style={styles.text}>{row.tradingDate}</Text><Text style={styles.text}>{t('시가')} {formatMoney(row.openPrice)}</Text><Text style={styles.text}>{t('종가')} {formatMoney(row.closePrice)}</Text><Text style={styles.text}>{Number(row.volume || 0).toLocaleString(locale)}</Text></View>) : <Text style={styles.empty}>{t('아직 집계된 일별 체결이 없습니다.')}</Text>}<Text style={styles.sectionLabel}>{t('호가 깊이')}</Text><View style={styles.depth}><View style={styles.depthColumn}>{book.asks.length ? book.asks.map(level => <View style={styles.depthRow} key={`a${level.price}`}><Text style={styles.sellText}>{Number(level.price).toLocaleString(locale)}</Text><View style={styles.barTrack}><View style={[styles.sellBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{Number(level.quantity).toLocaleString(locale)}</Text></View>) : <Text style={styles.empty}>{t('매도 대기 없음')}</Text>}</View><View style={styles.depthColumn}>{book.bids.length ? book.bids.map(level => <View style={styles.depthRow} key={`b${level.price}`}><Text style={styles.buyText}>{Number(level.price).toLocaleString(locale)}</Text><View style={styles.barTrack}><View style={[styles.buyBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{Number(level.quantity).toLocaleString(locale)}</Text></View>) : <Text style={styles.empty}>{t('매수 대기 없음')}</Text>}</View></View>
      <Text style={styles.sectionLabel}>{t('주문')}</Text><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, side === 'BUY' && styles.activeBuy]} onPress={() => setSide('BUY')}><Text style={styles.text}>{t('매수')}</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, side === 'SELL' && styles.activeSell]} onPress={() => setSide('SELL')}><Text style={styles.text}>{t('매도')}</Text></TouchableOpacity></View><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, orderType === 'MARKET' && styles.active]} onPress={() => { setOrderType('MARKET'); setLimitPrice(''); }}><Text style={styles.text}>{t('시장가')}</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, orderType === 'LIMIT' && styles.active]} onPress={() => setOrderType('LIMIT')}><Text style={styles.text}>{t('지정가')}</Text></TouchableOpacity></View><TextInput style={styles.input} placeholderTextColor={isDark ? '#aaa4b8' : '#697386'} keyboardType="number-pad" value={quantity} onChangeText={setQuantity} placeholder={t('수량')} />{orderType === 'MARKET' && marketEstimate && <View style={styles.marketEstimate}><Text style={styles.marketEstimateLabel}>{t('시장가 예상 체결금액')}</Text><Text style={styles.marketEstimateAmount}>{formatMoney(marketEstimate.gross)}</Text><Text style={styles.holdingMeta}>{marketEstimate.filled > 0 ? `${t('현재 호가')} ${marketEstimate.filled}` : '반대 호가가 없어 현재가 기준'} · {side === 'BUY' ? '수수료 포함 예상 출금액' : '수수료 차감 예상 입금액'} {formatMoney(marketEstimate.net)}{marketEstimate.remaining > 0 && marketEstimate.filled > 0 ? ` · ${marketEstimate.remaining}` : ''}</Text></View>}{orderType === 'LIMIT' && <TextInput style={styles.input} placeholderTextColor={isDark ? '#aaa4b8' : '#697386'} keyboardType="number-pad" value={limitPrice} onChangeText={setLimitPrice} placeholder={t('지정가')} />}<TouchableOpacity style={styles.orderButton} onPress={submitOrder}><Text style={styles.orderText}>{t('주문 제출')}</Text></TouchableOpacity><Text style={styles.message}>{message}</Text>
      <Text style={styles.sectionLabel}>{t('최근 전체 체결 10건')}</Text>{trades.slice(0, 10).map((trade, index) => <View style={styles.tradeRow} key={`${trade.createdAt}-${index}`}><Text style={trade.side === 'BUY' ? styles.buyText : styles.sellText}>{trade.side === 'BUY' ? t('매수') : t('매도')}</Text><Text style={styles.text}>{Number(trade.quantity).toLocaleString(locale)}</Text><Text style={styles.text}>{formatMoney(trade.price)}</Text></View>)}
      <Text style={styles.sectionLabel}>{t('관련 뉴스')}</Text>{news.length ? news.slice(0, 5).map((item, index) => { const change = Number(selectedStock?.changePercent ?? item.priceChangePercent ?? 0); const priceStyle = change > 0 ? styles.up : change < 0 ? styles.down : styles.eventNeutral; return <TouchableOpacity style={styles.newsRow} key={`${item.title}-${index}`} onPress={() => setSelectedNews(item)}><View style={styles.newsContent}><Text style={[styles.newsTitle, item.sentiment === 'negative' ? styles.down : item.sentiment === 'neutral' ? styles.eventNeutral : styles.up]}>{item.title}</Text><Text style={styles.eventMeta}>{item.stockCode || selected || t('시장 전체')} <Text style={priceStyle}>{describePriceLabel({ priceChangePercent: change })}</Text></Text></View></TouchableOpacity>; }) : <Text style={styles.empty}>{t('관련 뉴스 없음')}</Text>}
    </View>}
    <Text style={styles.title}>{t('시장 이벤트')}</Text>{market.events.length ? market.events.map((event, index) => { const liveStock = market.stocks.find(stock => stock.code === event.stockCode); const change = Number(liveStock?.changePercent ?? event.priceChangePercent ?? 0); const priceStyle = change > 0 ? styles.up : change < 0 ? styles.down : styles.eventNeutral; return <TouchableOpacity style={styles.event} key={`${event.title}-${index}`} onPress={() => setSelectedNews(event)}><View style={styles.eventContent}><Text style={[styles.eventTitle, event.sentiment === 'negative' ? styles.down : event.sentiment === 'neutral' ? styles.eventNeutral : styles.up]}>{event.title}</Text><Text style={styles.eventMeta}>{event.stockCode || t('시장 전체')} {event.stockCode && <Text style={priceStyle}>{describePriceLabel({ priceChangePercent: change })}</Text>}</Text><Text style={styles.eventReason}>{priceReason(event)}</Text></View></TouchableOpacity>; }) : <Text style={styles.empty}>{t('아직 수집된 뉴스가 없습니다.')}</Text>}{user && <TouchableOpacity style={styles.resetShortcut} onPress={resetAccount}><Text style={styles.text}>{t('인생 리셋')}</Text></TouchableOpacity>}
  </ScrollView><Modal transparent visible={profileOpen} animationType="fade" onRequestClose={() => { if (!account?.requiresNickname) setProfileOpen(false); }}><View style={styles.modalBackdrop}><View style={styles.modalCard}><Text style={styles.detailTitle}>{account?.requiresNickname ? t('닉네임 설정') : t('마이페이지')}</Text><Text style={styles.modalHelp}>{account?.requiresNickname ? t('첫 로그인 기념으로 닉네임을 정해 주세요.') : (profile?.email || t('프로필 정보를 관리하세요.'))}</Text><TextInput style={styles.input} placeholderTextColor={isDark ? '#aaa4b8' : '#697386'} value={nickname} onChangeText={setNickname} placeholder="Nickname (2~50)" maxLength={50} /><TouchableOpacity style={styles.orderButton} onPress={saveProfile}><Text style={styles.orderText}>{t('프로필 저장')}</Text></TouchableOpacity><Text style={styles.message}>{profileMessage}</Text><Text style={styles.sectionLabel}>{t('보유 종목')}</Text>{(market.portfolio?.positions || []).length ? market.portfolio.positions.map(position => <View style={styles.holdingRow} key={position.stockCode}><View><Text style={styles.newsTitle}>{position.stockCode} · {Number(position.quantity).toLocaleString(locale)}</Text><Text style={styles.holdingMeta}>평균 {formatMoney(position.averagePrice)} · 평가액 {formatMoney(position.marketValue)}</Text><Text style={styles.holdingMeta}>{t('체결 완료')} {Number(position.settledQuantity ?? position.quantity).toLocaleString(locale)} · 실현손익 {formatMoney(position.realizedProfitLoss || 0)}</Text></View><Text style={position.profitLoss >= 0 ? styles.up : styles.down}>{position.profitLoss >= 0 ? '+' : ''}{formatMoney(position.profitLoss)}{'\n'}평가손익 {position.profitLossPercent?.toFixed?.(2) || '0.00'}%</Text></View>) : <Text style={styles.empty}>보유 중인 종목이 없습니다.</Text>}<Text style={styles.sectionLabel}>{t('미체결 주문')}</Text>{openOrders.length ? openOrders.map(order => <View style={styles.openOrderRow} key={order.id}><View style={styles.openOrderInfo}><Text style={order.side === 'BUY' ? styles.orderSideBuy : styles.orderSideSell}>{order.side === 'BUY' ? t('매수') : t('매도')} · {order.stockCode}</Text><Text style={styles.holdingMeta}>{Number(order.remainingQuantity).toLocaleString(locale)} · {t('지정가')} {formatMoney(order.price)}</Text><Text style={styles.holdingMeta}>{order.side === 'BUY' ? `예약금 ${formatMoney(order.reservedCash)}` : `예약수량 ${Number(order.reservedQuantity).toLocaleString(locale)}`}</Text></View><TouchableOpacity style={styles.cancelOrder} onPress={() => cancelOpenOrder(order.id)}><Text style={styles.text}>취소</Text></TouchableOpacity></View>) : <Text style={styles.empty}>미체결 주문이 없습니다.</Text>}<Text style={styles.sectionLabel}>{t('체결 완료')}</Text>{settlements.length ? settlements.map(item => <View style={styles.openOrderRow} key={`s${item.id}`}><View style={styles.openOrderInfo}><Text style={item.side === 'BUY' ? styles.orderSideBuy : styles.orderSideSell}>{item.side === 'BUY' ? t('매수') : t('매도')} · {item.stockCode}</Text><Text style={styles.holdingMeta}>{Number(item.quantity).toLocaleString(locale)} · 수수료 {formatMoney(item.fee)}</Text><Text style={styles.holdingMeta}>{t('체결 완료')} {new Date(item.settlementAt).toLocaleString(locale)}</Text></View><View style={styles.settlementActions}><Text style={styles.holdingMeta}>{item.side === 'BUY' ? '출금' : '입금'} {formatMoney(item.netAmount)}</Text><Text style={styles.holdingMeta}>{t('체결 완료')}</Text></View></View>) : <Text style={styles.empty}>체결 완료된 거래가 없습니다.</Text>}{!account?.requiresNickname && <TouchableOpacity style={styles.modalClose} onPress={() => setProfileOpen(false)}><Text style={styles.text}>{t('닫기')}</Text></TouchableOpacity>}</View></View></Modal><Modal transparent visible={rankingOpen} animationType="fade" onRequestClose={() => setRankingOpen(false)}><View style={styles.modalBackdrop}><View style={styles.modalCard}><Text style={styles.detailTitle}>{t('투자 랭킹')}</Text>{ranking.length ? ranking.map(entry => <View style={styles.rankingRow} key={`${entry.rank}-${entry.nickname}`}><Text style={styles.rankingRank}>{entry.rank}</Text><Text style={styles.rankingNickname}>{entry.nickname}</Text><Text style={styles.rankingChange}>{`${entry.changePercent >= 0 ? '+' : ''}${Number(entry.changePercent || 0).toFixed(2)}%`}</Text><Text style={styles.rankingAsset}>{formatMoney(entry.totalAsset)}</Text></View>) : <Text style={styles.empty}>{t('아직 랭킹에 참여한 사용자가 없습니다.')}</Text>}<TouchableOpacity style={styles.modalClose} onPress={() => setRankingOpen(false)}><Text style={styles.text}>{t('닫기')}</Text></TouchableOpacity></View></View></Modal></SafeAreaView>;
}

const baseStyles = StyleSheet.create({ safe:{flex:1,backgroundColor:'#f7f8fb'},page:{padding:22,paddingBottom:50},header:{flexDirection:'row',justifyContent:'space-between',alignItems:'center',marginBottom:14},brand:{fontSize:28,fontWeight:'800',color:'#6750a4'},status:{color:'#697386',marginTop:2},river:{color:'#6750a4',fontSize:11,marginTop:2},loginButton:{backgroundColor:'#6750a4',borderRadius:10,paddingVertical:10,paddingHorizontal:12},loginText:{color:'white',fontWeight:'700'},menuPanel:{position:'absolute',right:22,top:72,zIndex:5,backgroundColor:'white',borderRadius:12,padding:6,elevation:5,shadowColor:'#000',shadowOpacity:.15,shadowRadius:8,minWidth:190},menuItem:{paddingVertical:11,paddingHorizontal:16,minWidth:140},menuLoginText:{color:'#6750a4',fontWeight:'700'},asset:{backgroundColor:'#6750a4',borderRadius:18,padding:22,marginBottom:8},label:{color:'#e9ddff'},assetValue:{fontSize:30,fontWeight:'800',color:'white',marginVertical:6},cash:{color:'#f1ecff'},guest:{backgroundColor:'#ede8fb',borderRadius:14,padding:15},guestText:{color:'#6750a4',lineHeight:20},title:{fontSize:19,fontWeight:'800',marginTop:22,marginBottom:10},card:{backgroundColor:'white',borderRadius:14,padding:18,marginBottom:10},selectedCard:{borderWidth:2,borderColor:'#6750a4'},cardTop:{flexDirection:'row',justifyContent:'space-between',alignItems:'center'},code:{color:'#697386',fontSize:12},gameNameRow:{flexDirection:'row',alignItems:'center',gap:10,minWidth:0},gameIcon:{width:36,height:36,borderRadius:18},detailGameIcon:{width:48,height:48,borderRadius:24},name:{fontSize:16,fontWeight:'700',flexShrink:1},price:{fontSize:21,fontWeight:'800',marginTop:14},volume:{color:'#697386',fontSize:12,marginTop:7},up:{color:'#e04f5f',fontWeight:'700'},down:{color:'#2379ba',fontWeight:'700'},detail:{backgroundColor:'white',borderRadius:16,padding:18,marginTop:10},detailTitle:{fontSize:21,fontWeight:'800'},detailPrice:{fontSize:24,fontWeight:'800',color:'#6750a4',marginTop:5},sectionLabel:{fontSize:14,fontWeight:'800',marginTop:20,marginBottom:8},chart:{height:100,flexDirection:'row',alignItems:'flex-end',gap:3,borderBottomWidth:1,borderBottomColor:'#dfe3ec',paddingHorizontal:3},chartBar:{flex:1,minWidth:3,backgroundColor:'#8c78ce',borderTopLeftRadius:3,borderTopRightRadius:3},depth:{flexDirection:'row',gap:6,minHeight:180},depthColumn:{flex:1,justifyContent:'flex-start',gap:4},depthRow:{minHeight:23,flexDirection:'row',alignItems:'center',gap:3},barTrack:{flex:1,height:18,justifyContent:'center'},buyBar:{height:18,backgroundColor:'#e04f5f',opacity:.25,borderRadius:3},sellBar:{height:18,backgroundColor:'#2379ba',opacity:.25,borderRadius:3},buyText:{color:'#e04f5f',fontWeight:'700',fontSize:11},sellText:{color:'#2379ba',fontWeight:'700',fontSize:11},depthQty:{fontSize:10,color:'#697386',width:35,textAlign:'right'},empty:{color:'#697386',fontSize:12},switchRow:{flexDirection:'row',gap:8,marginBottom:8},switch:{flex:1,borderWidth:1,borderColor:'#d4d7df',borderRadius:9,padding:11,alignItems:'center'},active:{backgroundColor:'#6750a4',borderColor:'#6750a4'},activeBuy:{backgroundColor:'#ffe7ea',borderColor:'#e04f5f'},activeSell:{backgroundColor:'#e4f1fb',borderColor:'#2379ba'},input:{borderWidth:1,borderColor:'#cdd3dd',borderRadius:9,paddingHorizontal:12,height:44,marginBottom:8},orderButton:{height:44,borderRadius:9,backgroundColor:'#6750a4',alignItems:'center',justifyContent:'center'},orderText:{color:'white',fontWeight:'800'},message:{color:'#c92a39',marginTop:8,minHeight:18},tradeRow:{flexDirection:'row',justifyContent:'space-between',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsRow:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsTitle:{flex:1,marginRight:8,lineHeight:19},holdingRow:{flexDirection:'row',justifyContent:'space-between',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},holdingMeta:{color:'#697386',fontSize:11,marginTop:3},profileInitial:{width:64,height:64,borderRadius:32,alignSelf:'center',marginVertical:12,backgroundColor:'#ede8fb',alignItems:'center',justifyContent:'center'},modalBackdrop:{flex:1,backgroundColor:'rgba(23,32,47,.48)',justifyContent:'center',padding:20},modalCard:{backgroundColor:'white',borderRadius:18,padding:22,maxHeight:'88%'},modalHelp:{color:'#697386',marginTop:6,marginBottom:12},modalClose:{marginTop:14,alignItems:'center',padding:10},rankingRow:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:11,gap:10},rankingRank:{color:'#6750a4',fontWeight:'800',width:24,textAlign:'center'},rankingAsset:{fontWeight:'700'},event:{backgroundColor:'white',padding:15,borderRadius:12,marginBottom:8,flexDirection:'row',justifyContent:'space-between'},eventTitle:{flex:1,marginRight:10}});
baseStyles.event = StyleSheet.flatten([baseStyles.event, { borderRadius: 12 }]);
baseStyles.holdingRow = StyleSheet.flatten([baseStyles.holdingRow, { alignItems: 'center' }]);
baseStyles.openOrderRow = { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: '#e5e9ef', paddingVertical: 10 };
baseStyles.openOrderInfo = { flex: 1, marginRight: 10 };
baseStyles.settlementActions = { alignItems: 'flex-end', gap: 6 };
baseStyles.orderSideBuy = { color: '#e04f5f', fontWeight: '700' };
baseStyles.orderSideSell = { color: '#2379ba', fontWeight: '700' };
baseStyles.cancelOrder = { borderWidth: 1, borderColor: '#cdd3dd', borderRadius: 8, paddingVertical: 7, paddingHorizontal: 10 };
baseStyles.resetShortcut = { marginTop: 18, borderWidth: 1, borderColor: '#f0b8b4', borderRadius: 9, padding: 11, alignItems: 'center', backgroundColor: '#fff3f3' };
baseStyles.resetButton = { marginTop: 8, borderWidth: 1, borderColor: '#f0b8b4', borderRadius: 9, padding: 11, alignItems: 'center', backgroundColor: '#fff3f3' };
baseStyles.disabledButton = { opacity: .55 };
baseStyles.riverMenu = { color: '#6750a4', fontSize: 11, paddingVertical: 8, paddingHorizontal: 16, borderBottomWidth: 1, borderBottomColor: '#e5e9ef' };
baseStyles.marketEstimate = { borderWidth: 1, borderColor: '#d9d2ee', borderRadius: 10, padding: 12, marginBottom: 8, backgroundColor: '#faf8ff' };
baseStyles.marketEstimateLabel = { color: '#697386', fontSize: 12, fontWeight: '700' };
baseStyles.marketEstimateAmount = { color: '#6750a4', fontSize: 20, fontWeight: '800', marginTop: 4 };
baseStyles.chartRangeRow = { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 10 };
baseStyles.chartRangeButton = { borderWidth: 1, borderColor: '#d4d7df', borderRadius: 8, paddingVertical: 7, paddingHorizontal: 9 };
baseStyles.chartRangeActive = { backgroundColor: '#6750a4', borderColor: '#6750a4' };
baseStyles.chartRangeText = { color: '#697386', fontSize: 11, fontWeight: '700' };
baseStyles.chartRangeActiveText = { color: 'white' };
baseStyles.rankingNickname = { flex: 1, flexShrink: 1, flexWrap: 'wrap', lineHeight: 19 };
baseStyles.rankingChange = { color: '#e04f5f', fontWeight: '700', minWidth: 68, textAlign: 'right' };
baseStyles.eyebrow = { color: '#6750a4', fontSize: 11, letterSpacing: 1.2, fontWeight: '800', marginBottom: 6 };
baseStyles.eventContent = { flex: 1, marginRight: 10 };
baseStyles.eventMeta = { color: '#697386', fontSize: 11, marginTop: 4 };
baseStyles.eventReason = { color: '#697386', fontSize: 11, lineHeight: 17, marginTop: 5 };
baseStyles.eventNeutral = { color: '#697386', fontWeight: '700' };
baseStyles.newsContent = { flex: 1, marginRight: 8 };
baseStyles.newsModalReason = { marginTop: 14, padding: 11, borderRadius: 9, backgroundColor: '#f7f5fc', color: '#6750a4', fontWeight: '700', lineHeight: 19 };
baseStyles.newsModalSummary = { marginTop: 10, color: '#17202f', lineHeight: 21 };
baseStyles.newsModalPriceRow = { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10, marginTop: 4 };
baseStyles.newsModalSource = { marginTop: 12, alignSelf: 'flex-start' };
baseStyles.newsModalSourceText = { color: '#6750a4', fontSize: 12, fontWeight: '800' };
baseStyles.priceDrivers = { marginTop: 4, paddingBottom: 4 };
baseStyles.priceDriversUpdated = { marginTop: 3, color: '#697386', fontSize: 10, lineHeight: 15 };
baseStyles.driverGrid = { flexDirection: 'row', flexWrap: 'wrap', gap: 7, marginTop: 8 };
baseStyles.driverCard = { width: '48%', padding: 9, borderWidth: 1, borderColor: '#eeeaf5', borderRadius: 9, backgroundColor: '#faf9fc' };
baseStyles.driverLabel = { color: '#697386', fontSize: 11, fontWeight: '700' };
baseStyles.driverValue = { marginTop: 4, fontSize: 11, lineHeight: 16 };
baseStyles.text = { color: '#17202f' };
baseStyles.settingsDivider = { borderTopWidth: 1, borderBottomWidth: 1, borderColor: '#e5e9ef', paddingVertical: 6, marginVertical: 4 };
baseStyles.languageLabel = { color: '#697386', fontSize: 11, fontWeight: '700', paddingHorizontal: 16, paddingTop: 7 };
baseStyles.languageRow = { flexDirection: 'row', paddingHorizontal: 12, paddingVertical: 7, gap: 5 };
baseStyles.languageButton = { flex: 1, borderWidth: 1, borderColor: '#cdd3dd', borderRadius: 7, paddingVertical: 6, alignItems: 'center' };
baseStyles.languageButtonActive = { borderColor: '#6750a4', backgroundColor: '#ede8fb' };
baseStyles.languageText = { color: '#17202f', fontSize: 11 };
baseStyles.languageTextActive = { color: '#6750a4', fontWeight: '800' };
// Keep the mobile shell visually aligned with the web market board.
baseStyles.page = StyleSheet.flatten([baseStyles.page, { padding: 18, paddingBottom: 50 }]);
baseStyles.header = StyleSheet.flatten([baseStyles.header, { marginBottom: 18 }]);
baseStyles.asset = StyleSheet.flatten([baseStyles.asset, { borderRadius: 20, padding: 20, marginBottom: 10 }]);
baseStyles.card = StyleSheet.flatten([baseStyles.card, { borderWidth: 1, borderColor: '#e5e9ef', borderRadius: 15, padding: 17 }]);
baseStyles.detail = StyleSheet.flatten([baseStyles.detail, { borderWidth: 1, borderColor: '#e5e9ef', borderRadius: 17 }]);
baseStyles.event = StyleSheet.flatten([baseStyles.event, { borderWidth: 1, borderColor: '#e5e9ef', borderRadius: 13 }]);

const createStyles = dark => {
  if (!dark) return baseStyles;
  const overrides = {
    safe:{backgroundColor:'#121016'}, text:{color:'#f0edf7'}, status:{color:'#aaa4b8'}, menuPanel:{backgroundColor:'#1d1924'},
    menuItem:{backgroundColor:'#1d1924'}, title:{color:'#f0edf7'}, card:{backgroundColor:'#1d1924',borderColor:'#3b3648'}, code:{color:'#aaa4b8'}, name:{color:'#f0edf7'},
    price:{color:'#f0edf7'}, volume:{color:'#aaa4b8'}, detail:{backgroundColor:'#1d1924'}, detailTitle:{color:'#f0edf7'}, sectionLabel:{color:'#f0edf7'},
    chart:{borderBottomColor:'#3b3648'}, depthQty:{color:'#aaa4b8'}, empty:{color:'#aaa4b8'}, switch:{borderColor:'#514960',backgroundColor:'#26212f'},
    active:{backgroundColor:'#8067c7'}, input:{borderColor:'#514960',backgroundColor:'#26212f',color:'#f0edf7'}, tradeRow:{borderTopColor:'#3b3648'},
    newsRow:{borderTopColor:'#3b3648'}, newsTitle:{color:'#f0edf7'}, holdingRow:{borderTopColor:'#3b3648'}, holdingMeta:{color:'#aaa4b8'},
    modalCard:{backgroundColor:'#1d1924'}, modalHelp:{color:'#aaa4b8'}, rankingRow:{borderTopColor:'#3b3648'}, rankingAsset:{color:'#f0edf7'},
    detail:{backgroundColor:'#1d1924',borderColor:'#3b3648'}, event:{backgroundColor:'#1d1924',borderColor:'#3b3648'}, eventTitle:{color:'#f0edf7'}, openOrderRow:{borderTopColor:'#3b3648'}, cancelOrder:{borderColor:'#514960',backgroundColor:'#26212f'},
    resetShortcut:{backgroundColor:'#2b2024'}, riverMenu:{borderBottomColor:'#3b3648'}, marketEstimate:{borderColor:'#514960',backgroundColor:'#26212f'},
    eventMeta:{color:'#aaa4b8'}, eventReason:{color:'#aaa4b8'}, newsModalReason:{backgroundColor:'#26212f'}, newsModalSummary:{color:'#f0edf7'},
    priceDriversUpdated:{color:'#aaa4b8'}, driverCard:{borderColor:'#3b3648',backgroundColor:'#26212f'}, driverLabel:{color:'#aaa4b8'}, driverValue:{color:'#f0edf7'},
    settingsDivider:{borderColor:'#3b3648'}, languageLabel:{color:'#aaa4b8'}, languageButton:{borderColor:'#514960'}, languageButtonActive:{borderColor:'#bca7ff',backgroundColor:'#332a42'},
    languageText:{color:'#f0edf7'}, languageTextActive:{color:'#bca7ff'}, profileInitial:{backgroundColor:'#332a42'}
  };
  return Object.fromEntries(Object.entries(baseStyles).map(([key, value]) => [key, overrides[key] ? [value, overrides[key]] : value]));
};
