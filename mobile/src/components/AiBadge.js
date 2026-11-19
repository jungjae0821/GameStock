import React from 'react';
import { StyleSheet, View } from 'react-native';
import { useAiNicknames } from '../engagement';
import { RADIUS_XS, Txt, useTheme } from '../theme';

/** 운영 봇(AI 트레이더) 이름 옆에 붙는 표시. 사람이 아닌 참여자를 사람처럼 보이지 않게 한다(웹 AiBadge와 같다). */
export function AiBadge({ nickname }) {
  const theme = useTheme();
  const names = useAiNicknames();
  if (!nickname || !names.has(nickname)) return null;
  return (
    <View style={[styles.badge, { borderColor: theme.edge }]} accessible accessibilityLabel="AI 트레이더">
      <Txt variant="micro" tone="inkSoft">AI</Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: { borderRadius: RADIUS_XS, borderWidth: 1, paddingHorizontal: 5, paddingVertical: 0 },
});
