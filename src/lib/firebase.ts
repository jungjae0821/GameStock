import { getApps, initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, GoogleAuthProvider } from "firebase/auth";

/** Firebase Web SDK 설정. 공개 Web 키는 인증 초기화에만 사용하고,
 * 관리자 서비스 계정 키는 절대 프론트 번들에 포함하지 않는다. */
const configuredFirebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
  measurementId: import.meta.env.VITE_FIREBASE_MEASUREMENT_ID,
};

/* Firebase 설정이 없는 개발 환경에서도 공개 시세와 뉴스는 볼 수 있어야 한다.
 * 인증을 누르면 Firebase가 설정된 환경에서만 실제 로그인 요청이 동작한다. */
const firebaseConfig = {
  apiKey: configuredFirebaseConfig.apiKey || "local-development-api-key",
  authDomain: configuredFirebaseConfig.authDomain || "local-development.firebaseapp.com",
  projectId: configuredFirebaseConfig.projectId || "local-development",
  storageBucket: configuredFirebaseConfig.storageBucket || "local-development.appspot.com",
  messagingSenderId: configuredFirebaseConfig.messagingSenderId || "000000000000",
  appId: configuredFirebaseConfig.appId || "1:000000000000:web:local-development",
  measurementId: configuredFirebaseConfig.measurementId || undefined,
};

export const firebaseApp = getApps()[0] ?? initializeApp(firebaseConfig);
export const firebaseAuth = getAuth(firebaseApp);
if (import.meta.env.DEV && import.meta.env.VITE_FIREBASE_EMULATOR === "true") {
  connectAuthEmulator(firebaseAuth, "http://127.0.0.1:9099", { disableWarnings: true });
}
export const googleProvider = new GoogleAuthProvider();

/* Google Analytics(GA4)는 운영 빌드에서만 켠다. 개발 중 방문이 통계에 섞이지 않게 하고,
 * 첫 화면 번들을 키우지 않도록 analytics 모듈은 따로 불러온다.
 * 화면 이동(pushState)은 GA4 향상된 측정이 페이지뷰로 자동 기록한다. */
if (import.meta.env.PROD && firebaseConfig.measurementId) {
  void import("firebase/analytics")
    .then(async ({ getAnalytics, isSupported }) => {
      if (await isSupported()) getAnalytics(firebaseApp);
    })
    .catch(() => {
      /* 광고 차단기 등으로 막혀도 앱 동작에는 영향이 없다. */
    });
}
