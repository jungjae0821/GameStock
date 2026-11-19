import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Image, KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Svg, { Path } from 'react-native-svg';
import * as WebBrowser from 'expo-web-browser';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { authErrorMessage, useAuth } from '../auth';
import { WEB_APP_URL } from '../config';
import { FONT_FAMILY, RADIUS, SPACE, TYPE, Txt, useTheme } from '../theme';

const TITLES = { choose: '로그인', email: '이메일 로그인', register: '이메일로 회원가입', reset: '비밀번호 찾기' };
const DESCRIPTIONS = {
  choose: '',
  email: '이메일과 비밀번호로 로그인하세요.',
  register: '이메일과 비밀번호로 새 계정을 만들어요.',
  reset: '가입할 때 쓴 이메일을 입력하면 재설정 메일을 보내 드려요.',
};

/** 로그인 시트를 닫지 않게 약관은 앱 안 브라우저 창으로 연다. 웹 /terms·/privacy 화면과 같은 문서다. */
const openLegal = path => void WebBrowser.openBrowserAsync(new URL(path, WEB_APP_URL).toString()).catch(() => {});

/** "이용약관과 개인정보처리방침" 문장 안의 두 링크. */
function LegalLinks() {
  return (
    <>
      <Txt variant="caption" tone="ink" style={styles.underline} accessibilityRole="link" onPress={() => openLegal('/terms')}>이용약관</Txt>
      과{' '}
      <Txt variant="caption" tone="ink" style={styles.underline} accessibilityRole="link" onPress={() => openLegal('/privacy')}>개인정보처리방침</Txt>
    </>
  );
}

/** Google 브랜드 4색 G. 웹 LoginCard의 GoogleIcon과 같은 도형이다. */
function GoogleMark() {
  return (
    <Svg viewBox="0 0 18 18" width={18} height={18}>
      <Path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92a8.78 8.78 0 0 0 2.68-6.62z" />
      <Path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z" />
      <Path fill="#FBBC05" d="M3.97 10.72a5.4 5.4 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3.01-2.33z" />
      <Path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z" />
    </Svg>
  );
}

/** 로그인 시트. 로그인이 끝나면(user가 생기면) 스스로 닫힌다. */
export function LoginScreen({ visible, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user, googleError, googleInApp, signInEmail, register, resetPassword, startGoogle } = useAuth();
  const [method, setMethod] = useState('choose');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [waitingGoogle, setWaitingGoogle] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const passwordRef = useRef(null);
  const confirmRef = useRef(null);

  useEffect(() => { if (visible && user) onClose(); }, [visible, user, onClose]);
  useEffect(() => { if (googleError) { setError(googleError); setWaitingGoogle(false); } }, [googleError]);
  useEffect(() => {
    if (!visible) return;
    setMethod('choose');
    setError('');
    setNotice('');
    setWaitingGoogle(false);
  }, [visible]);

  const switchTo = next => {
    setMethod(next);
    setPassword('');
    setConfirm('');
    setShowPassword(false);
    setAgreed(false);
    setError('');
    setNotice('');
  };

  const google = async () => {
    setError('');
    setWaitingGoogle(true);
    try { if (!(await startGoogle())) setWaitingGoogle(false); } catch (failure) { setError(authErrorMessage(failure)); setWaitingGoogle(false); }
  };

  const submit = async () => {
    if (busy) return;
    setError('');
    setNotice('');
    if (!email.trim()) { setError('이메일을 입력해 주세요.'); return; }
    if (method !== 'reset' && !password) { setError('비밀번호를 입력해 주세요.'); return; }
    if (method === 'register' && !agreed) { setError('만 14세 이상인지, 약관에 동의하는지 확인해 주세요.'); return; }
    if (method === 'register' && password !== confirm) { setError('비밀번호 확인이 일치하지 않아요.'); return; }
    setBusy(true);
    try {
      if (method === 'reset') {
        await resetPassword(email);
        setNotice('가입된 이메일이라면 재설정 메일을 보냈어요. 받은편지함과 스팸함을 확인해 주세요.');
      } else if (method === 'register') {
        await register(email, password);
      } else {
        await signInEmail(email, password);
      }
    } catch (failure) {
      setError(authErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const isForm = method !== 'choose';
  // 입력칸과 버튼의 테두리는 찾아 눌러야 하는 경계라 바탕 대비 3:1 이상인 색을 쓴다.
  const field = [styles.input, { borderColor: theme.edge, backgroundColor: theme.raised, color: theme.ink }];

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      {/* 화면 가운데에 뜨는 로그인 카드. 뒤 화면은 흐림 없이 어둡게만 덮어 카드에 시선을 모은다
          (DESIGN.md 평평한 무대 규칙). 키보드가 올라오면 카드도 따라 올라간다. */}
      <Pressable style={[StyleSheet.absoluteFill, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="로그인 닫기" />
      <KeyboardAvoidingView style={styles.sheetWrap} behavior={Platform.OS === 'ios' ? 'padding' : undefined} pointerEvents="box-none">
        <View style={[styles.sheet, { backgroundColor: theme.card, marginTop: insets.top + SPACE.lg, marginBottom: insets.bottom + SPACE.lg }]}>
          <View style={styles.bar}>
            {isForm ? (
              <Pressable accessibilityRole="button" accessibilityLabel="로그인 방법 다시 고르기" onPress={() => switchTo(method === 'email' ? 'choose' : 'email')} hitSlop={8} style={styles.barButton}>
                <Ionicons name="chevron-back" size={24} color={theme.ink} />
              </Pressable>
            ) : null}
            <View style={styles.heading}>
              <Image source={require('../../assets/brand/shuen-mark.png')} style={styles.mark} accessibilityIgnoresInvertColors />
              <Txt variant="title" accessibilityRole="header" numberOfLines={1} style={styles.shrink}>{TITLES[method]}</Txt>
            </View>
            <Pressable accessibilityRole="button" accessibilityLabel="로그인 닫기" onPress={onClose} hitSlop={8} style={styles.barButton}>
              <Ionicons name="close" size={24} color={theme.ink} />
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled" bounces={false}>
          {DESCRIPTIONS[method] ? <Txt variant="callout" tone="inkSoft" style={styles.description}>{DESCRIPTIONS[method]}</Txt> : null}

          {method === 'choose' ? (
            <View style={styles.stack}>
              <Pressable accessibilityRole="button" onPress={() => void google()} disabled={waitingGoogle} style={({ pressed }) => [styles.button, styles.outline, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}>
                {waitingGoogle ? <ActivityIndicator color={theme.inkSoft} /> : <GoogleMark />}
                <Txt variant="body">{waitingGoogle ? 'Google 계정을 확인하는 중이에요' : 'Google로 로그인'}</Txt>
              </Pressable>
              <Pressable accessibilityRole="button" onPress={() => switchTo('email')} style={({ pressed }) => [styles.button, styles.outline, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : theme.card }]}>
                <Ionicons name="mail-outline" size={19} color={theme.ink} />
                <Txt variant="body">이메일로 로그인</Txt>
              </Pressable>
              <Txt variant="caption" tone="inkSoft" style={styles.help}>
                처음 로그인하면 계정이 만들어져요. 계속하면 만 14세 이상이며 <LegalLinks />에 동의한 것으로 봐요.
              </Txt>
              {waitingGoogle && !googleInApp ? <Txt variant="caption" tone="inkSoft" style={styles.help}>로그인 창에서 "앱 로그인 완료하기"를 누르면 이 화면으로 돌아와요.</Txt> : null}
            </View>
          ) : (
            <View style={styles.stack}>
              <Label text="이메일" />
              <TextInput
                style={field}
                keyboardAppearance={theme.scheme}
                selectionColor={theme.brand}
                value={email}
                onChangeText={setEmail}
                keyboardType="email-address"
                autoCapitalize="none"
                autoCorrect={false}
                autoComplete="email"
                textContentType="emailAddress"
                returnKeyType={method === 'reset' ? 'send' : 'next'}
                onSubmitEditing={() => (method === 'reset' ? void submit() : passwordRef.current?.focus())}
                editable={!busy}
                accessibilityLabel="이메일"
                placeholder="email@example.com"
                placeholderTextColor={theme.inkSoft}
              />
              {method !== 'reset' ? (
                <>
                  <View style={styles.labelRow}>
                    <Label text="비밀번호" />
                    <Pressable accessibilityRole="button" onPress={() => setShowPassword(show => !show)} hitSlop={12}>
                      <Txt variant="caption" tone="inkSoft">{showPassword ? '숨기기' : '보이기'}</Txt>
                    </Pressable>
                  </View>
                  <TextInput
                    ref={passwordRef}
                    style={field}
                    keyboardAppearance={theme.scheme}
                    selectionColor={theme.brand}
                    value={password}
                    onChangeText={setPassword}
                    secureTextEntry={!showPassword}
                    autoCapitalize="none"
                    autoComplete={method === 'register' ? 'new-password' : 'current-password'}
                    textContentType={method === 'register' ? 'newPassword' : 'password'}
                    returnKeyType={method === 'register' ? 'next' : 'go'}
                    onSubmitEditing={() => (method === 'register' ? confirmRef.current?.focus() : void submit())}
                    editable={!busy}
                    accessibilityLabel="비밀번호"
                  />
                </>
              ) : null}
              {method === 'register' ? (
                <>
                  <Label text="비밀번호 확인" />
                  <TextInput
                    ref={confirmRef}
                    style={field}
                    keyboardAppearance={theme.scheme}
                    selectionColor={theme.brand}
                    value={confirm}
                    onChangeText={setConfirm}
                    secureTextEntry={!showPassword}
                    autoCapitalize="none"
                    autoComplete="new-password"
                    textContentType="newPassword"
                    returnKeyType="go"
                    onSubmitEditing={() => void submit()}
                    editable={!busy}
                    accessibilityLabel="비밀번호 확인"
                  />
                  {/* 웹 가입 화면(LoginCard)과 같은 필수 동의. 체크하지 않으면 가입 요청을 보내지 않는다. */}
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: agreed, disabled: busy }}
                    accessibilityLabel="만 14세 이상이며, 이용약관과 개인정보처리방침에 동의합니다. 필수"
                    onPress={() => setAgreed(value => !value)}
                    disabled={busy}
                    style={styles.consent}
                  >
                    <Ionicons name={agreed ? 'checkbox' : 'square-outline'} size={22} color={agreed ? theme.ink : theme.inkSoft} />
                    <Txt variant="caption" tone="inkSoft" style={styles.consentText}>
                      만 14세 이상이며, <LegalLinks />에 동의합니다. (필수)
                    </Txt>
                  </Pressable>
                </>
              ) : null}
              <Pressable accessibilityRole="button" onPress={() => void submit()} disabled={busy} style={({ pressed }) => [styles.button, { backgroundColor: theme.ink, opacity: busy ? 0.6 : pressed ? 0.85 : 1, marginTop: SPACE.sm }]}>
                {busy ? <ActivityIndicator color={theme.ground} /> : null}
                <Txt variant="body" style={{ color: theme.ground }}>{method === 'register' ? '회원가입' : method === 'reset' ? '재설정 메일 받기' : '로그인'}</Txt>
              </Pressable>
              {method === 'email' ? (
                <View style={styles.links}>
                  <TextLink text="회원가입" onPress={() => switchTo('register')} />
                  <TextLink text="비밀번호 찾기" onPress={() => switchTo('reset')} />
                </View>
              ) : (
                <View style={styles.links}><TextLink text="이메일 로그인으로 돌아가기" onPress={() => switchTo('email')} /></View>
              )}
            </View>
          )}

          {error ? (
            <View style={[styles.message, { backgroundColor: theme.raised }]} accessibilityLiveRegion="assertive">
              <Ionicons name="alert-circle" size={18} color={theme.ink} />
              <Txt variant="callout" style={{ flex: 1 }}>{error}</Txt>
            </View>
          ) : null}
          {notice ? (
            <View style={[styles.message, { backgroundColor: theme.raised }]} accessibilityLiveRegion="polite">
              <Ionicons name="mail-unread-outline" size={18} color={theme.ink} />
              <Txt variant="callout" style={{ flex: 1 }}>{notice}</Txt>
            </View>
          ) : null}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

function Label({ text }) {
  return <Txt variant="caption" tone="inkSoft" style={styles.label}>{text}</Txt>;
}

function TextLink({ text, onPress }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} hitSlop={10} style={styles.link}>
      <Txt variant="callout" tone="inkSoft" style={styles.underline}>{text}</Txt>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  sheetWrap: { flex: 1, justifyContent: 'center', paddingHorizontal: SPACE.xl },
  sheet: { width: '100%', maxWidth: 420, alignSelf: 'center', borderRadius: RADIUS, maxHeight: '86%', paddingBottom: SPACE.xl },
  bar: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: SPACE.sm, paddingTop: SPACE.sm, minHeight: 56 },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  heading: { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, paddingLeft: SPACE.md },
  shrink: { flexShrink: 1 },
  body: { paddingHorizontal: SPACE.xl, paddingTop: SPACE.sm },
  mark: { width: 28, height: 28 },
  description: { marginBottom: SPACE.lg },
  stack: { gap: SPACE.sm },
  button: { minHeight: 52, borderRadius: RADIUS, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm, paddingHorizontal: SPACE.lg },
  outline: { borderWidth: 1 },
  help: { marginTop: SPACE.xs, textAlign: 'center' },
  label: { marginTop: SPACE.xs },
  labelRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: SPACE.xs },
  input: { minHeight: 52, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: SPACE.lg, fontFamily: FONT_FAMILY, fontSize: TYPE.body.fontSize },
  links: { flexDirection: 'row', justifyContent: 'center', gap: SPACE.xl, marginTop: SPACE.md },
  link: { minHeight: 44, justifyContent: 'center' },
  underline: { textDecorationLine: 'underline' },
  consent: { flexDirection: 'row', alignItems: 'flex-start', gap: SPACE.sm, minHeight: 44, paddingVertical: SPACE.sm, marginTop: SPACE.xs },
  consentText: { flex: 1, minWidth: 0, lineHeight: 20 },
  message: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, padding: SPACE.md, marginTop: SPACE.lg },
});
