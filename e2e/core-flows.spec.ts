import { expect, test } from "@playwright/test";
import { MockBackend, signIn } from "./mockBackend";

/* 발표 시연에서 끊기면 안 되는 흐름: 시세 보기 → 로그인 → 주문 → 결과 안내. 백엔드는 e2e/mockBackend.ts가 대신한다. */

test.describe("비로그인", () => {
  test("시장 목록과 종목 화면이 열린다", async ({ page, isMobile }) => {
    await MockBackend.install(page);
    await page.goto("/market");
    await expect(page.getByText("원신").first()).toBeVisible();

    await page.goto("/market/GI");
    await expect(page.getByRole("combobox", { name: "종목 선택" })).toHaveValue("GI");
    // 휴대폰 폭에서는 주문 입력이 처음부터 펼쳐져 있지 않다.
    if (isMobile) return;
    await expect(page.getByRole("form", { name: "주문 입력" })).toBeVisible();
    await expect(page.getByText("로그인 후 주문할 수 있습니다.")).toBeVisible();
  });

  test("로그인이 필요한 화면은 안내를 보여 준다", async ({ page }) => {
    await MockBackend.install(page);
    await page.goto("/mypage");
    await expect(page.getByRole("heading", { name: "로그인이 필요한 화면이에요" })).toBeVisible();
  });

  test("없는 주소는 안내 화면으로 간다", async ({ page }) => {
    await MockBackend.install(page);
    await page.goto("/no-such-screen");
    await expect(page.getByRole("heading", { name: "없는 화면이에요" })).toBeVisible();
  });
});

test.describe("로그인 후 주문", () => {
  // 휴대폰 폭에서는 주문 입력이 시트로 바뀌어 별도 흐름이다. 여기서는 데스크톱 주문 입력만 본다.
  test.skip(({ isMobile }) => isMobile, "데스크톱 주문 입력 전용");

  test("시장가 매수 주문이 서버로 전송되고 결과가 보인다", async ({ page }) => {
    const backend = await MockBackend.install(page);
    await signIn(page, "/market/GI");
    await expect(page).toHaveURL(/\/market\/GI/);

    const ticket = page.getByRole("form", { name: "주문 입력" });
    await ticket.getByRole("button", { name: "시장가" }).click();
    await ticket.getByLabel("수량").fill("2");
    await ticket.locator("button[type=submit]").click();

    await expect(ticket.getByRole("status").last()).toContainText("주문이 접수됐어요");
    expect(backend.orders).toEqual([expect.objectContaining({ stockCode: "GI", side: "BUY", quantity: 2, orderType: "MARKET" })]);
  });

  test("주문이 너무 잦아 서버가 429로 막으면 그 이유를 보여 준다", async ({ page }) => {
    const backend = await MockBackend.install(page);
    backend.orderReplies.push({
      status: 429,
      headers: { "Retry-After": "3" },
      body: { message: "주문 요청이 너무 잦습니다. 3초 후에 다시 시도해 주세요." },
    });
    await signIn(page, "/market/GI");

    const ticket = page.getByRole("form", { name: "주문 입력" });
    await ticket.getByRole("button", { name: "시장가" }).click();
    await ticket.getByLabel("수량").fill("1");
    await ticket.locator("button[type=submit]").click();

    await expect(ticket.getByRole("status").last()).toContainText("주문 요청이 너무 잦습니다");
  });
});

test.describe("오류 경계", () => {
  test("한 화면이 예상 밖 응답으로 고장 나도 메뉴는 살아 있고, 다른 화면으로 옮기면 회복한다", async ({ page }) => {
    const backend = await MockBackend.install(page);
    // 랭킹은 배열을 기대한다. 서버가 객체를 보내면 렌더링 중 예외가 난다.
    for (const path of ["/api/ranking", "/api/ranking/wealth", "/api/titles/weekly-ranking"])
      backend.overrides.set(`GET ${path}`, { status: 200, body: { unexpected: true } });
    const errors: string[] = [];
    page.on("console", (message) => { if (message.type() === "error") errors.push(message.text()); });

    await page.goto("/ranking");
    await expect(page.getByTestId("screen-error")).toBeVisible();
    await expect(page.getByRole("heading", { name: "이 화면을 불러오지 못했어요" })).toBeVisible();
    expect(errors.some((text) => text.includes("[ErrorBoundary:screen]"))).toBe(true);

    // 상단·하단 메뉴는 그대로 있어 다른 화면으로 갈 수 있다.
    await page.locator('a[href="/market"]:visible').first().click();
    await expect(page.getByTestId("screen-error")).toHaveCount(0);
    await expect(page.getByText("원신").first()).toBeVisible();
  });
});
