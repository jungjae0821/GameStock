import React from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { WEB_APP_URL } from '../config';
import { useAuth } from '../auth';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RADIUS, RADIUS_SM, SPACE, Txt, useTheme, useThemeMode } from '../theme';

// 웹 계정 메뉴(ProfileMenu)의 항목. 모두 로그인 전용이라, 로그인하지 않았으면 앱이 로그인 시트를 먼저 띄운다.
// 관리자 화면은 관리자 계정에만 보인다. 서버도 모든 관리 요청에서 권한을 다시 확인한다.
const ACCOUNT = { label: '내 계좌', detail: '보유 종목과 손익, 주문·체결 내역', icon: 'wallet-outline', path: '/mypage' };
const ITEMS = [
  { label: '출석체크', detail: '연속 출석 보상과 이번 달 출석 기록', icon: 'calendar-outline', path: '/attendance' },
  { label: '충전하기', detail: '오늘의 투자 미션과 모의 자금 보상', icon: 'gift-outline', path: '/charge' },
  { label: '대출', detail: '모의 대출과 상환, 한도와 이자', icon: 'cash-outline', path: '/loan' },
  { label: '전광판', detail: '홈 맨 위에 한 줄 문구 띄우기', icon: 'megaphone-outline', path: '/billboard' },
  { label: '퀀트 전략', detail: '매매 조건을 짜서 백테스트하고 자동 주문 켜기', icon: 'git-branch-outline', path: '/quant' },
  { label: '칭호 설정', detail: '칭호 조건과 획득 현황, 장착', icon: 'ribbon-outline', path: '/titles' },
  { label: '프로필·계정 설정', detail: '닉네임, 프로필 사진, 계좌 초기화', icon: 'person-circle-outline', path: '/settings' },
];
const ADMIN = { label: '관리자', detail: '회원 조치, 주문 조회, 공지, 전광판, 프로모션 코드, 감사 기록', icon: 'shield-checkmark-outline', path: '/admin' };

const API_DOCS = { label: 'OpenAPI 문서', detail: '시세·주문 API 사용법과 API 키 발급. 브라우저에서 열려요', icon: 'code-slash-outline' };

const SCHEMES = [
  { key: 'dark', label: '어둡게', icon: 'moon' },
  { key: 'light', label: '밝게', icon: 'sunny' },
];

export function MenuScreen({ onOpen, onLogin }) {
  const theme = useTheme();
  const { scheme, setScheme } = useThemeMode();
  const insets = useSafeAreaInsets();
  const { user, profile, signOut } = useAuth();
  const confirmSignOut = () => Alert.alert('로그아웃할까요?', '앱과 앱 안의 웹 화면에서 모두 로그아웃해요.', [
    { text: '취소', style: 'cancel' },
    { text: '로그아웃', style: 'destructive', onPress: () => void signOut() },
  ]);
  return (
    <View style={styles.fill}>
      <ScrollView
        style={{ backgroundColor: theme.ground }}
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl, paddingHorizontal: SPACE.lg }}
      >
        <Txt variant="page" accessibilityRole="header" style={styles.title}>설정</Txt>
        {user ? (
          <View style={styles.who} accessible accessibilityLabel={`${profile?.nickname ?? '내 계정'}, ${user.email ?? ''}로 로그인됨`}>
            <Txt variant="headline" numberOfLines={1}>{profile?.nickname ?? '내 계정'}</Txt>
            {user.email ? <Txt variant="caption" tone="inkSoft" numberOfLines={1}>{user.email}</Txt> : null}
          </View>
        ) : (
          <View style={[styles.card, styles.guest, { backgroundColor: theme.card }]}>
            <Txt variant="callout">로그인하면 계좌, 미션, 대출, 칭호를 쓸 수 있어요.</Txt>
            <Pressable accessibilityRole="button" onPress={onLogin} style={({ pressed }) => [styles.loginButton, { backgroundColor: theme.ink, opacity: pressed ? 0.85 : 1 }]}>
              <Txt variant="body" style={{ color: theme.ground }}>로그인</Txt>
            </Pressable>
          </View>
        )}
        <View style={[styles.card, { backgroundColor: theme.card }]}>
          <Row item={ACCOUNT} onOpen={onOpen} large />
        </View>
        <View style={[styles.card, { backgroundColor: theme.card }]}>
          {ITEMS.map((item, index) => <Row key={item.path} item={item} onOpen={onOpen} divided={index > 0} />)}
        </View>
        {profile?.admin ? (
          <View style={[styles.card, { backgroundColor: theme.card }]}>
            <Row item={ADMIN} onOpen={onOpen} />
          </View>
        ) : null}
        {/* 개발자용 문서와 API 키 발급은 넓은 화면에서 쓰는 일이라 기본 브라우저의 웹 화면으로 연다. */}
        <View style={[styles.card, { backgroundColor: theme.card }]}>
          <Row item={API_DOCS} onOpen={() => void Linking.openURL(new URL('/api-docs', WEB_APP_URL).toString()).catch(() => {})} trailing="open-outline" />
        </View>
        {/* 로그인 없이도 바꿀 수 있다. 앱 안의 웹 화면도 같은 밝기를 따른다. */}
        <View style={[styles.card, styles.scheme, { backgroundColor: theme.card }]}>
          <Txt variant="callout">화면 밝기</Txt>
          <View style={[styles.segment, { backgroundColor: theme.raised }]} accessibilityRole="radiogroup" accessibilityLabel="화면 밝기">
            {SCHEMES.map(item => {
              const selected = item.key === scheme;
              return (
                <Pressable
                  key={item.key}
                  accessibilityRole="radio"
                  accessibilityState={{ checked: selected }}
                  onPress={() => setScheme(item.key)}
                  style={[styles.segmentItem, selected && { backgroundColor: theme.ink }]}
                >
                  <Ionicons name={selected ? item.icon : `${item.icon}-outline`} size={16} color={selected ? theme.ground : theme.inkSoft} />
                  <Txt variant="caption" style={{ color: selected ? theme.ground : theme.ink }}>{item.label}</Txt>
                </Pressable>
              );
            })}
          </View>
        </View>
        {user ? (
          <Pressable accessibilityRole="button" onPress={confirmSignOut} style={({ pressed }) => [styles.card, styles.signOut, { backgroundColor: pressed ? theme.pressed : theme.card }]}>
            <Txt variant="callout" tone="inkSoft">로그아웃</Txt>
          </Pressable>
        ) : null}
      </ScrollView>
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
    </View>
  );
}

function Row({ item, onOpen, divided, large, trailing = 'chevron-forward' }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${item.label}. ${item.detail}`}
      onPress={() => onOpen(item.path)}
      style={({ pressed }) => [
        styles.row,
        large && styles.rowLarge,
        divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line },
        pressed && { backgroundColor: theme.pressed },
      ]}
    >
      <Ionicons name={item.icon} size={large ? 26 : 22} color={theme.ink} />
      <View style={styles.rowText}>
        <Txt variant={large ? 'headline' : 'callout'}>{item.label}</Txt>
        <Txt variant="caption" tone="inkSoft" style={{ marginTop: 2 }}>{item.detail}</Txt>
      </View>
      <Ionicons name={trailing} size={18} color={theme.inkSoft} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  title: { minHeight: 48, textAlignVertical: 'center', marginBottom: SPACE.sm, paddingTop: SPACE.sm },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 64, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  rowLarge: { minHeight: 76 },
  rowText: { flex: 1, minWidth: 0 },
  who: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.lg, gap: 2 },
  guest: { padding: SPACE.lg + 2, gap: SPACE.md },
  loginButton: { minHeight: 48, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
  signOut: { minHeight: 52, alignItems: 'center', justifyContent: 'center' },
  scheme: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACE.md, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  segment: { flexDirection: 'row', borderRadius: RADIUS, padding: 4, gap: 4 },
  segmentItem: { flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 44, minWidth: 80, paddingHorizontal: SPACE.md, borderRadius: RADIUS_SM, justifyContent: 'center' },
});
