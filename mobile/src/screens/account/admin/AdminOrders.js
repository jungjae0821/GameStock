import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { Button, Card, CardHead, Chips, Empty, Field, Notice, Row, SideTag, kit, kstShort } from '../../../components/Kit';
import { price } from '../../../format';
import { Txt } from '../../../theme';
import { num, styles, useCursorList } from './shared';

const ORDER_STATUS = { OPEN: '미체결', PARTIAL: '부분 체결', FILLED: '체결 완료', CANCELLED: '취소' };
const ORDER_FILTERS = [{ key: '', label: '전체' }, ...Object.entries(ORDER_STATUS).map(([key, label]) => ({ key, label }))];

export function Orders({ names }) {
  const [userId, setUserId] = useState('');
  const [stockCode, setStockCode] = useState('');
  const [status, setStatus] = useState('');
  const [applied, setApplied] = useState({ userId: '', stockCode: '', status: '' });
  const list = useCursorList('/api/admin/orders');
  const params = filters => Object.fromEntries(Object.entries(filters).filter(([, value]) => value));

  useEffect(() => { void list.load(params(applied), null); }, [applied]); // eslint-disable-line react-hooks/exhaustive-deps

  const apply = next => {
    setUserId(next.userId);
    setStockCode(next.stockCode);
    setStatus(next.status);
    setApplied(next);
  };
  const filtered = applied.userId || applied.stockCode || applied.status;

  return (
    <>
      <Card>
        <View style={styles.filters}>
          <Field label="회원 번호" style={kit.grow} numeric keyboardType="number-pad" value={userId} placeholder="전체" onChangeText={text => setUserId(text.replace(/\D/g, ''))} />
          <Field label="종목 코드" style={kit.grow} value={stockCode} placeholder="전체" autoCapitalize="characters" autoCorrect={false} onChangeText={text => setStockCode(text.toUpperCase().replace(/[^A-Z]/g, ''))} />
        </View>
        <Chips options={ORDER_FILTERS} value={status} onChange={next => apply({ userId, stockCode, status: next })} />
        <View style={styles.filters}>
          <Button label="조회" kind="primary" style={kit.grow} busy={list.loading} onPress={() => apply({ userId, stockCode, status })} />
          {filtered ? <Button label="조건 지우기" style={kit.grow} onPress={() => apply({ userId: '', stockCode: '', status: '' })} /> : null}
        </View>
      </Card>
      {list.error ? <Notice text={list.error} /> : null}
      <Card padded={false}>
        <CardHead padded title="주문" meta={`${list.rows.length.toLocaleString('ko-KR')}건${list.done ? '' : ' 이상'}`} />
        {list.loading && list.rows.length === 0 ? <Empty loading text="주문을 불러오는 중이에요" />
          : list.error && list.rows.length === 0 ? <Empty text="주문을 불러오지 못했어요." action="다시 시도" onAction={() => void list.load(params(applied), null)} />
          : list.rows.length === 0 ? <Empty text={filtered ? '조건에 맞는 주문이 없어요.' : '아직 주문이 없어요.'} />
          : list.rows.map((row, index) => {
            const code = String(row.stock_code ?? '');
            const quantity = num(row.quantity);
            const filled = quantity - num(row.remaining_quantity);
            const buy = row.side === 'BUY';
            const who = String(row.user_id ?? '');
            return (
              <Row key={String(row.id)} divided={index > 0} onPress={() => apply({ ...applied, userId: who })} accessibilityLabel={`주문 ${row.id}, ${row.nickname} 회원. 누르면 이 회원의 주문만 봐요`}>
                <View style={kit.grow}>
                  <View style={styles.inline}>
                    <SideTag buy={buy} />
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{names[code] ?? code}</Txt>
                    <Txt variant="caption" tone="inkSoft">{ORDER_STATUS[row.status] ?? row.status}</Txt>
                  </View>
                  <Txt variant="caption" tone="inkSoft" numeric numberOfLines={1}>{`${row.nickname ?? ''} #${who} · ${kstShort(row.created_at)}`}</Txt>
                </View>
                <View style={kit.right}>
                  <Txt variant="callout" numeric>{row.price ? `${price(num(row.price))}원` : '시장가'}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`${filled.toLocaleString('ko-KR')}/${quantity.toLocaleString('ko-KR')}주`}</Txt>
                </View>
              </Row>
            );
          })}
        {!list.done && list.rows.length > 0 ? (
          <View style={styles.more}><Button label="다음 50건" busy={list.loading} onPress={() => void list.load(params(applied), { before: list.lastId })} /></View>
        ) : null}
      </Card>
    </>
  );
}
