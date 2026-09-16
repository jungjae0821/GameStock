const LANGUAGE_STORAGE_KEY = "gamestock-language";
const THEME_STORAGE_KEY = "gamestock-theme";
const SUPPORTED_LANGUAGES = ["ko", "ja", "en"];
const LOCALES = { ko: "ko-KR", ja: "ja-JP", en: "en-US" };
const TRANSLATIONS = {
  ja: {
    "서브컬처 게임 모의주식": "サブカルゲーム模擬株式",
    "서버 확인 중": "サーバー確認中",
    "메뉴": "メニュー", "Google로 로그인": "Googleでログイン", "마이페이지": "マイページ",
    "랭킹": "ランキング", "로그아웃": "ログアウト", "언어": "言語", "다크 모드": "ダークモード", "라이트 모드": "ライトモード",
    "당신의 게임에\n지금바로 투자하세요": "あなたのゲームに\n今すぐ投資しよう",
    "당신도 여기서만큼은 부자가 될 수 있습니다!": "ここならあなたも資産家になれます！",
    "보유 현금": "保有現金", "주식 평가액": "株式評価額", "총 자산": "総資産", "상장 종목": "上場銘柄",
    "무슨 일이 일어나고 있나요?": "今、何が起きている？", "시장으로 돌아가기": "市場へ戻る",
    "가격 흐름": "価格推移", "실시간": "リアルタイム", "5분": "5分", "10분": "10分", "30분": "30分", "1시간": "1時間", "6시간": "6時間", "12시간": "12時間", "24시간": "24時間", "일주일": "1週間", "가격 결정 근거": "価格決定の根拠", "최근 24시간": "直近24時間",
    "뉴스와 거래 흐름을 계산하는 중입니다.": "ニュースと取引の流れを分析中です。", "가격 결정 근거를 불러오는 중입니다.": "価格決定の根拠を読み込み中です。",
    "이 종목 거래": "この銘柄を取引", "매수": "買い", "매도": "売り", "수량": "数量", "주문 유형": "注文種別",
    "시장가 (즉시 체결)": "成行（即時約定）", "지정가 (호가 대기)": "指値（板で待機）", "시장가 예상 체결금액": "成行の予想約定金額",
    "현재 호가와 수량을 기준으로 계산합니다.": "現在の板と数量を基準に計算します。", "희망 가격": "希望価格", "주문 체결하기": "注文する",
    "일별 시세": "日別相場", "체결 기준 시가·종가·거래량입니다. (고가·저가는 집계하지 않습니다.)": "約定基準の始値・終値・出来高です。（高値・安値は集計しません）",
    "호가창": "板情報", "전체 체결 내역": "全約定履歴", "다른 투자자의 체결도 익명으로 표시됩니다.": "他の投資家の約定も匿名で表示されます。", "관련 소식": "関連ニュース",
    "거래하려면 로그인하세요": "取引するにはログインしてください", "Google로 계속하기": "Googleで続ける",
    "프로필 정보를 입력해 주세요.": "プロフィール情報を入力してください。", "닉네임": "ニックネーム", "닉네임 저장": "ニックネームを保存",
    "보유 종목": "保有銘柄", "미체결 주문": "未約定注文", "체결 완료": "約定完了", "체결 즉시 현금과 주식에 반영됩니다.": "約定後すぐに現金と株式へ反映されます。",
    "인생 리셋": "人生リセット", "인생 리셋 사용": "人生リセットを使う", "투자 랭킹": "投資ランキング",
    "기본 지급금 100만원 대비 총자산 등락률을 함께 보여줍니다.": "初期資金100万ウォンに対する総資産の騰落率も表示します。",
    "원문 기사 열기 ↗": "元記事を開く ↗", "닫기": "閉じる", "시장 전체": "市場全体", "뉴스 상세": "ニュース詳細",
    "아직 수집된 뉴스가 없습니다.": "収集されたニュースはまだありません。", "상세 보기": "詳細を見る", "가격 변동 이유": "価格変動の理由",
    "거래량": "出来高", "실시간 연결됨": "リアルタイム接続済み", "서버 연결 실패": "サーバー接続失敗",
    "한강 수온(선유) 조회 불가": "漢江水温（仙遊）取得不可", "내 계정": "マイアカウント",
    "당신의 게임에 지금바로 투자하세요": "あなたのゲームに今すぐ投資しよう",
    "주": "株", "건": "件", "기준": "基準", "미체결 가능": "未約定の可能性", "현재 호가": "現在の板",
    "매수 잔량": "買い残量", "매도 잔량": "売り残量", "매도 대기 없음": "売り注文なし", "매수 대기 없음": "買い注文なし",
    "대기 중인 지정가 주문이 없습니다.": "待機中の指値注文はありません。", "호가를 불러오지 못했습니다.": "板情報を読み込めませんでした。",
    "전체 체결 내역을 불러오는 중입니다.": "全約定履歴を読み込み中です。", "아직 체결된 거래가 없습니다.": "約定した取引はまだありません。",
    "전체 체결 내역을 불러오지 못했습니다.": "全約定履歴を読み込めませんでした。", "수수료 포함 예상 출금액": "手数料込み予想支払額",
    "수수료 차감 예상 입금액": "手数料差引後の予想受取額", "반대 호가가 없어 현재가 기준": "反対注文がないため現在値基準",
    "최근 거래 흐름을 따라 가격이 상승하고 있습니다.": "最近の取引フローに沿って価格が上昇しています。",
    "최근 거래 흐름을 따라 가격이 하락하고 있습니다.": "最近の取引フローに沿って価格が下落しています。", "최근 가격은 보합 상태입니다.": "最近の価格は横ばいです。",
    "제공된 뉴스 요약이 없습니다.": "ニュース要約はありません。", "상승 방향": "上昇方向", "하락 방향": "下落方向", "혼합·보합": "混合・横ばい",
    "매수 우세": "買い優勢", "매도 우세": "売り優勢", "순": "純", "매수·매도 균형": "売買均衡", "최근 체결 없음": "直近の約定なし",
    "최근 뉴스 없음": "直近のニュースなし", "날짜 미상": "日時不明", "게시 시각 미상": "掲載時刻不明", "요청 처리 중 오류가 발생했습니다.": "処理中にエラーが発生しました。"
    ,"한강 수온(선유) 조회 중…": "漢江水温（仙遊）取得中…", "호가를 불러오는 중입니다.": "板情報を読み込み中です。",
    "투자에 대한 모든 책임은 투자자에게 있으며 거래에 사용되는 화폐는 실제 돈이 아닙니다.": "投資判断の責任は利用者にあり、取引に使う通貨は実際のお金ではありません。",
    "Google 계정으로 로그인하면 나만의 모의 투자 자산과 출석 보상을 받을 수 있습니다.": "Googleでログインすると、自分の模擬投資資産とログイン報酬を利用できます。",
    "미체결 주문을 불러오는 중입니다.": "未約定注文を読み込み中です。", "체결 내역을 불러오는 중입니다.": "約定履歴を読み込み中です。", "랭킹을 불러오는 중입니다.": "ランキングを読み込み中です。",
    "모든 보유 자산·미체결 주문·출석 기록을 초기화하고 처음부터 다시 시작합니다. Google 계정당 한 번만 사용할 수 있습니다.": "保有資産・未約定注文・ログイン記録を初期化して最初からやり直します。Googleアカウントごとに1回だけ利用できます。",
    "내 자산 요약": "資産サマリー", "화면 설정": "表示設定", "언어 설정": "言語設定", "뉴스 닫기": "ニュースを閉じる", "종목 가격 등락 그래프": "銘柄価格チャート",
    "당신의 게임에": "あなたのゲームに", "지금바로 투자하세요": "今すぐ投資しよう", "한강 수온(선유)": "漢江水温（仙遊）",
    "실제 게임 뉴스와 이용자 거래가 만나 오늘의 가격을 만듭니다.": "ゲームニュースと投資家の取引が今日の価格をつくります。", "뉴스와 체결 흐름을 한눈에": "ニュースと約定の流れをひと目で", "시세는 10초마다 새로고침됩니다": "相場は10秒ごとに更新されます", "실시간 시세": "リアルタイム相場", "10초마다 갱신": "10秒ごとに更新", "인기": "人気", "급상승": "急上昇", "급하락": "急落", "관심 종목": "注目銘柄", "관심": "お気に入り", "최신순": "新着順", "영향도순": "影響度順", "호가": "板", "체결": "約定", "미체결": "未約定", "투자자 한마디": "投資家のコメント", "서버 저장": "サーバー保存", "종목별 의견": "銘柄ごとの意見", "등록": "投稿", "종목 정보": "銘柄情報", "닫기": "閉じる", "가격 알림": "価格アラート", "관심 등록": "お気に入り登録", "관심 등록됨": "お気に入り登録済み", "+ 태그": "+ タグ", "뉴스와 거래 흐름을 계산하는 중입니다.": "ニュースと取引の流れを計算中です。", "건 언급": "件の言及", "새로운 소식이": "新しいニュースが", "가격을 움직여요": "価格を動かします", "종목별 관련 뉴스 자동 분석": "銘柄ごとの関連ニュースを自動分析", "부담 없이": "気軽に", "투자 감각 익히기": "投資感覚を身につける", "실제 돈이 아닌 모의 거래": "実際のお金を使わない模擬取引", "종목 둘러보기": "銘柄を探す"
  },
  en: {
    "서브컬처 게임 모의주식": "Subculture game stock simulator",
    "서버 확인 중": "Checking server", "메뉴": "Menu", "Google로 로그인": "Sign in with Google", "마이페이지": "My page",
    "랭킹": "Ranking", "로그아웃": "Sign out", "언어": "Language", "다크 모드": "Dark mode", "라이트 모드": "Light mode",
    "당신의 게임에\n지금바로 투자하세요": "Invest in your game\nright now",
    "당신도 여기서만큼은 부자가 될 수 있습니다!": "Here, anyone can build a virtual fortune!",
    "보유 현금": "Cash", "주식 평가액": "Stock value", "총 자산": "Total assets", "상장 종목": "Listed stocks",
    "무슨 일이 일어나고 있나요?": "What's happening now?", "시장으로 돌아가기": "Back to market",
    "가격 흐름": "Price history", "실시간": "Live", "5분": "5 min", "10분": "10 min", "30분": "30 min", "1시간": "1 hour", "6시간": "6 hours", "12시간": "12 hours", "24시간": "24 hours", "일주일": "1 week", "가격 결정 근거": "Price drivers", "최근 24시간": "Last 24 hours",
    "뉴스와 거래 흐름을 계산하는 중입니다.": "Analyzing news and trading flow.", "가격 결정 근거를 불러오는 중입니다.": "Loading price drivers.",
    "이 종목 거래": "Trade this stock", "매수": "Buy", "매도": "Sell", "수량": "Quantity", "주문 유형": "Order type",
    "시장가 (즉시 체결)": "Market (immediate)", "지정가 (호가 대기)": "Limit (place on book)", "시장가 예상 체결금액": "Estimated market fill",
    "현재 호가와 수량을 기준으로 계산합니다.": "Calculated from the current order book and quantity.", "희망 가격": "Limit price", "주문 체결하기": "Place order",
    "일별 시세": "Daily prices", "체결 기준 시가·종가·거래량입니다. (고가·저가는 집계하지 않습니다.)": "Open, close and volume are based on executions. High and low are not aggregated.",
    "호가창": "Order book", "전체 체결 내역": "All executions", "다른 투자자의 체결도 익명으로 표시됩니다.": "Other investors' executions are shown anonymously.", "관련 소식": "Related news",
    "거래하려면 로그인하세요": "Sign in to trade", "Google로 계속하기": "Continue with Google",
    "프로필 정보를 입력해 주세요.": "Enter your profile information.", "닉네임": "Nickname", "닉네임 저장": "Save nickname",
    "보유 종목": "Holdings", "미체결 주문": "Open orders", "체결 완료": "Executed", "체결 즉시 현금과 주식에 반영됩니다.": "Cash and shares update immediately after execution.",
    "인생 리셋": "Account reset", "인생 리셋 사용": "Use account reset", "투자 랭킹": "Investment ranking",
    "기본 지급금 100만원 대비 총자산 등락률을 함께 보여줍니다.": "Shows total asset performance against the initial KRW 1,000,000.",
    "원문 기사 열기 ↗": "Open source article ↗", "닫기": "Close", "시장 전체": "Whole market", "뉴스 상세": "News details",
    "아직 수집된 뉴스가 없습니다.": "No news has been collected yet.", "상세 보기": "View details", "가격 변동 이유": "Why the price moved",
    "거래량": "Volume", "실시간 연결됨": "Live", "서버 연결 실패": "Server connection failed",
    "한강 수온(선유) 조회 불가": "Han River temperature (Seonyu) unavailable", "내 계정": "My account",
    "당신의 게임에 지금바로 투자하세요": "Invest in your game right now",
    "주": " shares", "건": " items", "기준": "basis", "미체결 가능": "may remain unfilled", "현재 호가": "Current book",
    "매수 잔량": "Buy depth", "매도 잔량": "Sell depth", "매도 대기 없음": "No sell orders", "매수 대기 없음": "No buy orders",
    "대기 중인 지정가 주문이 없습니다.": "There are no pending limit orders.", "호가를 불러오지 못했습니다.": "Could not load the order book.",
    "전체 체결 내역을 불러오는 중입니다.": "Loading all executions.", "아직 체결된 거래가 없습니다.": "There are no executions yet.",
    "전체 체결 내역을 불러오지 못했습니다.": "Could not load executions.", "수수료 포함 예상 출금액": "Estimated debit including fee",
    "수수료 차감 예상 입금액": "Estimated credit after fee", "반대 호가가 없어 현재가 기준": "Using current price because the opposite book is empty",
    "최근 거래 흐름을 따라 가격이 상승하고 있습니다.": "The price is rising with the recent trading flow.",
    "최근 거래 흐름을 따라 가격이 하락하고 있습니다.": "The price is falling with the recent trading flow.", "최근 가격은 보합 상태입니다.": "The recent price is flat.",
    "제공된 뉴스 요약이 없습니다.": "No news summary is available.", "상승 방향": "Upward", "하락 방향": "Downward", "혼합·보합": "Mixed or flat",
    "매수 우세": "Buy-dominant", "매도 우세": "Sell-dominant", "순": "net", "매수·매도 균형": "Balanced flow", "최근 체결 없음": "No recent execution",
    "최근 뉴스 없음": "No recent news", "날짜 미상": "Unknown date", "게시 시각 미상": "Unknown publish time", "요청 처리 중 오류가 발생했습니다.": "An error occurred while processing the request."
    ,"한강 수온(선유) 조회 중…": "Loading Han River temperature (Seonyu)…", "호가를 불러오는 중입니다.": "Loading the order book.",
    "투자에 대한 모든 책임은 투자자에게 있으며 거래에 사용되는 화폐는 실제 돈이 아닙니다.": "Users are responsible for their investment decisions, and the currency used here is not real money.",
    "Google 계정으로 로그인하면 나만의 모의 투자 자산과 출석 보상을 받을 수 있습니다.": "Sign in with Google to access your simulated portfolio and attendance rewards.",
    "미체결 주문을 불러오는 중입니다.": "Loading open orders.", "체결 내역을 불러오는 중입니다.": "Loading executions.", "랭킹을 불러오는 중입니다.": "Loading ranking.",
    "모든 보유 자산·미체결 주문·출석 기록을 초기화하고 처음부터 다시 시작합니다. Google 계정당 한 번만 사용할 수 있습니다.": "Reset all holdings, open orders, and attendance records and start again. This can be used once per Google account.",
    "내 자산 요약": "Asset summary", "화면 설정": "Display settings", "언어 설정": "Language settings", "뉴스 닫기": "Close news", "종목 가격 등락 그래프": "Stock price chart",
    "당신의 게임에": "Invest in your game", "지금바로 투자하세요": "right now", "한강 수온(선유)": "Han River temperature (Seonyu)",
    "실제 게임 뉴스와 이용자 거래가 만나 오늘의 가격을 만듭니다.": "Game news and investor trades shape today's prices.", "뉴스와 체결 흐름을 한눈에": "News and execution flow at a glance", "시세는 10초마다 새로고침됩니다": "Prices refresh every 10 seconds", "실시간 시세": "Live prices", "10초마다 갱신": "Refreshes every 10 seconds", "인기": "Popular", "급상승": "Top gainers", "급하락": "Top losers", "관심 종목": "Watchlist", "관심": "Watchlist", "최신순": "Latest", "영향도순": "Impact", "호가": "Quotes", "체결": "Trades", "미체결": "Open orders", "투자자 한마디": "Investor comments", "서버 저장": "Saved on server", "종목별 의견": "Stock comments", "등록": "Post", "종목 정보": "Stock info", "닫기": "Close", "가격 알림": "Price alert", "관심 등록": "Add to watchlist", "관심 등록됨": "In watchlist", "+ 태그": "+ Tag", "건 언급": "mentions", "게임 토픽": "Game topics", "인기 급상승": "Trending now", "홈": "Home", "시장": "Market", "뉴스": "News", "종목 도구": "Stock tools", "새로운 소식이": "Fresh news", "가격을 움직여요": "moves prices", "종목별 관련 뉴스 자동 분석": "Automatic related-news analysis", "부담 없이": "Without the pressure", "투자 감각 익히기": "Practice investing", "실제 돈이 아닌 모의 거래": "Paper trading, not real money", "종목 둘러보기": "Explore stocks"
  }
};
let currentLanguage = SUPPORTED_LANGUAGES.includes(document.documentElement.lang)
  ? document.documentElement.lang : "ko";
let money = createMoneyFormatter();

function t(source) { return TRANSLATIONS[currentLanguage]?.[source] || source; }
function locale() { return LOCALES[currentLanguage] || LOCALES.ko; }
function createMoneyFormatter() {
  return new Intl.NumberFormat(locale(), { style: "currency", currency: "KRW", maximumFractionDigits: 0 });
}
function formatNumber(value) { return Number(value || 0).toLocaleString(locale()); }
function formatDateTime(value) { return new Date(value).toLocaleString(locale()); }
function applyStaticTranslations() {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let node;
  while ((node = walker.nextNode())) {
    if (["SCRIPT", "STYLE"].includes(node.parentElement?.tagName)) continue;
    if (node.__gamestockSourceText === undefined) node.__gamestockSourceText = node.nodeValue;
    const source = node.__gamestockSourceText;
    const trimmed = source.trim().replace(/\s+/g, " ");
    if (!trimmed) continue;
    const translated = t(trimmed);
    if (translated !== trimmed) {
      const leading = source.match(/^\s*/)?.[0] || "";
      const trailing = source.match(/\s*$/)?.[0] || "";
      node.nodeValue = `${leading}${translated}${trailing}`;
    } else if (currentLanguage === "ko") node.nodeValue = source;
  }
  document.querySelectorAll("[placeholder]").forEach((element) => {
    if (!element.dataset.sourcePlaceholder) element.dataset.sourcePlaceholder = element.placeholder;
    element.placeholder = t(element.dataset.sourcePlaceholder);
  });
  ["aria-label", "title"].forEach((attribute) => {
    document.querySelectorAll(`[${attribute}]`).forEach((element) => {
      const dataKey = attribute === "title" ? "sourceTitle" : "sourceAriaLabel";
      if (!element.dataset[dataKey]) element.dataset[dataKey] = element.getAttribute(attribute);
      element.setAttribute(attribute, t(element.dataset[dataKey]));
    });
  });
  document.title = currentLanguage === "ja" ? "ゲーム株" : currentLanguage === "en" ? "GameStock" : "씹덕주식";
}
function updateThemeControl() {
  const button = document.querySelector("#theme-toggle");
  if (!button) return;
  const dark = document.documentElement.dataset.theme === "dark";
  button.setAttribute("aria-pressed", String(dark));
  button.textContent = dark ? `☀️ ${t("라이트 모드")}` : `🌙 ${t("다크 모드")}`;
}
function setTheme(theme, persist = true) {
  document.documentElement.dataset.theme = theme === "dark" ? "dark" : "light";
  if (persist) localStorage.setItem(THEME_STORAGE_KEY, document.documentElement.dataset.theme);
  updateThemeControl();
  if (!detailPage.hidden) renderDetail();
}
function setLanguage(language, persist = true) {
  currentLanguage = SUPPORTED_LANGUAGES.includes(language) ? language : "ko";
  document.documentElement.lang = currentLanguage;
  if (persist) localStorage.setItem(LANGUAGE_STORAGE_KEY, currentLanguage);
  money = createMoneyFormatter();
  const select = document.querySelector("#language-select");
  if (select) select.value = currentLanguage;
  applyStaticTranslations();
  updateThemeControl();
  updateChartRangeControls();
  updateLoginButton();
  renderPortfolio(currentPortfolio);
  renderStocks(currentStocks);
  renderEvents(currentEvents);
  if (currentDetailNews.length && loadedNewsCode) renderStockNews(currentDetailNews, loadedNewsCode);
  renderDetail();
}
function signedMoney(value) {
  const amount = Number(value || 0);
  const sign = amount > 0 ? "+" : amount < 0 ? "-" : "";
  return `${sign}${money.format(Math.abs(amount))}`;
}
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
// A fresh web launch always starts on the market home screen. Profile is
// opened only after the user explicitly selects it from the menu.
if (location.hash === "#profile") history.replaceState(null, "", `${location.pathname}${location.search}`);
const message = document.querySelector("#order-message");
const priceHistory = new Map();
const chartState = { points: [], width: 0, height: 0 };
const CHART_RANGES = [
  { value: "5m", label: "5분", milliseconds: 5 * 60 * 1000 },
  { value: "10m", label: "10분", milliseconds: 10 * 60 * 1000 },
  { value: "30m", label: "30분", milliseconds: 30 * 60 * 1000 },
  { value: "1h", label: "1시간", milliseconds: 60 * 60 * 1000 },
  { value: "6h", label: "6시간", milliseconds: 6 * 60 * 60 * 1000 },
  { value: "12h", label: "12시간", milliseconds: 12 * 60 * 60 * 1000 },
  { value: "24h", label: "24시간", milliseconds: 24 * 60 * 60 * 1000 },
  { value: "7d", label: "일주일", milliseconds: 7 * 24 * 60 * 60 * 1000 },
];
let chartRange = "24h";
let marketSort = "volume";
const orderBookCache = new Map();
let currentStocks = [];
let currentEvents = [];
let currentPortfolio = null;
let tradeRefreshTimer = null;
let loadedPriceHistoryCode = null;
let loadedDailyCode = null;
let loadedNewsCode = null;
let loadedOrderBookCode = null;
let loadedTradeCode = null;
let loadedPriceDriversCode = null;
let loadedFeaturesCode = null;
let currentUser = null;
let currentProfile = null;
let currentOpenOrders = [];
let currentSettlements = [];
let currentDetailNews = [];
let firebaseAuth = null;
let newsFilter = "latest";
let detailMarketTab = "book";
let watchlist = new Set();
const tagCache = new Map();
// 이전 버전의 기기 전용 저장값은 서버 DB와 혼동되지 않도록 폐기한다.
["gamestock-watchlist", "gamestock-tags", "gamestock-price-alerts"].forEach((key) => localStorage.removeItem(key));
const stockTags = {
  UMA: ["육성", "서브컬처", "라이브서비스"],
  BA: ["학원", "수집형", "라이브서비스"],
  GOV: ["RPG", "수집형", "액션"],
};

function currentStockCode() { return location.hash.startsWith("#stock/") ? decodeURIComponent(location.hash.slice(7)) : null; }
function renderWatchlistButton(stockCode) {
  const button = document.querySelector("#watchlist-button");
  if (!button) return;
  const active = watchlist.has(stockCode);
  button.setAttribute("aria-pressed", String(active));
  button.textContent = active ? "★ 관심 등록됨" : "☆ 관심 등록";
}
function renderDetailTabs() {
  document.querySelectorAll("[data-detail-tab]").forEach((button) => {
    const active = button.dataset.detailTab === detailMarketTab;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
  });
  document.querySelectorAll("[data-detail-panel]").forEach((panel) => { panel.hidden = panel.dataset.detailPanel !== detailMarketTab; });
}

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
    throw new Error(payload.message || t("요청 처리 중 오류가 발생했습니다."));
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
  renderMarketOverview(snapshot.stocks);
  renderStocks(snapshot.stocks);
  if (useSnapshotPortfolio && currentUser && snapshot.portfolio) renderPortfolio(snapshot.portfolio);
  if (!currentUser) renderPortfolio(null);
  renderEvents(snapshot.events);
  renderDetail();
}

function renderStocks(stocks) {
  updateMarketTabs();
  const stockCount = document.querySelector("#stock-count");
  if (stockCount) stockCount.textContent = `${formatNumber(stocks.length)}${t("개 종목")}`;
  const sortedStocks = [...stocks].sort((left, right) => {
    if (marketSort === "watchlist") return Number(watchlist.has(right.code)) - Number(watchlist.has(left.code));
    if (marketSort === "gainers") return Number(right.changePercent || 0) - Number(left.changePercent || 0);
    if (marketSort === "losers") return Number(left.changePercent || 0) - Number(right.changePercent || 0);
    if (marketSort === "popular") return Number(right.volume || 0) - Number(left.volume || 0);
    return Number(right.volume || 0) - Number(left.volume || 0);
  });
  const visibleStocks = marketSort === "watchlist" ? sortedStocks.filter((stock) => watchlist.has(stock.code)) : sortedStocks;
  stockContainer.innerHTML = visibleStocks.length ? visibleStocks
    .map((stock) => {
      const up = stock.changePercent >= 0;
      return `<a class="stock-card stock-row" href="#stock/${encodeURIComponent(stock.code)}" aria-label="${escapeHtml(stock.name)} ${t("상세 보기")}"><div class="stock-identity"><span class="watch-mark">${watchlist.has(stock.code) ? "★" : "☆"}</span><img class="game-icon" src="assets/game-icons/${encodeURIComponent(stock.code)}.png" alt="" width="42" height="42" loading="lazy"><div><h3>${escapeHtml(stock.name)}</h3><span class="code">${escapeHtml(stock.code)} · ${escapeHtml(stock.genre)}</span></div></div><div class="stock-quote"><strong class="price">${money.format(stock.price)}</strong><span class="change ${up ? "up" : "down"}">${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%</span></div><div class="stock-row-chart">${sparklineSvg(stock.code, up)}</div><div class="stock-volume"><span>${t("거래량")}</span><strong>${formatNumber(stock.volume)}${t("주")}</strong></div><span class="card-link">${t("상세 보기")} →</span></a>`;
    })
    .join("") : '<p class="empty-state market-empty">관심 등록한 종목이 없습니다. 종목 상세에서 ☆ 관심 등록을 눌러 추가해보세요.</p>';
}

function renderMarketOverview(stocks) {
  const rows = Array.isArray(stocks) ? stocks : [];
  if (!rows.length) return;
  const average = rows.reduce((sum, stock) => sum + Number(stock.price || 0), 0) / rows.length;
  const averageChange = rows.reduce((sum, stock) => sum + Number(stock.changePercent || 0), 0) / rows.length;
  const turnover = rows.reduce((sum, stock) => sum + Number(stock.price || 0) * Number(stock.volume || 0), 0);
  const gainers = rows.filter((stock) => Number(stock.changePercent || 0) > 0).length;
  const losers = rows.filter((stock) => Number(stock.changePercent || 0) < 0).length;
  const leader = [...rows].sort((a, b) => Number(b.volume || 0) - Number(a.volume || 0))[0];
  const averageEl = document.querySelector("#market-average");
  const averageChangeEl = document.querySelector("#market-average-change");
  const turnoverEl = document.querySelector("#market-turnover");
  const stockCountEl = document.querySelector("#market-stock-count");
  const breadthEl = document.querySelector("#market-breadth");
  const leaderEl = document.querySelector("#market-leader");
  const leaderVolumeEl = document.querySelector("#market-leader-volume");
  if (averageEl) averageEl.textContent = money.format(Math.round(average));
  if (averageChangeEl) {
    averageChangeEl.className = averageChange > 0 ? "up" : averageChange < 0 ? "down" : "flat";
    averageChangeEl.textContent = `${averageChange >= 0 ? "▲" : "▼"} ${Math.abs(averageChange).toFixed(2)}%`;
  }
  if (turnoverEl) turnoverEl.textContent = money.format(Math.round(turnover));
  if (stockCountEl) stockCountEl.textContent = `${formatNumber(rows.length)}${t("개 종목")}`;
  if (breadthEl) breadthEl.textContent = `${gainers} / ${losers}`;
  if (leaderEl) leaderEl.textContent = leader ? leader.name : "-";
  if (leaderVolumeEl) leaderVolumeEl.textContent = leader ? `${formatNumber(leader.volume)}${t("주")}` : "-";
}

function updateMarketTabs() {
  document.querySelectorAll(".market-tab").forEach((button) => {
    const active = button.dataset.marketSort === marketSort;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
  });
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
  return `<svg class="sparkline ${up ? "up" : "down"}" viewBox="0 0 80 32" role="img" aria-label="${t("가격 흐름")}"><polyline points="${coordinates}" /></svg>`;
}

function renderPortfolio(portfolio) {
  currentPortfolio = portfolio;
  const summary = document.querySelector("#portfolio-summary");
  if (!portfolio) {
    summary?.setAttribute("hidden", "");
    document.querySelector("#cash").textContent = "-";
    document.querySelector("#asset-value").textContent = "-";
    document.querySelector("#total-asset").textContent = "-";
    document.querySelector("#portfolio-profit").textContent = "-";
    document.querySelector("#portfolio-profit-rate").textContent = "-";
    return;
  }
  summary?.removeAttribute("hidden");
  document.querySelector("#cash").textContent = money.format(portfolio.cash);
  document.querySelector("#asset-value").textContent = money.format(
    portfolio.assetValue,
  );
  document.querySelector("#total-asset").textContent = money.format(
    portfolio.totalAsset,
  );
  const profit = Number(portfolio.totalAsset || 0) - 1000000;
  const profitRate = profit / 10000;
  const profitEl = document.querySelector("#portfolio-profit");
  const profitRateEl = document.querySelector("#portfolio-profit-rate");
  if (profitEl) {
    profitEl.className = profit >= 0 ? "profit-up" : "profit-down";
    profitEl.textContent = signedMoney(profit);
  }
  if (profitRateEl) {
    profitRateEl.className = profit >= 0 ? "profit-up" : "profit-down";
    profitRateEl.textContent = `${profitRate >= 0 ? "+" : ""}${profitRate.toFixed(2)}%`;
  }
}

async function refreshPortfolio() {
  if (!currentUser) { renderPortfolio(null); return; }
  const [portfolio, settlements] = await Promise.all([
    api("/api/portfolio"),
    currentProfile ? api("/api/settlements") : Promise.resolve(currentSettlements),
  ]);
  renderPortfolio(portfolio);
  // 실시간 가격 갱신으로 보유 종목만 다시 그릴 때 입력 중인 닉네임/사진을 덮어쓰지 않는다.
  if (currentProfile) renderProfile(currentProfile, portfolio, { preserveForm: true, settlements });
}

async function refreshWatchlist() {
  if (!currentUser) { watchlist = new Set(); renderStocks(currentStocks); return; }
  try {
    const rows = await api("/api/watchlist");
    watchlist = new Set((rows || []).map((row) => row.stockCode));
  } catch { watchlist = new Set(); }
  renderStocks(currentStocks);
  renderWatchlistButton(currentStockCode());
}

function renderEvents(events) {
  const container = document.querySelector("#event-list");
  if (!container) return;
  if (!events.length) {
    container.innerHTML = `<p class="empty-state">${t("아직 수집된 뉴스가 없습니다.")}</p>`;
    return;
  }
  container.innerHTML = events.slice(0, 5)
    .map((event, index) => {
      const stock = currentStocks.find((item) => item.code === event.stockCode);
      const stockLabel = stock ? `${stock.code} · ${stock.name}` : t("시장 전체");
      const priceChange = stock ? Number(stock.changePercent || 0) : Number(event.priceChangePercent || 0);
      const priceClass = priceChange > 0 ? "up" : priceChange < 0 ? "down" : "flat";
      const priceLabel = priceChange > 0 ? `▲ ${priceChange.toFixed(2)}%` : priceChange < 0 ? `▼ ${Math.abs(priceChange).toFixed(2)}%` : "— 0.00%";
      const stockMarkup = `<span>${escapeHtml(stockLabel)}</span>${stock ? `<span class="event-stock-change ${priceClass}">${priceLabel}</span>` : ""}`;
      return `<article class="event ${event.sentiment || "neutral"}"><button type="button" class="event-news-button" data-news-index="${index}"><strong>${escapeHtml(event.title)}</strong><small class="event-stock">${stockMarkup}</small></button><div class="event-insight"><small>${escapeHtml(event.priceReason || fallbackPriceReason(priceChange))}</small></div></article>`;
    })
    .join("");
}

function fallbackPriceReason(changePercent) {
  const change = Number(changePercent || 0);
  if (change > 0) return t("최근 거래 흐름을 따라 가격이 상승하고 있습니다.");
  if (change < 0) return t("최근 거래 흐름을 따라 가격이 하락하고 있습니다.");
  return t("최근 가격은 보합 상태입니다.");
}

function newsSummary(description) {
  const summary = String(description || "")
    .replace(/\s*출처:\s*https?:\/\/\S+\s*$/i, "")
    .replace(/<[^>]*>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, " ")
    .trim();
  return summary || t("제공된 뉴스 요약이 없습니다.");
}

function newsSourceUrl(description) {
  const match = String(description || "").match(/출처:\s*(https?:\/\/[^\s<]+)/i);
  if (!match) return null;
  try {
    const url = new URL(match[1]);
    return url.protocol === "http:" || url.protocol === "https:" ? url.toString() : null;
  } catch { return null; }
}

function renderStockInfo(stock) {
  const container = document.querySelector("#stock-info-content");
  if (!container || !stock) return;
  const change = Number(stock.changePercent || 0);
  container.innerHTML = [
    ["종목 코드", stock.code], ["게임 장르", stock.genre], ["현재가", money.format(stock.price)],
    ["전일 대비", `${change >= 0 ? "+" : ""}${change.toFixed(2)}%`], ["누적 거래량", `${formatNumber(stock.volume)}주`],
    ["가격 제한", "일일 ±30% (가상시장)"]
  ].map(([label, value]) => `<div class="stock-info-item"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`).join("");
}

function renderStockTags(stockCode) {
  const content = document.querySelector("#detail-genre");
  if (!content) return;
  const serverTags = tagCache.get(stockCode) || [];
  const tags = serverTags.length ? serverTags.map((tag) => typeof tag === "string" ? tag : tag.tag) : (stockTags[stockCode] || []);
  const tagMarkup = tags.map((tag) => `<span class="tag-chip">#${escapeHtml(tag)}</span>`).join("");
  content.innerHTML = `${escapeHtml(content.dataset.base || "")} <span class="detail-tags">${tagMarkup}</span><button type="button" class="tag-add" data-add-tag="${escapeHtml(stockCode)}">+ 태그</button>`;
}

async function loadDetailFeatures(stockCode) {
  try {
    const tags = await api(`/api/stocks/${encodeURIComponent(stockCode)}/tags`);
    if (location.hash !== `#stock/${stockCode}`) return;
    tagCache.set(stockCode, tags || []);
    renderStockTags(stockCode);
  } catch {
    // 공개 읽기 실패 시에도 종목 화면과 기본 태그는 계속 사용할 수 있다.
    renderStockTags(stockCode);
  }
}

function renderDetailOpenOrders(stockCode) {
  const container = document.querySelector("#detail-open-orders");
  if (!container) return;
  const orders = currentOpenOrders.filter((order) => !stockCode || order.stockCode === stockCode).slice(0, 10);
  container.innerHTML = orders.length ? orders.map((order) => `<div class="history-row"><span class="history-side ${order.side === "BUY" ? "buy" : "sell"}">${order.side === "BUY" ? "매수" : "매도"}</span><strong>${formatNumber(order.remainingQuantity)}주</strong><span>${money.format(order.price)}</span><time>${formatDateTime(order.createdAt)}</time></div>`).join("") : `<p class="empty-state">현재 대기 중인 주문이 없습니다.</p>`;
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
    loadedDailyCode = null;
    loadedNewsCode = null;
    loadedTradeCode = null;
    loadedPriceDriversCode = null;
    loadedFeaturesCode = null;
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
  const detailIcon = document.querySelector("#detail-icon");
  if (detailIcon) {
    detailIcon.src = `assets/game-icons/${encodeURIComponent(stock.code)}.png`;
    detailIcon.alt = `${stock.name} 아이콘`;
  }
  document.querySelector("#detail-name").textContent = stock.name;
  document.querySelector("#detail-genre").textContent =
    `${t("거래량")} ${formatNumber(stock.volume)}`;
  document.querySelector("#detail-genre").dataset.base = `${t("거래량")} ${formatNumber(stock.volume)}`;
  renderStockTags(stock.code);
  renderStockInfo(stock);
  renderWatchlistButton(stock.code);
  renderDetailTabs();
  document.querySelector("#detail-price").textContent = money.format(
    stock.price,
  );
  updateMarketPriceEstimate(stock.code);
  const change = document.querySelector("#detail-change");
  change.className = `detail-change ${up ? "up" : "down"}`;
  change.textContent = `${up ? "▲" : "▼"} ${Math.abs(stock.changePercent).toFixed(2)}%`;
  document.querySelector("#stock-code").value = stock.code;
  if (loadedPriceHistoryCode !== stock.code) {
    loadedPriceHistoryCode = stock.code;
    loadPriceHistory(stock.code);
  }
  if (loadedDailyCode !== stock.code) {
    loadedDailyCode = stock.code;
    loadDailySummaries(stock.code);
  }
  if (loadedTradeCode !== stock.code) {
    loadedTradeCode = stock.code;
    startTradeRefresh(stock.code);
  }
  if (loadedPriceDriversCode !== stock.code) {
    loadedPriceDriversCode = stock.code;
    loadPriceDrivers(stock.code);
  }
  if (loadedNewsCode !== stock.code) {
    loadedNewsCode = stock.code;
    loadStockNews(stock.code);
  } else if (currentDetailNews.length) {
    // WebSocket 가격 갱신 때 뉴스 행의 종목별 실시간 등락률도 함께 갱신한다.
    renderStockNews(currentDetailNews, stock.code);
  }
  if (loadedOrderBookCode !== stock.code) { loadedOrderBookCode = stock.code; loadOrderBook(stock.code); }
  renderDetailOpenOrders(stock.code);
  if (loadedFeaturesCode !== stock.code) { loadedFeaturesCode = stock.code; loadDetailFeatures(stock.code); }
  drawChart(chartPointsFor(stock.code, stock.price));
}

function selectedChartRange() {
  return CHART_RANGES.find((range) => range.value === chartRange) || CHART_RANGES[6];
}

function chartPointsFor(stockCode, fallbackPrice) {
  const cutoff = Date.now() - selectedChartRange().milliseconds;
  const points = (priceHistory.get(stockCode) || []).filter((point) => point.time.getTime() >= cutoff);
  return points.length ? points : [{ price: Number(fallbackPrice || 0), time: new Date() }];
}

function updateChartRangeControls() {
  const selected = selectedChartRange();
  document.querySelectorAll("#chart-range-selector button").forEach((button) => {
    const active = button.dataset.range === selected.value;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  const label = document.querySelector("#chart-range");
  if (label) label.textContent = t(selected.label);
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
    const levelMarkup = (level, side) => `<div class="depth-level ${side}"><span class="depth-price">${money.format(level.price)}</span><span class="depth-bar"><i style="width:${Math.max(5, level.quantity / maxQuantity * 100)}%"></i></span><strong>${formatNumber(level.quantity)}${t("주")}</strong><small>${formatNumber(level.orderCount)}${t("건")}</small></div>`;
    container.innerHTML = bids.length || asks.length ? `<div class="depth-legend"><span class="book-buy">${t("매수 잔량")}</span><span class="book-sell">${t("매도 잔량")}</span></div><div class="depth-chart"><div class="depth-column asks">${asks.map(level => levelMarkup(level, "sell")).join("") || `<p class="empty-state">${t("매도 대기 없음")}</p>`}</div><div class="depth-column bids">${bids.map(level => levelMarkup(level, "buy")).join("") || `<p class="empty-state">${t("매수 대기 없음")}</p>`}</div></div>` : `<p class="empty-state">${t("대기 중인 지정가 주문이 없습니다.")}</p>`;
  } catch { container.innerHTML = `<p class="empty-state">${t("호가를 불러오지 못했습니다.")}</p>`; }
}

function updateMarketPriceEstimate(stockCode = loadedOrderBookCode, book = orderBookCache.get(stockCode)) {
  const amountDisplay = document.querySelector("#market-amount");
  if (!amountDisplay || !stockCode) return;
  const stock = currentStocks.find((item) => item.code === stockCode);
  if (!stock) return;
  const side = document.querySelector('input[name="side"]:checked')?.value || "BUY";
  const quantity = Math.max(1, Number(document.querySelector('#order-form [name="quantity"]')?.value || 1));
  const referencePrice = Math.max(1, Number(stock.price || 0));
  const collar = side === "BUY" ? referencePrice * 1.10 : referencePrice * 0.90;
  const allLevels = side === "BUY" ? (book?.asks || []) : (book?.bids || []);
  // 372.ro식 시장가 동작: 반대 호가를 순서대로 훑되 현재가 기준 ±10% 안에서만 계산한다.
  const levels = allLevels.filter((level) => {
    const price = Number(level.price || 0);
    return side === "BUY" ? price <= collar : price >= collar;
  });
  let remaining = quantity;
  let filled = 0;
  let total = 0;
  for (const level of levels) {
    const levelQuantity = Math.max(0, Number(level.quantity || 0));
    const matched = Math.min(remaining, levelQuantity);
    const levelPrice = Number(level.price || 0);
    total += matched * levelPrice;
    filled += matched;
    remaining -= matched;
    if (remaining <= 0) break;
  }
  const fallbackGross = referencePrice * quantity;
  const grossAmount = filled > 0 ? Math.round(total) : fallbackGross;
  const fee = grossAmount > 0 ? Math.max(1, Math.round(grossAmount * 0.001)) : 0;
  const cashAmount = side === "BUY" ? grossAmount + fee : Math.max(0, grossAmount - fee);
  amountDisplay.textContent = money.format(grossAmount);
  const help = document.querySelector("#market-price-help");
  if (help) {
    const amountLabel = side === "BUY" ? t("수수료 포함 예상 출금액") : t("수수료 차감 예상 입금액");
    const outsideCollar = allLevels.length > 0 && levels.length === 0;
    const depthText = filled > 0
      ? `${t("현재 호가")} ${formatNumber(filled)}${t("주")} ${t("기준")}`
      : outsideCollar ? t("허용 범위 안 호가가 없어 현재가 기준") : t("반대 호가가 없어 현재가 기준");
    help.textContent = `${depthText} · ${amountLabel} ${money.format(cashAmount)} · ${t("허용 범위 ±10%")}${remaining > 0 && filled > 0 ? ` · ${formatNumber(remaining)}${t("주")} ${t("미체결 가능")}` : ""}`;
  }
}

async function loadPublicTrades(stockCode) {
  const container = document.querySelector("#public-trades");
  container.innerHTML = `<p class="empty-state">${t("전체 체결 내역을 불러오는 중입니다.")}</p>`;
  try {
    const trades = await api(`/api/stocks/${encodeURIComponent(stockCode)}/trades`);
    if (location.hash !== `#stock/${stockCode}`) return;
    container.innerHTML = trades.length ? trades.map((trade) => {
      const isBuy = trade.side === "BUY";
      const date = formatDateTime(trade.createdAt);
      return `<div class="history-row"><span class="history-side ${isBuy ? "buy" : "sell"}">${isBuy ? t("매수") : t("매도")}</span><strong>${formatNumber(trade.quantity)}${t("주")}</strong><span>${money.format(trade.price)}</span><time>${date}</time></div>`;
    }).join("") : `<p class="empty-state">${t("아직 체결된 거래가 없습니다.")}</p>`;
  } catch { loadedTradeCode = null; container.innerHTML = `<p class="empty-state">${t("전체 체결 내역을 불러오지 못했습니다.")}</p>`; }
}

async function loadStockNews(stockCode) {
  const newsContainer = document.querySelector("#detail-events");
  newsContainer.innerHTML =
    '<p class="empty-state">관련 소식을 불러오는 중입니다.</p>';
  try {
    const news = await api(`/api/stocks/${encodeURIComponent(stockCode)}/news`);
    if (location.hash !== `#stock/${stockCode}`) return;
    currentDetailNews = news;
    renderStockNews(news, stockCode);
  } catch (error) {
    loadedNewsCode = null;
    currentDetailNews = [];
    newsContainer.innerHTML =
      '<p class="empty-state">관련 소식을 불러오지 못했습니다.</p>';
  }
}

function renderPriceDrivers(drivers) {
  const container = document.querySelector("#price-drivers");
  const reason = document.querySelector("#price-drivers-reason");
  const updated = document.querySelector("#price-drivers-updated");
  if (!container || !drivers) return;
  const newsDirection = drivers.newsImpact > 0.2 ? t("상승 방향") : drivers.newsImpact < -0.2 ? t("하락 방향") : t("혼합·보합");
  const flowLabel = (buy, sell) => {
    const net = Number(buy || 0) - Number(sell || 0);
    if (net > 0) return `${t("매수 우세")} · ${t("순")} ${formatNumber(net)}${t("주")}`;
    if (net < 0) return `${t("매도 우세")} · ${t("순")} ${formatNumber(Math.abs(net))}${t("주")}`;
    return t("매수·매도 균형");
  };
  const latest = drivers.latestTradeAt ? formatDateTime(drivers.latestTradeAt) : t("최근 체결 없음");
  const latestNews = drivers.latestNewsAt ? formatDateTime(drivers.latestNewsAt) : t("최근 뉴스 없음");
  if (reason) reason.textContent = `${drivers.reason || "뉴스와 거래 흐름이 현재 가격에 반영되었습니다."} (최근 체결 ${latest})`;
  if (updated) updated.textContent = `분석 범위: 최근 24시간 · 마지막 뉴스 ${latestNews} · 마지막 체결 ${latest}`;
  container.innerHTML = [
    ["뉴스 흐름", `${newsDirection} · ${Number(drivers.newsCount || 0).toLocaleString()}건`],
    ["이용자 거래", flowLabel(drivers.userBuyVolume, drivers.userSellVolume)],
    ["현재 호가", `매수 ${Number(drivers.openBuyVolume || 0).toLocaleString()}주 · 매도 ${Number(drivers.openSellVolume || 0).toLocaleString()}주`],
  ].map(([label, value]) => `<div class="price-driver"><span>${label}</span><strong>${escapeHtml(value)}</strong></div>`).join("");
}

async function loadPriceDrivers(stockCode) {
  const container = document.querySelector("#price-drivers");
  try {
    const drivers = await api(`/api/stocks/${encodeURIComponent(stockCode)}/price-drivers`);
    if (location.hash !== `#stock/${stockCode}`) return;
    renderPriceDrivers(drivers);
  } catch {
    if (container) container.innerHTML = '<p class="empty-state">가격 결정 근거를 불러오지 못했습니다.</p>';
  }
}

function renderStockNews(news, stockCode) {
  const newsContainer = document.querySelector("#detail-events");
  const mentionCount = document.querySelector("#news-mention-count");
  if (mentionCount) mentionCount.textContent = `${news.length}건 언급`;
  const filtered = [...news].sort((left, right) => {
    if (newsFilter === "impact") return Math.abs(Number(right.impact || 0)) - Math.abs(Number(left.impact || 0));
    return new Date(right.publishedAt || 0).getTime() - new Date(left.publishedAt || 0).getTime();
  });
  newsContainer.innerHTML = filtered
    .map((event) => {
      const eventIndex = news.indexOf(event);
      const stock = currentStocks.find((item) => item.code === event.stockCode)
        || currentStocks.find((item) => item.code === stockCode);
      const priceChange = stock ? Number(stock.changePercent || 0) : Number(event.priceChangePercent || 0);
      const priceClass = priceChange > 0 ? "up" : priceChange < 0 ? "down" : "flat";
      const priceLabel = priceChange > 0 ? `▲ ${priceChange.toFixed(2)}%` : priceChange < 0 ? `▼ ${Math.abs(priceChange).toFixed(2)}%` : "— 0.00%";
      const stockLabel = stock ? `${stock.code} · ${stock.name}` : stockCode;
      return `<article class="event ${event.sentiment || "neutral"}"><button type="button" class="event-news-button" data-detail-news-index="${eventIndex}"><strong>${escapeHtml(event.title)}</strong><small class="event-stock"><span>${escapeHtml(stockLabel)}</span><span class="event-stock-change ${priceClass}">${priceLabel}</span></small><small class="event-date">${event.publishedAt ? formatDateTime(event.publishedAt) : t("날짜 미상")}</small></button><div class="event-insight"><small>${escapeHtml(event.priceReason || fallbackPriceReason(priceChange))}</small></div></article>`;
    })
    .join("") || '<p class="empty-state">아직 관련 소식이 없습니다.</p>';
}

async function loadPriceHistory(stockCode, requestedRange = chartRange) {
  try {
    const savedPoints = await api(
      `/api/stocks/${encodeURIComponent(stockCode)}/history?range=${encodeURIComponent(requestedRange)}`,
    );
    if (location.hash !== `#stock/${stockCode}` || chartRange !== requestedRange) return;
    const saved = savedPoints.map((point) => ({
      price: point.price,
      time: new Date(point.recordedAt),
    }));
    const cutoff = Date.now() - (CHART_RANGES.find((range) => range.value === requestedRange)?.milliseconds || CHART_RANGES[6].milliseconds);
    const existing = (priceHistory.get(stockCode) || []).filter((point) => point.time.getTime() >= cutoff);
    const lastSavedTime = saved.at(-1)?.time.getTime() || 0;
    const livePoints = existing.filter(
      (point) => point.time.getTime() > lastSavedTime,
    );
    priceHistory.set(stockCode, [...saved, ...livePoints].slice(-2000));
    drawChart(chartPointsFor(stockCode, currentStocks.find((stock) => stock.code === stockCode)?.price));
  } catch (error) {
    loadedPriceHistoryCode = null;
  }
}

document.querySelectorAll("#chart-range-selector button").forEach((button) => {
  button.addEventListener("click", () => {
    const nextRange = button.dataset.range;
    if (!CHART_RANGES.some((range) => range.value === nextRange) || nextRange === chartRange) return;
    chartRange = nextRange;
    updateChartRangeControls();
    const code = location.hash.startsWith("#stock/") ? location.hash.slice(7) : null;
    if (code) loadPriceHistory(code, chartRange);
  });
});

async function loadDailySummaries(stockCode) {
  const container = document.querySelector("#daily-summaries");
  if (!container) return;
  container.innerHTML = '<p class="empty-state">일별 시세를 불러오는 중입니다.</p>';
  try {
    const rows = await api(`/api/stocks/${encodeURIComponent(stockCode)}/daily`);
    if (location.hash !== `#stock/${stockCode}`) return;
    container.innerHTML = rows.length
      ? `<table class="daily-table"><thead><tr><th>날짜</th><th>시가</th><th>종가</th><th>거래량</th></tr></thead><tbody>${rows.map((row) => `<tr><td>${escapeHtml(row.tradingDate)}</td><td>${money.format(row.openPrice)}</td><td>${money.format(row.closePrice)}</td><td>${Number(row.volume || 0).toLocaleString()}주</td></tr>`).join("")}</tbody></table>`
      : '<p class="empty-state">아직 집계된 일별 체결이 없습니다.</p>';
  } catch (error) {
    loadedDailyCode = null;
    container.innerHTML = '<p class="empty-state">일별 시세를 불러오지 못했습니다.</p>';
  }
}

function startTradeRefresh(stockCode) {
  clearTradeRefresh();
  loadPublicTrades(stockCode);
  tradeRefreshTimer = setInterval(() => {
    if (location.hash === `#stock/${stockCode}`) {
      loadPublicTrades(stockCode);
      loadOrderBook(stockCode);
      loadDailySummaries(stockCode);
      loadPriceDrivers(stockCode);
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
            const date = formatDateTime(order.createdAt);
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
  context.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue("--line").trim() || "#e5e9ef";
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
    const timeLabel = selectedChartRange().milliseconds > 24 * 60 * 60 * 1000
      ? point.time.toLocaleDateString(locale(), { month: "short", day: "numeric" })
      : point.time.toLocaleTimeString(locale(), { hour: "2-digit", minute: "2-digit" });
    tooltip.textContent = `${timeLabel} · ${money.format(point.price)}`;
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
      const feeText = result.fee ? ` · 수수료 ${money.format(result.fee)}` : "";
      const settlementText = result.settlementStatus === "SETTLED" ? " · 즉시 결제 완료" : "";
      message.textContent = `${result.message} ${result.stockCode} ${result.quantity}주${feeText}${settlementText}`;
      await refreshMarket();
    } catch (error) {
      message.className = "message error";
      message.textContent = error.message;
    }
  });

window.addEventListener("hashchange", renderDetail);
function syncOrderPriceFields(orderType = document.querySelector("#order-type").value) {
  const marketPriceField = document.querySelector("#market-price-field");
  const limitPriceField = document.querySelector("#limit-price-field");
  const isMarket = orderType === "MARKET";
  if (marketPriceField) {
    marketPriceField.hidden = !isMarket;
    marketPriceField.style.display = isMarket ? "" : "none";
  }
  if (limitPriceField) {
    const isLimit = orderType === "LIMIT";
    limitPriceField.hidden = !isLimit;
    limitPriceField.style.display = isLimit ? "" : "none";
  }
}
document.querySelector("#order-type").addEventListener("change", (event) => {
  syncOrderPriceFields(event.target.value);
  updateMarketPriceEstimate();
});
syncOrderPriceFields();
function updateOrderActionLabel() {
  const submit = document.querySelector("#order-submit");
  const side = document.querySelector('input[name="side"]:checked')?.value || "BUY";
  if (submit) submit.textContent = side === "BUY" ? t("매수 주문") : t("매도 주문");
}
document.querySelectorAll('input[name="side"]').forEach((input) => input.addEventListener("change", () => {
  updateOrderActionLabel();
  updateMarketPriceEstimate();
}));
updateOrderActionLabel();
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
    if (status) {
      status.textContent = t("실시간 연결됨");
      status.classList.add("ok");
    }
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
const themeToggle = document.querySelector("#theme-toggle");
const languageSelect = document.querySelector("#language-select");
const newsModal = document.querySelector("#news-modal");
const newsModalTitle = document.querySelector("#news-modal-title");
const newsModalStock = document.querySelector("#news-modal-stock");
const newsModalPrice = document.querySelector("#news-modal-price");
const newsModalDate = document.querySelector("#news-modal-date");
const newsModalReason = document.querySelector("#news-modal-reason");
const newsModalSummary = document.querySelector("#news-modal-summary");
const newsModalSource = document.querySelector("#news-modal-source");
const resetAccountButton = document.querySelector("#reset-account");
const resetAccountMessage = document.querySelector("#reset-account-message");
let profileRequired = false;

function openLogin() { closeMenu(); loginModal.hidden = false; }
function closeLogin() { loginModal.hidden = true; loginMessage.textContent = ""; }
function closeMenu() { menuPanel.hidden = true; menuButton.setAttribute("aria-expanded", "false"); }
function openNewsModal(event) {
  if (!event || !newsModal) return;
  const stock = currentStocks.find((item) => item.code === event.stockCode);
  const change = stock ? Number(stock.changePercent || 0) : Number(event.priceChangePercent || 0);
  newsModalStock.textContent = stock ? `${stock.code} · ${stock.name}` : t("시장 전체");
  newsModalTitle.textContent = event.title || t("뉴스 상세");
  const priceClass = change > 0 ? "up" : change < 0 ? "down" : "flat";
  newsModalPrice.className = `event-price ${priceClass}`;
  newsModalPrice.textContent = change > 0 ? `▲ ${change.toFixed(2)}%` : change < 0 ? `▼ ${Math.abs(change).toFixed(2)}%` : "— 0.00%";
  newsModalDate.textContent = event.publishedAt ? formatNewsDate(event.publishedAt) : t("게시 시각 미상");
  newsModalReason.textContent = event.priceReason || fallbackPriceReason(change);
  newsModalSummary.textContent = newsSummary(event.description);
  const sourceUrl = newsSourceUrl(event.description);
  if (newsModalSource) {
    newsModalSource.hidden = !sourceUrl;
    if (sourceUrl) newsModalSource.href = sourceUrl;
    else newsModalSource.removeAttribute("href");
  }
  newsModal.hidden = false;
}
function closeNewsModal() { if (newsModal) newsModal.hidden = true; }
function formatNewsDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(locale());
}
function updateLoginButton() {
  menuButton.hidden = false;
  menuButton.textContent = currentUser ? `${currentUser.nickname || t("내 계정")} ▾` : `☰ ${t("메뉴")}`;
  const menuLoginButton = document.querySelector("#menu-login-button");
  const profileButton = document.querySelector("#profile-button");
  const logoutButton = document.querySelector("#logout-button");
  if (menuLoginButton) menuLoginButton.hidden = Boolean(currentUser);
  if (profileButton) profileButton.hidden = !currentUser;
  if (logoutButton) logoutButton.hidden = !currentUser;
}

function avatarMarkup(name, className) {
  const initial = escapeHtml((name || "G").trim().charAt(0).toUpperCase() || "G");
  return `<div class="${className}">${initial}</div>`;
}

function renderProfile(profile, portfolio = null, { preserveForm = false, orders = currentOpenOrders, settlements = currentSettlements } = {}) {
  currentProfile = profile;
  currentOpenOrders = orders || [];
  currentSettlements = settlements || [];
  const resetPlaceholder = profileRequired && String(profile.nickname || "").startsWith("reset_");
  if (!preserveForm) document.querySelector("#profile-nickname").value = resetPlaceholder ? "" : (profile.nickname || "");
  document.querySelector("#profile-email").textContent = profile.email || "Google 계정";
  const avatar = document.querySelector("#profile-avatar");
  if (avatar) {
    avatar.className = "profile-avatar";
    avatar.textContent = (resetPlaceholder ? "G" : profile.nickname || "G").trim().charAt(0).toUpperCase() || "G";
  }
  const positions = portfolio?.positions || [];
  document.querySelector("#profile-positions").innerHTML = positions.length ? positions.map((position) => {
    const profitClass = position.profitLoss >= 0 ? "profit-up" : "profit-down";
    const sign = position.profitLoss >= 0 ? "+" : "";
    const settled = Number(position.settledQuantity ?? position.quantity);
    const unsettled = Number(position.unsettledQuantity ?? Math.max(0, position.quantity - settled));
    const realized = Number(position.realizedProfitLoss || 0);
    return `<div class="holding-row"><div><strong>${escapeHtml(position.stockCode)}</strong><div class="holding-meta">${position.quantity.toLocaleString()}주 · 평균 ${money.format(position.averagePrice)} · 평가액 ${money.format(position.marketValue)}</div><div class="holding-meta">결제 완료 ${settled.toLocaleString()}주${unsettled ? ` · 미결제 ${unsettled.toLocaleString()}주` : ""} · 실현손익 ${signedMoney(realized)}</div></div><span class="${profitClass}">${sign}${money.format(position.profitLoss)}<br /><small>평가손익 ${sign}${Number(position.profitLossPercent || 0).toFixed(2)}%</small></span></div>`;
  }).join("") : '<p class="empty-state">보유 중인 종목이 없습니다.</p>';
  const ordersContainer = document.querySelector("#profile-orders");
  ordersContainer.innerHTML = currentOpenOrders.length ? currentOpenOrders.slice(0, 5).map((order) => {
    const isBuy = order.side === "BUY";
    const sideLabel = isBuy ? "매수" : "매도";
    const sideClass = isBuy ? "buy" : "sell";
    const expiry = order.expiresAt ? formatDateTime(order.expiresAt) : "-";
    const reservation = isBuy ? `예약금 ${money.format(order.reservedCash)}` : `예약수량 ${Number(order.reservedQuantity || order.remainingQuantity).toLocaleString()}주`;
    return `<div class="open-order-row"><div><strong class="order-side ${sideClass}">${sideLabel} · ${escapeHtml(order.stockCode)}</strong><div class="holding-meta">${order.remainingQuantity.toLocaleString()}주 · 지정가 ${money.format(order.price)} · ${reservation}</div><small class="holding-meta">만료 예정 ${expiry}</small></div><button type="button" class="cancel-order" data-cancel-order="${order.id}">주문 취소</button></div>`;
  }).join("") : '<p class="empty-state">미체결 주문이 없습니다.</p>';
  const settlementsContainer = document.querySelector("#profile-settlements");
  settlementsContainer.innerHTML = currentSettlements.length ? currentSettlements.slice(0, 5).map((settlement) => {
    const isBuy = settlement.side === "BUY";
    const completedAt = settlement.settlementAt ? formatDateTime(settlement.settlementAt) : "-";
    const amountLabel = isBuy ? `출금 ${money.format(settlement.netAmount)}` : `입금 ${money.format(settlement.netAmount)}`;
    return `<div class="settlement-row"><div><strong class="order-side ${isBuy ? "buy" : "sell"}">${isBuy ? "매수" : "매도"} · ${escapeHtml(settlement.stockCode)}</strong><div class="holding-meta">${Number(settlement.quantity).toLocaleString()}주 · ${amountLabel} · 수수료 ${money.format(settlement.fee)}</div><small class="holding-meta">체결 완료 ${completedAt}</small></div><span class="settlement-status">체결 완료</span></div>`;
  }).join("") : '<p class="empty-state">체결 완료된 거래가 없습니다.</p>';
  const resetButton = document.querySelector("#reset-account");
  const resetMessage = document.querySelector("#reset-account-message");
  if (resetButton) {
    const available = profile.resetAvailable !== false;
    resetButton.hidden = profileRequired;
    resetButton.disabled = !available || profileRequired;
    resetButton.textContent = available ? "인생 리셋 사용" : "인생 리셋 사용 완료";
  }
  if (resetMessage && profile.resetAvailable === false) {
    resetMessage.className = "message";
    resetMessage.textContent = "이 Google 계정은 인생 리셋을 이미 사용했습니다.";
  } else if (resetMessage) {
    resetMessage.className = "message";
    resetMessage.textContent = "";
  }
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
    const [profile, portfolio, orders, settlements] = await Promise.all([api("/api/profile"), api("/api/portfolio"), api("/api/orders"), api("/api/settlements")]);
    renderProfile(profile, portfolio, { orders, settlements });
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
    container.innerHTML = ranking.length ? ranking.map((entry) => { const change = Number(entry.changePercent || 0); const sign = change >= 0 ? "+" : ""; const changeClass = change >= 0 ? "profit-up" : "profit-down"; return `<div class="ranking-row"><span class="ranking-rank">${entry.rank}</span><strong class="ranking-user">${escapeHtml(entry.nickname)}</strong><span class="ranking-change ${changeClass}">${sign}${change.toFixed(2)}%</span><span class="ranking-asset">${money.format(entry.totalAsset)}</span></div>`; }).join("") : '<p class="empty-state">아직 랭킹에 참여한 사용자가 없습니다.</p>';
  } catch (error) { container.innerHTML = `<p class="empty-state">랭킹을 불러오지 못했습니다: ${escapeHtml(error.message)}</p>`; }
}

async function handleAuthenticatedUser(user) {
  currentUser = user;
  updateLoginButton();
  await Promise.all([refreshMarket(), refreshWatchlist()]);
  if (location.hash === "#profile") await openProfile(Boolean(user.requiresNickname));
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
document.querySelector("#menu-login-button").addEventListener("click", openLogin);
document.querySelector("#google-login").addEventListener("click", signInWithGoogle);
document.querySelector("#close-login").addEventListener("click", closeLogin);
menuButton.addEventListener("click", () => { const expanded = menuButton.getAttribute("aria-expanded") === "true"; menuButton.setAttribute("aria-expanded", String(!expanded)); menuPanel.hidden = expanded; });
document.querySelector("#profile-button").addEventListener("click", () => openProfile(false));
document.querySelector("#ranking-button").addEventListener("click", openRanking);
themeToggle?.addEventListener("click", () => {
  setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
});
languageSelect?.addEventListener("change", (event) => setLanguage(event.target.value));
document.querySelectorAll(".market-tab").forEach((button) => {
  button.addEventListener("click", () => {
    marketSort = button.dataset.marketSort || "volume";
    renderStocks(currentStocks);
  });
});
document.querySelector("#event-list")?.addEventListener("click", (event) => {
  const button = event.target.closest("[data-news-index]");
  if (button) openNewsModal(currentEvents[Number(button.dataset.newsIndex)]);
});
document.querySelector("#detail-events").addEventListener("click", (event) => {
  const button = event.target.closest("[data-detail-news-index]");
  if (button) openNewsModal(currentDetailNews[Number(button.dataset.detailNewsIndex)]);
});
document.querySelector("#news-filter-tabs")?.addEventListener("click", (event) => {
  const button = event.target.closest("[data-news-filter]");
  if (!button) return;
  newsFilter = button.dataset.newsFilter || "latest";
  document.querySelectorAll("[data-news-filter]").forEach((tab) => {
    const active = tab.dataset.newsFilter === newsFilter;
    tab.classList.toggle("active", active); tab.setAttribute("aria-selected", String(active));
  });
  if (loadedNewsCode) renderStockNews(currentDetailNews, loadedNewsCode);
});
document.querySelector("#detail-market-tabs")?.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-detail-tab]");
  if (!button) return;
  detailMarketTab = button.dataset.detailTab || "book";
  if (detailMarketTab === "orders" && currentUser) {
    try { currentOpenOrders = await api("/api/orders"); } catch { currentOpenOrders = []; }
  }
  renderDetailOpenOrders(currentStockCode());
  renderDetailTabs();
});
document.querySelector("#watchlist-button")?.addEventListener("click", async () => {
  const code = currentStockCode(); if (!code) return;
  if (!currentUser) { openLogin(); return; }
  try {
    if (watchlist.has(code)) { await api(`/api/watchlist/${encodeURIComponent(code)}`, { method: "DELETE" }); watchlist.delete(code); }
    else { await api(`/api/watchlist/${encodeURIComponent(code)}`, { method: "PUT" }); watchlist.add(code); }
  } catch (error) { window.alert(error.message); return; }
  renderWatchlistButton(code); renderStocks(currentStocks);
});
document.querySelector("#stock-info-button")?.addEventListener("click", () => {
  const panel = document.querySelector("#stock-info-panel"); if (panel) panel.hidden = !panel.hidden;
});
document.querySelector("#close-stock-info")?.addEventListener("click", () => { const panel = document.querySelector("#stock-info-panel"); if (panel) panel.hidden = true; });
document.querySelector("#price-alert-button")?.addEventListener("click", async () => {
  const code = currentStockCode(); const stock = currentStocks.find((item) => item.code === code); if (!stock) return;
  if (!currentUser) { openLogin(); return; }
  const value = window.prompt(`알림 받을 가격을 입력하세요 (현재 ${stock.price})`, String(stock.price));
  const target = Number(value); if (!Number.isFinite(target) || target <= 0) return;
  try {
    await api("/api/price-alerts", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ stockCode: code, targetPrice: target }) });
    window.alert(`${stock.name} 가격 ${money.format(target)} 알림 조건을 서버에 저장했어.`);
  } catch (error) { window.alert(error.message); }
});
document.querySelector("#detail-genre")?.addEventListener("click", (event) => {
  const button = event.target.closest("[data-add-tag]"); if (!button) return;
  if (!currentUser) { openLogin(); return; }
  const tag = window.prompt("추가할 태그를 입력하세요"); if (!tag?.trim()) return;
  api(`/api/stocks/${encodeURIComponent(button.dataset.addTag)}/tags`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ tag: tag.trim() }) })
    .then((created) => { const tags = tagCache.get(button.dataset.addTag) || []; tagCache.set(button.dataset.addTag, [...tags, created]); renderStockTags(button.dataset.addTag); })
    .catch((error) => window.alert(error.message));
});
document.querySelector("#close-news").addEventListener("click", closeNewsModal);
newsModal.addEventListener("click", (event) => { if (event.target === newsModal) closeNewsModal(); });
document.addEventListener("keydown", (event) => { if (event.key === "Escape") closeNewsModal(); });
document.querySelector("#logout-button").addEventListener("click", async () => { closeMenu(); await firebaseAuth.signOut(); currentUser = null; currentProfile = null; currentOpenOrders = []; currentSettlements = []; profileRequired = false; watchlist = new Set(); if (location.hash === "#profile") location.hash = ""; updateLoginButton(); renderPortfolio(null); await refreshMarket(); });
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
resetAccountButton?.addEventListener("click", async () => {
  if (!currentUser || currentProfile?.resetAvailable === false) return;
  if (!window.confirm("보유 주식, 주문, 출석 기록이 모두 초기화됩니다. 이 기능은 Google 계정당 한 번만 사용할 수 있습니다. 정말 진행할까요?")) return;
  resetAccountButton.disabled = true;
  resetAccountMessage.className = "message";
  resetAccountMessage.textContent = "계정을 초기화하는 중입니다…";
  try {
    await api("/api/account/reset", { method: "DELETE" });
    currentUser = { ...currentUser, nickname: "", requiresNickname: true };
    currentProfile = null;
    currentOpenOrders = [];
    currentSettlements = [];
    profileRequired = true;
    await refreshMarket();
    await openProfile(true);
  } catch (error) {
    resetAccountButton.disabled = false;
    resetAccountMessage.className = "message error";
    resetAccountMessage.textContent = error.message;
  }
});
// 초기 상태(비로그인)에서도 메뉴와 기기별 화면 설정을 즉시 표시한다.
setTheme(document.documentElement.dataset.theme, false);
setLanguage(currentLanguage, false);
updateLoginButton();
if (window.GAMESTOCK_FIREBASE_CONFIG && window.firebase) {
  firebase.initializeApp(window.GAMESTOCK_FIREBASE_CONFIG);
  firebaseAuth = firebase.auth();
  firebaseAuth.onAuthStateChanged(async (firebaseUser) => {
    if (!firebaseUser) return;
    try {
      const response = await fetch(`${API_BASE_URL}/api/auth/google`, { method: "POST", headers: { Authorization: `Bearer ${await firebaseUser.getIdToken()}` } });
      if (!response.ok) throw new Error();
      await handleAuthenticatedUser(await response.json());
    } catch (error) { currentUser = null; updateLoginButton(); const status = document.querySelector("#server-status"); if (status) status.textContent = error.message || "로그인 처리 실패"; }
  });
}

api("/api/health")
  .then(() => connectRealtimeMarket())
  .catch(() => {
    const status = document.querySelector("#server-status");
    if (status) status.textContent = t("서버 연결 실패");
  });
refreshMarket().catch((error) => {
  stockContainer.textContent = `시장 정보를 불러오지 못했습니다: ${error.message}`;
});

async function loadHanRiverTemperature() {
  const element = document.querySelector("#han-river-temperature");
  if (!element) return;
  try {
    const reading = await api("/api/han-river-temperature");
    if (reading.available && reading.temperature != null) {
      const stale = String(reading.message || "").includes("최근 조회값");
      element.className = `menu-river-temperature ${stale ? "stale" : "ok"}`;
      element.textContent = `${t("한강 수온(선유)")} ${Number(reading.temperature).toFixed(1)}℃`;
      element.title = `${reading.location || "한강"} · 측정 ${reading.measuredAt || "시간 미상"} · ${reading.message || "조회 완료"}`;
    } else {
      element.className = "menu-river-temperature";
      element.textContent = "한강 수온(선유) 조회 불가";
      element.title = reading.message || "한강 수온 사이트를 확인할 수 없습니다.";
    }
  } catch (error) {
    element.className = "menu-river-temperature";
    element.textContent = "한강 수온(선유) 조회 불가";
    element.title = "한강 수온 사이트를 확인할 수 없습니다.";
  }
}
loadHanRiverTemperature();
setInterval(loadHanRiverTemperature, 30 * 60 * 1000);
