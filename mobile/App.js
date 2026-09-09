import React, { useEffect, useMemo, useState } from 'react';
import * as WebBrowser from 'expo-web-browser';
import * as Google from 'expo-auth-session/providers/google';
import { initializeApp } from 'firebase/app';
import { GoogleAuthProvider, getAuth, getReactNativePersistence, initializeAuth, onAuthStateChanged, signInWithCredential, signOut } from 'firebase/auth';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SafeAreaView, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { API_BASE_URL, MARKET_SOCKET_URL, FIREBASE_CONFIG, GOOGLE_WEB_CLIENT_ID } from './src/config';

WebBrowser.maybeCompleteAuthSession();
const firebaseApp = initializeApp(FIREBASE_CONFIG);
let firebaseAuth;
try { firebaseAuth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) }); }
catch { firebaseAuth = getAuth(firebaseApp); }
const money = value => `${Number(value || 0).toLocaleString('ko-KR')}원`;

export default function App() {
  const [market, setMarket] = useState({ stocks: [], portfolio: null, events: [] });
  const [selected, setSelected] = useState(null);
  const [book, setBook] = useState({ bids: [], asks: [] });
  const [trades, setTrades] = useState([]);
  const [history, setHistory] = useState([]);
  const [news, setNews] = useState([]);
  const [side, setSide] = useState('BUY');
  const [orderType, setOrderType] = useState('MARKET');
  const [quantity, setQuantity] = useState('1');
  const [limitPrice, setLimitPrice] = useState('');
  const [user, setUser] = useState(null);
  const [status, setStatus] = useState('서버 연결 중');
  const [message, setMessage] = useState('');
  const [request, response, promptAsync] = Google.useAuthRequest({ webClientId: GOOGLE_WEB_CLIENT_ID });

  const api = async (path, options = {}) => {
    const token = firebaseAuth.currentUser ? await firebaseAuth.currentUser.getIdToken() : null;
    const result = await fetch(`${API_BASE_URL}${path}`, { ...options, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers || {}) } });
    const payload = await result.json();
    if (!result.ok) throw new Error(payload.message || '요청 처리에 실패했습니다.');
    return payload;
  };

  const refreshPortfolio = async () => {
    if (!firebaseAuth.currentUser) return;
    const portfolio = await api('/api/portfolio');
    setMarket(previous => ({ ...previous, portfolio }));
  };
  const loadDetails = async code => { setSelected(code); try { const [nextBook, nextTrades, nextHistory, nextNews] = await Promise.all([api(`/api/orderbook/${code}`), api(`/api/stocks/${code}/trades`), api(`/api/stocks/${code}/history`), api(`/api/stocks/${code}/news`)]); setBook(nextBook); setTrades(nextTrades); setHistory(nextHistory); setNews(nextNews); } catch (error) { setMessage(error.message); } };

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(firebaseAuth, async current => { setUser(current); if (current) { try { await api('/api/auth/google', { method: 'POST' }); await refreshPortfolio(); } catch (error) { setMessage(error.message); } } else setMarket(previous => ({ ...previous, portfolio: null })); });
    Promise.all([fetch(`${API_BASE_URL}/api/stocks`).then(result => result.json()), fetch(`${API_BASE_URL}/api/market-events`).then(result => result.json())]).then(([stocks, events]) => { setMarket(previous => ({ ...previous, stocks, events })); setStatus('실시간 연결됨'); }).catch(() => setStatus('API 주소를 확인하세요'));
    const socket = new WebSocket(MARKET_SOCKET_URL); socket.onopen = () => setStatus('실시간 연결됨'); socket.onmessage = event => { const data = JSON.parse(event.data); if (data.type === 'MARKET_UPDATED') setMarket(previous => ({ ...previous, stocks: data.payload.stocks, events: data.payload.events })); }; socket.onerror = () => setStatus('실시간 연결 실패');
    return () => { unsubscribe(); socket.close(); };
  }, []);

  useEffect(() => { if (!selected) return; const timer = setInterval(() => loadDetails(selected), 10000); return () => clearInterval(timer); }, [selected]);
  useEffect(() => { if (response?.type === 'success') { const credential = GoogleAuthProvider.credential(response.params.id_token); signInWithCredential(firebaseAuth, credential).catch(error => setMessage(error.message)); } }, [response]);

  const selectedStock = useMemo(() => market.stocks.find(stock => stock.code === selected), [market.stocks, selected]);
  const maxDepth = Math.max(1, ...book.bids.map(level => level.quantity), ...book.asks.map(level => level.quantity));
  const chartPrices = history.slice(-30).map(point => Number(point.price));
  const chartMin = chartPrices.length ? Math.min(...chartPrices) : 0;
  const chartMax = chartPrices.length ? Math.max(...chartPrices) : 1;
  const submitOrder = async () => {
    if (!user) { setMessage('거래하려면 먼저 Google 로그인이 필요합니다.'); return; }
    try { const payload = { stockCode: selected, side, quantity: Number(quantity), orderType }; if (orderType === 'LIMIT') payload.price = Number(limitPrice); const result = await api('/api/orders', { method: 'POST', body: JSON.stringify(payload) }); setMessage(result.message); await refreshPortfolio(); await loadDetails(selected); } catch (error) { setMessage(error.message); }
  };

  return <SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.page}>
    <View style={styles.header}><View><Text style={styles.brand}>GameStock</Text><Text style={styles.status}>{status}</Text></View><TouchableOpacity style={styles.loginButton} onPress={() => user ? signOut(firebaseAuth) : promptAsync()} disabled={!request && !user}><Text style={styles.loginText}>{user ? '로그아웃' : 'Google 로그인'}</Text></TouchableOpacity></View>
    {market.portfolio ? <View style={styles.asset}><Text style={styles.label}>총 자산</Text><Text style={styles.assetValue}>{money(market.portfolio.totalAsset)}</Text><Text style={styles.cash}>현금 {money(market.portfolio.cash)}</Text></View> : <View style={styles.guest}><Text style={styles.guestText}>시장은 누구나 볼 수 있습니다. 거래하려면 Google 로그인하세요.</Text></View>}
    <Text style={styles.title}>오늘의 게임 종목</Text>
    {market.stocks.map(stock => <TouchableOpacity style={[styles.card, selected === stock.code && styles.selectedCard]} key={stock.code} onPress={() => loadDetails(stock.code)}><View style={styles.cardTop}><Text style={styles.code}>{stock.code} · {stock.genre}</Text><Text style={stock.changePercent >= 0 ? styles.up : styles.down}>{stock.changePercent >= 0 ? '▲' : '▼'} {Math.abs(stock.changePercent).toFixed(2)}%</Text></View><Text style={styles.name}>{stock.name}</Text><Text style={styles.price}>{money(stock.price)}</Text><Text style={styles.volume}>거래량 {Number(stock.volume).toLocaleString()} · 탭하여 거래</Text></TouchableOpacity>)}
    {selectedStock && <View style={styles.detail}><Text style={styles.detailTitle}>{selectedStock.name}</Text><Text style={styles.detailPrice}>{money(selectedStock.price)}</Text><Text style={styles.sectionLabel}>가격 추이</Text><View style={styles.chart}>{chartPrices.length ? chartPrices.map((price, index) => <View key={`${price}-${index}`} style={[styles.chartBar, { height: `${Math.max(8, ((price - chartMin) / Math.max(1, chartMax - chartMin)) * 88 + 12)}%` }]} />) : <Text style={styles.empty}>가격 데이터 없음</Text>}</View><Text style={styles.sectionLabel}>호가 깊이</Text><View style={styles.depth}><View style={styles.depthColumn}>{book.asks.length ? book.asks.map(level => <View style={styles.depthRow} key={`a${level.price}`}><Text style={styles.sellText}>{level.price.toLocaleString()}</Text><View style={styles.barTrack}><View style={[styles.sellBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{level.quantity}주</Text></View>) : <Text style={styles.empty}>매도 대기 없음</Text>}</View><View style={styles.depthColumn}>{book.bids.length ? book.bids.map(level => <View style={styles.depthRow} key={`b${level.price}`}><Text style={styles.buyText}>{level.price.toLocaleString()}</Text><View style={styles.barTrack}><View style={[styles.buyBar, { width: `${Math.max(5, level.quantity / maxDepth * 100)}%` }]} /></View><Text style={styles.depthQty}>{level.quantity}주</Text></View>) : <Text style={styles.empty}>매수 대기 없음</Text>}</View></View>
      <Text style={styles.sectionLabel}>주문</Text><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, side === 'BUY' && styles.activeBuy]} onPress={() => setSide('BUY')}><Text>매수</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, side === 'SELL' && styles.activeSell]} onPress={() => setSide('SELL')}><Text>매도</Text></TouchableOpacity></View><View style={styles.switchRow}><TouchableOpacity style={[styles.switch, orderType === 'MARKET' && styles.active]} onPress={() => setOrderType('MARKET')}><Text>시장가</Text></TouchableOpacity><TouchableOpacity style={[styles.switch, orderType === 'LIMIT' && styles.active]} onPress={() => setOrderType('LIMIT')}><Text>지정가</Text></TouchableOpacity></View><TextInput style={styles.input} keyboardType="number-pad" value={quantity} onChangeText={setQuantity} placeholder="수량" />{orderType === 'LIMIT' && <TextInput style={styles.input} keyboardType="number-pad" value={limitPrice} onChangeText={setLimitPrice} placeholder="지정가" />}<TouchableOpacity style={styles.orderButton} onPress={submitOrder}><Text style={styles.orderText}>주문 제출</Text></TouchableOpacity><Text style={styles.message}>{message}</Text>
      <Text style={styles.sectionLabel}>최근 전체 체결 10건</Text>{trades.slice(0, 10).map((trade, index) => <View style={styles.tradeRow} key={`${trade.createdAt}-${index}`}><Text style={trade.side === 'BUY' ? styles.buyText : styles.sellText}>{trade.side === 'BUY' ? '매수' : '매도'}</Text><Text>{trade.quantity}주</Text><Text>{money(trade.price)}</Text></View>)}
      <Text style={styles.sectionLabel}>관련 뉴스</Text>{news.length ? news.map((item, index) => <View style={styles.newsRow} key={`${item.title}-${index}`}><Text style={styles.newsTitle}>{item.title}</Text><Text style={item.impact >= 0 ? styles.up : styles.down}>{item.impact >= 0 ? '+' : ''}{item.impact}</Text></View>) : <Text style={styles.empty}>관련 뉴스 없음</Text>}
    </View>}
    <Text style={styles.title}>시장 이벤트</Text>{market.events.map((event, index) => <View style={styles.event} key={`${event.title}-${index}`}><Text style={styles.eventTitle}>{event.title}</Text><Text style={event.impact > 0 ? styles.up : styles.down}>{event.impact > 0 ? '+' : ''}{event.impact}</Text></View>)}
  </ScrollView></SafeAreaView>;
}

const styles = StyleSheet.create({ safe:{flex:1,backgroundColor:'#f7f8fb'},page:{padding:22,paddingBottom:50},header:{flexDirection:'row',justifyContent:'space-between',alignItems:'center',marginBottom:14},brand:{fontSize:28,fontWeight:'800',color:'#6750a4'},status:{color:'#697386',marginTop:2},loginButton:{backgroundColor:'#6750a4',borderRadius:10,paddingVertical:10,paddingHorizontal:12},loginText:{color:'white',fontWeight:'700'},asset:{backgroundColor:'#6750a4',borderRadius:18,padding:22,marginBottom:8},label:{color:'#e9ddff'},assetValue:{fontSize:30,fontWeight:'800',color:'white',marginVertical:6},cash:{color:'#f1ecff'},guest:{backgroundColor:'#ede8fb',borderRadius:14,padding:15},guestText:{color:'#6750a4',lineHeight:20},title:{fontSize:19,fontWeight:'800',marginTop:22,marginBottom:10},card:{backgroundColor:'white',borderRadius:14,padding:18,marginBottom:10},selectedCard:{borderWidth:2,borderColor:'#6750a4'},cardTop:{flexDirection:'row',justifyContent:'space-between'},code:{color:'#697386',fontSize:12},name:{fontSize:16,fontWeight:'700',marginTop:5},price:{fontSize:21,fontWeight:'800',marginTop:14},volume:{color:'#697386',fontSize:12,marginTop:7},up:{color:'#e04f5f',fontWeight:'700'},down:{color:'#2379ba',fontWeight:'700'},detail:{backgroundColor:'white',borderRadius:16,padding:18,marginTop:10},detailTitle:{fontSize:21,fontWeight:'800'},detailPrice:{fontSize:24,fontWeight:'800',color:'#6750a4',marginTop:5},sectionLabel:{fontSize:14,fontWeight:'800',marginTop:20,marginBottom:8},chart:{height:100,flexDirection:'row',alignItems:'flex-end',gap:3,borderBottomWidth:1,borderBottomColor:'#dfe3ec',paddingHorizontal:3},chartBar:{flex:1,minWidth:3,backgroundColor:'#8c78ce',borderTopLeftRadius:3,borderTopRightRadius:3},depth:{flexDirection:'row',gap:6,minHeight:180},depthColumn:{flex:1,justifyContent:'flex-start',gap:4},depthRow:{minHeight:23,flexDirection:'row',alignItems:'center',gap:3},barTrack:{flex:1,height:18,justifyContent:'center'},buyBar:{height:18,backgroundColor:'#e04f5f',opacity:.25,borderRadius:3},sellBar:{height:18,backgroundColor:'#2379ba',opacity:.25,borderRadius:3},buyText:{color:'#e04f5f',fontWeight:'700',fontSize:11},sellText:{color:'#2379ba',fontWeight:'700',fontSize:11},depthQty:{fontSize:10,color:'#697386',width:35,textAlign:'right'},empty:{color:'#697386',fontSize:12},switchRow:{flexDirection:'row',gap:8,marginBottom:8},switch:{flex:1,borderWidth:1,borderColor:'#d4d7df',borderRadius:9,padding:11,alignItems:'center'},active:{backgroundColor:'#6750a4',borderColor:'#6750a4'},activeBuy:{backgroundColor:'#ffe7ea',borderColor:'#e04f5f'},activeSell:{backgroundColor:'#e4f1fb',borderColor:'#2379ba'},input:{borderWidth:1,borderColor:'#cdd3dd',borderRadius:9,paddingHorizontal:12,height:44,marginBottom:8},orderButton:{height:44,borderRadius:9,backgroundColor:'#6750a4',alignItems:'center',justifyContent:'center'},orderText:{color:'white',fontWeight:'800'},message:{color:'#c92a39',marginTop:8,minHeight:18},tradeRow:{flexDirection:'row',justifyContent:'space-between',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsRow:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderTopColor:'#e5e9ef',paddingVertical:10},newsTitle:{flex:1,marginRight:8,lineHeight:19},event:{backgroundColor:'white',padding:15,borderRadius:12,marginBottom:8,flexDirection:'row',justifyContent:'space-between'},eventTitle:{flex:1,marginRight:10}});
