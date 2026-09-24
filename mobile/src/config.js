// 앱은 웹과 동일한 Railway 백엔드를 기본으로 사용한다.
// 로컬 Spring Boot를 테스트할 때만 EXPO_PUBLIC_API_BASE_URL에
// PC의 LAN 주소를 지정해 덮어쓴다.
export const API_BASE_URL =
  process.env.EXPO_PUBLIC_API_BASE_URL || "https://gamestock-production.up.railway.app";
export const MARKET_SOCKET_URL =
  API_BASE_URL.replace(/^http/, "ws") + "/ws/market";

// 모바일 앱은 웹 클라이언트를 그대로 보여주므로, 기본값은 Firebase Hosting 주소를 사용한다.
// 로컬 웹 화면을 확인할 때는 EXPO_PUBLIC_WEB_APP_URL에 PC의 LAN 주소를 지정한다.
export const WEB_APP_URL =
  process.env.EXPO_PUBLIC_WEB_APP_URL || "https://gamestock-20994.web.app";

// 웹 Firebase 설정과 동일한 프로젝트를 사용한다.
export const FIREBASE_CONFIG = {
  apiKey: "AIzaSyAzUEj5xwH14p3BBj7RwJ92tj9eoM9cuKI",
  authDomain: "gamestock-20994.firebaseapp.com",
  projectId: "gamestock-20994",
  storageBucket: "gamestock-20994.firebasestorage.app",
  messagingSenderId: "957680390567",
  appId: "1:957680390567:web:8f0d60ea888daae68e2682",
};
// Firebase 콘솔의 Google OAuth 웹 클라이언트 ID를 지정해야 네이티브 로그인이 작동한다.
export const GOOGLE_WEB_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID || "";
export const GOOGLE_IOS_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID || "";
export const GOOGLE_ANDROID_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID || "";
