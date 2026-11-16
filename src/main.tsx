import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./styles/index.css";
import App from "./App";
import { AppErrorFallback, ErrorBoundary } from "./components/ErrorBoundary";
import { MarketProvider } from "./market/MarketProvider";

// The supplied UI uses a light paper palette, including native form controls.
document.documentElement.dataset.theme = "light";
document.documentElement.style.colorScheme = "light";
document.documentElement.dataset.appShell = new URLSearchParams(window.location.search).get("app-shell") === "1" ? "mobile" : "web";
if (document.documentElement.dataset.appShell === "mobile") {
  window.history.scrollRestoration = "manual";
} else {
  /* 휴대폰 브라우저의 아래 탭바가 홈 표시줄 높이(safe-area)를 알 수 있게 한다. 앱 WebView는 네이티브가 여백을 잡으므로 건드리지 않는다. */
  const viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]');
  if (viewport && !viewport.content.includes("viewport-fit")) viewport.content += ", viewport-fit=cover";
}

const container = document.getElementById("root");
if (!container) throw new Error("#root 컨테이너를 찾을 수 없습니다.");

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary area="app" fallback={<AppErrorFallback />}>
      <MarketProvider>
        <App />
      </MarketProvider>
    </ErrorBoundary>
  </StrictMode>,
);
