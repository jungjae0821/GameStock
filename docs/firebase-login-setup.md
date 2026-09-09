# Firebase Google 로그인 설정

1. [Firebase Console](https://console.firebase.google.com/)에서 프로젝트를 만들고 **Authentication → Sign-in method → Google**을 활성화합니다. Spark(무료) 요금제로 충분합니다.
2. 프로젝트 설정의 **웹 앱**에서 받은 `firebaseConfig` 값을 `frontend/firebase-config.js`의 `null` 자리에 넣습니다. 이 파일에는 서비스 계정 키를 넣지 않습니다.
3. Firebase Console의 **서비스 계정**에서 새 비공개 키 JSON을 내려받아, 저장소 밖의 안전한 위치에 둡니다. `.env`에 다음을 추가합니다.

```env
FIREBASE_SERVICE_ACCOUNT_JSON=C:\secure\gamestock-firebase-admin.json
```

4. MySQL에 기존 스키마를 이미 적용했다면 백엔드 첫 실행 시 로그인용 열과 출석 보상 테이블이 자동 추가됩니다. 새 DB는 `database/schema.sql`을 적용합니다.

Google 로그인 성공 시 Firebase UID, 이메일, 표시 이름 및 프로필 사진 주소가 `users`에 저장됩니다. Google ID 토큰은 서버에서 검증하며, 매수·매도와 내 자산/주문 내역 API는 토큰 없이는 사용할 수 없습니다. 시장·차트·뉴스는 로그인 없이 볼 수 있습니다.

출석 보상은 한국 시간 날짜 기준으로 첫 로그인 10만원부터 5일차 50만원까지 증가하고, 6일차부터는 50만원입니다. 하루를 건너뛰면 1일차부터 다시 시작합니다.
