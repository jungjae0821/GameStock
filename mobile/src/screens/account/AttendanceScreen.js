import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request } from '../../auth';
import { AccountFrame, Card, CardHead, Empty, Figure, Notice, kit } from '../../components/Kit';
import { RADIUS_SM, SPACE, Txt, useTheme } from '../../theme';

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
// 서버 AuthService.grantAttendanceReward와 같은 표. 5일차부터는 매일 5만원이 내 지갑으로 들어온다.
const STEP = 10_000;
const STEPS = [1, 2, 3, 4, 5].map(day => ({ day, reward: day * STEP }));

/** 10,000 → "1만원". 출석 보상은 모두 만원 단위다. */
const manWon = value => `${(value / 10_000).toLocaleString('ko-KR')}만원`;
const monthLabel = month => { const [year, index] = month.split('-').map(Number); return `${year}년 ${index}월`; };
function shiftMonth(month, by) {
  const [year, index] = month.split('-').map(Number);
  const next = new Date(Date.UTC(year, index - 1 + by, 1));
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** 출석체크. 화면을 여는 요청에서 오늘 출석이 먼저 처리된다(웹 NxAttendancePage와 같은 API). */
export function AttendanceScreen({ onBack }) {
  const [month, setMonth] = useState(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  const load = useCallback(async (target = month) => {
    setError('');
    try {
      const next = await request(`/api/auth/attendance${target ? `?month=${encodeURIComponent(target)}` : ''}`);
      if (alive.current) setData(next);
    } catch {
      if (alive.current) setError('출석 기록을 불러오지 못했어요. 아래로 당겨 다시 불러오세요.');
    }
  }, [month]);
  useEffect(() => { void load(month); }, [month]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) {
    return (
      <AccountFrame title="출석체크" onBack={onBack} onRefresh={() => load()}>
        <Card><Empty loading={!error} text={error || '출석 기록을 확인하는 중이에요'} action={error ? '다시 시도' : null} onAction={() => void load()} /></Card>
      </AccountFrame>
    );
  }

  const thisMonth = data.today.slice(0, 7);
  return (
    <AccountFrame title="출석체크" onBack={onBack} onRefresh={() => load()}>
      <Txt variant="callout" tone="inkSoft" style={styles.lead}>
        하루에 한 번 앱을 열면 자동으로 출석돼요. 이어서 출석할수록 보상이 1만원씩 커지고, 하루를 건너뛰면 1일차부터 다시 시작해요. 보상은 내 지갑으로 들어와요.
      </Txt>
      <Card>
        <Txt variant="callout" tone="inkSoft">{data.checkedToday ? '오늘 출석했어요' : '오늘은 아직 출석 전이에요'}</Txt>
        <View style={styles.combo}>
          <Txt variant="hero" numeric>{data.streak}</Txt>
          <Txt variant="headline">일 연속</Txt>
        </View>
        <View style={styles.figures}>
          {data.checkedToday ? <Figure label="오늘 받은 보상" value={manWon(data.todayReward)} /> : null}
          <Figure label={data.checkedToday ? '내일도 열면' : '오늘 열면'} value={manWon(data.nextReward)} />
        </View>
        <Steps streak={data.streak} />
      </Card>

      <Card>
        <View style={kit.between}>
          <MonthButton icon="chevron-back" label="이전 달" onPress={() => setMonth(shiftMonth(data.month, -1))} />
          <View style={styles.monthTitle}>
            <Txt variant="headline" accessibilityRole="header" numeric>{monthLabel(data.month)}</Txt>
            <Txt variant="caption" tone="inkSoft" numeric>{data.days.length ? `이 달에 ${data.days.length}일 출석` : '이 달에는 출석 기록이 없어요'}</Txt>
          </View>
          <MonthButton icon="chevron-forward" label="다음 달" disabled={data.month >= thisMonth} onPress={() => setMonth(shiftMonth(data.month, 1))} />
        </View>
        {error ? <Notice text={error} /> : null}
        <MonthGrid month={data.month} today={data.today} days={data.days} />
      </Card>

      <Card>
        <CardHead title="지금까지" />
        <View style={styles.figures}>
          <Figure label="출석한 날" value={`${data.totalDays.toLocaleString('ko-KR')}일`} />
          <Figure label="가장 긴 연속" value={`${data.longestStreak}일`} />
          <Figure label="받은 보상" value={manWon(data.totalReward)} />
        </View>
        <Txt variant="caption" tone="inkSoft">출석 보상은 운용계좌가 아니라 내 지갑으로 들어와요.</Txt>
      </Card>
    </AccountFrame>
  );
}

/** 연속 일수별 보상 다섯 칸. 오늘의 칸만 잉크 면이고, 지난 칸은 흐리게 둔다. */
function Steps({ streak }) {
  const theme = useTheme();
  const hit = Math.min(streak, 5);
  return (
    <View style={styles.steps} accessible accessibilityLabel={hit ? `보상 ${hit}단계: 하루 ${manWon(hit * STEP)}` : '아직 이어 가는 출석이 없어요'}>
      {STEPS.map(step => {
        const now = step.day === hit;
        const passed = step.day < hit;
        return (
          <View key={step.day} style={[styles.step, { backgroundColor: now ? theme.ink : theme.raised, opacity: passed ? 0.55 : 1 }]}>
            <Txt variant="caption" numeric style={{ color: now ? theme.ground : theme.ink }}>{manWon(step.reward)}</Txt>
            <Txt variant="micro" style={{ color: now ? theme.ground : theme.inkSoft }}>{step.day === 5 ? '5일차~' : `${step.day}일차`}</Txt>
          </View>
        );
      })}
    </View>
  );
}

function MonthButton({ icon, label, disabled, onPress }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: Boolean(disabled) }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.monthButton, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : 'transparent', opacity: disabled ? 0.35 : 1 }]}
    >
      <Ionicons name={icon} size={20} color={theme.ink} />
    </Pressable>
  );
}

/** 달력. 출석한 날은 잉크 면에 그날 받은 보상을 적는다. */
function MonthGrid({ month, today, days }) {
  const theme = useTheme();
  const byDate = new Map(days.map(day => [day.date, day]));
  const [year, index] = month.split('-').map(Number);
  const first = new Date(Date.UTC(year, index - 1, 1)).getUTCDay();
  const length = new Date(Date.UTC(year, index, 0)).getUTCDate();
  const cells = Array(first).fill(null);
  for (let day = 1; day <= length; day += 1) cells.push(`${month}-${String(day).padStart(2, '0')}`);
  while (cells.length % 7) cells.push(null);
  return (
    <View>
      <View style={styles.week} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        {WEEKDAYS.map(name => <Txt key={name} variant="micro" tone="inkSoft" style={styles.weekday}>{name}</Txt>)}
      </View>
      <View style={styles.grid}>
        {cells.map((date, position) => {
          if (!date) return <View key={`blank-${position}`} style={styles.cell} />;
          const record = byDate.get(date);
          const day = Number(date.slice(8));
          const isToday = date === today;
          return (
            <View
              key={date}
              accessible
              accessibilityLabel={record ? `${index}월 ${day}일 출석, 연속 ${record.streakDay}일차, ${manWon(record.reward)}` : `${index}월 ${day}일`}
              style={styles.cell}
            >
              <View style={[styles.cellFace, record && { backgroundColor: theme.ink }, isToday && !record && { borderWidth: 1, borderColor: theme.edge }]}>
                <Txt variant="caption" numeric style={{ color: record ? theme.ground : theme.ink }}>{day}</Txt>
                {record ? <Txt variant="micro" numeric style={{ color: theme.ground }}>{`+${record.reward / 10_000}만`}</Txt> : null}
              </View>
            </View>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  lead: { paddingHorizontal: SPACE.xs, marginBottom: SPACE.md },
  combo: { flexDirection: 'row', alignItems: 'baseline', gap: SPACE.xs },
  figures: { flexDirection: 'row', gap: SPACE.md },
  steps: { flexDirection: 'row', gap: SPACE.xs },
  step: { flex: 1, minWidth: 0, borderRadius: RADIUS_SM, paddingVertical: SPACE.sm, alignItems: 'center', gap: 2 },
  monthTitle: { flex: 1, alignItems: 'center' },
  monthButton: { width: 44, height: 44, borderRadius: RADIUS_SM, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  week: { flexDirection: 'row' },
  weekday: { width: `${100 / 7}%`, textAlign: 'center', paddingVertical: SPACE.xs },
  grid: { flexDirection: 'row', flexWrap: 'wrap' },
  cell: { width: `${100 / 7}%`, aspectRatio: 1, padding: 2 },
  cellFace: { flex: 1, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center' },
});
