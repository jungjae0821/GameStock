# 제3자 소프트웨어·폰트 저작권 및 라이선스 고지

확인일: 2026-11-03

이 문서는 GameStock에서 사용하는 제3자 구성요소의 라이선스와 저작권 고지를 안내합니다. 각 구성요소에는 해당 권리자가 정한 조건이 적용됩니다. 이 문서가 프로젝트 자체 또는 포함된 이미지·기사에 새로운 이용 허락을 부여하지는 않습니다.

## 라이선스 원문과 버전별 목록

| 대상 | 확인 근거 | 목록·원문 |
| --- | --- | --- |
| 웹 npm 의존성 | 루트 `package-lock.json`의 209개 항목 | [웹 의존성 고지](docs/licenses/npm-web-notices.md) |
| 모바일 npm 의존성 | `mobile/package-lock.json`의 581개 항목 | [모바일 의존성 고지](docs/licenses/npm-mobile-notices.md) |
| 백엔드 직접 의존성 | `backend/pom.xml`, Spring Boot 3.5.16 의존성 관리, 해당 버전의 로컬 JAR 및 upstream | [Maven 직접 의존성 고지](docs/licenses/maven-direct-notices.md) |
| Pretendard 1.3.9 | 해당 버전 upstream LICENSE | [저작권 표시 및 OFL-1.1 원문](docs/licenses/Pretendard-1.3.9-LICENSE.txt) |
| 모바일 아이콘 래퍼의 vendored 코드 | `@expo/vector-icons`에 포함된 `react-native-vector-icons/LICENSE` | [저작권 표시 및 MIT 원문](docs/licenses/react-native-vector-icons-LICENSE.txt) |
| Apache-2.0 공통 원문 | Apache Software Foundation | [Apache-2.0](docs/licenses/Apache-2.0.txt) |

npm 목록은 개발 도구와 다른 운영체제용 선택적 패키지를 포함합니다. 잠금 파일의 모든 항목이 웹 번들이나 모바일 앱에 포함된다는 뜻은 아닙니다. 설치된 패키지의 버전을 잠금 파일과 대조했으며, 확보한 LICENSE·NOTICE 등의 원문은 패키지별 출처와 함께 보존했습니다. 동일한 원문은 중복을 묶었습니다.

총 790개 잠금 파일 항목 중 586개는 라이선스 원문을 수록했습니다. 134개는 선언된 라이선스 메타데이터만 확인했고, 70개는 현재 플랫폼에 설치되어 있지 않습니다. 각 목록의 `Collection status`로 구분합니다. SPDX 식별자나 upstream 링크만으로 모든 고지 의무를 이행했다고 간주하지 않습니다.

## 주요 구성요소

- React·React DOM·Expo·React Native 등은 MIT 라이선스로 제공됩니다. 정확한 버전별 저작권자와 허가문은 위 목록에 수록했습니다.
- Firebase JavaScript SDK와 Firebase Admin Java SDK는 Apache-2.0 라이선스로 제공됩니다. 개별 구성요소에 추가 고지가 있으면 함께 적용됩니다.
- Pretendard의 저작권자는 Kil Hyung-jin이며, Reserved Font Name은 Pretendard입니다. 폰트에는 OFL-1.1이 적용되며, 이 라이선스가 GameStock 소스 전체에 적용되는 것은 아닙니다.
- MySQL Connector/J 9.7.0 Community에는 GPLv2 및 Universal FOSS Exception 1.0이 적용됩니다. 해당 버전 JAR의 전체 LICENSE를 Maven 고지에 수록했습니다. 실행 파일을 배포할 때에는 실제 결합·배포 방식과 예외 적용 요건을 별도로 확인해야 합니다.
- `lightningcss` 계열은 MPL-2.0, `caniuse-lite`는 CC-BY-4.0으로 선언되어 있습니다. `node-forge`의 선언은 `(BSD-3-Clause OR GPL-2.0)`입니다. 목록에 존재한다는 이유만으로 프로젝트 전체의 라이선스가 바뀌지는 않습니다.

## 경기천년체

경기천년제목체의 저작권 표시는 다음과 같습니다. 모바일에 포함된 `GyeonggiTitleM.ttf`의 메타데이터에서 확인했습니다.

> Copyright (C) 2017 Gyeonggi and PYJ&TYPOLAB Inc. All rights reserved.

[경기도 공식 서체 안내](https://www.gg.go.kr/contents/contents.do?ciIdx=679&menuId=2457)는 무료 사용, 웹·로고 등 다양한 매체에서의 사용, 프로그램 탑재·배포를 허용합니다. 폰트 자체를 그대로 판매하거나 유료로 양도하는 행위는 허용하지 않습니다. 경기천년체를 MIT 또는 OFL로 재지정하지 않습니다.

## 이미지·게임 IP·뉴스 콘텐츠

GameStock은 비공식 모의 투자 프로젝트입니다. 이 저장소의 고지는 게임사·퍼블리셔·언론사 등의 승인이나 후원을 뜻하지 않습니다.

게임명, 로고, 캐릭터, 공식 일러스트 및 뉴스 기사에 관한 권리는 각 권리자에게 있습니다. 이러한 자료는 위 소프트웨어 라이선스의 적용 대상으로 일괄 포함하지 않습니다. 다음 파일의 주소는 출처 기록이며 재사용 허가를 증명하지 않습니다.

- [추가 게임 아이콘 출처](public/game-icons/additional-sources.json)
- [교체 게임 아이콘 출처](public/game-icons/replacement-sources.json)

Minecraft 관련 자료에 관하여: NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

출처 표시나 비공식 안내만으로 이미지·기사의 이용 허락 문제가 해결되지는 않습니다. 별도 허가가 확인되지 않은 자산에는 이 문서로 새로운 라이선스를 부여하지 않습니다.

## 범위와 배포 시 확인 사항

- 이 문서는 현재 잠금 파일과 확보한 원문을 기준으로 한 저장소용 스냅샷입니다. 의존성이나 자산이 변경되면 갱신해야 합니다.
- Maven 목록은 직접 의존성을 대상으로 하며, 최종 배포물의 전체 전이 의존성 목록이 아닙니다. npm 패키지 안의 모든 중첩·vendored 자산까지 검증했다는 의미도 아닙니다. 아이콘 폰트 자체의 조건은 래퍼 코드와 별도로 확인해야 합니다.
- GitHub에 이 문서를 추가하는 것만으로 웹 `dist`, APK·IPA 또는 서버 JAR·컨테이너에 고지가 자동 포함되지는 않습니다. 배포물에는 실제 포함된 구성요소의 허가문·저작권·NOTICE 및 해당되는 소스 제공 안내를 함께 전달해야 합니다.
- GameStock 자체 코드의 재사용 라이선스는 이 변경에서 지정하지 않았습니다. 제3자 라이선스가 프로젝트 자체에 대한 포괄적 재사용 허가를 대신하지 않습니다.
