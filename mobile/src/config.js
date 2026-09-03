// 실기기에서는 이 주소를 개발 PC의 같은 Wi-Fi IPv4 주소로 변경하세요.
// 예: http://192.168.0.15:8081
export const API_BASE_URL = 'http://192.168.45.50:8081';
export const MARKET_SOCKET_URL = API_BASE_URL.replace(/^http/, 'ws') + '/ws/market';
