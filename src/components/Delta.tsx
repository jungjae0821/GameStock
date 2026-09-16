import { rate, signedWon, trendArrow } from "../market/format";

interface Props {
  change: number;
  /** 등락률을 비율로 받는다(0.0097 = +0.97%). */
  ratio: number;
  /** 금액을 생략하고 등락률만 보여 줄 때 false. */
  showAmount?: boolean;
  className?: string;
}

/** 색만으로 방향을 알리지 않도록 화살표 글리프와 부호를 함께 쓴다. */
export function Delta({ change, ratio, showAmount = true, className }: Props) {
  const tone = change > 0 ? "is-up" : change < 0 ? "is-down" : "is-flat";
  return (
    <span className={`delta ${tone}${className ? ` ${className}` : ""}`}>
      <span aria-hidden="true" className="delta-arrow">
        {trendArrow(change)}
      </span>
      {showAmount && <span className="num">{signedWon(change)}</span>}
      <span className="num">{rate(ratio)}</span>
    </span>
  );
}
