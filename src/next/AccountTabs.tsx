import { Link } from "../components/Link";
import type { Route } from "../router";
import { AnimatedNav } from "./AnimatedNav";

type Tab = { id: Route["name"]; label: string; to: string };

/* 계정 메뉴의 한 항목이 여는 화면 묶음. 주소는 화면마다 그대로 두고 위에 탭만 단다. */
export const ACCOUNT_GROUPS = {
  funds: { label: "자금", tabs: [
    { id: "charge", label: "충전하기", to: "/charge" },
    { id: "attendance", label: "출석체크", to: "/attendance" },
    { id: "loan", label: "대출", to: "/loan" },
  ] },
  decor: { label: "꾸미기", tabs: [
    { id: "titles", label: "칭호", to: "/titles" },
    { id: "billboard", label: "전광판", to: "/billboard" },
  ] },
} as const satisfies Record<string, { label: string; tabs: readonly Tab[] }>;

export type AccountGroup = keyof typeof ACCOUNT_GROUPS;

export function AccountTabs({ group, active }: { group: AccountGroup; active: Route["name"] }) {
  const { label, tabs } = ACCOUNT_GROUPS[group];
  return (
    <AnimatedNav animationId={`account-${group}`} activeKey={active} className="nx-product-tabs" aria-label={label}>
      {tabs.map(tab => <Link key={tab.id} to={tab.to} className={active === tab.id ? "is-active" : undefined}
        aria-current={active === tab.id ? "page" : undefined}><span className="nx-bold-reserve" data-label={tab.label}>{tab.label}</span></Link>)}
    </AnimatedNav>
  );
}
