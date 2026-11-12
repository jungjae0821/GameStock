import React from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GameIcon } from '../../components/GameIcon';
import { price, signedPercent } from '../../format';
import { sessionRate } from '../../market';
import { RADIUS, SPACE, Txt, toneOf, useTheme } from '../../theme';
import { styles as shared } from './parts';

/** 목록으로 돌아가지 않고 다른 종목으로 바꾸는 시트. 웹 거래 화면의 종목 선택과 같은 일을 한다. */
export function StockPicker({ visible, stocks, current, onPick, onClose }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={[StyleSheet.absoluteFill, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel="종목 고르기 닫기" />
      <View style={[styles.picker, { backgroundColor: theme.card, paddingBottom: insets.bottom + SPACE.md }]}>
        <View style={styles.pickerHead}>
          <Txt variant="headline" accessibilityRole="header">종목 고르기</Txt>
          <Pressable accessibilityRole="button" accessibilityLabel="닫기" onPress={onClose} hitSlop={8} style={shared.barButton}>
            <Ionicons name="close" size={24} color={theme.ink} />
          </Pressable>
        </View>
        <ScrollView>
          {stocks.map((item, index) => {
            const selected = item.code === current;
            const rate = sessionRate(item);
            return (
              <Pressable
                key={item.code}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={`${item.name} ${price(item.price)}원, 오늘 ${signedPercent(rate)}`}
                onPress={() => onPick(item.code)}
                style={({ pressed }) => [styles.pickerRow, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, (pressed || selected) && { backgroundColor: theme.raised }]}
              >
                <GameIcon code={item.code} />
                <Txt variant="callout" numberOfLines={1} style={shared.shrink}>{item.name}</Txt>
                <View style={styles.pickerPrice}>
                  <Txt variant="callout" numeric>{price(item.price)}</Txt>
                  <Txt variant="caption" tone={toneOf(rate)} numeric>{signedPercent(rate)}</Txt>
                </View>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  picker: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '75%', borderTopLeftRadius: RADIUS, borderTopRightRadius: RADIUS },
  pickerHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingLeft: SPACE.lg + 2, paddingRight: SPACE.xs, paddingTop: SPACE.xs },
  pickerRow: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 60, paddingHorizontal: SPACE.lg + 2 },
  pickerPrice: { alignItems: 'flex-end' },
});
