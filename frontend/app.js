const money = new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 });
// 로컬 웹 서버는 8081 백엔드를 사용하고, 배포 환경은 현재 공개 도메인을 사용한다.
const API_BASE_URL = window.GAMESTOCK_API_BASE_URL ||
  (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
    ? 'http://localhost:8081'
    : window.location.origin);
const MARKET_SOCKET_URL = `${API_BASE_URL.replace(/^http/, 'ws')}/ws/market`;
const stockContainer = document.querySelector('#stocks');
const stockSelect = document.querySelector('#stock-code');
const message = document.querySelector('#order-message');

async function api(path, options) {
  const response = await fetch(`${API_BASE_URL}${path}`, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.message || '요청 처리 중 오류가 발생했습니다.');
  return payload;
}

function applyMarketSnapshot(snapshot) {
  renderStocks(snapshot.stocks);
  renderPortfolio(snapshot.portfolio);
  renderEvents(snapshot.events);
}

function renderStocks(stocks) {
  stockContainer.innerHTML = stocks.map(stock => {
    const up = stock.changePercent >= 0;
    return `<article class="stock-card"><span class="code">${stock.code} · ${stock.genre}</span><h3>${stock.name}</h3><div class="price">${money.format(stock.price)}</div><span class="change ${up ? 'up' : 'down'}">${up ? '▲' : '▼'} ${Math.abs(stock.changePercent).toFixed(2)}%</span><span class="meta"> · 거래량 ${stock.volume.toLocaleString()}</span></article>`;
  }).join('');
  stockSelect.innerHTML = stocks.map(stock => `<option value="${stock.code}">${stock.code} · ${stock.name}</option>`).join('');
}

function renderPortfolio(portfolio) {
  document.querySelector('#cash').textContent = money.format(portfolio.cash);
  document.querySelector('#asset-value').textContent = money.format(portfolio.assetValue);
  document.querySelector('#total-asset').textContent = money.format(portfolio.totalAsset);
}

function renderEvents(events) {
  document.querySelector('#event-list').innerHTML = events.map(event => `<div class="event"><div><strong>${event.title}</strong><small>${event.stockCode} 종목에 반영 중</small></div><span class="impact ${event.sentiment}">${event.impact > 0 ? '+' : ''}${event.impact}</span></div>`).join('');
}

async function refreshMarket() {
  const [stocks, portfolio, events] = await Promise.all([api('/api/stocks'), api('/api/portfolio'), api('/api/market-events')]);
  applyMarketSnapshot({ stocks, portfolio, events });
}

document.querySelector('#order-form').addEventListener('submit', async event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(event.currentTarget));
  message.className = 'message'; message.textContent = '주문을 처리하고 있습니다…';
  try {
    const result = await api('/api/orders', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(data) });
    message.className = 'message success'; message.textContent = `${result.stockCode} ${result.quantity}주 주문이 체결되었습니다.`;
    await refreshMarket();
    stockSelect.value = result.stockCode;
  } catch (error) {
    message.className = 'message error'; message.textContent = error.message;
  }
});

function connectRealtimeMarket() {
  const socket = new WebSocket(MARKET_SOCKET_URL);
  socket.addEventListener('open', () => {
    const status = document.querySelector('#server-status');
    status.textContent = '실시간 연결됨'; status.classList.add('ok');
  });
  socket.addEventListener('message', event => {
    const data = JSON.parse(event.data);
    if (data.type === 'MARKET_UPDATED') applyMarketSnapshot(data.payload);
  });
  socket.addEventListener('close', () => setTimeout(connectRealtimeMarket, 2000));
}

api('/api/health').then(() => connectRealtimeMarket()).catch(() => document.querySelector('#server-status').textContent = '서버 연결 실패');
refreshMarket().catch(error => { stockContainer.textContent = `시장 정보를 불러오지 못했습니다: ${error.message}`; });
