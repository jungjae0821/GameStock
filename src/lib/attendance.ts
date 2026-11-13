import { apiFetch } from "./api";

export type AttendanceDay = { date: string; streakDay: number; reward: number };

export type Attendance = {
  today: string;
  checkedToday: boolean;
  streak: number;
  todayReward: number;
  nextReward: number;
  longestStreak: number;
  totalDays: number;
  totalReward: number;
  month: string;
  days: AttendanceDay[];
};

/* 서버 AuthService.grantAttendanceReward와 같은 표. 5일차부터는 매일 5만원이 내 지갑으로 들어온다. */
export const ATTENDANCE_STEP = 10_000;
export const ATTENDANCE_STEPS = [1, 2, 3, 4, 5].map((day) => ({ day, reward: day * ATTENDANCE_STEP }));

/** 화면을 여는 요청에서 오늘 출석이 먼저 처리된다. month는 "2026-10" 형식. */
export function fetchAttendance(month: string | null, signal?: AbortSignal): Promise<Attendance> {
  return apiFetch<Attendance>(`/api/auth/attendance${month ? `?month=${encodeURIComponent(month)}` : ""}`, { signal });
}
