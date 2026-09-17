import type { ReactNode } from "react";
import { Link } from "./Link";
import { useRoute } from "../router";

interface Props {
  id: string;
  title: string;
  meta?: string;
  action?: { label: string; to: string };
  /** true면 본문 패딩 없이 테두리에 붙인다(표 전용). */
  flush?: boolean;
  level?: 2 | 3;
  children: ReactNode;
}

/*
 * 구획은 색으로 구분하지 않는다. 같은 무채색 머리와 괘선을 쓰고, 색은
 * 안에 담긴 값(등락·손익·체결)에만 붙는다.
 */
export function Panel({ id, title, meta, action, flush = false, level = 2, children }: Props) {
  const route = useRoute();
  const current =
    route.name === "market"
      ? (route.ticker ? `/market/${route.ticker}` : "/market")
      : route.name === "news"
        ? "/news"
        : route.name === "ranking"
          ? "/ranking"
          : route.name === "mypage"
            ? "/mypage"
          : "/";
  const Heading = level === 2 ? "h2" : "h3";

  return (
    <section className="panel" aria-labelledby={id}>
      <div className="panel-head">
        <span className="panel-mark" aria-hidden="true" />
        <Heading id={id} className="panel-title">
          {title}
        </Heading>
        {meta && <span className="panel-meta num">{meta}</span>}
        {action && action.to !== current && (
          <Link className="panel-action" to={action.to}>
            {action.label}
          </Link>
        )}
      </div>
      {flush ? children : <div className="panel-body">{children}</div>}
    </section>
  );
}
