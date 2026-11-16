import React, { Component } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useTheme } from '../theme';

/**
 * 화면을 그리다 예외가 나면 React Native는 앱 전체를 빨간 화면(개발) 또는 강제 종료(배포)로 끝낸다.
 * 경계를 두어 고장 난 화면만 안내로 바꾸고 탭바와 다른 탭은 계속 쓸 수 있게 한다.
 * resetKey가 바뀌면(다른 탭·종목으로 이동) 오류 상태를 풀고 다시 그린다.
 */
export class ErrorBoundary extends Component {
  state = { error: null, resetKey: this.props.resetKey };

  static getDerivedStateFromError(error) {
    return { error };
  }

  static getDerivedStateFromProps(props, state) {
    if (props.resetKey === state.resetKey) return null;
    return { error: null, resetKey: props.resetKey };
  }

  componentDidCatch(error, info) {
    console.error(`[ErrorBoundary:${this.props.area}]`, error, info?.componentStack);
  }

  retry = () => this.setState({ error: null });

  render() {
    if (!this.state.error) return this.props.children;
    const Fallback = this.props.fallback ?? ErrorFallback;
    return <Fallback onRetry={this.retry} title={this.props.title} />;
  }
}

/** 글꼴을 못 읽은 상태에서도 그려지도록 기본 글꼴과 테마 색만 쓴다. */
export function ErrorFallback({ onRetry, title = '이 화면을 불러오지 못했어요' }) {
  const theme = useTheme();
  return (
    <View style={[styles.wrap, { backgroundColor: theme.ground }]} accessibilityRole="alert">
      <Text style={[styles.title, { color: theme.ink }]}>{title}</Text>
      <Text style={[styles.body, { color: theme.inkSoft }]}>
        화면을 그리는 중에 문제가 생겼어요. 계좌와 주문 내역은 서버에 그대로 있어요. 다시 시도해도 같으면 다른 탭으로 옮겨 주세요.
      </Text>
      <Pressable
        accessibilityRole="button"
        onPress={onRetry}
        style={({ pressed }) => [styles.button, { backgroundColor: theme.ink }, pressed && { opacity: 0.85 }]}
      >
        <Text style={[styles.buttonText, { color: theme.ground }]}>다시 시도</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 28, gap: 12 },
  title: { fontSize: 18, textAlign: 'center' },
  body: { fontSize: 14, lineHeight: 21, textAlign: 'center' },
  button: { marginTop: 8, minHeight: 44, paddingHorizontal: 22, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  buttonText: { fontSize: 15 },
});
