// 배포 시 EXPO_PUBLIC_API_BASE_URL에 공개 서버 주소를 지정한다.
// 예: EXPO_PUBLIC_API_BASE_URL=https://api.example.com
export const API_BASE_URL =
  process.env.EXPO_PUBLIC_API_BASE_URL || "http://192.168.45.50:8081";
export const MARKET_SOCKET_URL =
  API_BASE_URL.replace(/^http/, "ws") + "/ws/market";
