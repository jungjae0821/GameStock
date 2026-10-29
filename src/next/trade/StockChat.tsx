import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { openLoginPrompt, useAuthUser } from "../../lib/auth";
import { useProfile } from "../../lib/profile";
import { CHAT_MAX_LENGTH, deleteChat, failure, isAbort, loadChat, sendChat, type ChatEntry } from "../../lib/research";
import { clock, kstDateTime, serverTimestamp } from "../../market/format";
import { MARKET_PATH } from "../../router";

/** 새 메시지 조회 간격. 서버 이벤트가 없어 커서로 따라잡는다. */
const POLL_MS = 2_000;
/** 지워진 메시지는 after 커서로 오지 않아 최신 목록을 가끔 다시 받아 맞춘다. */
const REFRESH_MS = 30_000;
/** 한 번에 따라잡을 최대 페이지 수. 넘으면 다음 폴링에서 이어 받는다. */
const CATCH_UP_PAGES = 5;

/* 보낸 메시지의 authorId를 기억해 내 메시지를 알아본다. 프로필 응답에는 사용자 ID가 없다. */
const authorKey = (uid: string) => `gamestock.chat.author.${uid}`;
function readAuthor(uid: string | null): number | null {
  if (!uid) return null;
  try { const value = Number(window.localStorage.getItem(authorKey(uid))); return Number.isFinite(value) && value > 0 ? value : null; } catch { return null; }
}
function rememberAuthor(uid: string, id: number) {
  try { window.localStorage.setItem(authorKey(uid), String(id)); } catch { /* 저장소가 막혀 있으면 이번 화면에서만 기억한다. */ }
}

/** ID 오름차순으로 합친다. 같은 ID는 나중 값(삭제 표시 등)으로 바꾼다. */
function merge(current: ChatEntry[], incoming: ChatEntry[]): ChatEntry[] {
  if (incoming.length === 0) return current;
  const byId = new Map(current.map((entry) => [entry.id, entry]));
  for (const entry of incoming) byId.set(entry.id, entry);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

const length = (text: string) => text.trim().length;

/** 종목별 실시간 채팅. 짧은 대화용이라 답글·좋아요 없이 시간순으로만 쌓는다. */
export function StockChat({ code, name }: { code: string; name: string }) {
  const { user, ready } = useAuthUser();
  const profile = useProfile();
  const uid = user?.uid ?? null;
  const [entries, setEntries] = useState<ChatEntry[] | null>(null);
  const [olderMore, setOlderMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState("");
  const [author, setAuthor] = useState<number | null>(() => readAuthor(uid));
  const after = useRef(0);
  const list = useRef<HTMLOListElement>(null);
  const stick = useRef(true);
  const prepend = useRef<{ height: number; top: number } | null>(null);

  useEffect(() => { setAuthor(readAuthor(uid)); }, [uid]);

  const advance = (items: ChatEntry[]) => {
    for (const entry of items) if (entry.id > after.current) after.current = entry.id;
  };

  /* 최신 50개를 받아 기존 목록과 합친다. 처음 열 때와 지워진 글을 맞출 때 쓴다. */
  const refresh = useCallback(async (signal?: AbortSignal) => {
    const page = await loadChat(code, {}, signal);
    advance(page.items);
    setEntries((current) => merge(current ?? [], page.items));
    return page;
  }, [code]);

  useEffect(() => {
    setEntries(null);
    setError("");
    after.current = 0;
    stick.current = true;
    const controller = new AbortController();
    let pollTimer = 0;
    let lastRefresh = Date.now();
    let polling = false;

    const poll = async () => {
      if (polling || document.hidden) return;
      polling = true;
      try {
        if (Date.now() - lastRefresh >= REFRESH_MS) {
          lastRefresh = Date.now();
          await refresh(controller.signal);
        }
        for (let pageCount = 0; pageCount < CATCH_UP_PAGES; pageCount += 1) {
          const page = await loadChat(code, { after: after.current }, controller.signal);
          advance(page.items);
          if (page.items.length) setEntries((current) => merge(current ?? [], page.items));
          if (!page.hasMore || page.nextCursor === null) break;
        }
        setError("");
      } catch (reason) {
        if (!isAbort(reason)) setError(failure(reason, "새 메시지를 받지 못했어요. 잠시 후 다시 연결해요."));
      } finally {
        polling = false;
      }
    };

    const start = () => {
      window.clearInterval(pollTimer);
      if (document.hidden) return;
      pollTimer = window.setInterval(() => void poll(), POLL_MS);
    };
    /* 탭으로 돌아오면 놓친 메시지를 바로 받고 폴링을 다시 시작한다. */
    const onVisibility = () => {
      if (document.hidden) window.clearInterval(pollTimer);
      else { void poll(); start(); }
    };

    refresh(controller.signal).then((page) => {
      setOlderMore(page.hasMore);
      start();
    }).catch((reason: unknown) => {
      if (isAbort(reason)) return;
      setEntries([]);
      setError(failure(reason, "채팅을 불러오지 못했어요."));
      start();
    });
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      controller.abort();
      window.clearInterval(pollTimer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [code, refresh]);

  /* 맨 아래를 보고 있을 때만 새 메시지를 따라 내려간다. 과거 글을 붙이면 보던 위치를 지킨다. */
  useLayoutEffect(() => {
    const element = list.current;
    if (!element) return;
    if (prepend.current) {
      element.scrollTop = prepend.current.top + (element.scrollHeight - prepend.current.height);
      prepend.current = null;
    } else if (stick.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [entries]);

  const older = async () => {
    const oldest = entries?.[0];
    if (!oldest || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const page = await loadChat(code, { before: oldest.id });
      const element = list.current;
      if (element) prepend.current = { height: element.scrollHeight, top: element.scrollTop };
      setEntries((current) => merge(current ?? [], page.items));
      setOlderMore(page.hasMore);
    } catch (reason) {
      setError(failure(reason, "이전 메시지를 불러오지 못했어요."));
    } finally {
      setLoadingOlder(false);
    }
  };

  const sent = (entry: ChatEntry) => {
    if (uid) { rememberAuthor(uid, entry.authorId); setAuthor(entry.authorId); }
    stick.current = true;
    setEntries((current) => merge(current ?? [], [entry]));
  };

  const removed = async (entry: ChatEntry) => {
    if (!window.confirm("이 메시지를 지울까요?")) return;
    try {
      await deleteChat(code, entry.id);
      setEntries((current) => current && current.map((item) => item.id === entry.id ? { ...item, deleted: true, body: "" } : item));
    } catch (reason) {
      setError(failure(reason, "메시지를 지우지 못했어요."));
      void refresh().catch(() => undefined);
    }
  };

  const mine = (entry: ChatEntry) => author !== null ? entry.authorId === author : !!profile && entry.nickname === profile.nickname;
  const canDelete = (entry: ChatEntry) => !!user && !entry.deleted && (mine(entry) || !!profile?.admin);

  return (
    <section className="nx-stock-chat" aria-labelledby="nx-stock-chat-title">
      <header>
        <h2 id="nx-stock-chat-title">실시간 채팅</h2>
        <span className="nx-muted">{name} 채널</span>
      </header>
      {error && <p className="nx-msg is-error" role="alert">{error}</p>}
      <div className="nx-chat-window">
        {entries === null ? <div className="nx-skeleton" role="status" aria-label="채팅을 불러오는 중"><span /><span /><span /></div> : (
          <ol className="nx-chat-list" ref={list} aria-live="polite" aria-relevant="additions" tabIndex={0} aria-label={`${name} 채팅 메시지`}
            onScroll={(event) => {
              const element = event.currentTarget;
              stick.current = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
            }}>
            {olderMore && <li className="nx-chat-older">
              <button type="button" className="nx-link-button" disabled={loadingOlder} onClick={() => void older()}>{loadingOlder ? "불러오는 중…" : "이전 메시지 보기"}</button>
            </li>}
            {entries.length === 0 && <li className="nx-chat-empty nx-muted">아직 대화가 없어요.{user ? " 먼저 말을 걸어 보세요." : ""}</li>}
            {entries.map((entry) => {
              const at = serverTimestamp(entry.createdAt);
              const own = mine(entry);
              return (
                <li key={entry.id} className={`nx-chat-item${own ? " is-mine" : ""}${entry.deleted ? " is-deleted" : ""}`}>
                  <div className="nx-chat-meta">
                    <strong>{own ? "나" : entry.nickname}</strong>
                    <time dateTime={entry.createdAt} title={kstDateTime(at)} className="nx-num">{clock(at)}</time>
                    {canDelete(entry) && <button type="button" className="nx-link-button nx-chat-delete" onClick={() => void removed(entry)}
                      aria-label={`${clock(at)} 메시지 지우기`}>지우기</button>}
                  </div>
                  <p className="nx-chat-body">{entry.deleted ? "지워진 메시지예요." : entry.body}</p>
                </li>
              );
            })}
          </ol>
        )}
      </div>
      {ready && user ? <ChatComposer code={code} onSent={sent} />
        : <div className="nx-talk-signin">
            <p className="nx-muted">로그인하면 채팅에 참여할 수 있어요.</p>
            <button type="button" className="nx-btn is-small" onClick={() => openLoginPrompt(MARKET_PATH(code))}>로그인하기</button>
          </div>}
    </section>
  );
}

function ChatComposer({ code, onSent }: { code: string; onSent: (entry: ChatEntry) => void }) {
  const id = useId();
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const count = length(body);
  const over = count > CHAT_MAX_LENGTH;

  /* 전송은 자동으로 다시 보내지 않는다. 응답이 없으면 글이 이미 저장됐을 수 있다. */
  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (busy || count === 0 || over) return;
    setBusy(true);
    setMessage("");
    try {
      onSent(await sendChat(code, body.trim()));
      setBody("");
    } catch (reason) {
      setMessage(failure(reason, "메시지를 보내지 못했어요. 채팅창에 올라왔는지 확인한 뒤 다시 보내 주세요."));
    } finally {
      setBusy(false);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  };

  return (
    <form className="nx-chat-compose" onSubmit={submit} noValidate>
      <label htmlFor={id} className="vh">채팅 메시지</label>
      <textarea id={id} className="nx-input nx-chat-input" value={body} rows={1} placeholder="메시지 입력 · Shift+Enter 줄바꿈"
        aria-describedby={`${id}-count`} aria-invalid={over || undefined} onKeyDown={onKeyDown}
        onChange={(event) => setBody(event.target.value)} />
      <button type="submit" className="nx-btn is-primary is-small" disabled={busy || count === 0 || over}>{busy ? "보내는 중…" : "보내기"}</button>
      <span id={`${id}-count`} className={`nx-field-note nx-num${over ? " is-over" : ""}`}>{count > 0 ? `${count.toLocaleString("ko-KR")}/${CHAT_MAX_LENGTH.toLocaleString("ko-KR")}자` : ""}</span>
      {message && <p className="nx-msg is-error" role="alert">{message}</p>}
    </form>
  );
}
