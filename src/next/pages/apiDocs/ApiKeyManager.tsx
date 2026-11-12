import { useEffect, useRef, useState } from "react";
import { apiFetch } from "../../../lib/api";
import { openLoginPrompt, useAuthUser } from "../../../lib/auth";
import { firebaseAuth } from "../../../lib/firebase";
import { CodeBlock } from "./DocsParts";

type KeyInfo = {
  id: string; name: string; prefix: string; permission: "READ" | "TRADE";
  createdAt: string; expiresAt: string; revokedAt: string | null;
};
type IssuedKey = { key: KeyInfo; token: string };

/** 로그인한 사용자의 API 키 발급·목록·폐기. 로그인 전에는 로그인 버튼만 보인다. */
export function ApiKeyManager() {
  const { user, ready } = useAuthUser();
  if (!ready) return <p role="status">로그인 확인 중…</p>;
  if (!user) return <button className="nx-btn is-primary" type="button" onClick={() => openLoginPrompt("/api-docs")}>로그인하고 API 키 발급</button>;
  return <AccountKeys key={user.uid} uid={user.uid} />;
}

function AccountKeys({ uid }: { uid: string }) {
  const [keys, setKeys] = useState<KeyInfo[]>([]);
  const [issued, setIssued] = useState<IssuedKey | null>(null);
  const [name, setName] = useState("my-bot");
  const [permission, setPermission] = useState<"READ" | "TRADE">("READ");
  const [days, setDays] = useState("90");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const locked = useRef(false);
  const mounted = useRef(false);
  const current = () => mounted.current && firebaseAuth.currentUser?.uid === uid;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    apiFetch<KeyInfo[]>("/api/openapi/keys", { signal: controller.signal })
      .then((items) => { if (!controller.signal.aborted && current()) setKeys(items); })
      .catch((cause: unknown) => { if (!controller.signal.aborted && current()) setError(cause instanceof Error ? cause.message : "키 목록을 불러오지 못했습니다."); })
      .finally(() => { if (!controller.signal.aborted && current()) setLoading(false); });
    return () => controller.abort();
  }, [uid, reload]);

  const mutate = async (keyId?: string) => {
    if (locked.current || !current()) return;
    if (!keyId && (!name.trim() || !Number.isInteger(Number(days)) || Number(days) < 1 || Number(days) > 365)) {
      setError("키 이름과 만기(1~365일)를 확인해 주세요.");
      return;
    }
    locked.current = true;
    setBusy(true);
    setError("");
    try {
      if (keyId) {
        await apiFetch<void>(`/api/openapi/keys/${encodeURIComponent(keyId)}`, { method: "DELETE" });
        if (!current()) return;
        setKeys((items) => items.map((item) => item.id === keyId ? { ...item, revokedAt: new Date().toISOString() } : item));
        setIssued((value) => value?.key.id === keyId ? null : value);
        setConfirmId(null);
      } else {
        const result = await apiFetch<IssuedKey>("/api/openapi/keys", {
          method: "POST", body: JSON.stringify({ name: name.trim(), permission, expiresInDays: Number(days) }),
        });
        if (!current()) return;
        setIssued(result);
        setKeys((items) => [result.key, ...items]);
      }
    } catch (cause) {
      if (current()) setError(cause instanceof Error ? `${cause.message} 목록을 새로고침해 처리 결과를 확인해 주세요.` : "요청을 완료하지 못했습니다. 목록을 새로고침해 확인해 주세요.");
    } finally {
      locked.current = false;
      if (current()) setBusy(false);
    }
  };

  return <div>
    <form onSubmit={(event) => { event.preventDefault(); void mutate(); }}>
      <fieldset disabled={busy || loading || issued !== null}>
        <legend>새 API 키</legend>
        <div className="nx-field"><label htmlFor="api-key-name">키 이름</label><input className="nx-input" id="api-key-name" required maxLength={80} value={name} onChange={(event) => setName(event.target.value)} /></div>
        <div className="nx-field"><label htmlFor="api-key-permission">권한</label><select className="nx-input" id="api-key-permission" value={permission} onChange={(event) => setPermission(event.target.value as "READ" | "TRADE")}><option value="READ">조회 전용</option><option value="TRADE">조회 + 주문</option></select></div>
        <div className="nx-field"><label htmlFor="api-key-days">만기 (일)</label><input className="nx-input" id="api-key-days" type="number" required min={1} max={365} step={1} value={days} onChange={(event) => setDays(event.target.value)} /></div>
        <button className="nx-btn is-primary" type="submit">{busy ? "처리 중…" : "API 키 발급"}</button>
      </fieldset>
    </form>
    {error && <p role="alert" className="nx-field-hint is-error">{error}</p>}
    {issued && <div role="status">
      <p>키가 발급되었습니다. 원문은 다시 조회할 수 없으니 지금 복사해 보관하세요.</p>
      <CodeBlock title={`${issued.key.name} API 키`} code={issued.token} />
      <button className="nx-btn" type="button" onClick={() => setIssued(null)}>보관 완료 · 키 숨기기</button>
    </div>}
    <h3>내 API 키</h3>
    <button className="nx-btn" type="button" disabled={loading || busy} onClick={() => setReload((value) => value + 1)}>목록 새로고침</button>
    {loading ? <p role="status">키 목록을 불러오는 중…</p> : keys.length === 0 ? <p>발급된 키가 없습니다.</p> : <div className="nx-docs-table-wrap"><table className="nx-docs-table" aria-label="내 API 키"><thead><tr><th scope="col">이름 / 접두사</th><th scope="col">권한</th><th scope="col">만기</th><th scope="col">상태 / 관리</th></tr></thead><tbody>
      {keys.map((key) => <tr key={key.id}>
        <td>{key.name}<br /><code>{key.prefix}…</code></td><td>{key.permission === "READ" ? "조회" : "조회 + 주문"}</td><td>{new Date(key.expiresAt).toLocaleString("ko-KR")}</td>
        <td>{key.revokedAt ? "폐기됨" : Date.parse(key.expiresAt) <= Date.now() ? "만료됨" : confirmId === key.id ? <><p>폐기하면 이 키로 API를 호출할 수 없습니다.</p><button className="nx-btn" type="button" disabled={busy || loading} onClick={() => void mutate(key.id)}>폐기 확인</button><button className="nx-btn" type="button" disabled={busy} onClick={() => setConfirmId(null)}>취소</button></> : <button className="nx-btn" type="button" disabled={busy || loading} aria-label={`${key.name} 키 폐기`} onClick={() => setConfirmId(key.id)}>폐기</button>}</td>
      </tr>)}
    </tbody></table></div>}
  </div>;
}
