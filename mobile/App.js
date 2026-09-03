import React, { useEffect, useState } from 'react';
import { SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import { API_BASE_URL, MARKET_SOCKET_URL } from './src/config';

const money = value => `${Number(value).toLocaleString('ko-KR')}원`;

export default function App() {
  const [market, setMarket] = useState({ stocks: [], portfolio: { cash: 0, totalAsset: 0 }, events: [] });
  const [status, setStatus] = useState('서버 연결 중');

  useEffect(() => {
    fetch(`${API_BASE_URL}/api/stocks`).then(response => response.json()).then(stocks => setMarket(previous => ({ ...previous, stocks }))).catch(() => setStatus('API 주소를 확인하세요'));
    const socket = new WebSocket(MARKET_SOCKET_URL);
    socket.onopen = () => setStatus('실시간 연결됨');
    socket.onmessage = event => { const data = JSON.parse(event.data); if (data.type === 'MARKET_UPDATED') setMarket(data.payload); };
    socket.onerror = () => setStatus('실시간 연결 실패');
    return () => socket.close();
  }, []);

  return <SafeAreaView style={styles.safe}><ScrollView contentContainerStyle={styles.page}>
    <Text style={styles.brand}>GameStock</Text><Text style={styles.status}>{status}</Text>
    <View style={styles.asset}><Text style={styles.label}>총 자산</Text><Text style={styles.assetValue}>{money(market.portfolio.totalAsset)}</Text><Text style={styles.cash}>현금 {money(market.portfolio.cash)}</Text></View>
    <Text style={styles.title}>오늘의 게임 종목</Text>
    {market.stocks.map(stock => <View style={styles.card} key={stock.code}><Text style={styles.code}>{stock.code} · {stock.genre}</Text><Text style={styles.name}>{stock.name}</Text><Text style={styles.price}>{money(stock.price)}</Text><Text style={stock.changePercent >= 0 ? styles.up : styles.down}>{stock.changePercent >= 0 ? '▲' : '▼'} {Math.abs(stock.changePercent).toFixed(2)}%</Text></View>)}
    <Text style={styles.title}>시장 이벤트</Text>
    {market.events.map(event => <View style={styles.event} key={event.title}><Text>{event.title}</Text><Text style={event.impact > 0 ? styles.up : styles.down}>{event.impact > 0 ? '+' : ''}{event.impact}</Text></View>)}
  </ScrollView></SafeAreaView>;
}

const styles = StyleSheet.create({ safe:{flex:1,backgroundColor:'#f7f8fb'},page:{padding:22,gap:12},brand:{fontSize:28,fontWeight:'800',color:'#6750a4'},status:{color:'#697386',marginBottom:12},asset:{backgroundColor:'#6750a4',borderRadius:18,padding:22},label:{color:'#e9ddff'},assetValue:{fontSize:30,fontWeight:'800',color:'white',marginVertical:6},cash:{color:'#f1ecff'},title:{fontSize:19,fontWeight:'800',marginTop:22},card:{backgroundColor:'white',borderRadius:14,padding:18},code:{color:'#697386',fontSize:12},name:{fontSize:16,fontWeight:'700',marginTop:5},price:{fontSize:21,fontWeight:'800',marginTop:17},up:{color:'#e04f5f',fontWeight:'700'},down:{color:'#2379ba',fontWeight:'700'},event:{backgroundColor:'white',padding:16,borderRadius:12,flexDirection:'row',justifyContent:'space-between'} });
