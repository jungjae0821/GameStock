import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useAccount } from '../../account';
import { request } from '../../auth';
import { AccountFrame, Button, Card, CardHead, Empty, Field, Figure, Notice, Row, SideTag, kit, kstShort } from '../../components/Kit';
import { won } from '../../format';
import { RADIUS, SPACE, Txt, useTheme } from '../../theme';

const KIND_LABEL = { BORROW: '대출', REPAY: '상환', INTEREST: '이자', LIQUIDATION: '반대매매 상환' };
const RATIOS = [
  { label: '10%', ratio: 0.1 },
  { label: '25%', ratio: 0.25 },
  { label: '50%', ratio: 0.5 },
  { label: '최대', ratio: 1 },
];
// 담보비율 게이지는 100%~300% 구간을 그린다. 300%가 넘으면 꽉 찬다.
const GAUGE_MAX = 3;
const gaugeShare = ratio => Math.max(0, Math.min(1, (ratio - 1) / (GAUGE_MAX - 1)));
const percent = (ratio, digits = 0) => `${(ratio * 100).toFixed(digits)}%`;
const digits = value => value.replace(/[^0-9]/g, '');
const grouped = value => (value ? Number(value).toLocaleString('ko-KR') : '');

/**
 * 담보비율 게이지(DESIGN.md). 100%~300% 트랙에 반대매매·경고 구간을 트랙 대비 3:1 넘는 진한 면으로 깔고,
 * 지금 비율은 핑크 판정선 하나로 꽂는다. 판정선은 카드색 테두리로 구간 색과 떨어뜨린다.
 */
function Gauge({ loan }) {
  const theme = useTheme();
  const ratio = loan.collateralRatio;
  const danger = gaugeShare(loan.liquidationRatio);
  const warning = gaugeShare(loan.warningRatio);
  const zone = ratio == null ? 'none' : ratio < loan.liquidationRatio ? 'danger' : ratio < loan.warningRatio ? 'warning' : 'safe';
  const valueTone = zone === 'danger' ? 'up' : zone === 'warning' ? 'guardText' : 'ink';
  return (
    <View style={styles.gauge}>
      <View style={kit.between} accessible accessibilityLabel={`담보비율 ${ratio == null ? '대출 없음' : percent(ratio)}. 반대매매 ${percent(loan.liquidationRatio)}, 경고 ${percent(loan.warningRatio)}`}>
        <View>
          <Txt variant="callout">담보비율</Txt>
          <Txt variant="caption" tone="inkSoft">총 자산 ÷ 대출 잔액</Txt>
        </View>
        <Txt variant="page" tone={valueTone} numeric>{ratio == null ? '대출 없음' : percent(ratio)}</Txt>
      </View>
      <View style={[styles.track, { backgroundColor: theme.raised }]} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        <View style={[styles.zone, { left: 0, width: `${danger * 100}%`, backgroundColor: theme.gaugeDanger, borderTopLeftRadius: RADIUS, borderBottomLeftRadius: RADIUS }]} />
        <View style={[styles.zone, { left: `${danger * 100}%`, width: `${(warning - danger) * 100}%`, backgroundColor: theme.gaugeWarn }]} />
        {ratio != null ? <View style={[styles.now, { left: `${gaugeShare(ratio) * 100}%`, backgroundColor: theme.brand, borderColor: theme.card }]} /> : null}
      </View>
      <View style={styles.scale} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        <Txt variant="micro" tone="inkSoft" numeric>100%</Txt>
        <Txt variant="micro" tone="inkSoft" numeric>300%+</Txt>
      </View>
      {/* 구간 경계는 폭이 좁은 폰에서 눈금 글자가 겹치므로 눈금 대신 범례로 적는다. */}
      <View style={styles.legend} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: theme.gaugeDanger }]} />
          <Txt variant="caption" tone="inkSoft" numeric>{`반대매매 ${percent(loan.liquidationRatio)} 미만`}</Txt>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.legendDot, { backgroundColor: theme.gaugeWarn }]} />
          <Txt variant="caption" tone="inkSoft" numeric>{`경고 ${percent(loan.warningRatio)} 미만`}</Txt>
        </View>
      </View>
    </View>
  );
}

/** 대출. 할 일은 빌리고 갚기다. 규칙과 서버 호출은 웹 대출 화면과 같다. */
export function LoanScreen({ onBack }) {
  const account = useAccount();
  const [loan, setLoan] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [borrow, setBorrow] = useState('');
  const [repay, setRepay] = useState('');
  const [pending, setPending] = useState(null);
  const [message, setMessage] = useState(null);
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const load = useCallback(async () => {
    try {
      const next = await request('/api/loan');
      if (!alive.current) return;
      setLoan(next);
      setLoadError('');
    } catch (failure) {
      if (alive.current) setLoadError(failure.message || '대출 정보를 불러오지 못했어요.');
    }
  }, []);

  // 한도와 담보비율은 시세에 따라 바뀌므로 10초마다 다시 읽는다.
  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 10_000);
    return () => clearInterval(timer);
  }, [load]);

  const cash = Number(account.portfolio?.cash ?? loan?.cash) || 0;
  const balance = loan?.balance ?? 0;
  const repayMax = Math.min(cash, balance);
  const borrowValue = Number(borrow) || 0;
  const repayValue = Number(repay) || 0;

  const act = async (kind, amount) => {
    if (pending) return;
    setPending(kind);
    setMessage(null);
    try {
      const result = await request(`/api/loan/${kind}`, { method: 'POST', body: { amount } });
      if (!alive.current) return;
      setLoan(result.loan);
      setMessage({ text: result.message, ok: true });
      if (kind === 'borrow') setBorrow(''); else setRepay('');
      void account.reload();
    } catch (failure) {
      if (alive.current) setMessage({ text: failure.message, ok: false });
    } finally {
      if (alive.current) setPending(null);
    }
  };

  if (!loan) {
    return (
      <AccountFrame title="대출" onBack={onBack} onRefresh={load}>
        <Card><Empty loading={!loadError} text={loadError || '대출 정보를 불러오는 중이에요'} action={loadError ? '다시 시도' : null} onAction={() => void load()} /></Card>
      </AccountFrame>
    );
  }

  const borrowError = !borrow ? ''
    : borrowValue < loan.minAmount ? `${won(loan.minAmount)}부터 빌릴 수 있어요.`
    : borrowValue > loan.available ? `지금은 최대 ${won(loan.available)}까지 빌릴 수 있어요.` : '';
  const repayError = !repay ? '' : repayValue < 1 ? '갚을 금액을 입력해 주세요.'
    : repayValue > repayMax ? `지금은 최대 ${won(repayMax)}까지 갚을 수 있어요.` : '';
  const blockedReason = loan.borrowingOpen === false ? '신규 대출은 중단됐어요. 운용계좌는 회사 돈이라 직급에 따른 운용한도로만 굴려요. 이미 빌린 돈은 여기서 갚을 수 있어요.'
    : !loan.borrowBlocked || loan.warning ? ''
    : loan.limit - loan.balance < loan.minAmount ? `남은 한도가 최소 대출 금액 ${won(loan.minAmount)}보다 적어 지금은 빌릴 수 없어요.`
    : '관리자 계정은 대출을 이용할 수 없어요.';
  const ratio = loan.collateralRatio;

  return (
    <AccountFrame title="대출" onBack={onBack} onRefresh={load}>
      {loan.warning ? (
        <Notice tone="guard" text={`담보비율이 ${percent(ratio ?? 0)}예요. ${percent(loan.warningRatio)} 밑에서는 새로 빌릴 수 없고, ${percent(loan.liquidationRatio)} 밑으로 내려가면 보유 주식이 자동으로 팔려 대출을 갚아요(반대매매).`} />
      ) : null}

      <View style={kit.hero}>
        <Txt variant="caption" tone="inkSoft">대출 잔액</Txt>
        <Txt variant="price" numeric numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6}>{won(loan.balance)}</Txt>
      </View>

      <Card>
        <View style={styles.figures}>
          <Figure label="대출 한도" value={won(loan.limit)} />
          <Figure label="지금 빌릴 수 있는 금액" value={won(loan.available)} />
          <Figure label="순자산" value={won(loan.netAsset)} />
        </View>
        <Gauge loan={loan} />
        {blockedReason ? <Notice text={blockedReason} /> : null}
        <View style={styles.rules}>
          <Txt variant="caption" tone="inkSoft">{`한도는 순자산의 ${percent(loan.limitRatio)}예요.`}</Txt>
          <Txt variant="caption" tone="inkSoft">{`이자는 하루 ${percent(loan.dailyInterestRate, 1)}이고, 한국시간 자정마다 잔액에 붙어요.`}</Txt>
          <Txt variant="caption" tone="inkSoft">언제든 일부 또는 전액을 수수료 없이 갚을 수 있어요.</Txt>
        </View>
      </Card>

      {message ? <Notice text={message.text} tone={message.ok ? 'ok' : 'neutral'} /> : null}

      <Card>
        <CardHead title="빌리기" meta={`최대 ${won(loan.available)}`} />
        <Field
          label="빌릴 금액"
          numeric
          unit="원"
          keyboardType="number-pad"
          value={grouped(borrow)}
          onChangeText={text => setBorrow(digits(text))}
          placeholder={loan.borrowBlocked ? '지금은 빌릴 수 없어요' : '0'}
          editable={!loan.borrowBlocked && !pending}
          error={borrowError}
          hint={borrowValue > 0 ? `하루 이자 약 ${won(Math.ceil(borrowValue * loan.dailyInterestRate))}` : ''}
        />
        <View style={styles.ratios}>
          {RATIOS.map(item => (
            <Button key={item.label} label={item.label} small style={kit.grow} disabled={loan.borrowBlocked || pending != null}
              onPress={() => setBorrow(String(Math.floor(loan.available * item.ratio)))} accessibilityLabel={`빌릴 수 있는 금액의 ${item.label}`} />
          ))}
        </View>
        <Button label="대출받기" kind="primary" busy={pending === 'borrow'} disabled={loan.borrowBlocked || pending != null || !borrow || Boolean(borrowError)}
          onPress={() => void act('borrow', borrowValue)} />
      </Card>

      <Card>
        <CardHead title="갚기" meta={`보유 현금 ${won(cash)}`} />
        <Field
          label="갚을 금액"
          numeric
          unit="원"
          keyboardType="number-pad"
          value={grouped(repay)}
          onChangeText={text => setRepay(digits(text))}
          placeholder={balance === 0 ? '갚을 대출이 없어요' : won(repayMax)}
          editable={balance > 0 && !pending}
          error={repayError}
          hint={balance > cash ? '보유 현금이 잔액보다 적어요. 주식을 팔면 더 갚을 수 있어요.' : '비워 두면 갚을 수 있는 만큼 전부 갚아요.'}
        />
        <Button label={repay ? '상환하기' : '전액 상환'} kind="primary" busy={pending === 'repay'}
          disabled={balance === 0 || repayMax === 0 || pending != null || Boolean(repayError)}
          onPress={() => void act('repay', repay ? repayValue : repayMax)} />
      </Card>

      <Card padded={false}>
        <CardHead padded title="대출 내역" meta={`최근 ${loan.history.length}건`} />
        {loan.history.length === 0 ? <Empty text="아직 대출 내역이 없어요." /> : loan.history.map((entry, index) => (
          <Row key={entry.id} divided={index > 0} accessibilityLabel={`${kstShort(entry.createdAt)} ${KIND_LABEL[entry.kind] ?? entry.kind} ${won(entry.amount)}, 남은 잔액 ${won(entry.balanceAfter)}`}>
            {/* 잔액이 늘어나는 기록(대출·이자)은 적, 줄어드는 기록(상환)은 청. */}
            <SideTag buy={entry.kind === 'BORROW' || entry.kind === 'INTEREST'} label={KIND_LABEL[entry.kind] ?? entry.kind} />
            <Txt variant="caption" tone="inkSoft" numeric style={kit.grow}>{kstShort(entry.createdAt)}</Txt>
            <View style={kit.right}>
              <Txt variant="callout" numeric>{won(entry.amount)}</Txt>
              <Txt variant="caption" tone="inkSoft" numeric>{`잔액 ${won(entry.balanceAfter)}`}</Txt>
            </View>
          </Row>
        ))}
      </Card>
      <Txt variant="micro" tone="inkSoft" style={styles.footer}>빌린 돈은 수익률과 랭킹에 넣지 않아요. 실제 돈은 오가지 않아요.</Txt>
    </AccountFrame>
  );
}

const styles = StyleSheet.create({
  figures: { flexDirection: 'row', gap: SPACE.md },
  gauge: { gap: SPACE.sm, marginTop: SPACE.xs },
  track: { height: 14, borderRadius: RADIUS, marginTop: SPACE.sm },
  zone: { position: 'absolute', top: 0, bottom: 0 },
  now: { position: 'absolute', top: -7, bottom: -7, width: 7, borderRadius: 3, borderWidth: 2, marginLeft: -3.5 },
  scale: { flexDirection: 'row', justifyContent: 'space-between' },
  legend: { flexDirection: 'row', flexWrap: 'wrap', columnGap: SPACE.lg, rowGap: 4 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  legendDot: { width: 10, height: 10, borderRadius: 3 },
  rules: { gap: 4 },
  ratios: { flexDirection: 'row', gap: 6 },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
});
