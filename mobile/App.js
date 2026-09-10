import React, { useEffect, useMemo, useState } from 'react';
import * as WebBrowser from 'expo-web-browser';
import * as Google from 'expo-auth-session/providers/google';
import { initializeApp } from 'firebase/app';
import { GoogleAuthProvider, getAuth, getReactNativePersistence, initializeAuth, onAuthStateChanged, signInWithCredential, signOut } from 'firebase/auth';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Alert, Modal, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { API_BASE_URL, MARKET_SOCKET_URL, FIREBASE_CONFIG, GOOGLE_WEB_CLIENT_ID } from './src/config';

WebBrowser.maybeCompleteAuthSession();
const firebaseApp = initializeApp(FIREBASE_CONFIG);
let firebaseAuth;
try { firebaseAuth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) }); }
catch { firebaseAuth = getAuth(firebaseApp); }
const money = value => {
  const rankingValue = value && typeof value === 'object';
  const amount = rankingValue ? value.amount : value;
  const formatted = `${Number(amount || 0).toLocaleString('ko-KR')}원`;
  if (!rankingValue || value.changePercent == null) return formatted;
  const change = Number(value.changePercent);
  return `${formatted} (${change >= 0 ? '+' : ''}${change.toFixed(2)}%)`;
};
const signedMoney = value => { const amount = Number(value || 0); return `${amount > 0 ? '+' : amount < 0 ? '-' : ''}${money(Math.abs(amount))}`; };

export default function App() {
  const [market, setMarket] = useState({ stocks: [], portfolio: null, events: [] });
  const [selected, setSelected] = useState(null);
  const [book, setBook] = useState({ bids: [], asks: [] });
  const [trades, setTrades] = useState([]);
  const [history, setHistory] = useState([]);
  const [daily, setDaily] = useState([]);
  const [news, setNews] = useState([]);
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
  const [request, response, promptAsync] = Google.useAuthRequest({ webClientId: GOOGLE_WEB_CLIENT_ID });

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
  const loadDetails = async code => { setSelected(code); try { const [nextBook, nextTrades, nextHistory, nextNews, nextDaily] = await Promise.all([api(`/api/orderbook/${code}`), api(`/api/stocks/${code}/trades`), api(`/api/stocks/${code}/history`), api(`/api/stocks/${code}/news`), api(`/api/stocks/${code}/daily`)]); setBook(nextBook); setTrades(nextTrades); setHistory(nextHistory); setNews(nextNews); setDaily(nextDaily); } catch (error) { setMessage(error.message); } };

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(firebaseAuth, async current => { setUser(current); if (current) { try { const serverUser = await api('/api/auth/google', { method: 'POST' }); setAccount(serverUser); await refreshPortfolio(); } catch (error) { setMessage(error.message); } } else { setAccount(null); setProfile(null); setMarket(previous => ({ ...previous, portfolio: null })); } });
    Promise.all([fetch(`${API_BASE_URL}/api/stocks`).then(result => result.json()), fetch(`${API_BASE_URL}/api/market-events`).then(result => result.json())]).then(([stocks, events]) => { setMarket(previous => ({ ...previous, stocks, events })); setStatus('실시간 연결됨'); }).catch(() => setStatus('API 주소를 확인하세요'));
    const socket = new WebSocket(MARKET_SOCKET_URL); socket.onopen = () => setStatus('실시간 연결됨'); socket.onmessage = event => { const data = JSON.parse(event.data); if (data.type === 'MARKET_UPDATED') { setMarket(previous => ({ ...previous, stocks: data.payload.stocks, events: data.payload.events })); if (firebaseAuth.currentUser) refreshPortfolio().catch(() => {}); } }; socket.onerror = () => setStatus('실시간 연결 실패');
    return () => { unsubscribe(); socket.close(); };
  }, []);

  useEffect(() => { if (!selected) return; const timer = setInterval(() => loadDetails(selected), 10000); return () => clearInterval(timer); }, [selected]);
  useEffect(() => { loadRiverTemperature(); const timer = setInterval(loadRiverTemperature, 30 * 60 * 1000); return () => clearInterval(timer); }, []);
  useEffect(() => { if (response?.type === 'success') { const credential = GoogleAuthProvider.credential(response.params.id_token); signInWithCredential(firebaseAuth, credential).catch(error => setMessage(error.message)); } }, [response]);

  const selectedStock = useMemo(() => market.stocks.find(stock => stock.code === selected), [market.stocks, selected]);
  const maxDepth = Math.max(1, ...book.bids.map(level => level.quantity), ...book.asks.map(level => level.quantity));
  const chartPrices = history.slice(-30).map(point => Number(point.price));
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
    try { const payload = { stockCode: selected, side, quantity: Number(quantity), orderType }; if (orderType === 'LIMIT') payload.price = Number(limitPrice); const result = await api('/api/orders', { method: 'POST', body: JSON.stringify(payload) }); const feeText = result.fee ? ` · 수수료 ${money(result.fee)}` : ''; const settlementText = result.settlementStatus === 'SETTLED' ? ' · 즉시 결제 완료' : ''; setMessage(`${result.message}${feeText}${settlementText}`); await refreshPortfolio(); await loadDetails(selected); } catch (error) { setMessage(error.message); }
  };

  return <SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.page}>
    <View style={styles.header}><View><Text style={styles.brand}>GameStock</Text><Text style={styles.status}>{status}</Text></View>{user ? <TouchableOpacity style={styles.loginButton} onPress={() => setMenuOpen(previous => !previous)}><Text style={styles.loginText}>{account?.nickname || '내 계정'} ▾</Text></TouchableOpacity> : <TouchableOpacity style={styles.loginButton} onPress={() => promptAsync()} disabled={!request}><Text style={styles.loginText}>Google 로그인</Text></TouchableOpacity>}</View>
    {menuOpen && user && <View style={styles.menuPanel}><TouchableOpacity style={styles.menuItem} onPress={openProfile}><Text>마이페이지</Text></TouchableOpacity><TouchableOpacity style={styles.menuItem} onPress={openRanking}><Text>랭킹</Text></TouchableOpacity><Text style={styles.riverMenu}>{riverTemperature?.available && riverTemperature.temperature != null ? `한강 수온(선유) ${Number(riverTemperature.temperature).toFixed(1)}℃` : '한강 수온(선유) 조회 불가'}</Text><TouchableOpacity style={styles.menuItem} onPress={() => { setMenuOpen(false); signOut(firebaseAuth); }}><Text>로그아웃</Text></TouchableOpacity></View>}
    {market.portfolio ? <View style={styles.asset}><Text style={styles.label}>총 자산</Text><Text style={styles.assetValue}>{money(market.portfolio.totalAsset)}</Text><Text style={styles.cash}>현금 {money(market.portfolio.cash)}</Text></View> : <View style={styles.guest}><Text style={styles.guestText}>시장은 누구나 볼 수 있습니다. 거래하려면 Google 로그인하세요.</Text></View>}
    <Text style={styles.title}>오늘의 게임 종목</Text>
    {market.stocks.map(stock => <TouchableOpacity style={[styles.card, selected === stock.code && styles.selectedCard]} key={stock.code} onPress={() => loadDetails(stock.code)}><View style={styles.cardTop}><Text style={styles.code}>{stock.code} · {stock.genre}</Text><Text style={stock.changePercent >= 0 ? styles.up : styles.down}>{stock.changePercent >= 0 ? '▲' : '▼'} {Math.abs(stock.changePercent).toFixed(2)}%</Text></View><Text style={styles.name}>{stock.name}</Text><Text style={styles.price}>{money(stock.price)}</Text><Text style={styles.volume}>거래량 {Number(stock.volume).toLocaleString()} · 탭하여 거래</Text></TouchableOpacity>)}
    {selectedStock && <View style={styles.detail}><Text style={styles.detailTitle}>{selectedStock.name}</Text><Text style={styles.detailPrice}>{money(selectedStock.price)}</Text><Text style={styles.sectionLabel}>가격 추이</Text><View style={styles.chart}>{chartPrices.length ? chartPrices.map((price, index) => <View key={`${price}-${index}`} style={[styles.chartBar, { height: `${Math.max(8, ((price - chartMin) / Math.max(1, chartMax - chartMin)) * 88 + 12)}%` }]} />) : <Text style={styles.empty}>가격 데이터 없음</Text>}</View><Text style={styles.sectionLabel}>일별 시세 (시가 · 종가 · 거래량)</Text>{daily.length ? daily.slice(0, 7).map(row => <View style={styles.tradeRow} key={row.tradingDate}><Text>{row.tradingDate}</Text><Text>시가 {money(row.openPrice)}</Text><Text>종가 {money(row.closePrice)}</Text><Text>{Number(row.volume || 0).toLocaleString()}주</Text></View>) : <Text style={styles.empty}>아직 집계된 일별 체결이 없습니다.</Text>}<Text style={styles.sectionLabel}>호가 깊이</Text><View style={styles.depth}><View style={styles.depthColumn}>{book.asks.length ? book.asks.map(level => <View style={styles.depthRow} key={`a${level.price}`}><Text style={styles.sellText}>{level.price.toLocaleString()}</Text><View style={styles.barTrack}><View style={[styles.sellBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{level.quantity}주</Text></View>) : <Text style={styles.empty}>매도 대기 없음</Text>}</View><View style={styles.depthColumn}>{book.bids.length ? book.bids.map(level => <View style={styles.depthRow} key={`b${level.price}`}><Text style={styles.buyText}>{level.price.toLocaleString()}</Text><View style={styles.barTrack}><View style={[styles.buyBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{level.quantity}주</Text></View>) : <Text style={styles.empty}>매수 대기 없음</Text>}</View></View>
      <Text style={styles.sectionLabel}>주문</Text><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, side === 'BUY' && styles.activeBuy]} onPress={() => setSide('BUY')}><Text>매수</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, side === 'SELL' && styles.activeSell]} onPress={() => setSide('SELL')}><Text>매도</Text></TouchableOpacity></View><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, orderType === 'MARKET' && styles.active]} onPress={() => { setOrderType('MARKET'); setLimitPrice(''); }}><Text>시장가</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, orderType === 'LIMIT' && styles.active]} onPress={() => setOrderType('LIMIT')}><Text>지정가</Text></TouchableOpacity></View><TextInput style={styles.input} keyboardType="number-pad" value={quantity} onChangeText={setQuantity} placeholder="수량" />{orderType === 'MARKET' && marketEstimate && <View style={styles.marketEstimate}><Text style={styles.marketEstimateLabel}>시장가 예상 체결금액</Text><Text style={styles.marketEstimateAmount}>{money(marketEstimate.gross)}</Text><Text style={styles.holdingMeta}>{marketEstimate.filled > 0 ? `현재 호가 ${marketEstimate.filled}주 기준` : '반대 호가가 없어 현재가 기준'} · {side === 'BUY' ? '수수료 포함 예상 출금액' : '수수료 차감 예상 입금액'} {money(marketEstimate.net)}{marketEstimate.remaining > 0 && marketEstimate.filled > 0 ? ` · ${marketEstimate.remaining}주 미체결 가능` : ''}</Text></View>}{orderType === 'LIMIT' && <TextInput style={styles.input} keyboardType="number-pad" value={limitPrice} onChangeText={setLimitPrice} placeholder="지정가" />}<TouchableOpacity style={styles.orderButton} onPress={submitOrder}><Text style={styles.orderText}>주문 제출</Text></TouchableOpacity><Text style={styles.message}>{message}</Text>
      <Text style={styles.sectionLabel}>최근 전체 체결 10건</Text>{trades.slice(0, 10).map((trade, index) => <View style={styles.tradeRow} key={`${trade.createdAt}-${index}`}><Text style={trade.side === 'BUY' ? styles.buyText : styles.sellText}>{trade.side === 'BUY' ? '매수' : '매도'}</Text><Text>{trade.quantity}주</Text><Text>{money(trade.price)}</Text></View>)}
      <Text style={styles.sectionLabel}>관련 뉴스</Text>{news.length ? news.map((item, index) => <View style={styles.newsRow} key={`${item.title}-${index}`}><Text style={styles.newsTitle}>{item.title}</Text><Text style={item.impact >= 0 ? styles.up : styles.down}>{item.impact >= 0 ? '+' : ''}{item.impact}</Text></View>) : <Text style={styles.empty}>관련 뉴스 없음</Text>}
    </View>}
    <Text style={styles.title}>시장 이벤트</Text>{market.events.map((event, index) => <View style={styles.event} key={`${event.title}-${index}`}><Text style={styles.eventTitle}>{event.title}</Text><Text style={event.impact > 0 ? styles.up : styles.down}>{event.impact > 0 ? '+' : ''}{event.impact}</Text></View>)}{user && <TouchableOpacity style={styles.resetShortcut} onPress={resetAccount}><Text>인생 리셋</Text></TouchableOpacity>}
  </ScrollView><Modal transparent visible={profileOpen} animationType="fade" onRequestClose={() => { if (!account?.requiresNickname) setProfileOpen(false); }}><View style={styles.modalBackdrop}><View style={styles.modalCard}><Text style={styles.detailTitle}>{account?.requiresNickname ? '닉네임 설정' : '마이페이지'}</Text><Text style={styles.modalHelp}>{account?.requiresNickname ? '첫 로그인 기념으로 닉네임을 정해 주세요.' : (profile?.email || '프로필 정보를 관리하세요.')}</Text><View style={styles.profileInitial}><Text>{(nickname || 'G').charAt(0).toUpperCase()}</Text></View><TextInput style={styles.input} value={nickname} onChangeText={setNickname} placeholder="닉네임 (2~50자)" maxLength={50} /><TouchableOpacity style={styles.orderButton} onPress={saveProfile}><Text style={styles.orderText}>프로필 저장</Text></TouchableOpacity><Text style={styles.message}>{profileMessage}</Text><Text style={styles.sectionLabel}>보유 종목</Text>{(market.portfolio?.positions || []).length ? market.portfolio.positions.map(position => <View style={styles.holdingRow} key={position.stockCode}><View><Text style={styles.newsTitle}>{position.stockCode} · {position.quantity}주</Text><Text style={styles.holdingMeta}>평균 {money(position.averagePrice)} · 평가액 {money(position.marketValue)}</Text><Text style={styles.holdingMeta}>결제 완료 {position.settledQuantity ?? position.quantity}주 · 실현손익 {money(position.realizedProfitLoss || 0)}</Text></View><Text style={position.profitLoss >= 0 ? styles.up : styles.down}>{position.profitLoss >= 0 ? '+' : ''}{money(position.profitLoss)}{'\n'}평가손익 {position.profitLossPercent?.toFixed?.(2) || '0.00'}%</Text></View>) : <Text style={styles.empty}>보유 중인 종목이 없습니다.</Text>}<Text style={styles.sectionLabel}>미체결 주문</Text>{openOrders.length ? openOrders.map(order => <View style={styles.openOrderRow} key={order.id}><View style={styles.openOrderInfo}><Text style={order.side === 'BUY' ? styles.orderSideBuy : styles.orderSideSell}>{order.side === 'BUY' ? '매수' : '매도'} · {order.stockCode}</Text><Text style={styles.holdingMeta}>{order.remainingQuantity}주 · 지정가 {money(order.price)}</Text><Text style={styles.holdingMeta}>{order.side === 'BUY' ? `예약금 ${money(order.reservedCash)}` : `예약수량 ${order.reservedQuantity}주`}</Text></View><TouchableOpacity style={styles.cancelOrder} onPress={() => cancelOpenOrder(order.id)}><Text>취소</Text></TouchableOpacity></View>) : <Text style={styles.empty}>미체결 주문이 없습니다.</Text>}<Text style={styles.sectionLabel}>체결 완료</Text>{settlements.length ? settlements.map(item => <View style={styles.openOrderRow} key={`s${item.id}`}><View style={styles.openOrderInfo}><Text style={item.side === 'BUY' ? styles.orderSideBuy : styles.orderSideSell}>{item.side === 'BUY' ? '매수' : '매도'} · {item.stockCode}</Text><Text style={styles.holdingMeta}>{item.quantity}주 · 수수료 {money(item.fee)}</Text><Text style={styles.holdingMeta}>체결 완료 {new Date(item.settlementAt).toLocaleString('ko-KR')}</Text></View><View style={styles.settlementActions}><Text style={styles.holdingMeta}>{item.side === 'BUY' ? '출금' : '입금'} {money(item.netAmount)}</Text><Text style={styles.holdingMeta}>체결 완료</Text></View></View>) : <Text style={styles.empty}>체결 완료된 거래가 없습니다.</Text>}{!account?.requiresNickname && <TouchableOpacity style={styles.modalClose} onPress={() => setProfileOpen(false)}><Text>닫기</Text></TouchableOpacity>}</View></View></Modal><Modal transparent visible={rankingOpen} animationType="fade" onRequestClose={() => setRankingOpen(false)}><View style={styles.modalBackdrop}><View style={styles.modalCard}><Text style={styles.detailTitle}>투자 랭킹</Text><Text style={styles.modalHelp}>봇을 제외한 사용자만 표시됩니다.</Text>{ranking.length ? ranking.map(entry => <View style={styles.rankingRow} key={`${entry.rank}-${entry.nickname}`}><Text style={styles.rankingRank}>{entry.rank}</Text><Text style={styles.newsTitle}>{entry.nickname}</Text><Text style={styles.rankingAsset}>{money(entry.totalAsset)}</Text></View>) : <Text style={styles.empty}>아직 랭킹에 참여한 사용자가 없습니다.</Text>}<TouchableOpacity style={styles.modalClose} onPress={() => setRankingOpen(false)}><Text>닫기</Text></TouchableOpacity></View></View></Modal></SafeAreaView>;
}

const styles = StyleSheet.create({ safe:{flex:1,backgroundColor:'#f7f8fb'},page:{padding:22,paddingBottom:50},header:{flexDirection:'row',justifyContent:'space-between',alignItems:'center',marginBottom:14},brand:{fontSize:28,fontWeight:'800',color:'#6750a4'},status:{color:'#697386',marginTop:2},river:{color:'#6750a4',fontSize:11,marginTop:2},loginButton:{backgroundColor:'#6750a4',borderRadius:10,paddingVertical:10,paddingHorizontal:12},loginText:{color:'white',fontWeight:'700'},menuPanel:{position:'absolute',right:22,top:72,zIndex:5,backgroundColor:'white',borderRadius:12,padding:6,elevation:5,shadowColor:'#000',shadowOpacity:.15,shadowRadius:8},menuItem:{paddingVertical:11,paddingHorizontal:16,minWidth:140},asset:{backgroundColor:'#6750a4',borderRadius:18,padding:22,marginBottom:8},label:{color:'#e9ddff'},assetValue:{fontSize:30,fontWeight:'800',color:'white',marginVertical:6},cash:{color:'#f1ecff'},guest:{backgroundColor:'#ede8fb',borderRadius:14,padding:15},guestText:{color:'#6750a4',lineHeight:20},title:{fontSize:19,fontWeight:'800',marginTop:22,marginBottom:10},card:{backgroundColor:'white',borderRadius:14,padding:18,marginBottom:10},selectedCard:{borderWidth:2,borderColor:'#6750a4'},cardTop:{flexDirection:'row',justifyContent:'space-between'},code:{color:'#697386',fontSize:12},name:{fontSize:16,fontWeight:'700',marginTop:5},price:{fontSize:21,fontWeight:'800',marginTop:14},volume:{color:'#697386',fontSize:12,marginTop:7},up:{color:'#e04f5f',fontWeight:'700'},down:{color:'#2379ba',fontWeight:'700'},detail:{backgroundColor:'white',borderRadius:16,padding:18,marginTop:10},detailTitle:{fontSize:21,fontWeight:'800'},detailPrice:{fontSize:24,fontWeight:'800',color:'#6750a4',marginTop:5},sectionLabel:{fontSize:14,fontWeight:'800',marginTop:20,marginBottom:8},chart:{height:100,flexDirection:'row',alignItems:'flex-end',gap:3,borderBottomWidth:1,borderBottomColor:'#dfe3ec',paddingHorizontal:3},chartBar:{flex:1,minWidth:3,backgroundColor:'#8c78ce',borderTopLeftRadius:3,borderTopRightRadius:3},depth:{flexDirection:'row',gap:6,minHeight:180},depthColumn:{flex:1,justifyContent:'flex-start',gap:4},depthRow:{minHeight:23,flexDirection:'row',alignItems:'center',gap:3},barTrack:{flex:1,height:18,justifyContent:'center'},buyBar:{height:18,backgroundColor:'#e04f5f',opacity:.25,borderRadius:3},sellBar:{height:18,backgroundColor:'#2379ba',opacity:.25,borderRadius:3},buyText:{color:'#e04f5f',fontWeight:'700',fontSize:11},sellText:{color:'#2379ba',fontWeight:'700',fontSize:11},depthQty:{fontSize:10,color:'#697386',width:35,textAlign:'right'},empty:{color:'#697386',fontSize:12},switchRow:{flexDirection:'row',gap:8,marginBottom:8},switch:{flex:1,borderWidth:1,borderColor:'#d4d7df',borderRadius:9,padding:11,alignItems:'center'},active:{backgroundColor:'#6750a4',borderColor:'#6750a4'},activeBuy:{backgroundColor:'#ffe7ea',borderColor:'#e04f5f'},activeSell:{backgroundColor:'#e4f1fb',borderColor:'#2379ba'},input:{borderWidth:1,borderColor:'#cdd3dd',borderRadius:9,paddingHorizontal:12,height:44,marginBottom:8},orderButton:{height:44,borderRadius:9,backgroundColor:'#6750a4',alignItems:'center',justifyContent:'center'},orderText:{color:'white',fontWeight:'800'},message:{color:'#c92a39',marginTop:8,minHeight:18},tradeRow:{flexDirection:'row',justifyContent:'space-between',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsRow:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsTitle:{flex:1,marginRight:8,lineHeight:19},holdingRow:{flexDirection:'row',justifyContent:'space-between',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},holdingMeta:{color:'#697386',fontSize:11,marginTop:3},profileInitial:{width:64,height:64,borderRadius:32,alignSelf:'center',marginVertical:12,backgroundColor:'#ede8fb',alignItems:'center',justifyContent:'center'},modalBackdrop:{flex:1,backgroundColor:'rgba(23,32,47,.48)',justifyContent:'center',padding:20},modalCard:{backgroundColor:'white',borderRadius:18,padding:22,maxHeight:'88%'},modalHelp:{color:'#697386',marginTop:6,marginBottom:12},modalClose:{marginTop:14,alignItems:'center',padding:10},rankingRow:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:11,gap:10},rankingRank:{color:'#6750a4',fontWeight:'800',width:24,textAlign:'center'},rankingAsset:{fontWeight:'700'},event:{backgroundColor:'white',padding:15,borderRadius:12,marginBottom:8,flexDirection:'row',justifyContent:'space-between'},eventTitle:{flex:1,marginRight:10}});
styles.event = StyleSheet.flatten([styles.event, { borderRadius: 12 }]);
styles.holdingRow = StyleSheet.flatten([styles.holdingRow, { alignItems: 'center' }]);
styles.openOrderRow = { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: '#e5e9ef', paddingVertical: 10 };
styles.openOrderInfo = { flex: 1, marginRight: 10 };
styles.settlementActions = { alignItems: 'flex-end', gap: 6 };
styles.orderSideBuy = { color: '#e04f5f', fontWeight: '700' };
styles.orderSideSell = { color: '#2379ba', fontWeight: '700' };
styles.cancelOrder = { borderWidth: 1, borderColor: '#cdd3dd', borderRadius: 8, paddingVertical: 7, paddingHorizontal: 10 };
styles.resetShortcut = { marginTop: 18, borderWidth: 1, borderColor: '#f0b8b4', borderRadius: 9, padding: 11, alignItems: 'center', backgroundColor: '#fff3f3' };
styles.resetButton = { marginTop: 8, borderWidth: 1, borderColor: '#f0b8b4', borderRadius: 9, padding: 11, alignItems: 'center', backgroundColor: '#fff3f3' };
styles.disabledButton = { opacity: .55 };
styles.riverMenu = { color: '#6750a4', fontSize: 11, paddingVertical: 8, paddingHorizontal: 16, borderBottomWidth: 1, borderBottomColor: '#e5e9ef' };
styles.marketEstimate = { borderWidth: 1, borderColor: '#d9d2ee', borderRadius: 10, padding: 12, marginBottom: 8, backgroundColor: '#faf8ff' };
styles.marketEstimateLabel = { color: '#697386', fontSize: 12, fontWeight: '700' };
styles.marketEstimateAmount = { color: '#6750a4', fontSize: 20, fontWeight: '800', marginTop: 4 };
