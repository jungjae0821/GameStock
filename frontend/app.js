const money = new Intl.NumberFormat("ko-KR", {
  style: "currency",
  currency: "KRW",
  maximumFractionDigits: 0,
});
// 로컬 웹 서버는 8081 백엔드를 사용하고, 배포 환경은 현재 공개 도메인을 사용한다.
const API_BASE_URL =
  window.GAMESTOCK_API_BASE_URL ||
  (window.location.hostname === "localhost" ||
  window.location.hostname === "127.0.0.1"
    ? "http://localhost:8081"
    : window.location.origin);
const MARKET_SOCKET_URL = `${API_BASE_URL.replace(/^http/, "ws")}/ws/market`;
const stockContainer = document.querySelector("#stocks");
const marketPage = document.querySelector("body > main:not(#stock-detail)");
const detailPage = document.querySelector("#stock-detail");
const message = document.querySelector("#order-message");
const priceHistory = new Map();
let currentStocks = [];
let currentEvents = [];

async function api(path, options) {
  const response = await fetch(`${API_BASE_URL}${path}`, options);
  const payload = await response.json();
  if (!response.ok)
    throw new Error(payload.message || "요청 처리 중 오류가 발생했습니다.");
  return payload;
}

function applyMarketSnapshot(snapshot) {
  currentStocks = snapshot.stocks;
  currentEvents = snapshot.events;
  snapshot.stocks.forEach((stock) => {
    const history = priceHistory.get(stock.code) || [];
    history.push(stock.price);
    priceHistory.set(stock.code, history.slice(-40));
  });
  renderStocks(snapshot.stocks);
  renderPortfolio(snapshot.portfolio);
  renderEvents(snapshot.events);
  renderDetail();
}

function renderStocks(stocks) {
  stockContainer.innerHTML = stocks
    .map((stock) => {
      const up = stock.changePercent >= 0;
      return `<a class="stock-card" href="#stock/${stock.code}" aria-label="${stock.name} 상세 보기"><span class="code">${stock.code} · ${stock.genre}</span><h3>${stock.name}</h3><div class="price">${money.format(stock.price)}</div><span class="change ${up ? "up" : "down"}">${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%</span><span class="meta"> · 거래량 ${stock.volume.toLocaleString()}</span><span class="card-link">상세 보기 →</span></a>`;
    })
    .join("");
}

function renderPortfolio(portfolio) {
  document.querySelector("#cash").textContent = money.format(portfolio.cash);
  document.querySelector("#asset-value").textContent = money.format(
    portfolio.assetValue,
  );
  document.querySelector("#total-asset").textContent = money.format(
    portfolio.totalAsset,
  );
}

function renderEvents(events) {
  document.querySelector("#event-list").innerHTML = events
    .map((event) => {
      const stock = currentStocks.find((item) => item.code === event.stockCode);
      const stockLabel = stock
        ? `${stock.code} · ${stock.name}`
        : "시장 전체";
      return `<div class="event"><div><strong>${event.title}</strong><small>${stockLabel}</small></div></div>`;
    })
    .join("");
}

function renderDetail() {
  const code = location.hash.startsWith("#stock/") ? location.hash.slice(7) : null;
  const stock = currentStocks.find((item) => item.code === code);
  if (!stock) {
    marketPage.hidden = false;
    detailPage.hidden = true;
    return;
  }
  marketPage.hidden = true;
  detailPage.hidden = false;
  const up = stock.changePercent >= 0;
  document.querySelector("#detail-code").textContent = `${stock.code} · ${stock.genre}`;
  document.querySelector("#detail-name").textContent = stock.name;
  document.querySelector("#detail-genre").textContent = `거래량 ${stock.volume.toLocaleString()}`;
  document.querySelector("#detail-price").textContent = money.format(stock.price);
  const change = document.querySelector("#detail-change");
  change.className = `detail-change ${up ? "up" : "down"}`;
  change.textContent = `${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%`;
  document.querySelector("#stock-code").value = stock.code;
  document.querySelector("#detail-events").innerHTML = currentEvents
    .filter((event) => event.stockCode === stock.code)
    .map((event) => `<div class="event"><strong>${event.title}</strong></div>`)
    .join("") || '<p class="empty-state">아직 관련 소식이 없습니다.</p>';
  drawChart(priceHistory.get(stock.code) || [stock.price]);
}

function drawChart(values) {
  const canvas = document.querySelector("#price-chart");
  const context = canvas.getContext("2d");
  const width = canvas.clientWidth * window.devicePixelRatio;
  const height = canvas.clientHeight * window.devicePixelRatio;
  canvas.width = width;
  canvas.height = height;
  context.scale(window.devicePixelRatio, window.devicePixelRatio);
  const displayWidth = canvas.clientWidth;
  const displayHeight = canvas.clientHeight;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  context.clearRect(0, 0, displayWidth, displayHeight);
  context.strokeStyle = "#e5e9ef";
  context.lineWidth = 1;
  for (let index = 1; index < 4; index += 1) {
    const y = (displayHeight / 4) * index;
    context.beginPath(); context.moveTo(0, y); context.lineTo(displayWidth, y); context.stroke();
  }
  context.strokeStyle = values.at(-1) >= values[0] ? "#e04f5f" : "#2379ba";
  context.lineWidth = 3;
  context.beginPath();
  values.forEach((value, index) => {
    const x = values.length === 1 ? displayWidth / 2 : (displayWidth / (values.length - 1)) * index;
    const y = displayHeight - ((value - min) / range) * (displayHeight - 24) - 12;
    index === 0 ? context.moveTo(x, y) : context.lineTo(x, y);
  });
  context.stroke();
}

async function refreshMarket() {
  const [stocks, portfolio, events] = await Promise.all([
    api("/api/stocks"),
    api("/api/portfolio"),
    api("/api/market-events"),
  ]);
  applyMarketSnapshot({ stocks, portfolio, events });
}

document.querySelector("#order-form")
  .addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = Object.fromEntries(new FormData(event.currentTarget));
    message.className = "message";
    message.textContent = "주문을 처리하고 있습니다…";
    try {
      const result = await api("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      });
      message.className = "message success";
      message.textContent = `${result.stockCode} ${result.quantity}주 주문이 체결되었습니다.`;
      await refreshMarket();
      renderDetail();
    } catch (error) {
      message.className = "message error";
      message.textContent = error.message;
    }
  });

window.addEventListener("hashchange", renderDetail);
document.querySelector("#back-to-market").addEventListener("click", () => {
  location.hash = "";
});
window.addEventListener("resize", () => {
  if (!detailPage.hidden) renderDetail();
});

function connectRealtimeMarket() {
  const socket = new WebSocket(MARKET_SOCKET_URL);
  socket.addEventListener("open", () => {
    const status = document.querySelector("#server-status");
    status.textContent = "실시간 연결됨";
    status.classList.add("ok");
  });
  socket.addEventListener("message", (event) => {
    const data = JSON.parse(event.data);
    if (data.type === "MARKET_UPDATED") applyMarketSnapshot(data.payload);
  });
  socket.addEventListener("close", () =>
    setTimeout(connectRealtimeMarket, 2000),
  );
}

api("/api/health")
  .then(() => connectRealtimeMarket())
  .catch(
    () =>
      (document.querySelector("#server-status").textContent = "서버 연결 실패"),
  );
refreshMarket().catch((error) => {
  stockContainer.textContent = `시장 정보를 불러오지 못했습니다: ${error.message}`;
});
