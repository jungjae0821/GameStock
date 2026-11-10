import { Link } from "../../components/Link";
import { useProfile } from "../../lib/profile";
import { useRoute, type AdminSection } from "../../router";
import { AnimatedNav } from "../AnimatedNav";
import { AdminAudit } from "./admin/AdminAudit";
import { AdminBillboard } from "./admin/AdminBillboard";
import { AdminNotices } from "./admin/AdminNotices";
import { AdminOrders } from "./admin/AdminOrders";
import { AdminPromos } from "./admin/AdminPromos";
import { AdminRuntime } from "./admin/AdminRuntime";
import { AdminUsers } from "./admin/AdminUsers";

const ADMIN_SECTIONS: { section: AdminSection; label: string; to: string }[] = [
  { section: "users", label: "회원", to: "/admin" },
  { section: "orders", label: "주문", to: "/admin/orders" },
  { section: "notices", label: "공지", to: "/admin/notices" },
  { section: "billboard", label: "전광판", to: "/admin/billboard" },
  { section: "promos", label: "프로모션 코드", to: "/admin/promos" },
  { section: "audit", label: "감사 기록", to: "/admin/audit" },
];

/** 관리자 화면. 서버가 모든 요청에서 ADMIN 권한을 다시 확인한다. 각 구획의 동작은 기존 관리 화면과 같다. */
export function NxAdminPage() {
  const profile = useProfile();
  const route = useRoute();
  const section = route.name === "admin" ? route.section : "users";

  if (!profile) return <p className="nx-empty-state" role="status">권한을 확인하고 있어요.</p>;
  if (!profile.admin) return <p className="nx-note is-error" role="alert">관리자만 볼 수 있는 화면이에요.</p>;

  return (
    <div className="nx-screen nx-admin">
      <header className="nx-screen-head">
        <div>
          <h1 className="nx-page-title">관리자</h1>
          <p className="nx-screen-sub">모든 조치는 감사 기록에 남아요.</p>
        </div>
      </header>
      <div className="nx-admin-runtime"><AdminRuntime /></div>
      {/* 구획이 몇 개뿐이라 계좌 화면처럼 가로 탭으로 두고, 본문이 화면 폭을 다 쓰게 한다. */}
      <AnimatedNav animationId="admin" activeKey={section} className="nx-tabs nx-admin-nav" aria-label="관리 메뉴">
        {ADMIN_SECTIONS.map((item) => (
          <Link key={item.section} to={item.to} className={item.section === section ? "is-active" : undefined} aria-current={item.section === section ? "page" : undefined}>
            {item.label}
          </Link>
        ))}
      </AnimatedNav>
      <div className="nx-admin-body">
        {section === "users" && <AdminUsers />}
        {section === "orders" && <AdminOrders />}
        {section === "notices" && <AdminNotices />}
        {section === "billboard" && <AdminBillboard />}
        {section === "promos" && <AdminPromos />}
        {section === "audit" && <AdminAudit />}
      </div>
    </div>
  );
}
