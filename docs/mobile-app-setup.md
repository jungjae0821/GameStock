# 모바일 앱 실행 및 Google 로그인 설정

```powershell
cd mobile
npm install
# 배포 백엔드를 사용할 때는 위 환경변수 생략 가능
$env:EXPO_PUBLIC_API_BASE_URL = "https://gamestock-production.up.railway.app"
$env:EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = "Firebase 프로젝트의 웹 OAuth 클라이언트 ID"
$env:EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID = "Firebase 프로젝트의 iOS OAuth 클라이언트 ID"
$env:EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID = "Firebase 프로젝트의 Android OAuth 클라이언트 ID"
npx expo start
```

로컬 Spring Boot를 직접 테스트할 때만 `EXPO_PUBLIC_API_BASE_URL`을 같은 Wi-Fi의 PC LAN IP(예: `http://192.168.x.x:8081`)로 바꿉니다. Firebase Console의 Google 로그인 provider와 승인 도메인 설정은 웹과 동일합니다.

모바일 앱은 웹과 같은 Firebase 프로젝트와 Spring Boot API를 사용합니다. Google 로그인에는 플랫폼별 OAuth 클라이언트 ID가 필요합니다. 웹·iOS·Android Client ID는 Google Cloud Console의 Firebase 프로젝트 사용자 인증 정보에서 각각 확인할 수 있으며, 웹 Client ID를 iOS Client ID 대신 사용하면 안 됩니다.

앱은 WebView 안에서 Google 팝업을 직접 열지 않고, 휴대폰의 기본 브라우저에서 웹 로그인을 완료한 뒤 `gamestock://auth/callback` 딥링크로 돌아옵니다. 백엔드는 브라우저의 Firebase ID 토큰을 2분 동안 한 번만 쓸 수 있는 코드로 교환하고, 앱은 그 코드를 Firebase Custom Token으로 바꿔 WebView에 전달합니다. 인증용 브라우저 안에 팝업을 다시 여는 구조를 피하므로 sessionStorage가 분리된 환경에서도 로그인할 수 있습니다. 따라서 앱에 iOS·Android Google Client ID를 직접 넣지 않아도 되며, `app.json`의 `scheme`은 인증 결과가 앱으로 돌아오는 주소를 위해 사용합니다.
