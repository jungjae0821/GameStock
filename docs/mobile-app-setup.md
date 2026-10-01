# 모바일 앱 실행 및 로그인 설정

```powershell
cd mobile
npm install
# 배포 백엔드를 사용할 때는 위 환경변수 생략 가능
$env:EXPO_PUBLIC_API_BASE_URL = "https://gamestock-production.up.railway.app"
npx expo start
```

앱은 `WebMirrorScreen`으로 웹 공용 화면을 표시합니다. Firebase Console의 Google 및 이메일/비밀번호 공급자 설정은 웹과 같습니다.
로컬 화면을 사용할 때는 `EXPO_PUBLIC_WEB_APP_URL`을 PC의 LAN 주소로 지정하고, 해당 웹의 `VITE_API_BASE_URL`도 휴대폰에서 접근 가능한 PC 주소를 사용합니다.
앱의 Google 인증 코드 교환에 쓰는 `EXPO_PUBLIC_API_BASE_URL` 역시 같은 백엔드를 가리켜야 합니다.

로그인이 필요한 기능을 누르면 `/login`에서 방법을 선택합니다. 이메일 로그인·회원가입·비밀번호 찾기는 공용 웹 화면에서 처리합니다.
이메일과 비밀번호는 앱이나 서비스 DB에 별도로 저장하지 않으며 Firebase SDK가 인증합니다.

Google을 선택하면 기본 브라우저의 `/login/google` 페이지에서 인증하고, **앱 로그인 완료하기**를 누르면 `gamestock://auth/callback` 딥링크로 돌아옵니다.
백엔드는 Firebase ID 토큰을 2분 동안 한 번만 쓸 수 있는 코드로 교환하고, 앱은 그 코드를 Custom Token으로 바꿔 WebView에 전달합니다.
로그인이 완료되면 원래 보던 서비스 화면으로 돌아갑니다. 현재 공용 웹 방식은 앱에 iOS·Android Google Client ID를 직접 넣지 않아도 되며,
`app.json`의 `scheme`은 인증 결과가 앱으로 돌아오는 주소에 사용됩니다. 실물 기기의 전체 인증 흐름은 별도 확인이 필요합니다.
