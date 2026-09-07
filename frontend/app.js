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
const chartState = { points: [], width: 0, height: 0 };
let currentStocks = [];
let currentEvents = [];
let loadedHistoryCode = null;
let orderHistoryTimer = null;
let loadedPriceHistoryCode = null;

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
    history.push({ price: stock.price, time: new Date() });
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
      const stockLabel = stock ? `${stock.code} · ${stock.name}` : "시장 전체";
      return `<div class="event"><div><strong>${event.title}</strong><small>${stockLabel}</small></div></div>`;
    })
    .join("");
}

function renderDetail() {
  const code = location.hash.startsWith("#stock/")
    ? location.hash.slice(7)
    : null;
  const stock = currentStocks.find((item) => item.code === code);
  if (!stock) {
    clearOrderHistoryRefresh();
    loadedHistoryCode = null;
    loadedPriceHistoryCode = null;
    marketPage.hidden = false;
    detailPage.hidden = true;
    return;
  }
  marketPage.hidden = true;
  detailPage.hidden = false;
  message.className = "message";
  message.textContent = "";
  const up = stock.changePercent >= 0;
  document.querySelector("#detail-code").textContent =
    `${stock.code} · ${stock.genre}`;
  document.querySelector("#detail-name").textContent = stock.name;
  document.querySelector("#detail-genre").textContent =
    `거래량 ${stock.volume.toLocaleString()}`;
  document.querySelector("#detail-price").textContent = money.format(
    stock.price,
  );
  const change = document.querySelector("#detail-change");
  change.className = `detail-change ${up ? "up" : "down"}`;
  change.textContent = `${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%`;
  document.querySelector("#stock-code").value = stock.code;
  if (loadedPriceHistoryCode !== stock.code) {
    loadedPriceHistoryCode = stock.code;
    loadPriceHistory(stock.code);
  }
  if (loadedHistoryCode !== stock.code) {
    loadedHistoryCode = stock.code;
    startOrderHistoryRefresh(stock.code);
  }
  document.querySelector("#detail-events").innerHTML =
    currentEvents
      .filter((event) => event.stockCode === stock.code)
      .map(
        (event) => `<div class="event"><strong>${event.title}</strong></div>`,
      )
      .join("") || '<p class="empty-state">아직 관련 소식이 없습니다.</p>';
  drawChart(priceHistory.get(stock.code) || [stock.price]);
}

async function loadPriceHistory(stockCode) {
  try {
    const savedPoints = await api(
      `/api/stocks/${encodeURIComponent(stockCode)}/history`,
    );
    if (location.hash !== `#stock/${stockCode}`) return;
    const saved = savedPoints.map((point) => ({
      price: point.price,
      time: new Date(point.recordedAt),
    }));
    const existing = priceHistory.get(stockCode) || [];
    const lastSavedTime = saved.at(-1)?.time.getTime() || 0;
    const livePoints = existing.filter(
      (point) => point.time.getTime() > lastSavedTime,
    );
    priceHistory.set(stockCode, [...saved, ...livePoints].slice(-200));
    drawChart(priceHistory.get(stockCode));
  } catch (error) {
    loadedPriceHistoryCode = null;
  }
}

function startOrderHistoryRefresh(stockCode) {
  clearOrderHistoryRefresh();
  loadOrderHistory(stockCode);
  orderHistoryTimer = setInterval(() => {
    if (location.hash === `#stock/${stockCode}`) loadOrderHistory(stockCode);
  }, 5000);
}

function clearOrderHistoryRefresh() {
  if (orderHistoryTimer !== null) {
    clearInterval(orderHistoryTimer);
    orderHistoryTimer = null;
  }
}

async function loadOrderHistory(stockCode) {
  const historyContainer = document.querySelector("#order-history");
  historyContainer.innerHTML =
    '<p class="empty-state">거래 내역을 불러오는 중입니다.</p>';
  try {
    const orders = await api(`/api/orders/${encodeURIComponent(stockCode)}`);
    if (!location.hash.endsWith(stockCode)) return;
    historyContainer.innerHTML = orders.length
      ? orders
          .map((order) => {
            const isBuy = order.side === "BUY";
            const date = new Date(order.createdAt).toLocaleString("ko-KR");
            return `<div class="history-row"><span class="history-side ${isBuy ? "buy" : "sell"}">${isBuy ? "매수" : "매도"}</span><strong>${order.quantity.toLocaleString()}주</strong><span>${money.format(order.price)}</span><time>${date}</time></div>`;
          })
          .join("")
      : '<p class="empty-state">아직 거래 내역이 없습니다.</p>';
  } catch (error) {
    historyContainer.innerHTML = `<p class="empty-state">거래 내역을 불러오지 못했습니다.</p>`;
  }
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
  const points = values.map((point) =>
    typeof point === "number" ? { price: point, time: new Date() } : point,
  );
  const prices = points.map((point) => point.price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const range = max - min || 1;
  context.clearRect(0, 0, displayWidth, displayHeight);
  context.strokeStyle = "#e5e9ef";
  context.lineWidth = 1;
  for (let index = 1; index < 4; index += 1) {
    const y = (displayHeight / 4) * index;
    context.beginPath();
    context.moveTo(0, y);
    context.lineTo(displayWidth, y);
    context.stroke();
  }
  context.strokeStyle = prices.at(-1) >= prices[0] ? "#e04f5f" : "#2379ba";
  context.lineWidth = 3;
  context.beginPath();
  const chartPoints = points.map((point, index) => {
    const x =
      points.length === 1
        ? displayWidth / 2
        : (displayWidth / (points.length - 1)) * index;
    const y =
      displayHeight - ((point.price - min) / range) * (displayHeight - 24) - 12;
    index === 0 ? context.moveTo(x, y) : context.lineTo(x, y);
    return { ...point, x, y };
  });
  context.stroke();
  chartState.points = chartPoints;
  chartState.width = displayWidth;
  chartState.height = displayHeight;
}

document
  .querySelector("#price-chart")
  .addEventListener("mousemove", (event) => {
    if (!chartState.points.length) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - bounds.left;
    const point = chartState.points.reduce((closest, candidate) =>
      Math.abs(candidate.x - x) < Math.abs(closest.x - x) ? candidate : closest,
    );
    const tooltip = document.querySelector("#chart-tooltip");
    tooltip.hidden = false;
    tooltip.textContent = `${point.time.toLocaleTimeString("ko-KR")} · ${money.format(point.price)}`;
    tooltip.style.left = `${Math.min(Math.max(point.x, 70), chartState.width - 70)}px`;
    tooltip.style.top = `${Math.max(point.y - 48, 4)}px`;
  });

document.querySelector("#price-chart").addEventListener("mouseleave", () => {
  document.querySelector("#chart-tooltip").hidden = true;
});

async function refreshMarket() {
  const [stocks, portfolio, events] = await Promise.all([
    api("/api/stocks"),
    api("/api/portfolio"),
    api("/api/market-events"),
  ]);
  applyMarketSnapshot({ stocks, portfolio, events });
}

document
  .querySelector("#order-form")
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
      loadOrderHistory(data.stockCode);
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
