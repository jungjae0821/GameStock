import { useEffect, useRef, useState } from "react";
import { signOut } from "firebase/auth";
import { Link } from "./Link";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { useProfile } from "../lib/profile";
import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";
import { navigate, type Route } from "../router";

const ITEMS = [
  { label: "내 계좌", to: "/mypage", match: "mypage" },
  { label: "설정", to: "/settings", match: "settings" },
  { label: "충전하기", to: "/charge", match: "charge" },
] as const;

/** 상단 오른쪽 프로필 버튼. 누르면 계좌·설정·충전·로그아웃 메뉴를 펼친다. */
export function ProfileMenu({ route }: { route: Route }) {
  const auth = useAuthUser();
  const profile = useProfile();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => setOpen(false), [route]);

  useEffect(() => {
    if (!open) return undefined;
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
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
  const current = route.name === "mypage" || route.name === "settings" || route.name === "charge";

  const logout = async () => {
    setOpen(false);
    try {
      await signOut(firebaseAuth);
      if (current) navigate("/", { replace: true });
    } catch {
      window.alert("로그아웃하지 못했습니다. 다시 시도해 주세요.");
    }
  };

  return (
    <div className="profile-menu" ref={root}>
      <button
        ref={trigger}
        type="button"
        className={`profile-trigger${open ? " is-open" : ""}`}
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls="profile-menu-panel"
        aria-label={`${nickname} 계정 메뉴`}
        title={nickname}
        onClick={() => setOpen((value) => !value)}
      >
        <img
          src={avatar}
          alt=""
          onError={(event) => {
            if (!event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
          }}
        />
      </button>
      {open && (
        <div className="profile-panel" id="profile-menu-panel">
          <p className="profile-panel-name">{nickname}</p>
          <ul className="profile-panel-list">
            {ITEMS.map((item) => (
              <li key={item.to}>
                <Link
                  className={`profile-panel-item${route.name === item.match ? " is-active" : ""}`}
                  to={item.to}
                  aria-current={route.name === item.match ? "page" : undefined}
                  onClick={() => setOpen(false)}
                >
                  {item.label}
                </Link>
              </li>
            ))}
            <li className="profile-panel-divider">
              <button type="button" className="profile-panel-item is-logout" onClick={() => void logout()}>
                로그아웃
              </button>
            </li>
          </ul>
        </div>
      )}
    </div>
  );
}
