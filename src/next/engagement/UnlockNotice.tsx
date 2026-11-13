import { featureLock, useEngagement, type FeatureId } from "../../lib/engagement";
import { Link } from "../../components/Link";
import { withSubject, withTopic } from "../../lib/korean";

/** 잠긴 데스크 화면 위 안내. 화면은 둘러볼 수 있게 두고, 주문만 서버에서 막는다. */
export function UnlockNotice({ feature: id }: { feature: FeatureId }) {
  const engagement = useEngagement();
  const feature = featureLock(engagement, id);
  if (!feature) return null;
  return (
    <section className="nx-unlock-notice" aria-labelledby={`nx-unlock-${id}`}>
      <LockGlyph />
      <div className="nx-unlock-copy">
        <h2 id={`nx-unlock-${id}`}>{withTopic(feature.name)} 아직 잠겨 있어요</h2>
        <p>{withSubject(feature.gradeLabel)} 되면 열려요. 시세는 지금도 볼 수 있어요.</p>
      </div>
      <Link className="nx-btn is-small" to="/career">승진 조건 보기</Link>
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
