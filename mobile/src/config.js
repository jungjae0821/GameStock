// 배포 시 EXPO_PUBLIC_API_BASE_URL에 공개 서버 주소를 지정한다.
// 예: EXPO_PUBLIC_API_BASE_URL=https://api.example.com
export const API_BASE_URL =
  process.env.EXPO_PUBLIC_API_BASE_URL || "http://192.168.45.50:8081";
export const MARKET_SOCKET_URL =
  API_BASE_URL.replace(/^http/, "ws") + "/ws/market";

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
