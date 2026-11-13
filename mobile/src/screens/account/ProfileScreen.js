import React, { useEffect, useRef, useState } from 'react';
import { Alert, Pressable, StyleSheet, View } from 'react-native';
import { useAccount } from '../../account';
import { request, useAuth } from '../../auth';
import { Avatar, PROFILE_AVATARS, isProfileAvatar, isUploadedPhoto } from '../../components/Avatar';
import { AccountFrame, Button, Card, CardHead, Empty, Field, Notice } from '../../components/Kit';
import { FandomSettings, PublicProfileSettings } from '../../components/Engagement';
import { RADIUS, SPACE, Txt, useTheme } from '../../theme';

const kstFull = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일 ${String(at.getUTCHours()).padStart(2, '0')}:${String(at.getUTCMinutes()).padStart(2, '0')}`;
};

/** 프로필·계정 설정. 할 일은 내 정보 바꾸기다: 닉네임, 프로필 사진, 그리고 계좌 초기화. */
export function ProfileScreen({ market, onBack }) {
  const theme = useTheme();
  const { user, refreshProfile } = useAuth();
  const account = useAccount();
  const [profile, setProfile] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [nickname, setNickname] = useState('');
  const [avatar, setAvatar] = useState('');
  const [saving, setSaving] = useState(null);
  const [message, setMessage] = useState(null);
  const [now, setNow] = useState(Date.now());
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const load = async () => {
    try {
      const next = await request('/api/profile');
      if (!alive.current) return;
      setProfile(next);
      setNickname(next.nickname ?? '');
      setAvatar(next.profileImageUrl ?? '');
      setLoadError('');
    } catch (failure) {
      if (alive.current) setLoadError(failure.message || '계정 정보를 불러오지 못했어요.');
    }
  };
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  // 닉네임 변경 가능 시각이 지나면 입력칸을 바로 풀어 준다.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const apply = next => {
    setProfile(next);
    setNickname(next.nickname ?? '');
    setAvatar(next.profileImageUrl ?? '');
    void refreshProfile?.();
  };

  const nextChange = profile?.nicknameChangeAvailableAt ? Date.parse(profile.nicknameChangeAvailableAt) : null;
  const nicknameLocked = profile?.nicknameChangeAvailable === false && (nextChange == null || now < nextChange);

  const saveNickname = async () => {
    const value = nickname.trim();
    if (value.length < 2 || value.length > 10) { setMessage({ text: '닉네임은 2~10자로 입력해 주세요.', ok: false }); return; }
    setSaving('nickname');
    setMessage(null);
    try {
      apply(await request('/api/profile', { method: 'PATCH', body: { nickname: value } }));
      setMessage({ text: '닉네임을 저장했어요.', ok: true });
    } catch (failure) {
      setMessage({ text: failure.message, ok: false });
    } finally {
      if (alive.current) setSaving(null);
    }
  };

  const saveAvatar = async () => {
    if (!profile || !avatar || avatar === (profile.profileImageUrl ?? '')) return;
    setSaving('avatar');
    setMessage(null);
    try {
      apply(await request('/api/profile', { method: 'PATCH', body: { nickname: profile.nickname, profileImageUrl: avatar } }));
      setMessage({ text: '프로필 사진을 저장했어요.', ok: true });
    } catch (failure) {
      setMessage({ text: failure.message, ok: false });
    } finally {
      if (alive.current) setSaving(null);
    }
  };

  const resetAccount = () => {
    Alert.alert('계좌를 초기화할까요?', '현금과 보유 주식, 거래 내역, 투자 미션에 더해 직급(수습으로)과 내 지갑(0원)까지 시작 상태로 돌아가요. 되돌릴 수 없어요.', [
      { text: '취소', style: 'cancel' },
      {
        text: '초기화', style: 'destructive', onPress: async () => {
          setSaving('reset');
          setMessage(null);
          try {
            await request('/api/account/reset', { method: 'DELETE' });
            await Promise.all([account.reload(), load()]);
            setMessage({ text: '계좌를 시작 자금 100만원으로 초기화했어요.', ok: true });
          } catch (failure) {
            setMessage({ text: failure.message, ok: false });
          } finally {
            if (alive.current) setSaving(null);
          }
        },
      },
    ]);
  };

  if (!profile) {
    return (
      <AccountFrame title="프로필·계정 설정" onBack={onBack} onRefresh={load}>
        <Card><Empty loading={!loadError} text={loadError || '계정 정보를 불러오는 중이에요'} action={loadError ? '다시 시도' : null} onAction={() => void load()} /></Card>
      </AccountFrame>
    );
  }

  const chosen = PROFILE_AVATARS.find(item => item.src === avatar);
  const avatarSaved = avatar !== '' && avatar === (profile.profileImageUrl ?? '');
  const avatarStatus = chosen
    ? avatarSaved ? `${chosen.name} 사용 중` : `고른 캐릭터: ${chosen.name}. 저장하면 바뀌어요.`
    : avatar ? '지금은 로그인 계정 사진을 쓰고 있어요. 바꾸려면 캐릭터를 골라 주세요.'
    : '캐릭터를 하나 골라 주세요.';

  return (
    <AccountFrame title="프로필·계정 설정" onBack={onBack} onRefresh={load}>
      <View style={styles.who}>
        <Avatar url={avatar || profile.profileImageUrl} size={64} />
        <View style={styles.shrink}>
          <Txt variant="title" numberOfLines={1}>{profile.nickname}</Txt>
          <Txt variant="caption" tone="inkSoft" numberOfLines={1}>{user?.email ?? profile.email ?? '로그인 계정'}</Txt>
        </View>
      </View>

      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}

      <Card>
        <CardHead title="닉네임" />
        <Field
          value={nickname}
          onChangeText={setNickname}
          maxLength={10}
          editable={!nicknameLocked && saving == null}
          autoCorrect={false}
          returnKeyType="done"
          onSubmitEditing={() => void saveNickname()}
          accessibilityLabel="닉네임"
          hint={nicknameLocked && nextChange != null ? `${kstFull(profile.nicknameChangeAvailableAt)}(한국시간)부터 다시 바꿀 수 있어요.` : '3일에 한 번 바꿀 수 있어요. 2~10자.'}
        />
        <Button label="닉네임 저장" kind="primary" busy={saving === 'nickname'} disabled={saving != null || nicknameLocked || nickname.trim() === profile.nickname} onPress={() => void saveNickname()} />
      </Card>

      <Card>
        <CardHead title="프로필 사진" />
        <Txt variant="caption" tone="inkSoft">랭킹에서 닉네임 옆에 보여요.</Txt>
        {profile.profileImageUrl && !isProfileAvatar(profile.profileImageUrl) ? (
          <View style={styles.current}>
            <Avatar url={profile.profileImageUrl} size={28} />
            <Txt variant="caption" tone="inkSoft">{isUploadedPhoto(profile.profileImageUrl) ? '지금은 웹에서 올린 내 사진을 쓰고 있어요' : '지금은 로그인 계정 사진을 쓰고 있어요'}</Txt>
          </View>
        ) : null}
        <View style={styles.avatars} accessibilityRole="radiogroup" accessibilityLabel="프로필 사진 선택">
          {PROFILE_AVATARS.map(item => {
            const selected = avatar === item.src;
            return (
              <Pressable
                key={item.src}
                accessibilityRole="radio"
                accessibilityState={{ checked: selected }}
                accessibilityLabel={item.name}
                onPress={() => setAvatar(item.src)}
                style={({ pressed }) => [styles.avatar, { borderColor: selected ? theme.brandText : 'transparent', backgroundColor: pressed || selected ? theme.raised : 'transparent' }]}
              >
                <Avatar url={item.src} size={52} />
                <Txt variant="micro" tone={selected ? 'ink' : 'inkSoft'} numberOfLines={1}>{item.name}</Txt>
              </Pressable>
            );
          })}
        </View>
        <Txt variant="caption" tone="inkSoft">{avatarStatus}</Txt>
        <Button label="프로필 사진 저장" kind="primary" busy={saving === 'avatar'} disabled={saving != null || !avatar || avatarSaved} onPress={() => void saveAvatar()} />
      </Card>

      <FandomSettings stocks={market?.stocks ?? []} />
      <PublicProfileSettings />

      <Card>
        <CardHead title="계좌 초기화" />
        <Txt variant="callout" tone="inkSoft">시작 자금 100만원으로 돌아가요. 보유 주식, 거래 기록, 투자 미션과 함께 직급은 수습으로, 내 지갑은 0원으로 초기화돼요.</Txt>
        {!profile.resetAvailable ? <Txt variant="caption" tone="inkSoft">지금은 계좌를 초기화할 수 없어요.</Txt> : null}
        <Button label="계좌 초기화" kind="danger" busy={saving === 'reset'} disabled={saving != null || !profile.resetAvailable} onPress={resetAccount} />
      </Card>
    </AccountFrame>
  );
}

const styles = StyleSheet.create({
  who: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.xl },
  shrink: { flex: 1, minWidth: 0 },
  current: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  avatars: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACE.sm },
  avatar: { width: 76, alignItems: 'center', gap: 4, paddingVertical: SPACE.sm, borderRadius: RADIUS, borderWidth: 2 },
});
