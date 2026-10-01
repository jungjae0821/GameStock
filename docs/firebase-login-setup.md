# Firebase Google 및 이메일 로그인 설정

1. [Firebase Console](https://console.firebase.google.com/)의 **Authentication → Sign-in method**에서 **Google**과 **이메일/비밀번호**를 활성화합니다.
2. 프로젝트 설정의 **웹 앱**에서 받은 `firebaseConfig` 값을 루트 `.env.local`의 `VITE_FIREBASE_*` 변수에 넣습니다. 서비스 계정 키는 웹 설정에 넣지 않습니다.
3. Firebase Console의 **서비스 계정**에서 새 비공개 키 JSON을 내려받아, 저장소 밖의 안전한 위치에 둡니다. `.env`에 다음을 추가합니다.

```env
FIREBASE_SERVICE_ACCOUNT_JSON=C:\secure\gamestock-firebase-admin.json
```

4. MySQL에 기존 스키마를 이미 적용했다면 백엔드 첫 실행 시 로그인용 열과 출석 보상 테이블이 자동 추가됩니다. 새 DB는 `database/schema.sql`을 적용합니다.

`/login`에서 Google 또는 이메일·비밀번호를 선택합니다. 이메일 로그인 화면에는 회원가입과 비밀번호 찾기도 연결돼 있습니다.
로그인 성공 시 Firebase UID와 이메일이 `users`에 연결되고 자동 닉네임이 생성됩니다. 비밀번호는 Firebase가 관리하며
서비스 DB에는 저장하지 않습니다. 서버는 `/api/auth/login`에서 Firebase ID 토큰을 검증합니다. 기존 `/api/auth/google` 주소도 호환됩니다.
기존 `google_uid` 열은 모든 Firebase 사용자의 UID를 저장하는 키로 사용하므로 기존 자산을 유지하며 별도 열 이름 변경은 필요 없습니다.
매수·매도와 개인 자산/주문 API는 토큰 없이는 사용할 수 없습니다. 시장·차트·뉴스는 로그인 없이 볼 수 있습니다.
비로그인 상태에서 개인 기능을 누르면 로그인 페이지로 이동하고, 로그인 후 원래 서비스 화면으로 돌아갑니다.

## 관리자 계정 지정

관리자도 Firebase 로그인이 필요합니다. Firebase Console의 **Authentication → Users**에서 관리자 계정의 UID를 확인한 뒤 프로젝트 루트 `.env`에 다음을 추가합니다(UID 사용 권장). 변수 이름은 기존 설정과 호환되도록 유지합니다.

```env
GAMESTOCK_ADMIN_GOOGLE_UID=관리자_계정의_FIREBASE_UID
# UID를 모를 때만 보조적으로 사용하며, 확인된 이메일만 관리자 지정에 사용
# GAMESTOCK_ADMIN_GOOGLE_EMAIL=admin@example.com
```

해당 계정이 로그인하면 `users.role`이 `ADMIN`으로 저장됩니다. 관리자는 `/api/admin/health`에 접근할 수 있고, 투자 랭킹에는 표시되지 않습니다. 일반 사용자는 계속 `USER` 역할로 생성됩니다.

출석 보상은 한국 시간 날짜 기준으로 첫 로그인 10만원부터 5일차 50만원까지 증가하고, 6일차부터는 50만원입니다. 하루를 건너뛰면 1일차부터 다시 시작합니다.
