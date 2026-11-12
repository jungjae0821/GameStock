import { useEffect, useRef, useState, type ReactNode } from "react";
import { AnimatedNav } from "../../AnimatedNav";
import { ORDER_EXAMPLES, type Endpoint } from "./apiDocsData";

export function CopyButton({ text, label = "복사" }: { text: string; label?: string }) {
  const [status, setStatus] = useState<"idle" | "copied" | "failed">("idle");
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setStatus("copied");
    } catch {
      setStatus("failed");
    }
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setStatus("idle"), 1600);
  };

  const textLabel = status === "copied" ? "복사됨" : status === "failed" ? "복사 실패" : label;
  return (
    <button
      type="button"
      className="nx-docs-copy"
      onClick={() => void copy()}
      aria-label={label === "복사" ? "복사" : `${label} 복사`}
    >
      {textLabel}
    </button>
  );
}

export function CodeBlock({ title, code }: { title?: string; code: string }) {
  return (
    <figure className="nx-docs-code">
      {title && <figcaption>{title}</figcaption>}
      <div className="nx-docs-code-body">
        <pre><code>{code}</code></pre>
        <CopyButton text={code} />
      </div>
    </figure>
  );
}

export function EndpointTable({ label, endpoints }: { label: string; endpoints: Endpoint[] }) {
  return (
    <div className="nx-docs-table-wrap">
      <table className="nx-docs-table" aria-label={label}>
        <thead>
          <tr>
            <th scope="col">메서드</th>
            <th scope="col">경로</th>
            <th scope="col">인증</th>
            <th scope="col">설명</th>
          </tr>
        </thead>
        <tbody>
          {endpoints.map((endpoint) => (
            <tr key={`${endpoint.method} ${endpoint.path}`}>
              <td data-label="메서드"><span className={`nx-docs-method is-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span></td>
              <td data-label="경로"><code className="nx-docs-path">{endpoint.path}</code></td>
              <td data-label="인증"><span className="nx-docs-auth">{endpoint.auth}</span></td>
              <td data-label="설명">{endpoint.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Section({ id, title, lede, children }: { id: string; title: string; lede?: string; children: ReactNode }) {
  return (
    <section id={id} className="nx-docs-section" aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className="nx-docs-section-title">{title}</h2>
      {lede && <p className="nx-docs-lede">{lede}</p>}
      {children}
    </section>
  );
}

export function OrderExamples() {
  const [active, setActive] = useState<(typeof ORDER_EXAMPLES)[number]["id"]>("stock");
  const example = ORDER_EXAMPLES.find((item) => item.id === active) ?? ORDER_EXAMPLES[0];

  return (
    <div className="nx-docs-examples">
      <AnimatedNav as="div" fill activeKey={active} className="nx-seg" role="group" aria-label="상품별 주문 본문">
        {ORDER_EXAMPLES.map((item) => (
          <button
            key={item.id}
            type="button"
            className={item.id === active ? "is-active" : ""}
            aria-pressed={item.id === active}
            onClick={() => setActive(item.id)}
          >
            {item.label}
          </button>
        ))}
      </AnimatedNav>
      <CodeBlock title="요청 본문" code={example.body} />
      <p className="nx-docs-note-inline">{example.note}</p>
    </div>
  );
}
