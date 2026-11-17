# antislop 점검 028 후속: 계정 메뉴 묶음과 묶음 탭

- 날짜: 2026-11-02
- 바꾼 파일: 다섯 화면(`NxChargePage`, `NxAttendancePage`, `NxLoanPage`, `NxTitlesPage`, `NxBillboardPage`), `src/styles/trade-navigation.css`

## 다시 확인한 결과 (375×667)

| # | 결과 | 근거 |
|---|---|---|
| 1 | 고침 | 다섯 화면의 `h1`이 `vh`라 화면에는 보이지 않고 화면 읽기 프로그램에는 남는다. 설명 문장의 위 여백은 0px로 계산된다. |
| 2 | 고침 | 탭 아래부터 설명 문장까지 45px에서 16px로 줄었다. |

## Delivery Gate 요약

- Hard Gate: em dash 없음(R-02). 탭 44px·가로 넘침 없음, 메뉴가 한 화면에 들어감(R-03). 묶음 항목과 탭은 모두 있는 화면으로 간다(R-24, R-26). 탭은 링크라 Tab·Enter로 쓸 수 있고 공통 초점 링을 받는다(R-32).
- `tsc --noEmit`, `vite build` 통과(R-35).
- 남은 한계: 다섯 화면은 로그인해야 열려서 실제 화면이 아니라 같은 마크업으로만 쟀다. 탭 밑줄 막대는 AnimatedNav가 그리므로, 같은 컴포넌트를 쓰는 거래 화면과 같게 그려진다고 보고 따로 재지 않았다.
