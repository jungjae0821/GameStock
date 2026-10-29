import { useCallback, useEffect, useId, useState, type FormEvent } from "react";
import { openLoginPrompt, useAuthUser } from "../../lib/auth";
import { deleteTalk, likeTalk, loadTalk, writeTalk, type TalkPage, type TalkPost } from "../../lib/stockTalk";
import { serverTimestamp, since } from "../../market/format";
import { TALK_PATH } from "../../router";

const MAX_LENGTH = 300;

const failure = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback);
const length = (text: string) => [...text.trim()].length;

/** 종목토론 화면의 글 목록. 최신 글부터 보이고, 답글은 한 단계까지만 단다. */
export function StockTalk({ code, name }: { code: string; name: string }) {
  const { user, ready } = useAuthUser();
  const [page, setPage] = useState<TalkPage | null>(null);
  const [error, setError] = useState("");
  const [loadingMore, setLoadingMore] = useState(false);
  const uid = user?.uid ?? null;

  const reload = useCallback((signal?: AbortSignal) => {
    setError("");
    loadTalk(code, 0, signal).then(setPage).catch((reason: unknown) => {
      if (signal?.aborted) return;
      setError(failure(reason, "토론 글을 불러오지 못했어요."));
    });
  }, [code]);

  /* 로그인 상태가 바뀌면 내 글·좋아요 표시가 달라지므로 다시 받는다. */
  useEffect(() => {
    if (!ready) return;
    setPage(null);
    const controller = new AbortController();
    reload(controller.signal);
    return () => controller.abort();
  }, [reload, ready, uid]);

  const more = async () => {
    const last = page?.posts.at(-1);
    if (!page || !last) return;
    setLoadingMore(true);
    try {
      const next = await loadTalk(code, last.id);
      setPage({ ...next, posts: [...page.posts, ...next.posts.filter((post) => !page.posts.some((item) => item.id === post.id))] });
    } catch (reason) {
      setError(failure(reason, "글을 더 불러오지 못했어요."));
    } finally {
      setLoadingMore(false);
    }
  };

  const update = (id: number, change: (post: TalkPost) => TalkPost | null) => setPage((current) => current && {
    ...current,
    posts: current.posts.flatMap((post) => {
      if (post.id === id) { const next = change(post); return next ? [next] : []; }
      const replies = post.replies.flatMap((reply) => { if (reply.id !== id) return [reply]; const next = change(reply); return next ? [next] : []; });
      return [replies.length === post.replies.length && replies.every((reply, index) => reply === post.replies[index]) ? post : { ...post, replies }];
    }),
  });

  const posted = (post: TalkPost, parentId: number | null) => setPage((current) => current && {
    ...current,
    posts: parentId === null ? [post, ...current.posts]
      : current.posts.map((item) => item.id === parentId ? { ...item, replies: [...item.replies, post] } : item),
  });

  return (
    <div className="nx-stock-talk">
      {ready && user ? <TalkComposer code={code} parentId={null} placeholder={`${name}에 대한 생각을 남겨 주세요`} onPosted={posted} />
        : <div className="nx-talk-signin">
            <p className="nx-muted">로그인하면 글을 쓰고 좋아요를 누를 수 있어요.</p>
            <button type="button" className="nx-btn" onClick={() => openLoginPrompt(TALK_PATH(code))}>로그인하기</button>
          </div>}
      {error && <p className="nx-note is-error" role="alert">{error} <button type="button" className="nx-link-button" onClick={() => reload()}>다시 시도</button></p>}
      {!page ? (!error && <div className="nx-skeleton" role="status" aria-label="토론 글을 불러오는 중"><span /><span /></div>)
        : page.posts.length === 0 ? <p className="nx-empty-state">{user ? "아직 글이 없어요. 첫 글을 남겨 보세요." : "아직 글이 없어요."}</p> : (
        <ol className="nx-talk-list">
          {page.posts.map((post) => (
            <li key={post.id}>
              <TalkItem code={code} post={post} signedIn={!!user} onChange={update} onPosted={posted} />
            </li>
          ))}
        </ol>
      )}
      {page?.hasMore && <button type="button" className="nx-link-button nx-talk-more" disabled={loadingMore} onClick={() => void more()}>{loadingMore ? "불러오는 중…" : "이전 글 더 보기"}</button>}
    </div>
  );
}

function TalkComposer({ code, parentId, placeholder, onPosted, onCancel }: {
  code: string;
  parentId: number | null;
  placeholder: string;
  onPosted: (post: TalkPost, parentId: number | null) => void;
  onCancel?: () => void;
}) {
  const id = useId();
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const count = length(body);
  const over = count > MAX_LENGTH;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy || count === 0 || over) return;
    setBusy(true);
    setMessage("");
    try {
      onPosted(await writeTalk(code, body, parentId), parentId);
      setBody("");
      onCancel?.();
    } catch (reason) {
      setMessage(failure(reason, "글을 올리지 못했어요."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className={`nx-talk-compose${parentId ? " is-reply" : ""}`} onSubmit={submit} noValidate>
      <label htmlFor={id} className="vh">{parentId ? "답글" : "새 글"}</label>
      <textarea id={id} className="nx-input nx-talk-input" value={body} rows={parentId ? 2 : 3} placeholder={placeholder}
        aria-describedby={`${id}-count`} aria-invalid={over || undefined} autoFocus={parentId !== null}
        onChange={(event) => setBody(event.target.value)} />
      <div className="nx-talk-compose-foot">
        <span id={`${id}-count`} className={`nx-field-note nx-num${over ? " is-over" : ""}`}>{count}/{MAX_LENGTH}자</span>
        {onCancel && <button type="button" className="nx-link-button" disabled={busy} onClick={onCancel}>그만두기</button>}
        <button type="submit" className="nx-btn is-primary" disabled={busy || count === 0 || over}>{busy ? "올리는 중…" : parentId ? "답글 올리기" : "글 올리기"}</button>
      </div>
      {message && <p className="nx-msg is-error" role="alert">{message}</p>}
    </form>
  );
}

function TalkItem({ code, post, signedIn, onChange, onPosted, reply = false }: {
  code: string;
  post: TalkPost;
  signedIn: boolean;
  onChange: (id: number, change: (post: TalkPost) => TalkPost | null) => void;
  onPosted: (post: TalkPost, parentId: number | null) => void;
  reply?: boolean;
}) {
  const [replying, setReplying] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const like = async () => {
    if (!signedIn) { openLoginPrompt(TALK_PATH(code)); return; }
    setBusy(true);
    setMessage("");
    try {
      const result = await likeTalk(post.id);
      onChange(post.id, (current) => ({ ...current, likes: result.likes, liked: result.liked }));
    } catch (reason) {
      setMessage(failure(reason, "좋아요를 반영하지 못했어요."));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    const warning = !reply && post.replies.length > 0 ? "이 글을 지울까요? 달린 답글도 함께 지워져요." : "이 글을 지울까요?";
    if (!window.confirm(warning)) return;
    setBusy(true);
    setMessage("");
    try {
      await deleteTalk(post.id);
      onChange(post.id, () => null);
    } catch (reason) {
      setMessage(failure(reason, "글을 지우지 못했어요."));
      setBusy(false);
    }
  };

  return (
    <article className={`nx-talk-item${reply ? " is-reply" : ""}`}>
      <header className="nx-talk-meta">
        <strong>{post.nickname}</strong>
        {post.mine && <span className="nx-talk-mine">내 글</span>}
        <time dateTime={post.createdAt} className="nx-muted">{since(serverTimestamp(post.createdAt), Date.now())}</time>
      </header>
      <p className="nx-talk-body">{post.body}</p>
      <div className="nx-talk-actions">
        <button type="button" className={`nx-link-button${post.liked ? " is-active" : ""}`} aria-pressed={post.liked} disabled={busy} onClick={() => void like()}>
          좋아요 <span className="nx-num">{post.likes}</span>
        </button>
        {!reply && <button type="button" className="nx-link-button" aria-expanded={replying}
          onClick={() => (signedIn ? setReplying((open) => !open) : openLoginPrompt(TALK_PATH(code)))}>
          답글{post.replies.length > 0 && <> <span className="nx-num">{post.replies.length}</span></>}
        </button>}
        {post.mine && <button type="button" className="nx-link-button" disabled={busy} onClick={() => void remove()}>지우기</button>}
      </div>
      {message && <p className="nx-msg is-error" role="alert">{message}</p>}
      {!reply && (post.replies.length > 0 || replying) && (
        <div className="nx-talk-replies">
          {post.replies.length > 0 && <ol>
            {post.replies.map((item) => <li key={item.id}><TalkItem code={code} post={item} signedIn={signedIn} onChange={onChange} onPosted={onPosted} reply /></li>)}
          </ol>}
          {replying && <TalkComposer code={code} parentId={post.id} placeholder={`${post.nickname}님에게 답글`} onPosted={onPosted} onCancel={() => setReplying(false)} />}
        </div>
      )}
    </article>
  );
}
