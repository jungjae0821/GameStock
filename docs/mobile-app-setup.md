# 모바일 앱 실행 및 Google 로그인 설정

```powershell
cd mobile
npm install
$env:EXPO_PUBLIC_API_BASE_URL = "http://내PC의-LAN-IP:8081"
$env:EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = "Firebase 프로젝트의 웹 OAuth 클라이언트 ID"
npx expo start
```

휴대폰 Expo Go에서 QR 코드를 스캔할 때는 `localhost`가 아니라 같은 Wi-Fi에 있는 PC의 LAN IP를 `EXPO_PUBLIC_API_BASE_URL`에 사용합니다. Firebase Console의 Google 로그인 provider와 승인 도메인 설정은 웹과 동일합니다.

모바일 앱은 웹과 같은 Firebase 프로젝트와 Spring Boot API를 사용합니다. `EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID`가 없으면 시장 조회·호가·전체 체결은 가능하지만 Google 로그인 버튼은 비활성화됩니다. 웹 OAuth 클라이언트 ID는 Google Cloud Console의 Firebase 프로젝트 사용자 인증 정보에서 확인할 수 있습니다.
