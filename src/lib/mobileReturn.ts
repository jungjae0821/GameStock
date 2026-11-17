export type MobileReturn = { uri: string; state: string; codeChallenge: string };

/**
 * 앱이 웹 로그인을 연 경우(mobileAuth=1), 로그인 코드를 돌려보낼 앱 주소를 고른다.
 *
 * 로그인 코드는 이 주소로 넘어가므로 아무 주소나 받으면 공격자가 만든 링크로 남의 계정 코드를 가져갈 수 있다.
 * - 운영: 앱 자체 스킴(gamestock://)만 받는다. Expo Go 주소(exp://호스트)는 누구나 자기 컴퓨터를 가리키게 만들 수 있다.
 * - 개발 서버: Expo Go로 확인할 수 있게 exp://도 받는다.
 * - 앱이 만든 PKCE code_challenge가 없으면 받지 않는다. 서버는 그 값의 검증값을 낸 앱에만 토큰을 준다.
 */
export function mobileReturnTarget(params: URLSearchParams, allowExpoGo: boolean): MobileReturn | null {
  if (params.get("mobileAuth") !== "1") return null;
  const uri = params.get("returnUri");
  const state = params.get("state");
  const codeChallenge = params.get("codeChallenge");
  if (!uri || !state || !codeChallenge || !/^[A-Za-z0-9_-]{43}$/.test(codeChallenge)) return null;
  let protocol: string;
  try {
    protocol = new URL(uri).protocol;
  } catch {
    return null;
  }
  return protocol === "gamestock:" || (allowExpoGo && protocol === "exp:") ? { uri, state, codeChallenge } : null;
}
