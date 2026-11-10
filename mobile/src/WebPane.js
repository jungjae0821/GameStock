import React, { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WEB_APP_URL } from './config';
import { THEME_KEY, useTheme } from './theme';
import { authInjection, isTrustedAppUrl } from './web-origin';

const PRICE_ALERT_PERMISSION_KEY = 'gamestock-price-alert-permission-asked';
const SIGN_OUT_FLAG = 'gamestock-native-signout';

const withShell = path => `${path}${path.includes('?') ? '&' : '?'}app-shell=1`;

// 웹 문서가 그려지기 전에 실행한다. 웹이 처음부터 앱과 같은 밝기로 그려지게 테마 저장값을 덮는다.
const beforeLoad = scheme => `(function () {
  try { localStorage.setItem('${THEME_KEY}', '${scheme}'); } catch (error) {}
  // 앱이 로그아웃했으면 웹 Firebase가 열기 전에 저장소를 지운다. IndexedDB는 요청 순서대로 처리된다.
  try {
    if (localStorage.getItem('${SIGN_OUT_FLAG}')) {
      localStorage.removeItem('${SIGN_OUT_FLAG}');
      indexedDB.deleteDatabase('firebaseLocalStorageDb');
    }
  } catch (error) {}
  // 네이티브 탭바가 있으니 웹 상단의 이동 메뉴 줄만 숨긴다.
  var style = document.createElement('style');
  style.textContent = '.nx-top .nx-nav { display: none !important; }';
  (document.head || document.documentElement).appendChild(style);
  // 웹 라우터의 주소 변경을 앱에 알려 앱이 현재 탭을 맞춘다.
  function report() {
    if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'ROUTE', path: location.pathname }));
  }
  ['pushState', 'replaceState'].forEach(function (name) {
    var original = history[name];
    history[name] = function () { var result = original.apply(this, arguments); report(); return result; };
  });
  window.addEventListener('popstate', report);
})(); true;`;

/**
 * 아직 네이티브로 옮기지 않은 화면이 공유하는 WebView 하나. 앱 시작과 함께 뒤에서 불러 두고,
 * 탭을 바꿀 때는 새로 불러오지 않고 웹 라우터로 주소만 옮긴다. 로그인은 앱이 하고 웹에 넘긴다.
 */
export const WebPane = forwardRef(function WebPane({ visible, initialPath, scheme, onScheme, onRoute, onLoginRequest, onReady }, ref) {
  const insets = useSafeAreaInsets();
  const theme = useTheme();
  const webViewRef = useRef(null);
  const ready = useRef(false);
  const pendingToken = useRef(null);
  // 첫 로딩이 끝나기 전의 주입은 무시되므로, 마지막 이동 요청을 들고 있다가 로딩이 끝나면 보낸다.
  const pendingPath = useRef(null);
  const canGoBack = useRef(false);
  const startUrl = `${WEB_APP_URL}${withShell(initialPath)}`;

  const inject = script => webViewRef.current?.injectJavaScript(`${script}; true;`);
  // 토큰은 앱 주소의 최상위 문서에만 건넨다. 다른 사이트로 이동한 WebView에는 보내지 않는다.
  const sendToken = token => webViewRef.current?.injectJavaScript(authInjection({ type: 'FIREBASE_CUSTOM_TOKEN', customToken: token }, WEB_APP_URL));
  const navigateNow = path => {
    const target = JSON.stringify(withShell(path));
    inject(`if (location.pathname + location.search !== ${target}) { history.pushState(null, '', ${target}); window.dispatchEvent(new PopStateEvent('popstate')); window.scrollTo(0, 0); }`);
  };

  // 앱에서 밝기를 바꾸면 열려 있는 웹 화면도 다시 불러오지 않고 바로 바꾼다(웹 uiMode.ts가 이 이벤트를 받는다).
  const sentScheme = useRef(scheme);
  useEffect(() => {
    if (sentScheme.current === scheme) return;
    sentScheme.current = scheme;
    if (!ready.current) return;
    inject(`try { localStorage.setItem('${THEME_KEY}', '${scheme}'); } catch (error) {} window.dispatchEvent(new CustomEvent('gamestock-native-theme', { detail: '${scheme}' }))`);
  }, [scheme]); // eslint-disable-line react-hooks/exhaustive-deps

  useImperativeHandle(ref, () => ({
    navigate(path) {
      if (!ready.current) { pendingPath.current = path; return; }
      navigateNow(path);
    },
    goBack() {
      if (!canGoBack.current) return false;
      webViewRef.current?.goBack();
      return true;
    },
    /** 앱에서 로그인한 계정을 웹 화면에도 로그인시킨다(웹 AuthBridge가 커스텀 토큰으로 로그인한다). */
    signIn(token) {
      if (!ready.current) { pendingToken.current = token; return; }
      sendToken(token);
    },
    /** 웹 화면도 로그아웃시킨다. 다음 로딩 때 BEFORE_LOAD가 웹의 로그인 저장소를 지운다. */
    signOut() {
      pendingToken.current = null;
      inject(`try { localStorage.setItem('${SIGN_OUT_FLAG}', '1'); } catch (error) {} location.reload()`);
    },
  }), []);

  const handleMessage = event => {
    if (!isTrustedAppUrl(event.nativeEvent.url, WEB_APP_URL)) return;
    let message;
    try { message = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (message?.type === 'ROUTE') { onRoute?.(message.path); return; }
    // 웹 상단의 해·달 버튼으로 바꾼 밝기는 앱 전체에 따른다.
    if (message?.type === 'THEME') { sentScheme.current = message.theme; onScheme?.(message.theme); return; }
    // 웹 로그인 화면의 Google 버튼은 앱 로그인 시트로 넘긴다.
    if (message?.type === 'GOOGLE_LOGIN') { onLoginRequest?.(); return; }
    // 가격 알림을 처음 저장했을 때만 네이티브 알림 권한을 묻는다.
    if (message?.type === 'PRICE_ALERT_ADDED') {
      void (async () => {
        try {
          if (await AsyncStorage.getItem(PRICE_ALERT_PERMISSION_KEY) === '1') return;
          await AsyncStorage.setItem(PRICE_ALERT_PERMISSION_KEY, '1');
          await Notifications.requestPermissionsAsync();
        } catch {
          // 권한 확인 실패는 알림 저장 자체에 영향을 주지 않는다.
        }
      })();
    }
  };

  return (
    <View
      style={[StyleSheet.absoluteFill, { paddingTop: insets.top, backgroundColor: theme.ground }, !visible && styles.hidden]}
      pointerEvents={visible ? 'auto' : 'none'}
      importantForAccessibility={visible ? 'auto' : 'no-hide-descendants'}
      accessibilityElementsHidden={!visible}
    >
      <WebView
        ref={webViewRef}
        source={{ uri: startUrl }}
        style={{ flex: 1, backgroundColor: theme.ground }}
        startInLoadingState
        javaScriptEnabled
        domStorageEnabled
        cacheEnabled={false}
        cacheMode="LOAD_NO_CACHE"
        sharedCookiesEnabled
        thirdPartyCookiesEnabled
        injectedJavaScriptBeforeContentLoaded={beforeLoad(scheme)}
        onNavigationStateChange={state => { canGoBack.current = state.canGoBack; }}
        onLoadStart={() => { ready.current = false; }}
        onLoadEnd={() => {
          ready.current = true;
          // 불러오는 사이에 밝기를 바꿨을 수 있어 끝날 때 한 번 더 맞춘다.
          inject(`window.dispatchEvent(new CustomEvent('gamestock-native-theme', { detail: '${sentScheme.current}' }))`);
          if (pendingPath.current) {
            const path = pendingPath.current;
            pendingPath.current = null;
            navigateNow(path);
          }
          if (pendingToken.current) {
            const token = pendingToken.current;
            pendingToken.current = null;
            sendToken(token);
          }
          onReady?.();
        }}
        onMessage={handleMessage}
      />
    </View>
  );
});

// 숨긴 동안에도 WebView를 살려 두어야 웹 화면의 위치가 유지된다.
const styles = StyleSheet.create({ hidden: { opacity: 0, zIndex: -1 } });
