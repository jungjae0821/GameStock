import { getApps, initializeApp } from "firebase/app";
import { getAuth, GoogleAuthProvider } from "firebase/auth";

/** Firebase Web SDK 설정. 공개 Web 키는 인증 초기화에만 사용하고,
 * 관리자 서비스 계정 키는 절대 프론트 번들에 포함하지 않는다. */
const configuredFirebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

const missing = Object.entries(configuredFirebaseConfig).filter(([, value]) => !value).map(([key]) => key);

/* Firebase 설정이 없는 개발 환경에서도 공개 시세와 뉴스는 볼 수 있어야 한다.
 * 인증을 누르면 Firebase가 설정된 환경에서만 실제 로그인 요청이 동작한다. */
const firebaseConfig = {
  apiKey: configuredFirebaseConfig.apiKey || "local-development-api-key",
  authDomain: configuredFirebaseConfig.authDomain || "local-development.firebaseapp.com",
  projectId: configuredFirebaseConfig.projectId || "local-development",
  storageBucket: configuredFirebaseConfig.storageBucket || "local-development.appspot.com",
  messagingSenderId: configuredFirebaseConfig.messagingSenderId || "000000000000",
  appId: configuredFirebaseConfig.appId || "1:000000000000:web:local-development",
};

export const firebaseConfigured = missing.length === 0;

export const firebaseApp = getApps()[0] ?? initializeApp(firebaseConfig);
export const firebaseAuth = getAuth(firebaseApp);
export const googleProvider = new GoogleAuthProvider();
