import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./styles/index.css";
import App from "./App";
import { MarketProvider } from "./market/MarketProvider";

const container = document.getElementById("root");
if (!container) throw new Error("#root 컨테이너를 찾을 수 없습니다.");

createRoot(container).render(
  <StrictMode>
    <MarketProvider>
      <App />
    </MarketProvider>
  </StrictMode>,
);
