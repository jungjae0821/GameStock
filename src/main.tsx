import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./styles/index.css";
import App from "./App";
import { MarketProvider } from "./market/MarketProvider";

// React가 첫 화면을 그리기 전에 저장된 테마를 적용해 다크모드 전환 시 깜빡임을 줄인다.
const storedTheme = window.localStorage.getItem("gamestock-theme");
document.documentElement.dataset.theme = storedTheme === "dark" ? "dark" : "light";
document.documentElement.style.colorScheme = storedTheme === "dark" ? "dark" : "light";

const container = document.getElementById("root");
if (!container) throw new Error("#root 컨테이너를 찾을 수 없습니다.");

createRoot(container).render(
  <StrictMode>
    <MarketProvider>
      <App />
    </MarketProvider>
  </StrictMode>,
);
