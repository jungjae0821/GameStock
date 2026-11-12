import React, { useState } from 'react';
import { View } from 'react-native';
import { useAuth } from '../../auth';
import { AccountFrame, Card, Chips, Empty } from '../../components/Kit';
import { Txt } from '../../theme';
import { Audit } from './admin/AdminAudit';
import { Notices } from './admin/AdminNotices';
import { Orders } from './admin/AdminOrders';
import { Promos } from './admin/AdminPromos';
import { styles } from './admin/shared';
import { Users } from './admin/AdminUsers';

/*
 * 관리자 화면. 서버가 모든 요청에서 관리자 권한을 다시 확인한다. 각 구획의 요청과 확인 단계는
 * 웹 관리 화면(src/pages/AdminPage.tsx, src/pages/admin/*)과 같다. 시장 운영 설정은 웹에만 둔다.
 */
const SECTIONS = [
  { key: 'users', label: '회원' },
  { key: 'orders', label: '주문' },
  { key: 'notices', label: '공지' },
  { key: 'promos', label: '프로모' },
  { key: 'audit', label: '감사 기록' },
];

export function AdminScreen({ market, onBack }) {
  const { profile } = useAuth();
  const [section, setSection] = useState('users');
  const names = Object.fromEntries((market?.stocks ?? []).map(stock => [stock.code, stock.name]));

  if (!profile?.admin) {
    return (
      <AccountFrame title="관리자" onBack={onBack}>
        <Card><Empty text={profile ? '관리자만 볼 수 있는 화면이에요.' : '권한을 확인하고 있어요.'} loading={!profile} /></Card>
      </AccountFrame>
    );
  }

  return (
    <AccountFrame title="관리자" onBack={onBack}>
      <Txt variant="caption" tone="inkSoft" style={styles.lead}>모든 조치는 감사 기록에 남아요. 시장 운영 설정은 웹 관리자 화면에서 바꿔요.</Txt>
      {/* 구획이 다섯이라 한 줄을 나누면 폰 폭에서 칸이 글자보다 좁아진다. 가로로 넘기는 칩으로 둔다. */}
      <View style={styles.sections} accessibilityLabel="관리 구획"><Chips options={SECTIONS} value={section} onChange={setSection} /></View>
      {section === 'users' ? <Users /> : null}
      {section === 'orders' ? <Orders names={names} /> : null}
      {section === 'notices' ? <Notices /> : null}
      {section === 'promos' ? <Promos /> : null}
      {section === 'audit' ? <Audit /> : null}
    </AccountFrame>
  );
}
