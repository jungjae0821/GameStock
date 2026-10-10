import { useEffect, useRef, useState, type SyntheticEvent } from "react";
import { createPortal } from "react-dom";
import { signOut } from "firebase/auth";
import { Link } from "./Link";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { useProfile } from "../lib/profile";
import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";
import { navigate, type Route } from "../router";

const ITEMS = [
  {
    label: "내 계좌",
    hint: "보유 종목 · 미체결 주문 · 체결 내역",
    to: "/mypage",
    match: "mypage",
    icon: "M3.5 7.5h17v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1zM3.5 7.5l2.5-3h12l2.5 3M15.5 13.5h2",
  },
  {
    label: "칭호 설정",
    hint: "모은 칭호를 닉네임 옆에 달기",
    to: "/titles",
    match: "titles",
    icon: "M12 3.5l2.5 5 5.5.8-4 3.9.9 5.5-4.9-2.6-4.9 2.6.9-5.5-4-3.9 5.5-.8z",
  },
  {
    label: "충전하기",
    hint: "모의 자금 채우기",
    to: "/charge",
    match: "charge",
    icon: "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM12 8v8M8 12h8",
  },
  {
    label: "설정",
    hint: "닉네임 · 프로필 사진 · 계좌 초기화",
    to: "/settings",
    match: "settings",
    icon: "M4 7h10M18 7h2M4 17h2M10 17h10M16 4.5v5M8 14.5v5",
  },
] as const;

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
    return (
      <div className="profile-menu">
        <button type="button" className="nav-link profile-login" onClick={() => openLoginPrompt(window.location.pathname)}>
          로그인
        </button>
      </div>
    );
  }

  const nickname = profile?.nickname ?? auth.user.displayName ?? "내 계정";
  const avatar = profile?.profileImageUrl || DEFAULT_PROFILE_AVATAR;
  const current = route.name === "mypage" || route.name === "settings" || route.name === "titles" || route.name === "charge";

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
                <p className="profile-panel-sub">모의 투자 계정</p>
              </div>
              <button type="button" className="profile-panel-close" aria-label="메뉴 닫기" onClick={close}>
                <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
                  <path d="M3.5 3.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
              </button>
            </div>
            <ul className="profile-panel-list">
              {ITEMS.map((item) => (
                <li key={item.to}>
                  <Link
                    className={`profile-panel-item${route.name === item.match ? " is-active" : ""}`}
                    to={item.to}
                    aria-current={route.name === item.match ? "page" : undefined}
                    onClick={close}
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
              ))}
            </ul>
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

function fallbackAvatar(event: SyntheticEvent<HTMLImageElement>) {
  if (!event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
}
