import { useRef, useState, type KeyboardEvent } from "react";
import { setFandom, setPublicProfile, useEngagement } from "../../lib/engagement";
import { LISTINGS, LISTING_BY_CODE } from "../../market/universe";
import { GameIcon } from "../../components/GameIcon";
import { ApiError } from "../../lib/api";

const errorText = (error: unknown) => (error instanceof ApiError || error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.");

/** 주력 게임 하나. 프로필과 랭킹에 함께 보이고, 팬덤 대항전의 소속이 된다. */
export function FandomSettings() {
  const engagement = useEngagement();
  const [pending, setPending] = useState<string | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [focusIndex, setFocusIndex] = useState<number | null>(null);
  const grid = useRef<HTMLDivElement>(null);
  if (!engagement) return null;
  const current = engagement.fandomCode;
  // Tab으로는 묶음 전체가 한 칸이다. 들어오면 고른 게임(없으면 첫 게임)에 서고, 방향키로 옮긴다.
  const selectedIndex = Math.max(0, LISTINGS.findIndex((listing) => listing.code === current));
  const active = focusIndex ?? selectedIndex;

  /* 방향키는 초점만 옮긴다. 고르면 바로 저장되고 일주일 잠기므로, 고르기는 Enter·Space(버튼 기본 동작)로만 한다. */
  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const columns = Math.max(1, getComputedStyle(grid.current!).gridTemplateColumns.split(" ").length);
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns, Home: -LISTINGS.length, End: LISTINGS.length }[event.key];
    if (step === undefined) return;
    event.preventDefault();
    const next = Math.min(LISTINGS.length - 1, Math.max(0, active + step));
    setFocusIndex(next);
    grid.current?.querySelectorAll<HTMLButtonElement>("button")[next]?.focus();
  };
  const lockedUntil = engagement.fandomChangeAvailableAt;

  const choose = async (code: string) => {
    if (code === current) return;
    setPending(code);
    setMessage(null);
    try {
      await setFandom(code);
      setMessage({ ok: true, text: `${LISTING_BY_CODE[code]?.name ?? code} 팬덤에 들어갔어요.` });
    } catch (error) {
      setMessage({ ok: false, text: errorText(error) });
    } finally {
      setPending(null);
    }
  };

  return (
    <section className="nx-panel" aria-labelledby="nx-fandom-title">
      <header className="nx-panel-head is-stacked">
        <h2 id="nx-fandom-title" className="nx-panel-title">주력 게임</h2>
        <p className="nx-muted">
          {current ? "팬덤 대항전에서 이 게임 편으로 집계돼요." : "응원하는 게임을 하나 골라 주세요. 처음 고를 때는 바로 정해져요."}
          {lockedUntil && ` 다음 변경은 ${new Date(lockedUntil).toLocaleDateString("ko-KR", { month: "numeric", day: "numeric", timeZone: "Asia/Seoul" })}부터 할 수 있어요.`}
        </p>
      </header>
      <div ref={grid} className="nx-fandom-grid" role="group" aria-labelledby="nx-fandom-title" onKeyDown={move}>
        {LISTINGS.map((listing, index) => {
          const selected = listing.code === current;
          const blocked = pending !== null || (!!lockedUntil && !selected);
          return (
            <button key={listing.code} type="button" aria-pressed={selected} tabIndex={index === active ? 0 : -1}
              className={`nx-fandom-option${selected ? " is-active" : ""}`}
              aria-disabled={blocked || undefined} onFocus={() => setFocusIndex(index)}
              onClick={() => { if (!blocked) void choose(listing.code); }}>
              <GameIcon code={listing.code} size="table" />
              <span>{listing.name}</span>
            </button>
          );
        })}
      </div>
      {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}
    </section>
  );
}

/** 랭킹에서 내 보유 종목을 보여 주고, 체결 피드에 닉네임을 띄울지 정한다. 기본은 비공개. */
export function PublicProfileSettings() {
  const engagement = useEngagement();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  if (!engagement) return null;
  const open = engagement.publicProfile;

  const toggle = async () => {
    setPending(true);
    setError("");
    try {
      await setPublicProfile(!open);
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setPending(false);
    }
  };

  return (
    <section className="nx-panel nx-public-setting" aria-labelledby="nx-public-title">
      <div>
        <h2 id="nx-public-title" className="nx-panel-title">포트폴리오 공개</h2>
        <p className="nx-muted">켜면 랭킹에서 다른 사람이 내 보유 종목을 볼 수 있고, 실시간 체결 소식에 내 닉네임이 보여요.</p>
        {error && <p className="nx-msg is-error" role="alert">{error}</p>}
      </div>
      <button type="button" role="switch" aria-checked={open} aria-labelledby="nx-public-title" className={`nx-switch${open ? " is-on" : ""}`}
        onClick={() => void toggle()} disabled={pending}>
        <span className="nx-switch-knob" aria-hidden="true" />
        <span className="nx-switch-text">{open ? "공개" : "비공개"}</span>
      </button>
    </section>
  );
}
