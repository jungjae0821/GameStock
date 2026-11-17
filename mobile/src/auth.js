import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Linking, Platform } from 'react-native';
import * as AuthSession from 'expo-auth-session';
import * as Crypto from 'expo-crypto';
import * as WebBrowser from 'expo-web-browser';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { initializeApp } from 'firebase/app';
import {
  createUserWithEmailAndPassword, deleteUser, getAuth, getReactNativePersistence, GoogleAuthProvider, initializeAuth, onIdTokenChanged,
  sendPasswordResetEmail, signInWithCredential, signInWithCustomToken, signInWithEmailAndPassword, signOut as firebaseSignOut, validatePassword,
} from 'firebase/auth';
import { API_BASE_URL, FIREBASE_CONFIG, GOOGLE_IOS_CLIENT_ID, GOOGLE_WEB_CLIENT_ID, WEB_APP_URL } from './config';

// 네이티브 Google 로그인. Android는 Play 서비스 계정 선택 창, iOS는 Google SDK가 띄우는
// ASWebAuthenticationSession("…을(를) 사용하려고 합니다" 팝업)을 쓴다.
// Expo Go처럼 네이티브 모듈이 없거나 클라이언트 ID가 비어 있으면 웹 /login/google 흐름으로 돌아간다.
let GoogleSignin = null;
try { ({ GoogleSignin } = require('@react-native-google-signin/google-signin')); } catch { GoogleSignin = null; }
const nativeGoogle = Boolean(GoogleSignin && GOOGLE_WEB_CLIENT_ID && (Platform.OS === 'android' || GOOGLE_IOS_CLIENT_ID));
if (nativeGoogle) GoogleSignin.configure({ webClientId: GOOGLE_WEB_CLIENT_ID, iosClientId: GOOGLE_IOS_CLIENT_ID || undefined });

const firebaseApp = initializeApp(FIREBASE_CONFIG);
let firebaseAuth;
try { firebaseAuth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) }); }
catch { firebaseAuth = getAuth(firebaseApp); }

const MOBILE_AUTH_STATE_KEY = 'gamestock-mobile-auth-state';
const MOBILE_AUTH_VERIFIER_KEY = 'gamestock-mobile-auth-verifier';

/**
 * PKCE(RFC 7636 S256). 로그인 코드는 딥링크로 돌아오므로 같은 스킴을 등록한 다른 앱이 가로챌 수 있다.
 * 검증값은 이 기기에만 두고 웹·서버에는 그 해시(challenge)만 보낸다. 서버는 검증값을 낸 쪽에만 토큰을 준다.
 */
async function pkcePair() {
  // UUID 두 개의 16진수 64자. PKCE 검증값 허용 문자와 길이(43~128자)에 맞는다.
  const verifier = `${Crypto.randomUUID()}${Crypto.randomUUID()}`.replace(/-/g, '');
  const digest = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, verifier, { encoding: Crypto.CryptoEncoding.BASE64 });
  return { verifier, challenge: digest.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') };
}

export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

const REQUEST_TIMEOUT_MS = 15_000;

/** 서버 요청. 로그인 상태면 Firebase ID 토큰을 붙인다. 서버의 오류 문구가 있으면 그대로 돌려준다. */
export async function request(path, { method = 'GET', body, auth = true, headers: extraHeaders } = {}) {
  const user = firebaseAuth.currentUser;
  const headers = { Accept: 'application/json', ...extraHeaders };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && user) headers.Authorization = `Bearer ${await user.getIdToken()}`;
  // 응답이 오지 않는 요청을 끝없이 기다리지 않는다. 웹 apiFetch와 같은 15초.
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal });
  } catch (error) {
    if (controller.signal.aborted) throw new Error('서버 응답이 지연되고 있어요. 주문 내역을 확인한 뒤 다시 시도해 주세요.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) throw new ApiError((data && data.message) || '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.', response.status);
  return data;
}

/** 웹 lib/auth.ts authErrorMessage와 같은 문구. */
export function authErrorMessage(error) {
  const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : '';
  switch (code) {
    case 'auth/invalid-email': return '이메일 주소를 확인해 주세요.';
    case 'auth/invalid-credential':
    case 'auth/user-not-found':
    case 'auth/wrong-password': return '이메일 또는 비밀번호를 확인해 주세요.';
    case 'auth/email-already-in-use': return '이미 사용 중인 이메일이에요. 로그인하거나 비밀번호를 재설정해 주세요.';
    case 'auth/weak-password':
    case 'auth/password-does-not-meet-requirements': return '비밀번호가 가입 조건에 맞지 않아요. 더 길고 복잡한 비밀번호를 입력해 주세요.';
    case 'auth/account-exists-with-different-credential': return '같은 이메일로 가입한 다른 로그인 방법을 사용해 주세요.';
    case 'auth/operation-not-allowed': return '이 로그인 방법은 지금 쓸 수 없어요. 다른 방법을 선택해 주세요.';
    case 'auth/too-many-requests': return '요청이 많아 잠시 제한됐어요. 잠시 후 다시 시도해 주세요.';
    case 'auth/user-disabled': return '이 계정은 지금 로그인할 수 없어요.';
    case 'auth/network-request-failed':
    case 'NETWORK_ERROR': return '연결을 확인한 뒤 다시 시도해 주세요.';
    case 'PLAY_SERVICES_NOT_AVAILABLE': return '이 기기에서 Google Play 서비스를 쓸 수 없어요. 이메일로 로그인해 주세요.';
    default: return error?.message && !code ? error.message : '로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

const AuthContext = createContext(null);
export const useAuth = () => useContext(AuthContext);

/**
 * 앱 로그인 상태. 로그인하면 웹과 같이 /api/auth/login으로 거래 계정을 연결하고,
 * WebView로 여는 웹 화면도 같은 계정이 되도록 커스텀 토큰을 onWebToken으로 넘긴다.
 */
export function AuthProvider({ children, onWebToken, onWebSignOut }) {
  const [user, setUser] = useState(firebaseAuth.currentUser);
  const [ready, setReady] = useState(false);
  const [profile, setProfile] = useState(null);
  const [googleError, setGoogleError] = useState('');
  const pendingState = useRef(null);
  const pendingVerifier = useRef(null);
  const handledCodes = useRef(new Set());
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'gamestock', path: 'auth/callback' });
  const webTokenRef = useRef(onWebToken);
  webTokenRef.current = onWebToken;

  const loadProfile = useCallback(async () => {
    try { setProfile(await request('/api/profile')); } catch { /* 프로필은 화면마다 다시 읽는다. */ }
  }, []);

  useEffect(() => onIdTokenChanged(firebaseAuth, next => {
    setUser(next);
    setReady(true);
    if (next) void loadProfile(); else setProfile(null);
  }), [loadProfile]);

  /** WebView의 웹 화면을 앱과 같은 계정으로 로그인시킨다. 서버가 일회용 코드를 커스텀 토큰으로 바꿔 준다. */
  const syncWeb = useCallback(async customToken => {
    if (!firebaseAuth.currentUser) return;
    let token = customToken;
    if (!token) {
      const pkce = await pkcePair();
      const { code } = await request('/api/auth/mobile/issue', { method: 'POST', body: { codeChallenge: pkce.challenge } });
      ({ customToken: token } = await request('/api/auth/mobile/exchange', { method: 'POST', body: { code, codeVerifier: pkce.verifier }, auth: false }));
    }
    if (token) webTokenRef.current?.(token);
  }, []);

  /** 서버 거래 계정 연결(웹과 같은 /api/auth/login)과 웹 화면 로그인. */
  const connect = useCallback(async customToken => {
    await request('/api/auth/login', { method: 'POST' });
    await syncWeb(customToken).catch(() => {});
    await loadProfile();
  }, [loadProfile, syncWeb]);

  const signInEmail = useCallback(async (email, password) => {
    await signInWithEmailAndPassword(firebaseAuth, email.trim(), password);
    await connect();
  }, [connect]);

  const register = useCallback(async (email, password) => {
    const validation = await validatePassword(firebaseAuth, password);
    if (!validation.isValid) {
      const missing = [
        validation.meetsMinPasswordLength === false ? `${validation.passwordPolicy.customStrengthOptions.minPasswordLength}자 이상` : '',
        validation.containsLowercaseLetter === false ? '영문 소문자' : '',
        validation.containsUppercaseLetter === false ? '영문 대문자' : '',
        validation.containsNumericCharacter === false ? '숫자' : '',
        validation.containsNonAlphanumericCharacter === false ? '특수문자' : '',
      ].filter(Boolean);
      throw new Error(missing.length ? `비밀번호 조건을 확인해 주세요: ${missing.join(', ')}` : '비밀번호가 가입 조건에 맞지 않아요.');
    }
    await createUserWithEmailAndPassword(firebaseAuth, email.trim(), password);
    await connect();
  }, [connect]);

  const resetPassword = useCallback(email => sendPasswordResetEmail(firebaseAuth, email.trim()), []);

  /** 웹 /login/google이 딥링크로 돌려준 일회용 코드를 커스텀 토큰으로 바꿔 로그인한다. */
  const finishWebGoogle = useCallback(async url => {
    if (!url) return;
    let callback;
    try { callback = new URL(url); } catch { return; }
    const code = callback.searchParams.get('code');
    if (!['gamestock:', 'exp:'].includes(callback.protocol) || !code || handledCodes.current.has(code)) return;
    const expected = pendingState.current || await AsyncStorage.getItem(MOBILE_AUTH_STATE_KEY);
    const verifier = pendingVerifier.current || await AsyncStorage.getItem(MOBILE_AUTH_VERIFIER_KEY);
    if (!expected || handledCodes.current.has(code)) return;
    // Android는 인증 세션 결과와 딥링크 이벤트가 같은 주소로 두 번 올 수 있다.
    handledCodes.current.add(code);
    pendingState.current = null;
    pendingVerifier.current = null;
    await AsyncStorage.multiRemove([MOBILE_AUTH_STATE_KEY, MOBILE_AUTH_VERIFIER_KEY]).catch(() => {});
    try {
      if (callback.searchParams.get('state') !== expected) throw new Error('인증 상태가 일치하지 않아요. 다시 로그인해 주세요.');
      if (!verifier) throw new Error('로그인 정보를 찾지 못했어요. 다시 로그인해 주세요.');
      const { customToken } = await request('/api/auth/mobile/exchange', { method: 'POST', body: { code, codeVerifier: verifier }, auth: false });
      await signInWithCustomToken(firebaseAuth, customToken);
      await connect(customToken);
    } catch (error) {
      setGoogleError(authErrorMessage(error));
    }
  }, [connect]);

  /**
   * Google 로그인. 네이티브 모듈이 있으면 앱 안에서 받은 ID 토큰으로 바로 Firebase에 로그인하고,
   * 없으면 인증 세션으로 웹 /login/google을 열어 끝나면 앱으로 돌아온다.
   * 사용자가 창을 닫으면 false를 돌려준다.
   */
  const startGoogle = useCallback(async () => {
    setGoogleError('');
    if (nativeGoogle) {
      if (Platform.OS === 'android') await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
      const response = await GoogleSignin.signIn();
      if (response.type !== 'success') return false;
      const idToken = response.data.idToken;
      if (!idToken) throw new Error('Google 계정 정보를 받지 못했어요. 다시 시도해 주세요.');
      await signInWithCredential(firebaseAuth, GoogleAuthProvider.credential(idToken));
      await connect();
      return true;
    }
    const state = Crypto.randomUUID();
    const pkce = await pkcePair();
    pendingState.current = state;
    pendingVerifier.current = pkce.verifier;
    // 인증 중에 앱이 다시 시작돼도 딥링크로 돌아온 코드를 마무리할 수 있게 기기에 남긴다.
    await AsyncStorage.multiSet([[MOBILE_AUTH_STATE_KEY, state], [MOBILE_AUTH_VERIFIER_KEY, pkce.verifier]]);
    const url = new URL('/login/google', WEB_APP_URL);
    url.searchParams.set('next', '/');
    url.searchParams.set('mobileAuth', '1');
    url.searchParams.set('returnUri', redirectUri);
    url.searchParams.set('state', state);
    url.searchParams.set('codeChallenge', pkce.challenge);
    const result = await WebBrowser.openAuthSessionAsync(url.toString(), redirectUri);
    if (result.type !== 'success') return false;
    await finishWebGoogle(result.url);
    return true;
  }, [connect, finishWebGoogle, redirectUri]);

  useEffect(() => {
    // 인증 세션 밖에서 딥링크로 돌아온 경우(앱이 다시 시작된 경우 등)도 받는다.
    const subscription = Linking.addEventListener('url', ({ url }) => void finishWebGoogle(url));
    Linking.getInitialURL().then(finishWebGoogle).catch(() => {});
    return () => subscription.remove();
  }, [finishWebGoogle]);

  /** 회원 탈퇴 뒤 정리. 서버가 로그인 계정을 지우지 못했으면 여기서 지우고, 기기와 웹 화면을 모두 로그아웃한다. */
  const finishAccountDeletion = useCallback(async loginAccountDeleted => {
    const current = firebaseAuth.currentUser;
    if (!loginAccountDeleted && current) await deleteUser(current).catch(() => {});
    await firebaseSignOut(firebaseAuth).catch(() => {});
    if (nativeGoogle) await GoogleSignin.signOut().catch(() => {});
    onWebSignOut?.();
  }, [onWebSignOut]);

  const signOut = useCallback(async () => {
    await firebaseSignOut(firebaseAuth);
    // 다음 로그인 때 계정 선택 창이 다시 뜨도록 기기의 Google 세션도 정리한다.
    if (nativeGoogle) await GoogleSignin.signOut().catch(() => {});
    onWebSignOut?.();
  }, [onWebSignOut]);

  const value = useMemo(() => ({
    user, ready, profile, googleError, googleInApp: nativeGoogle, signInEmail, register, resetPassword, startGoogle, signOut, finishAccountDeletion, syncWeb, refreshProfile: loadProfile,
  }), [user, ready, profile, googleError, signInEmail, register, resetPassword, startGoogle, signOut, finishAccountDeletion, syncWeb, loadProfile]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
