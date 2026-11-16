import type { Page, Route, WebSocketRoute } from "@playwright/test";
import { LISTINGS } from "../src/market/universe";

/**
 * E2E용 가짜 백엔드. 실제 Spring Boot·Firebase 없이 화면 흐름만 검증한다.
 * - API_ORIGIN: playwright.config.ts의 VITE_API_BASE_URL과 같아야 한다.
 * - AUTH_ORIGIN: firebase.ts가 VITE_FIREBASE_EMULATOR=true일 때 붙는 Auth 에뮬레이터 주소.
 * 모르는 API 경로는 404로 답하고 unhandled에 남긴다. 테스트는 이것이 비어 있는지 볼 수 있다.
 */
const API_ORIGIN = "http://127.0.0.1:4599";
const AUTH_ORIGIN = "http://127.0.0.1:9099";

export const TEST_USER = { uid: "e2e-user-1", email: "player@e2e.test", password: "e2e-password-1", nickname: "테스트투자자" };

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
export type OrderBody = { stockCode: string; side: string; quantity: number; orderType: string; price?: number };

const today = () => new Date().toISOString().slice(0, 10);
const isoAgo = (seconds: number) => new Date(Date.now() - seconds * 1000).toISOString();

function stock(code: string) {
  const listing = LISTINGS.find((item) => item.code === code)!;
  const reference = listing.prevClose;
  return {
    code: listing.code,
    name: listing.name,
    genre: listing.genre,
    price: reference,
    changePercent: 0,
    volume: 1200,
    referencePrice: reference,
    limitUp: Math.round(reference * 1.3),
    limitDown: Math.round(reference * 0.7),
    restriction: null,
  };
}

function orderBook(code: string) {
  const price = stock(code).price;
  const tick = Math.max(1, Math.round(price * 0.001));
  const levels = (sign: 1 | -1) => Array.from({ length: 5 }, (_, i) => ({ price: price + sign * tick * (i + 1), quantity: 10 + i * 5, orderCount: 1 + i }));
  return { stockCode: code, bids: levels(-1), asks: levels(1) };
}

/** Auth 에뮬레이터 토큰은 서명 없는 JWT다. Firebase SDK는 서명을 검사하지 않고 만료·사용자만 읽는다. */
function idToken() {
  const now = Math.floor(Date.now() / 1000);
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: "https://securetoken.google.com/gamestock-e2e",
    aud: "gamestock-e2e",
    auth_time: now,
    user_id: TEST_USER.uid,
    sub: TEST_USER.uid,
    iat: now,
    exp: now + 3600,
    email: TEST_USER.email,
    email_verified: true,
    firebase: { identities: { email: [TEST_USER.email] }, sign_in_provider: "password" },
  })}.`;
}

export class MockBackend {
  readonly unhandled: string[] = [];
  readonly orders: OrderBody[] = [];
  /** 다음 주문들에 돌려줄 응답. 비어 있으면 정상 접수로 답한다. */
  readonly orderReplies: Reply[] = [];
  /** "GET /api/..." 형태의 키로 특정 응답을 덮어쓴다. 서버가 예상과 다른 모양을 보낸 상황을 흉내 낼 때 쓴다. */
  readonly overrides = new Map<string, Reply>();

  static async install(page: Page): Promise<MockBackend> {
    const backend = new MockBackend();
    await page.route("**/*", (route) => backend.handle(route));
    await page.routeWebSocket(/\/ws\/stream/, (socket) => backend.stream(socket));
    return backend;
  }

  private async handle(route: Route) {
    const url = new URL(route.request().url());
    if (url.origin === API_ORIGIN) return this.reply(route, this.api(route.request().method(), url, route.request().postData()));
    if (url.origin === AUTH_ORIGIN) return this.reply(route, this.auth(url));
    if (url.hostname === "127.0.0.1" || url.hostname === "localhost") return route.continue();
    // 외부 글꼴·이미지·분석 요청은 끊어 테스트가 네트워크 상태에 흔들리지 않게 한다.
    return route.abort();
  }

  private reply(route: Route, reply: Reply) {
    return route.fulfill({
      status: reply.status,
      contentType: "application/json",
      headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "*", ...reply.headers },
      body: reply.body === undefined ? "" : JSON.stringify(reply.body),
    });
  }

  private api(method: string, url: URL, body: string | null): Reply {
    if (method === "OPTIONS") return { status: 204 };
    const path = url.pathname;
    const key = `${method} ${path}`;
    const override = this.overrides.get(key);
    if (override) return override;
    const stockPath = path.match(/^\/api\/stocks\/([A-Z]+)\/(orderbook|trades)$/);
    if (stockPath && LISTINGS.some((item) => item.code === stockPath[1])) {
      const [, code, kind] = stockPath;
      if (kind === "orderbook") return { status: 200, body: orderBook(code) };
      return { status: 200, body: [0, 1, 2].map((i) => ({ side: i % 2 ? "SELL" : "BUY", quantity: 3 + i, price: stock(code).price, orderType: "MARKET", createdAt: isoAgo(10 + i * 7) })) };
    }
    switch (key) {
      case "GET /api/stocks": return { status: 200, body: LISTINGS.map((item) => stock(item.code)) };
      case "GET /api/market-status": return { status: 200, body: { indexValue: 1000, indexPrevClose: 990, tradingDate: today(), restriction: null } };
      case "GET /api/market-events": return {
        status: 200,
        body: [{
          stockCode: "GI", title: "원신 신규 지역 업데이트 예고", description: "E2E 고정 뉴스", impact: 0.4, sentiment: "POSITIVE",
          source: "E2E", publishedAt: isoAgo(600), priceAtPublish: stock("GI").price, priceChangePercent: 0, priceDirection: "FLAT", excerpt: [], articleUrl: null,
        }],
      };
      case "GET /api/portfolio": return { status: 200, body: { cash: 1_000_000, positions: [], realizedProfitLoss: 0 } };
      // 공지 API가 실패하면 앱이 내장 대체 공지 창을 띄워 화면을 가린다. 빈 목록으로 답한다.
      case "GET /api/content":
      case "GET /api/settlements":
      case "GET /api/watchlist":
      case "GET /api/orders": return { status: 200, body: [] };
      case "GET /api/profile": return {
        status: 200,
        body: {
          nickname: TEST_USER.nickname, email: TEST_USER.email, profileImageUrl: null, profileCompleted: true, resetAvailable: true,
          nicknameChangeAvailable: true, nicknameChangeAvailableAt: null, admin: false, tradeBannedUntil: null, tradeBanReason: null,
        },
      };
      case "POST /api/orders": {
        const order = JSON.parse(body ?? "{}") as OrderBody;
        this.orders.push(order);
        return this.orderReplies.shift() ?? { status: 201, body: { message: `${order.quantity}주 ${order.side === "BUY" ? "매수" : "매도"} 주문이 접수됐어요.`, price: stock(order.stockCode).price } };
      }
    }
    this.unhandled.push(key);
    return { status: 404, body: { message: `E2E 모킹에 없는 API: ${key}` } };
  }

  private auth(url: URL): Reply {
    const path = url.pathname;
    const session = { idToken: idToken(), refreshToken: "e2e-refresh-token", expiresIn: "3600", localId: TEST_USER.uid, email: TEST_USER.email };
    if (path.endsWith("/accounts:signInWithPassword")) return { status: 200, body: { kind: "identitytoolkit#VerifyPasswordResponse", registered: true, displayName: "", ...session } };
    if (path.endsWith("/accounts:lookup")) {
      return {
        status: 200,
        body: {
          users: [{
            localId: TEST_USER.uid, email: TEST_USER.email, emailVerified: true, createdAt: "1700000000000", lastLoginAt: String(Date.now()),
            providerUserInfo: [{ providerId: "password", email: TEST_USER.email, federatedId: TEST_USER.email, rawId: TEST_USER.email }],
          }],
        },
      };
    }
    if (path.endsWith("/v1/token")) {
      return { status: 200, body: { access_token: session.idToken, id_token: session.idToken, refresh_token: session.refreshToken, expires_in: "3600", token_type: "Bearer", user_id: TEST_USER.uid } };
    }
    this.unhandled.push(`AUTH ${path}`);
    return { status: 400, body: { error: { code: 400, message: "UNSUPPORTED_IN_E2E" } } };
  }

  private stream(socket: WebSocketRoute) {
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw)) as { type: string };
      if (message.type === "PING") socket.send(JSON.stringify({ type: "PONG" }));
      if (message.type === "AUTH") socket.send(JSON.stringify({ type: "AUTHENTICATED" }));
    });
  }
}

/** 이메일 로그인 화면으로 로그인한다. 로그인 뒤 돌아갈 주소를 next로 준다. */
export async function signIn(page: Page, next = "/") {
  await page.goto(`/login/email?next=${encodeURIComponent(next)}`);
  const form = page.locator("form.login-form");
  await form.getByLabel("이메일").fill(TEST_USER.email);
  await form.getByLabel("비밀번호", { exact: true }).fill(TEST_USER.password);
  await form.getByRole("button", { name: "로그인", exact: true }).click();
}
