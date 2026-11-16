import { defineConfig, devices } from "@playwright/test";

/**
 * 화면 E2E. 실제 백엔드와 Firebase 없이 돈다.
 * API(127.0.0.1:4599)와 Firebase Auth 에뮬레이터 주소(127.0.0.1:9099)로 가는 요청은
 * e2e/mockBackend.ts가 가로채 고정 응답을 준다. 두 포트에 실제로 떠 있는 서버는 없다.
 */
const PORT = 4173;
export const E2E_API_BASE = "http://127.0.0.1:4599";

export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}`,
    // 이미 떠 있는 개발 서버는 다른 API 주소로 빌드됐을 수 있어 재사용하지 않는다.
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      VITE_API_BASE_URL: E2E_API_BASE,
      VITE_FIREBASE_EMULATOR: "true",
      VITE_FIREBASE_API_KEY: "e2e-api-key",
      VITE_FIREBASE_AUTH_DOMAIN: "e2e.firebaseapp.com",
      VITE_FIREBASE_PROJECT_ID: "gamestock-e2e",
      VITE_FIREBASE_APP_ID: "1:000000000000:web:e2e",
    },
  },
});
