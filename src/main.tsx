import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./styles/index.css";
import App from "./App";
import { MarketProvider } from "./market/MarketProvider";

// The supplied UI uses a light paper palette, including native form controls.
document.documentElement.dataset.theme = "light";
document.documentElement.style.colorScheme = "light";
document.documentElement.dataset.appShell = new URLSearchParams(window.location.search).get("app-shell") === "1" ? "mobile" : "web";
if (document.documentElement.dataset.appShell === "mobile") {
  window.history.scrollRestoration = "manual";
}

const container = document.getElementById("root");
if (!container) throw new Error("#root 컨테이너를 찾을 수 없습니다.");

createRoot(container).render(
  <StrictMode>
    <MarketProvider>
      <App />
    </MarketProvider>
  </StrictMode>,
);
