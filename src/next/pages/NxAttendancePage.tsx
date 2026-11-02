import { useEffect, useMemo, useState, type CSSProperties } from "react";
import { ATTENDANCE_STEPS, fetchAttendance, type Attendance, type AttendanceDay } from "../../lib/attendance";
import { AccountTabs } from "../AccountTabs";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** 100,000 → "10만원". 출석 보상은 모두 만원 단위다. */
function manWon(value: number): string {
  return `${(value / 10_000).toLocaleString("ko-KR")}만원`;
}

function shiftMonth(month: string, by: number): string {
  const [year, index] = month.split("-").map(Number);
  const next = new Date(Date.UTC(year, index - 1 + by, 1));
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, "0")}`;
}

function monthLabel(month: string): string {
  const [year, index] = month.split("-").map(Number);
  return `${year}년 ${index}월`;
}

/*
 * 보상 레인. 다섯 칸에 보상 노트가 떨어지고, 오늘의 연속 일수 칸 노트가 판정선에 닿는다.
 * 아직 닿지 않은 노트는 남은 일수만큼 위에 떠 있다.
 */
function RewardLane({ streak }: { streak: number }) {
  const hit = Math.min(streak, 5);
  return (
    <div className="nx-att-lane" role="img" aria-label={hit ? `보상 ${hit}단계: 하루 ${manWon(hit * 100_000)}` : "아직 이어 가는 출석이 없어요"}>
      {ATTENDANCE_STEPS.map((step) => {
        const away = step.day - hit;
        const state = away === 0 ? "is-hit" : away < 0 ? "is-passed" : "is-coming";
        return (
          <div key={step.day} className={`nx-att-col ${state}`} style={{ "--away": Math.max(away, 0), "--weight": step.day / 5 } as CSSProperties}>
            <span className="nx-att-note" />
            <span className="nx-att-reward nx-num">{manWon(step.reward)}</span>
            <span className="nx-att-day">{step.day === 5 ? "5일차부터" : `${step.day}일차`}</span>
          </div>
        );
      })}
      <span className="nx-att-judge" aria-hidden="true" />
    </div>
  );
}

function MonthGrid({ month, today, days }: { month: string; today: string; days: AttendanceDay[] }) {
  const byDate = useMemo(() => new Map(days.map((day) => [day.date, day])), [days]);
  const [year, index] = month.split("-").map(Number);
  const first = new Date(Date.UTC(year, index - 1, 1)).getUTCDay();
  const length = new Date(Date.UTC(year, index, 0)).getUTCDate();
  const cells: (string | null)[] = [...Array<null>(first).fill(null)];
  for (let day = 1; day <= length; day += 1) cells.push(`${month}-${String(day).padStart(2, "0")}`);
  while (cells.length % 7) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, row) => cells.slice(row * 7, row * 7 + 7));

  return (
    <table className="nx-att-cal">
      <caption className="nx-att-caption">{monthLabel(month)} 출석 기록</caption>
      <thead>
        <tr>{WEEKDAYS.map((name) => <th key={name} scope="col">{name}</th>)}</tr>
      </thead>
      <tbody>
        {weeks.map((week, row) => (
          <tr key={row}>
            {week.map((date, column) => {
              if (!date) return <td key={column} className="is-blank" />;
              const record = byDate.get(date);
              const next = week[column + 1];
              /* 같은 줄에서 다음 날도 출석했다면 칸을 이어 연속을 한 줄로 보여 준다. */
              const joined = record && next && byDate.get(next)?.streakDay === record.streakDay + 1;
              const day = Number(date.slice(8));
              const label = record ? `${index}월 ${day}일 출석, 연속 ${record.streakDay}일차, ${manWon(record.reward)}` : `${index}월 ${day}일`;
              return (
                <td key={column} className={`${record ? "is-on" : ""}${joined ? " is-joined" : ""}${date === today ? " is-today" : ""}`} aria-label={label}>
                  <span className="nx-att-date nx-num">{day}</span>
                  {record && <span className="nx-att-cell-reward nx-num">+{manWon(record.reward).replace("원", "")}</span>}
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function NxAttendancePage() {
  const [month, setMonth] = useState<string | null>(null);
  const [data, setData] = useState<Attendance | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    setError("");
    fetchAttendance(month, controller.signal)
      .then(setData)
      .catch(() => { if (!controller.signal.aborted) setError("출석 기록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."); });
    return () => controller.abort();
  }, [month]);

  const thisMonth = data?.today.slice(0, 7);
  const shown = data?.month ?? month;
  const monthDays = data?.days.length ?? 0;

  return (
    <div className="nx-screen nx-att">
      <AccountTabs group="funds" active="attendance" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">출석체크</h1>
          <p className="nx-screen-sub">하루에 한 번 접속하면 자동으로 출석돼요. 이어서 출석할수록 보상이 10만원씩 커지고, 하루를 건너뛰면 1일차부터 다시 시작해요.</p>
        </div>
      </header>

      {error && !data ? <p className="nx-note is-error">{error}</p> : !data ? (
        <div className="nx-skeleton" role="status" aria-label="출석 기록을 확인하는 중"><span /><span /><span /></div>
      ) : (
        <>
          <section className="nx-panel nx-att-hero" aria-label="오늘 출석">
            <div className="nx-att-today">
              <p className="nx-att-state">{data.checkedToday ? "오늘 출석했어요" : "오늘은 아직 출석 전이에요"}</p>
              <p className="nx-att-combo nx-num">{data.streak}<span>일 연속</span></p>
              <dl className="nx-att-pay">
                {data.checkedToday && (
                  <div>
                    <dt>오늘 받은 보상</dt>
                    <dd className="nx-num">{manWon(data.todayReward)}</dd>
                  </div>
                )}
                <div>
                  <dt>{data.checkedToday ? "내일도 접속하면" : "오늘 접속하면"}</dt>
                  <dd className="nx-num">{manWon(data.nextReward)}</dd>
                </div>
              </dl>
            </div>
            <RewardLane streak={data.streak} />
          </section>

          <section className="nx-panel nx-att-month" aria-labelledby="att-month-title">
            <div className="nx-att-month-head">
              <button type="button" className="nx-att-nav" aria-label="이전 달" onClick={() => setMonth(shiftMonth(shown ?? data.month, -1))}>
                <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M10 3.5L5.5 8l4.5 4.5" /></svg>
              </button>
              <h2 id="att-month-title" className="nx-att-month-title nx-num">{monthLabel(data.month)}</h2>
              <button type="button" className="nx-att-nav" aria-label="다음 달" disabled={data.month >= (thisMonth ?? data.month)} onClick={() => setMonth(shiftMonth(data.month, 1))}>
                <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M6 3.5L10.5 8 6 12.5" /></svg>
              </button>
              <p className="nx-att-month-count">{monthDays ? <>이 달에 <span className="nx-num">{monthDays}</span>일 출석</> : "이 달에는 출석 기록이 없어요"}</p>
            </div>
            {error && <p className="nx-msg is-error" role="alert">{error}</p>}
            <MonthGrid month={data.month} today={data.today} days={data.days} />
            <dl className="nx-figures nx-att-totals">
              <div><dt>지금까지 출석</dt><dd className="nx-num">{data.totalDays.toLocaleString("ko-KR")}일</dd></div>
              <div><dt>가장 길게 이은 연속</dt><dd className="nx-num">{data.longestStreak}일</dd></div>
              <div><dt>받은 출석 보상</dt><dd className="nx-num">{manWon(data.totalReward)}</dd></div>
            </dl>
            <p className="nx-muted">출석 보상은 현금으로 들어오고, 랭킹 수익률을 계산할 때는 빠져요.</p>
          </section>
        </>
      )}
    </div>
  );
}
