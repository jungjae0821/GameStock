import { useEffect, useState } from "react";
import { onAuthStateChanged, type User } from "firebase/auth";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";
import { firebaseAuth } from "../lib/firebase";
import { openLoginPrompt } from "../lib/auth";
import { setProfile as shareProfile, type Profile } from "../lib/profile";
import { DEFAULT_PROFILE_AVATAR, isProfileAvatar, PROFILE_AVATARS } from "../lib/profileAvatars";

export function AccountSettings() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [selectedAvatar, setSelectedAvatar] = useState("");
  const [nickname, setNickname] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState("");
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const nextNicknameChange = profile?.nicknameChangeAvailableAt ? Date.parse(profile.nicknameChangeAvailableAt) : null;
  const nicknameLocked = profile?.nicknameChangeAvailable === false && (nextNicknameChange === null || now < nextNicknameChange);

  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  useEffect(() => {
    if (!user) {
      setProfile(null);
      setSelectedAvatar("");
      return;
    }
    let active = true;
    setLoading(true);
    setMessage("");
    void apiFetch<Profile>("/api/profile")
      .then((nextProfile) => {
        if (!active) return;
        setProfile(nextProfile);
        setNickname(nextProfile.nickname ?? "");
        setSelectedAvatar(nextProfile.profileImageUrl ?? "");
      })
      .catch((error) => {
        if (active) setMessage(error instanceof Error ? error.message : "계정 정보를 불러오지 못했습니다.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [user]);

  const applyProfile = (next: Profile) => {
    setProfile(next);
    shareProfile(next);
    setNickname(next.nickname);
    setSelectedAvatar(next.profileImageUrl ?? "");
  };

  const saveNickname = async () => {
    const value = nickname.trim();
    if (value.length < 2 || value.length > 50) {
      setMessage("닉네임은 2~50자로 입력해 주세요.");
      return;
    }
    setSaving(true);
    setMessage("");
    try {
      applyProfile(await apiFetch<Profile>("/api/profile", {
        method: "PATCH",
        body: JSON.stringify({ nickname: value }),
      }));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "닉네임 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const saveAvatar = async () => {
    if (!profile || !selectedAvatar || selectedAvatar === (profile.profileImageUrl ?? "")) return;
    setSaving(true);
    setMessage("");
    try {
      applyProfile(await apiFetch<Profile>("/api/profile", {
        method: "PATCH",
        body: JSON.stringify({ nickname: profile.nickname, profileImageUrl: selectedAvatar }),
      }));
      setMessage("프로필 사진을 저장했습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "프로필 사진 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const resetAccount = async () => {
    if (!window.confirm("현금과 보유 주식, 거래 내역, 투자 미션을 초기 상태로 되돌릴까요?")) return;
    setResetting(true);
    setMessage("");
    try {
      await apiFetch("/api/account/reset", { method: "DELETE" });
      // Reload private views so stale mission completions cannot survive the reset.
      window.location.reload();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "인생 리셋에 실패했습니다.");
    } finally {
      setResetting(false);
    }
  };

  if (!user) {
    return (
      <div className="account-settings">
        <Panel id="settings-login" title="설정" level={3}>
          <div className="empty">
            <p>설정은 로그인 후 이용할 수 있습니다.</p>
            <button type="button" className="account-button is-primary" onClick={() => openLoginPrompt("/settings")}>로그인 방법 선택</button>
          </div>
        </Panel>
      </div>
    );
  }

  return (
    <div className="account-settings" aria-busy={loading}>
      {message && <p className="account-message" role="status">{message}</p>}
      <Panel id="settings-nickname" title="닉네임" level={3}>
        {loading ? <p className="empty">계정 정보를 불러오는 중…</p> : (
          <div className="account-profile-grid">
            <form className="account-form" onSubmit={(event) => { event.preventDefault(); void saveNickname(); }}>
              <label htmlFor="settings-nickname-input">닉네임</label>
              <div className="account-input-row">
                <input id="settings-nickname-input" value={nickname} disabled={nicknameLocked} minLength={2} maxLength={50} required onChange={(event) => setNickname(event.target.value)} />
                <button type="submit" className="account-button is-primary" disabled={saving || !profile || nicknameLocked || nickname.trim() === profile.nickname}>{saving ? "저장 중…" : "저장"}</button>
              </div>
              {!nicknameLocked && <p className="account-hint">닉네임은 3일에 한 번 변경할 수 있습니다. · 2~50자</p>}
              {nicknameLocked && nextNicknameChange !== null && <p className="account-hint">
                다음 변경 가능: {new Date(nextNicknameChange).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} (한국시간)
              </p>}
            </form>
            <div className="account-identity">
              <span className="account-label">로그인 계정</span>
              <p>{profile?.email || user.email || "연결된 계정"}</p>
            </div>
          </div>
        )}
      </Panel>

      <Panel id="settings-avatar" title="프로필 사진" level={3}>
        {loading ? <p className="empty">프로필을 불러오는 중…</p> : (
          <fieldset className="account-avatar-picker">
            <legend className="vh">프로필 사진 선택</legend>
            <p className="account-hint">선택한 캐릭터가 상단 프로필 버튼과 랭킹에 표시됩니다.</p>
            {profile?.profileImageUrl && !isProfileAvatar(profile.profileImageUrl) && (
              <div className="account-avatar-current">
                <img src={profile.profileImageUrl} alt="현재 계정 프로필 사진" onError={(event) => { event.currentTarget.src = DEFAULT_PROFILE_AVATAR; }} />
                <span>현재 계정 사진</span>
              </div>
            )}
            <div className="account-avatar-grid" role="group" aria-label="프로필 사진 선택">
              {PROFILE_AVATARS.map((avatar) => {
                const selected = selectedAvatar === avatar.src;
                return (
                  <button
                    key={avatar.id}
                    type="button"
                    className={`account-avatar-option${selected ? " is-selected" : ""}`}
                    aria-pressed={selected}
                    aria-label={`${avatar.name} 프로필 사진 선택`}
                    title={avatar.description}
                    onClick={() => setSelectedAvatar(avatar.src)}
                  >
                    <img src={avatar.src} alt="" />
                    <span>{avatar.name}</span>
                  </button>
                );
              })}
            </div>
            <div className="account-avatar-footer">
              <span className="account-hint">
                {selectedAvatar ? `${PROFILE_AVATARS.find((avatar) => avatar.src === selectedAvatar)?.name ?? "선택한 캐릭터"} 선택됨` : "캐릭터를 하나 골라 주세요."}
              </span>
              <button
                type="button"
                className="account-button is-primary"
                disabled={saving || !profile || !selectedAvatar || selectedAvatar === (profile.profileImageUrl ?? "")}
                onClick={() => void saveAvatar()}
              >
                {saving ? "저장 중…" : "프로필 사진 저장"}
              </button>
            </div>
          </fieldset>
        )}
      </Panel>

      <div className="account-reset">
        <div><h3>인생 리셋</h3><p className="account-hint">시작 자본으로 돌아갑니다. 보유 주식과 거래 기록, 투자 미션도 초기화됩니다.</p></div>
        <button type="button" className="account-button is-danger" onClick={() => void resetAccount()} disabled={loading || resetting || !profile || !profile.resetAvailable}>
          {resetting ? "초기화 중…" : "계좌 초기화"}
        </button>
      </div>
    </div>
  );
}
