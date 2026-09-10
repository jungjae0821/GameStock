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
const orderBookCache = new Map();
let currentStocks = [];
let currentEvents = [];
let currentPortfolio = null;
let tradeRefreshTimer = null;
let loadedPriceHistoryCode = null;
let loadedNewsCode = null;
let loadedOrderBookCode = null;
let loadedTradeCode = null;
let currentUser = null;
let currentProfile = null;
let currentOpenOrders = [];
let firebaseAuth = null;

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  }[character]));
}

async function api(path, options) {
  const token = firebaseAuth?.currentUser ? await firebaseAuth.currentUser.getIdToken() : null;
  const headers = { ...(options?.headers || {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });
  const payload = response.status === 204 ? null : await response.json();
  if (!response.ok)
    throw new Error(payload.message || "요청 처리 중 오류가 발생했습니다.");
  return payload;
}

function applyMarketSnapshot(snapshot, useSnapshotPortfolio = false) {
  currentStocks = snapshot.stocks;
  currentEvents = snapshot.events;
  snapshot.stocks.forEach((stock) => {
    const history = priceHistory.get(stock.code) || [];
    history.push({ price: stock.price, time: new Date() });
    priceHistory.set(stock.code, history.slice(-40));
  });
  renderStocks(snapshot.stocks);
  if (useSnapshotPortfolio && currentUser && snapshot.portfolio) renderPortfolio(snapshot.portfolio);
  if (!currentUser) renderPortfolio(null);
  renderEvents(snapshot.events);
  renderDetail();
}

function renderStocks(stocks) {
  stockContainer.innerHTML = stocks
    .map((stock) => {
      const up = stock.changePercent >= 0;
      return `<a class="stock-card" href="#stock/${stock.code}" aria-label="${stock.name} 상세 보기"><div class="stock-card-heading"><span class="code">${stock.code} · ${stock.genre}</span>${sparklineSvg(stock.code, up)}</div><h3>${stock.name}</h3><div class="price">${money.format(stock.price)}</div><span class="change ${up ? "up" : "down"}">${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%</span><span class="meta"> · 거래량 ${stock.volume.toLocaleString()}</span><span class="card-link">상세 보기 →</span></a>`;
    })
    .join("");
}

function sparklineSvg(stockCode, up) {
  const points = (priceHistory.get(stockCode) || []).map((point) => point.price);
  if (!points.length) points.push(currentStocks.find((stock) => stock.code === stockCode)?.price || 0);
  const min = Math.min(...points), max = Math.max(...points), range = max - min || 1;
  const coordinates = points.map((price, index) => {
    const x = points.length === 1 ? 2 : (index / (points.length - 1)) * 76 + 2;
    const y = 28 - ((price - min) / range) * 24;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return `<svg class="sparkline ${up ? "up" : "down"}" viewBox="0 0 80 32" role="img" aria-label="최근 가격 흐름"><polyline points="${coordinates}" /></svg>`;
}

function renderPortfolio(portfolio) {
  currentPortfolio = portfolio;
  if (!portfolio) {
    document.querySelector("#cash").textContent = "-";
    document.querySelector("#asset-value").textContent = "-";
    document.querySelector("#total-asset").textContent = "-";
    return;
  }
  document.querySelector("#cash").textContent = money.format(portfolio.cash);
  document.querySelector("#asset-value").textContent = money.format(
    portfolio.assetValue,
  );
  document.querySelector("#total-asset").textContent = money.format(
    portfolio.totalAsset,
  );
}

async function refreshPortfolio() {
  if (!currentUser) { renderPortfolio(null); return; }
  const portfolio = await api("/api/portfolio");
  renderPortfolio(portfolio);
  // 실시간 가격 갱신으로 보유 종목만 다시 그릴 때 입력 중인 닉네임/사진을 덮어쓰지 않는다.
  if (currentProfile) renderProfile(currentProfile, portfolio, { preserveForm: true });
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
  if (location.hash === "#profile" && currentUser) {
    clearTradeRefresh();
    marketPage.hidden = true;
    detailPage.hidden = true;
    profilePage.hidden = false;
    return;
  }
  profilePage.hidden = true;
  const code = location.hash.startsWith("#stock/")
    ? location.hash.slice(7)
    : null;
  const stock = currentStocks.find((item) => item.code === code);
  if (!stock) {
    clearTradeRefresh();
    loadedPriceHistoryCode = null;
    loadedNewsCode = null;
    loadedTradeCode = null;
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
  const marketPrice = document.querySelector("#market-price");
  if (marketPrice) marketPrice.value = money.format(stock.price);
  updateMarketPriceEstimate(stock.code);
  const change = document.querySelector("#detail-change");
  change.className = `detail-change ${up ? "up" : "down"}`;
  change.textContent = `${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%`;
  document.querySelector("#stock-code").value = stock.code;
  if (loadedPriceHistoryCode !== stock.code) {
    loadedPriceHistoryCode = stock.code;
    loadPriceHistory(stock.code);
  }
  if (loadedTradeCode !== stock.code) {
    loadedTradeCode = stock.code;
    startTradeRefresh(stock.code);
  }
  if (loadedNewsCode !== stock.code) {
    loadedNewsCode = stock.code;
    loadStockNews(stock.code);
  }
  if (loadedOrderBookCode !== stock.code) { loadedOrderBookCode = stock.code; loadOrderBook(stock.code); }
  drawChart(priceHistory.get(stock.code) || [stock.price]);
}

async function loadOrderBook(stockCode) {
  const container = document.querySelector("#order-book");
  try {
    const book = await api(`/api/orderbook/${encodeURIComponent(stockCode)}`);
    if (location.hash !== `#stock/${stockCode}`) return;
    orderBookCache.set(stockCode, book);
    updateMarketPriceEstimate(stockCode, book);
    const bids = book.bids || [], asks = book.asks || [];
    const maxQuantity = Math.max(1, ...bids.map(level => level.quantity), ...asks.map(level => level.quantity));
    const levelMarkup = (level, side) => `<div class="depth-level ${side}"><span class="depth-price">${level.price.toLocaleString()}원</span><span class="depth-bar"><i style="width:${Math.max(5, level.quantity / maxQuantity * 100)}%"></i></span><strong>${level.quantity.toLocaleString()}주</strong><small>${level.orderCount}건</small></div>`;
    container.innerHTML = bids.length || asks.length ? `<div class="depth-legend"><span class="book-buy">매수 잔량</span><span class="book-sell">매도 잔량</span></div><div class="depth-chart"><div class="depth-column asks">${asks.map(level => levelMarkup(level, "sell")).join("") || '<p class="empty-state">매도 대기 없음</p>'}</div><div class="depth-column bids">${bids.map(level => levelMarkup(level, "buy")).join("") || '<p class="empty-state">매수 대기 없음</p>'}</div></div>` : '<p class="empty-state">대기 중인 지정가 주문이 없습니다.</p>';
  } catch { container.innerHTML = '<p class="empty-state">호가를 불러오지 못했습니다.</p>'; }
}

function updateMarketPriceEstimate(stockCode = loadedOrderBookCode, book = orderBookCache.get(stockCode)) {
  const input = document.querySelector("#market-price");
  if (!input || !stockCode) return;
  const stock = currentStocks.find((item) => item.code === stockCode);
  if (!stock) return;
  const side = document.querySelector('input[name="side"]:checked')?.value || "BUY";
  const quantity = Math.max(1, Number(document.querySelector('#order-form [name="quantity"]')?.value || 1));
  const levels = side === "BUY" ? (book?.asks || []) : (book?.bids || []);
  let remaining = quantity;
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
  const estimated = filled > 0 ? Math.round(total / filled) : stock.price;
  input.value = money.format(estimated);
  const help = document.querySelector("#market-price-help");
  if (help) help.textContent = remaining > 0
    ? `현재 호가 ${filled.toLocaleString()}주 기준 · 잔량은 체결되지 않을 수 있습니다.`
    : "현재 호가를 기준으로 계산한 평균 예상가입니다.";
}

async function loadPublicTrades(stockCode) {
  const container = document.querySelector("#public-trades");
  container.innerHTML = '<p class="empty-state">전체 체결 내역을 불러오는 중입니다.</p>';
  try {
    const trades = await api(`/api/stocks/${encodeURIComponent(stockCode)}/trades`);
    if (location.hash !== `#stock/${stockCode}`) return;
    container.innerHTML = trades.length ? trades.map((trade) => {
      const isBuy = trade.side === "BUY";
      const date = new Date(trade.createdAt).toLocaleString("ko-KR");
      return `<div class="history-row"><span class="history-side ${isBuy ? "buy" : "sell"}">${isBuy ? "매수" : "매도"}</span><strong>${trade.quantity.toLocaleString()}주</strong><span>${money.format(trade.price)}</span><time>${date}</time></div>`;
    }).join("") : '<p class="empty-state">아직 체결된 거래가 없습니다.</p>';
  } catch { loadedTradeCode = null; container.innerHTML = '<p class="empty-state">전체 체결 내역을 불러오지 못했습니다.</p>'; }
}

async function loadStockNews(stockCode) {
  const newsContainer = document.querySelector("#detail-events");
  newsContainer.innerHTML =
    '<p class="empty-state">관련 소식을 불러오는 중입니다.</p>';
  try {
    const news = await api(`/api/stocks/${encodeURIComponent(stockCode)}/news`);
    if (location.hash !== `#stock/${stockCode}`) return;
    newsContainer.innerHTML =
      news
        .map(
          (event) => `<div class="event"><strong>${event.title}</strong></div>`,
        )
        .join("") || '<p class="empty-state">아직 관련 소식이 없습니다.</p>';
  } catch (error) {
    loadedNewsCode = null;
    newsContainer.innerHTML =
      '<p class="empty-state">관련 소식을 불러오지 못했습니다.</p>';
  }
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

function startTradeRefresh(stockCode) {
  clearTradeRefresh();
  loadPublicTrades(stockCode);
  tradeRefreshTimer = setInterval(() => {
    if (location.hash === `#stock/${stockCode}`) {
      loadPublicTrades(stockCode);
      loadOrderBook(stockCode);
    }
  }, 10000);
}

function clearTradeRefresh() {
  if (tradeRefreshTimer !== null) {
    clearInterval(tradeRefreshTimer);
    tradeRefreshTimer = null;
  }
}

async function loadOrderHistory(stockCode) {
  const historyContainer = document.querySelector("#order-history");
  historyContainer.innerHTML =
    '<p class="empty-state">거래 내역을 불러오는 중입니다.</p>';
  if (!currentUser) { historyContainer.innerHTML = '<p class="empty-state">로그인 후 내 거래 내역을 확인할 수 있습니다.</p>'; return; }
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
  const [stocks, events] = await Promise.all([
    api("/api/stocks"),
    api("/api/market-events"),
  ]);
  const portfolio = currentUser ? await api("/api/portfolio") : null;
  applyMarketSnapshot({ stocks, portfolio, events }, true);
}

document
  .querySelector("#order-form")
  .addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!currentUser) { openLogin(); return; }
    const data = Object.fromEntries(new FormData(event.currentTarget));
    if (data.orderType === "MARKET") delete data.price;
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
    } catch (error) {
      message.className = "message error";
      message.textContent = error.message;
    }
  });

window.addEventListener("hashchange", renderDetail);
function syncOrderPriceFields(orderType = document.querySelector("#order-type").value) {
  document.querySelector("#market-price-field").hidden = orderType !== "MARKET";
  document.querySelector("#limit-price-field").hidden = orderType !== "LIMIT";
}
document.querySelector("#order-type").addEventListener("change", (event) => syncOrderPriceFields(event.target.value));
syncOrderPriceFields();
document.querySelectorAll('input[name="side"]').forEach((input) => input.addEventListener("change", () => updateMarketPriceEstimate()));
document.querySelector('#order-form [name="quantity"]').addEventListener("input", () => updateMarketPriceEstimate());
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
    if (data.type === "MARKET_UPDATED") {
      applyMarketSnapshot(data.payload);
      if (currentUser) refreshPortfolio().catch(() => {});
    }
  });
  socket.addEventListener("close", () =>
    setTimeout(connectRealtimeMarket, 2000),
  );
}

const loginModal = document.querySelector("#login-modal");
const loginMessage = document.querySelector("#login-message");
const profilePage = document.querySelector("#profile-page");
const profileForm = document.querySelector("#profile-form");
const profileMessage = document.querySelector("#profile-message");
const profileClose = document.querySelector("#close-profile");
const rankingModal = document.querySelector("#ranking-modal");
const menuButton = document.querySelector("#menu-button");
const menuPanel = document.querySelector("#menu-panel");
let profileRequired = false;

function openLogin() { loginModal.hidden = false; }
function closeLogin() { loginModal.hidden = true; loginMessage.textContent = ""; }
function closeMenu() { menuPanel.hidden = true; menuButton.setAttribute("aria-expanded", "false"); }
function updateLoginButton() {
  const button = document.querySelector("#login-button");
  button.hidden = Boolean(currentUser);
  button.textContent = "Google로 로그인";
  button.classList.toggle("logged-in", Boolean(currentUser));
  menuButton.hidden = !currentUser;
  menuButton.textContent = currentUser ? `${currentUser.nickname || "내 계정"} ▾` : "☰ 메뉴";
}

function avatarMarkup(name, className) {
  const initial = escapeHtml((name || "G").trim().charAt(0).toUpperCase() || "G");
  return `<div class="${className}">${initial}</div>`;
}

function renderProfile(profile, portfolio = null, { preserveForm = false, orders = currentOpenOrders } = {}) {
  currentProfile = profile;
  currentOpenOrders = orders || [];
  if (!preserveForm) document.querySelector("#profile-nickname").value = profile.nickname || "";
  document.querySelector("#profile-email").textContent = profile.email || "Google 계정";
  const avatar = document.querySelector("#profile-avatar");
  if (avatar) {
    avatar.className = "profile-avatar";
    avatar.textContent = (profile.nickname || "G").trim().charAt(0).toUpperCase() || "G";
  }
  const positions = portfolio?.positions || [];
  document.querySelector("#profile-positions").innerHTML = positions.length ? positions.map((position) => {
    const profitClass = position.profitLoss >= 0 ? "profit-up" : "profit-down";
    const sign = position.profitLoss >= 0 ? "+" : "";
    return `<div class="holding-row"><div><strong>${escapeHtml(position.stockCode)}</strong><div class="holding-meta">${position.quantity.toLocaleString()}주 · 평균 ${money.format(position.averagePrice)} · 평가 ${money.format(position.marketValue)}</div></div><span class="${profitClass}">${sign}${money.format(position.profitLoss)}<br /><small>${sign}${Number(position.profitLossPercent || 0).toFixed(2)}%</small></span></div>`;
  }).join("") : '<p class="empty-state">보유 중인 종목이 없습니다.</p>';
  const ordersContainer = document.querySelector("#profile-orders");
  ordersContainer.innerHTML = currentOpenOrders.length ? currentOpenOrders.map((order) => {
    const isBuy = order.side === "BUY";
    const sideLabel = isBuy ? "매수" : "매도";
    const sideClass = isBuy ? "buy" : "sell";
    const expiry = order.expiresAt ? new Date(order.expiresAt).toLocaleString("ko-KR") : "-";
    const reservation = isBuy ? `예약금 ${money.format(order.reservedCash)}` : `예약수량 ${Number(order.reservedQuantity || order.remainingQuantity).toLocaleString()}주`;
    return `<div class="open-order-row"><div><strong class="order-side ${sideClass}">${sideLabel} · ${escapeHtml(order.stockCode)}</strong><div class="holding-meta">${order.remainingQuantity.toLocaleString()}주 · 지정가 ${money.format(order.price)} · ${reservation}</div><small class="holding-meta">만료 예정 ${expiry}</small></div><button type="button" class="cancel-order" data-cancel-order="${order.id}">주문 취소</button></div>`;
  }).join("") : '<p class="empty-state">미체결 주문이 없습니다.</p>';
}

async function openProfile(required = false) {
  profileRequired = required;
  closeMenu();
  if (location.hash !== "#profile") location.hash = "#profile";
  else renderDetail();
  profilePage.hidden = false;
  profileClose.hidden = required;
  document.querySelector("#profile-title").textContent = required ? "닉네임 설정" : "마이페이지";
  document.querySelector("#profile-help").textContent = required ? "첫 로그인 기념으로 닉네임을 정해 주세요." : "프로필과 보유 자산을 관리할 수 있습니다.";
  profileMessage.textContent = "불러오는 중입니다…";
  try {
    const [profile, portfolio, orders] = await Promise.all([api("/api/profile"), api("/api/portfolio"), api("/api/orders")]);
    renderProfile(profile, portfolio, { orders });
    profileMessage.textContent = "";
  } catch (error) { profileMessage.className = "message error"; profileMessage.textContent = error.message; }
}

function closeProfile() {
  if (profileRequired) return;
  profileMessage.className = "message";
  profileMessage.textContent = "";
  if (location.hash === "#profile") location.hash = "";
  else profilePage.hidden = true;
}

function closeRanking() { rankingModal.hidden = true; }

async function openRanking() {
  closeMenu();
  rankingModal.hidden = false;
  const container = document.querySelector("#ranking-list");
  container.innerHTML = '<p class="empty-state">랭킹을 불러오는 중입니다.</p>';
  try {
    const ranking = await api("/api/ranking");
    container.innerHTML = ranking.length ? ranking.map((entry) => { const change = Number(entry.changePercent || 0); const sign = change >= 0 ? "+" : ""; const changeClass = change >= 0 ? "profit-up" : "profit-down"; return `<div class="ranking-row"><span class="ranking-rank">${entry.rank}</span><div class="ranking-user">${avatarMarkup(entry.nickname, "ranking-avatar")}<strong>${escapeHtml(entry.nickname)}</strong></div><span class="ranking-change ${changeClass}">${sign}${change.toFixed(2)}%</span><span class="ranking-asset">${money.format(entry.totalAsset)}</span></div>`; }).join("") : '<p class="empty-state">아직 랭킹에 참여한 사용자가 없습니다.</p>';
  } catch (error) { container.innerHTML = `<p class="empty-state">랭킹을 불러오지 못했습니다: ${escapeHtml(error.message)}</p>`; }
}

async function handleAuthenticatedUser(user) {
  currentUser = user;
  updateLoginButton();
  await refreshMarket();
  if (location.hash === "#profile") await openProfile(Boolean(user.requiresNickname));
  else if (user.requiresNickname) await openProfile(true);
  if (user.attendanceReward) alert(`${user.attendanceStreak}일차 출석 보상 ${money.format(user.attendanceReward)}을 받았습니다.`);
}
async function signInWithGoogle() {
  if (!window.GAMESTOCK_FIREBASE_CONFIG) {
    loginMessage.className = "message error";
    loginMessage.textContent = "Firebase 설정이 아직 완료되지 않았습니다. firebase-config.js를 설정하세요.";
    return;
  }
  try {
    loginMessage.textContent = "Google 로그인 창을 여는 중입니다…";
    await firebaseAuth.signInWithPopup(new firebase.auth.GoogleAuthProvider());
    closeLogin();
  } catch (error) { loginMessage.className = "message error"; loginMessage.textContent = error.message; }
}
document.querySelector("#login-button").addEventListener("click", openLogin);
document.querySelector("#google-login").addEventListener("click", signInWithGoogle);
document.querySelector("#close-login").addEventListener("click", closeLogin);
menuButton.addEventListener("click", () => { const expanded = menuButton.getAttribute("aria-expanded") === "true"; menuButton.setAttribute("aria-expanded", String(!expanded)); menuPanel.hidden = expanded; });
document.querySelector("#profile-button").addEventListener("click", () => openProfile(false));
document.querySelector("#ranking-button").addEventListener("click", openRanking);
document.querySelector("#logout-button").addEventListener("click", async () => { closeMenu(); await firebaseAuth.signOut(); currentUser = null; currentProfile = null; currentOpenOrders = []; profileRequired = false; if (location.hash === "#profile") location.hash = ""; updateLoginButton(); renderPortfolio(null); await refreshMarket(); });
profileClose.addEventListener("click", closeProfile);
document.querySelector("#close-ranking").addEventListener("click", closeRanking);
document.querySelector("#profile-orders").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-cancel-order]");
  if (!button) return;
  button.disabled = true;
  profileMessage.className = "message";
  profileMessage.textContent = "주문을 취소하는 중입니다…";
  try {
    await api(`/api/orders/${encodeURIComponent(button.dataset.cancelOrder)}`, { method: "DELETE" });
    await openProfile(false);
  } catch (error) {
    button.disabled = false;
    profileMessage.className = "message error";
    profileMessage.textContent = error.message;
  }
});
profileForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  profileMessage.className = "message";
  profileMessage.textContent = "저장 중입니다…";
  try {
    const data = {
      nickname: document.querySelector("#profile-nickname").value.trim(),
    };
    profileMessage.textContent = "프로필 정보 저장 중…";
    const profile = await api("/api/profile", { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
    currentProfile = profile;
    currentUser = { ...currentUser, nickname: profile.nickname, requiresNickname: false };
    profileRequired = false;
    updateLoginButton();
    // 프로필 저장 완료를 자산 조회에 종속시키지 않고, 현재 화면의 자산을 우선 표시한다.
    renderProfile(profile, currentPortfolio);
    profileMessage.className = "message success";
    profileMessage.textContent = "프로필이 저장되었습니다.";
    setTimeout(() => { profileMessage.textContent = ""; if (location.hash === "#profile") location.hash = ""; }, 500);
    refreshPortfolio().catch(() => {});
  } catch (error) { profileMessage.className = "message error"; profileMessage.textContent = error.message; }
});
if (window.GAMESTOCK_FIREBASE_CONFIG && window.firebase) {
  firebase.initializeApp(window.GAMESTOCK_FIREBASE_CONFIG);
  firebaseAuth = firebase.auth();
  firebaseAuth.onAuthStateChanged(async (firebaseUser) => {
    if (!firebaseUser) return;
    try {
      const response = await fetch(`${API_BASE_URL}/api/auth/google`, { method: "POST", headers: { Authorization: `Bearer ${await firebaseUser.getIdToken()}` } });
      if (!response.ok) throw new Error();
      await handleAuthenticatedUser(await response.json());
    } catch (error) { currentUser = null; updateLoginButton(); document.querySelector("#server-status").textContent = error.message || "로그인 처리 실패"; }
  });
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
