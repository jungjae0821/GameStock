import { useEffect, useRef, useState, type SyntheticEvent } from "react";
import { createPortal } from "react-dom";
import { signOut } from "firebase/auth";
import { Link } from "./Link";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { useProfile } from "../lib/profile";
import { DEFAULT_PROFILE_AVATAR, profileImageSrc } from "../lib/profileAvatars";
import { ACCOUNT_GROUPS, type AccountGroup } from "../next/AccountTabs";
import { API_DOCS_PATH, PRIVATE_ROUTES, navigate, type Route } from "../router";

type PanelEntry = { label: string; hint: string; to: string; matches: readonly Route["name"][]; icon: string };

/* 묶음 항목은 첫 탭 화면으로 열고, 설명 줄에 안에 든 화면 이름을 그대로 적는다. 화면 위 탭(AccountTabs)과 이름이 같다. */
function groupItem(group: AccountGroup, icon: string): PanelEntry {
  const { label, tabs } = ACCOUNT_GROUPS[group];
  return { label, hint: tabs.map((tab) => tab.label).join(" · "), to: tabs[0].to, matches: tabs.map((tab) => tab.id), icon };
}

const ITEMS: readonly PanelEntry[] = [
  {
    label: "내 계좌",
    hint: "보유 종목 · 미체결 주문 · 체결 내역",
    to: "/mypage",
    matches: ["mypage"],
    icon: "M3.5 7.5h17v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1zM3.5 7.5l2.5-3h12l2.5 3M15.5 13.5h2",
  },
  groupItem("funds", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM12 8v8M8 12h8"),
  groupItem("decor", "M12 3.5l2.5 5 5.5.8-4 3.9.9 5.5-4.9-2.6-4.9 2.6.9-5.5-4-3.9 5.5-.8z"),
  {
    label: "설정",
    hint: "닉네임 · 프로필 사진 · 계좌 초기화",
    to: "/settings",
    matches: ["settings"],
    icon: "M4 7h10M18 7h2M4 17h2M10 17h10M16 4.5v5M8 14.5v5",
  },
];

/* 휴대폰 웹의 아래 탭바에서 뺀 화면. 넓은 화면은 상단 메뉴에 이미 있으니 CSS로 휴대폰 웹에서만 보인다. */
const NAV_ITEMS: readonly PanelEntry[] = [
  {
    label: "탭 베팅",
    hint: "5초 뒤 가격 칸 맞히기",
    to: "/tap",
    matches: ["tap"],
    icon: "M3.5 3.5h7v7h-7zM13.5 3.5h7v7h-7zM3.5 13.5h7v7h-7zM13.5 13.5h7v7h-7z",
  },
  {
    label: "퀀트",
    hint: "지표 분석 · 전략 연구",
    to: "/quant",
    matches: ["quant"],
    icon: "M4 19.5h16M6 16l4-5 3.5 3 5-7",
  },
  {
    label: "API",
    hint: "시세 · 주문 API 문서",
    to: API_DOCS_PATH,
    matches: ["apiDocs"],
    icon: "M8.5 7l-5 5 5 5M15.5 7l5 5-5 5M13.5 5l-3 14",
  },
];

/* 관리자 계정에만 설정 아래에 붙는다. 실제 권한은 서버가 매 요청마다 확인한다. */
const ADMIN_ITEM: PanelEntry = {
  label: "관리자",
  hint: "계정 조회 · 거래 제한 · 닉네임 변경",
  to: "/admin",
  matches: ["admin"],
  icon: "M12 3.5l7.5 3v5.5c0 4.2-3.1 7.4-7.5 8.5-4.4-1.1-7.5-4.3-7.5-8.5V6.5zM9 12l2.2 2.2L15.5 10",
};

/** 상단 오른쪽 프로필 버튼. 누르면 오른쪽에서 계좌·설정·칭호·충전·로그아웃 패널이 밀려 나온다. */
export function ProfileMenu({ route }: { route: Route }) {
  const auth = useAuthUser();
  const profile = useProfile();
  const [open, setOpen] = useState(false);
  const panel = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => setOpen(false), [route]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Tab") {
        trapFocus(event, panel.current);
        return;
      }
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    };
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", onKey);
    panel.current?.focus({ preventScroll: true });
    return () => {
      document.body.style.overflow = overflow;
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  if (!auth.ready) return <span className="profile-menu" aria-hidden="true" />;

  if (!auth.user) {
    /* 본문이 이미 로그인 화면·로그인 안내인 곳에서는 같은 버튼을 두 번 보이지 않는다. 자리는 남겨 밝기 버튼이 움직이지 않게 한다. */
    if (route.name === "login" || PRIVATE_ROUTES.has(route.name)) return <span className="profile-menu" aria-hidden="true" />;
    /* 홈에도 같은 자리에 둔다. 화면마다 상단 바 오른쪽이 비었다 찼다 하면 메뉴 위치를 다시 찾게 된다. */
    return (
      <div className="profile-menu">
        <button type="button" className="nav-link profile-login" onClick={() => openLoginPrompt(window.location.pathname)}>
          로그인
        </button>
      </div>
    );
  }

  const nickname = profile?.nickname ?? auth.user.displayName ?? "내 계정";
  const avatar = profileImageSrc(profile?.profileImageUrl);
  const current = route.name === "mypage" || route.name === "settings" || route.name === "titles" || route.name === "attendance" || route.name === "charge" || route.name === "loan" || route.name === "billboard" || route.name === "admin";
  const items = profile?.admin ? [...ITEMS, ADMIN_ITEM] : ITEMS;

  const logout = async () => {
    setOpen(false);
    try {
      await signOut(firebaseAuth);
      if (current) navigate("/", { replace: true });
    } catch {
      window.alert("로그아웃하지 못했습니다. 다시 시도해 주세요.");
    }
  };

  const close = () => setOpen(false);

  return (
    <div className="profile-menu">
      <button
        ref={trigger}
        type="button"
        className={`profile-trigger${open ? " is-open" : ""}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="profile-menu-panel"
        aria-label={`${nickname} 계정 메뉴`}
        title={nickname}
        onClick={() => setOpen((value) => !value)}
      >
        <img src={avatar} alt="" onError={fallbackAvatar} />
      </button>
      {/* 상단 바의 backdrop-filter가 fixed 기준을 가두므로 패널은 body로 내보낸다. */}
      {createPortal(
        <div className={`profile-drawer${open ? " is-open" : ""}`} inert={!open}>
          <div className="profile-drawer-scrim" onClick={close} aria-hidden="true" />
          <aside
            ref={panel}
            className="profile-panel"
            id="profile-menu-panel"
            role="dialog"
            aria-modal="true"
            aria-label="계정 메뉴"
            tabIndex={-1}
          >
            <div className="profile-panel-head">
              <img className="profile-panel-avatar" src={avatar} alt="" onError={fallbackAvatar} />
              <div className="profile-panel-who">
                <p className="profile-panel-name">{nickname}</p>
              </div>
              <button type="button" className="profile-panel-close" aria-label="메뉴 닫기" onClick={close}>
                <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
                  <path d="M3.5 3.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
              </button>
            </div>
            <div className="profile-panel-scroll">
              <ul className="profile-panel-list profile-panel-list-nav">
                {NAV_ITEMS.map((item) => <PanelItem key={item.to} item={item} active={item.matches.includes(route.name)} onClick={close} />)}
              </ul>
              <ul className="profile-panel-list">
                {items.map((item) => <PanelItem key={item.to} item={item} active={item.matches.includes(route.name)} onClick={close} />)}
              </ul>
            </div>
            <button type="button" className="profile-panel-logout" onClick={() => void logout()}>
              로그아웃
            </button>
          </aside>
        </div>,
        document.body,
      )}
    </div>
  );
}

function PanelItem({ item, active, onClick }: { item: PanelEntry; active: boolean; onClick: () => void }) {
  return (
    <li>
      <Link
        className={`profile-panel-item${active ? " is-active" : ""}`}
        /* 묶음 안 다른 탭(예: 대출)을 보고 있을 때 누르면 첫 탭으로 옮기지 않고 패널만 닫는다. */
        to={active ? window.location.pathname : item.to}
        aria-current={active ? "page" : undefined}
        onClick={onClick}
      >
        <svg className="profile-panel-icon" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
          <path d={item.icon} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="profile-panel-label">{item.label}</span>
        <span className="profile-panel-hint">{item.hint}</span>
        <svg className="profile-panel-chevron" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
          <path d="M6 3.5l4.5 4.5L6 12.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </Link>
    </li>
  );
}

/* Tab이 뒤 화면으로 빠지지 않게 패널 안에서 돌린다. 숨은 목록(넓은 화면의 퀀트·API)은 건너뛴다. */
function trapFocus(event: KeyboardEvent, root: HTMLElement | null) {
  if (!root) return;
  const focusable = [...root.querySelectorAll<HTMLElement>("a[href], button")].filter((el) => el.offsetParent !== null);
  if (focusable.length === 0) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement;
  if (event.shiftKey && (active === first || active === root)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (active === last || !root.contains(active))) {
    event.preventDefault();
    first.focus();
  }
}

function fallbackAvatar(event: SyntheticEvent<HTMLImageElement>) {
  if (!event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
}
