export function isTrustedAppUrl(value, appUrl) {
  try {
    const target = new URL(value);
    const app = new URL(appUrl);
    return ['https:', 'http:'].includes(target.protocol) && !target.username && !target.password && target.origin === app.origin;
  } catch { return false; }
}

export function authInjection(payload, appUrl) {
  const origin = JSON.stringify(new URL(appUrl).origin);
  const detail = JSON.stringify(payload);
  // Check in the document too: a navigation can race the native URL callback.
  return `if (window.top === window && window.location.origin === ${origin}) { window.dispatchEvent(new CustomEvent('gamestock-native-auth', {detail:${detail}})); } true;`;
}

/**
 * 앱 WebView가 이 주소를 직접 열어도 되는가. 뉴스 원문 같은 다른 사이트를 주소창 없는 앱 화면 안에서 열면
 * 어디에 있는지 알 수 없고 앱으로 돌아오기도 어렵다. 앱 출처 밖으로 가는 최상위 이동은 시스템 브라우저로 넘긴다.
 * - 'app': WebView에서 연다(앱 출처, 하위 프레임, about:·data:·blob:).
 * - 'external': 시스템 브라우저나 해당 앱(https·http·mailto·tel)으로 넘긴다.
 * - 'block': 열지 않는다(javascript: 등 그 밖의 스킴).
 */
export function navigationTarget(url, appUrl, isTopFrame = true) {
  if (isTopFrame === false) return 'app';
  let protocol;
  try { protocol = new URL(url).protocol; } catch { return 'block'; }
  if (['about:', 'data:', 'blob:'].includes(protocol)) return 'app';
  if (isTrustedAppUrl(url, appUrl)) return 'app';
  return ['https:', 'http:', 'mailto:', 'tel:'].includes(protocol) ? 'external' : 'block';
}
