import { featureLock, useEngagement, type FeatureId, type FeatureProgress } from "../../lib/engagement";
import { Link } from "../../components/Link";

/** 잠긴 기능의 남은 조건. 화면은 둘러볼 수 있게 두고, 주문만 서버에서 막는다. */
export function UnlockSteps({ feature }: { feature: FeatureProgress }) {
  return (
    <ol className="nx-unlock-steps">
      {feature.steps.map((step) => (
        <li key={step.label} className={step.done ? "is-done" : undefined}>
          <span className="nx-unlock-step-label">{step.label}</span>
          <span className="nx-unlock-meter" aria-hidden="true">
            <span style={{ width: `${Math.min(100, (step.current / Math.max(1, step.target)) * 100)}%` }} />
          </span>
          <span className="nx-num nx-unlock-count">{step.current}/{step.target}</span>
        </li>
      ))}
    </ol>
  );
}

export function UnlockNotice({ feature: id }: { feature: FeatureId }) {
  const engagement = useEngagement();
  const feature = featureLock(engagement, id);
  if (!feature) return null;
  return (
    <section className="nx-unlock-notice" aria-labelledby={`nx-unlock-${id}`}>
      <LockGlyph />
      <div className="nx-unlock-copy">
        <h2 id={`nx-unlock-${id}`}>{feature.name}은 아직 잠겨 있어요</h2>
        <p>{feature.hint} 시세는 지금도 볼 수 있어요.</p>
        <UnlockSteps feature={feature} />
      </div>
      <Link className="nx-btn is-small" to="/charge">해금 현황 보기</Link>
    </section>
  );
}

export function LockGlyph({ small = false }: { small?: boolean }) {
  return (
    <svg className={`nx-lock-glyph${small ? " is-small" : ""}`} viewBox="0 0 16 16" aria-hidden="true">
      <rect x="3" y="7" width="10" height="7" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  );
}
