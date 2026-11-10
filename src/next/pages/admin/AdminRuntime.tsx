import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../../../lib/api";

type Runtime = {
  botsPaused: boolean;
  tradersPaused: boolean;
  marketPaused: boolean;
  cadenceMs: number | null;
  targetPerSymbol: number | null;
  traderRampSeconds: number | null;
  traderShare: number;
};
type Status = { runtime: Runtime; matchingEnabled: boolean; matchingOwner: boolean; metrics: Record<string, number> };
type Switch = "market" | "traders" | "bots";

const POLL_MS = 30_000;
const RAMP_POLL_MS = 5_000;
const FIELD: Record<Switch, keyof Runtime> = { market: "marketPaused", traders: "tradersPaused", bots: "botsPaused" };
const RAMP_OPTIONS = [
  { seconds: 0, label: "바로 전부" },
  { seconds: 60, label: "1분에 걸쳐" },
  { seconds: 180, label: "3분에 걸쳐" },
  { seconds: 300, label: "5분에 걸쳐" },
  { seconds: 600, label: "10분에 걸쳐" },
];
const DEFAULT_RAMP = 300;

const COPY: Record<Switch, { name: string; running: string; paused: string; pauseNote: string }> = {
  market: {
    name: "시장",
    running: "시장 정상 운영 중",
    paused: "시장 일시 정지 중. 신규 주문과 체결이 막혀 있고, 주문 취소만 됩니다.",
    pauseNote: "모든 종목의 신규 주문과 체결을 막습니다. 회원은 주문 취소만 할 수 있습니다.",
  },
  traders: {
    name: "트레이더 봇",
    running: "트레이더 봇 정상 운영 중",
    paused: "트레이더 봇 정지 중. 유동성 봇 호가는 남아 있어 회원은 계속 거래할 수 있습니다.",
    pauseNote: "트레이더 봇 주문만 멈추고 걸려 있던 트레이더 호가를 취소합니다. 유동성 봇은 계속 호가를 냅니다.",
  },
  bots: {
    name: "전체 봇",
    running: "유동성 봇 정상 운영 중",
    paused: "전체 봇 정지 중. 유동성 봇 호가까지 모두 빠져 있어 회원끼리만 체결됩니다.",
    pauseNote: "유동성 봇을 포함한 모든 봇 주문을 멈추고 봇 호가를 전부 취소합니다. 호가창이 비어 회원 거래가 사실상 멈춥니다. 정산 취소·시즌 초기화 전에만 쓰세요.",
  },
};

/**
 * 모든 관리 탭 위에 붙는 운영 상태 줄. 평소에는 종이 위의 한 줄이고, 시장·봇이
 * 멈췄거나 DB 저장이 막히면 전광판 면으로 바뀌어 무엇이 멈췄는지 문장으로 말한다.
 * 정지·재개는 사유를 받은 뒤에만 보낸다. 서버가 설정 전체를 교체하므로
 * 봇 주기·목표 호가는 읽어 둔 값을 그대로 돌려보낸다.
 */
export function AdminRuntime() {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Switch | null>(null);
  const [reason, setReason] = useState("");
  const [ramp, setRamp] = useState(DEFAULT_RAMP);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    try {
      setStatus(await apiFetch<Status>("/api/admin/dashboard"));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "운영 상태를 불러오지 못했습니다.");
    }
  }, []);

  const ramping = !!status && status.runtime.traderShare > 0 && status.runtime.traderShare < 1;

  useEffect(() => {
    let timer: number | undefined;
    const tick = () => {
      window.clearTimeout(timer);
      void load();
      timer = window.setTimeout(tick, ramping ? RAMP_POLL_MS : POLL_MS);
    };
    const onVisibility = () => {
      if (document.hidden) window.clearTimeout(timer);
      else tick();
    };
    tick();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [load, ramping]);

  if (!status) {
    return error ? <p className="runtime-bar is-loading" role="alert">운영 상태를 불러오지 못했습니다. {error}</p> : null;
  }

  const { runtime } = status;
  const paused: Record<Switch, boolean> = {
    market: runtime.marketPaused,
    traders: runtime.botsPaused || runtime.tradersPaused,
    bots: runtime.botsPaused,
  };
  const storageBlocked = (status.metrics["persistence.storageBlocked"] ?? 0) > 0;
  const notOwner = !status.matchingEnabled || !status.matchingOwner;
  const alarm = paused.market || paused.traders || storageBlocked;
  // Resuming either bot switch brings traders back only when the other switch is not still holding them.
  const resumesTraders = (target: Switch) =>
    (target === "traders" && !runtime.botsPaused) || (target === "bots" && !runtime.tradersPaused);

  const stateText = (target: Switch) => {
    if (target === "traders" && runtime.botsPaused) return "트레이더 봇도 전체 봇 정지에 포함되어 멈춰 있습니다.";
    if (target === "traders" && ramping) return `트레이더 봇 재가동 중. 현재 ${Math.round(runtime.traderShare * 100)}%만 거래합니다.`;
    return paused[target] ? COPY[target].paused : COPY[target].running;
  };

  const open = (target: Switch) => {
    setEditing(editing === target ? null : target);
    setReason("");
    setRamp(runtime.traderRampSeconds ?? DEFAULT_RAMP);
    setMessage("");
  };

  const submit = async () => {
    if (!editing) return;
    const resuming = paused[editing];
    const next: Runtime = { ...runtime, [FIELD[editing]]: !runtime[FIELD[editing]] };
    if (resuming && resumesTraders(editing)) next.traderRampSeconds = ramp;
    setBusy(true);
    setMessage("");
    try {
      const saved = await apiFetch<Runtime>("/api/admin/runtime", {
        method: "PUT",
        body: JSON.stringify({ ...next, reason: reason.trim() }),
      });
      setStatus({ ...status, runtime: saved });
      setMessage(`${COPY[editing].name}을 ${resuming ? "재개했습니다" : "일시 정지했습니다"}.`);
      setEditing(null);
      setReason("");
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "설정을 바꾸지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const resumeNote = (target: Switch) => {
    if (target === "bots" && runtime.tradersPaused) return "유동성 봇을 다시 운영합니다. 트레이더 봇은 따로 재개할 때까지 계속 멈춰 있습니다.";
    if (target === "bots") return "유동성 봇은 바로, 트레이더 봇은 아래에서 고른 속도로 다시 운영합니다.";
    return `${COPY[target].name}을 다시 운영합니다.`;
  };

  return (
    <section className={`runtime-bar${alarm ? " is-alarm" : ""}`} aria-label="운영 상태">
      <ul className="runtime-states">
        {(["market", "traders", "bots"] as const).map((target) => {
          const locked = target === "traders" && runtime.botsPaused;
          return (
            <li key={target} className={paused[target] ? "is-paused" : undefined}>
              <span className="runtime-state" role={paused[target] ? "alert" : undefined}>{stateText(target)}</span>
              {!locked && (
                <button type="button" className="runtime-toggle" aria-expanded={editing === target}
                  aria-label={paused[target] ? `${COPY[target].name} 재개` : `${COPY[target].name} 일시 정지`}
                  onClick={() => open(target)}>
                  {paused[target] ? `${COPY[target].name} 재개` : `${COPY[target].name} 정지`}
                </button>
              )}
            </li>
          );
        })}
        {storageBlocked && <li className="is-paused"><span className="runtime-state" role="alert">DB 저장이 멈춰 새 주문이 쌓이고 있습니다.</span></li>}
        {notOwner && <li><span className="runtime-state">이 서버는 매칭을 맡고 있지 않습니다.</span></li>}
      </ul>

      {editing && (
        <form className="runtime-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <p className="runtime-note">{paused[editing] ? resumeNote(editing) : COPY[editing].pauseNote}</p>
          {paused[editing] && resumesTraders(editing) && (
            <>
              <label htmlFor="runtime-ramp">트레이더 봇 재가동 속도</label>
              <select id="runtime-ramp" value={ramp} onChange={(event) => setRamp(Number(event.target.value))}>
                {RAMP_OPTIONS.map((option) => <option key={option.seconds} value={option.seconds}>{option.label}</option>)}
              </select>
              <p className="runtime-hint">
                {ramp > 0
                  ? "처음엔 트레이더 5%만 거래하고 고른 시간 동안 100%까지 늘립니다. 정지 중 벌어진 가격으로 봇이 한꺼번에 몰리는 것을 막습니다."
                  : "모든 트레이더가 즉시 거래를 시작합니다. 정지 중 가격이 많이 움직였다면 순간 변동이 클 수 있습니다."}
              </p>
            </>
          )}
          <label htmlFor="runtime-reason">사유 (감사 기록에 남음)</label>
          <div className="runtime-form-row">
            <input id="runtime-reason" value={reason} maxLength={300} required autoFocus
              placeholder={paused[editing] ? "예: 장애 복구 완료" : "예: 체결 오류 조사"}
              onChange={(event) => setReason(event.target.value)} />
            <button type="submit" className={`account-button${paused[editing] ? " is-primary" : " is-danger"}`} disabled={busy || !reason.trim()}>
              {paused[editing] ? `${COPY[editing].name} 재개` : `${COPY[editing].name} 정지`}
            </button>
            <button type="button" className="text-button" onClick={() => setEditing(null)}>취소</button>
          </div>
        </form>
      )}
      {message && <p className="runtime-message" role="status">{message}</p>}
    </section>
  );
}
