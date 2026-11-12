import React, { useState } from 'react';
import { ActivityIndicator, Linking, Pressable, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { cleanTitle, newsSourceUrl, newsSummary, relativeTime } from '../../format';
import { SPACE, Txt, useTheme } from '../../theme';
import { Card, styles as shared } from './parts';

/** 관련 뉴스 5건. 누르면 요약과 원문 링크를 펼친다. */
export function StockNewsCard({ news, sortedNews, failed }) {
  const theme = useTheme();
  const [openNews, setOpenNews] = useState(null);
  return (
    <Card padded={false}>
      <View style={shared.cardHead}><Txt variant="headline" accessibilityRole="header">관련 뉴스</Txt></View>
      {news == null ? (failed
        ? <Txt variant="caption" tone="inkSoft" style={shared.bookEmpty}>관련 뉴스를 불러오지 못했어요. 30초마다 다시 불러와요.</Txt>
        : <View style={shared.chartLoading}><ActivityIndicator color={theme.inkSoft} /></View>)
        : sortedNews.length ? sortedNews.slice(0, 5).map((item, index) => {
          const key = `${item.publishedAt}-${index}`;
          const expanded = openNews === key;
          const summary = newsSummary(item.description);
          const source = newsSourceUrl(item);
          return (
            <View key={key} style={index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
              <Pressable accessibilityRole="button" accessibilityState={{ expanded }} onPress={() => setOpenNews(expanded ? null : key)} style={({ pressed }) => [styles.news, pressed && { backgroundColor: theme.pressed }]}>
                <Txt variant="callout" numberOfLines={expanded ? undefined : 2}>{cleanTitle(item.title)}</Txt>
                <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.xs }}>
                  {`${relativeTime(item.publishedAt)}${Number.isFinite(Number(item.priceChangePercent)) ? ` · 발행 뒤 ${Number(item.priceChangePercent) >= 0 ? '+' : ''}${Number(item.priceChangePercent).toFixed(2)}%` : ''}`}
                </Txt>
              </Pressable>
              {expanded ? (
                <View style={styles.newsBody}>
                  {summary && summary !== cleanTitle(item.title) ? <Txt variant="callout" tone="inkSoft">{summary}</Txt> : null}
                  {source ? (
                    <Pressable accessibilityRole="link" onPress={() => void Linking.openURL(source)} style={styles.sourceLink}>
                      <Txt variant="callout" style={styles.underline}>원문 기사 열기</Txt>
                      <Ionicons name="open-outline" size={16} color={theme.ink} />
                    </Pressable>
                  ) : null}
                </View>
              ) : null}
            </View>
          );
        }) : <Txt variant="caption" tone="inkSoft" style={shared.bookEmpty}>이 종목의 관련 뉴스가 아직 없어요</Txt>}
    </Card>
  );
}

const styles = StyleSheet.create({
  news: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  newsBody: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.md, gap: SPACE.sm },
  sourceLink: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44 },
  underline: { textDecorationLine: 'underline' },
});
