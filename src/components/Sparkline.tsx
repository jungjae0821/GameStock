import { useId } from "react";

interface Props {
  series: number[];
  prevClose: number;
  tone: "up" | "down" | "flat";
  width?: number;
  height?: number;
}

/** 세션 가격 경로만 보여 주는 최소 차트. 축은 상세 화면의 큰 차트가 담당한다. */
export function Sparkline({ series, prevClose, tone, width = 88, height = 26 }: Props) {
  const clipId = useId();
  const points = series.length > 1 ? series.slice(-Math.min(series.length, width * 2)) : series;
  const values = points.length > 0 ? [...points, prevClose] : [prevClose];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const pad = span * 0.08;
  const scaleY = (value: number) => height - ((value - (min - pad)) / (span + pad * 2)) * height;
  const stepX = points.length > 1 ? width / (points.length - 1) : width;
  const path = points.map((value, index) => `${index * stepX},${scaleY(value)}`).join(" ");
  /* 경로 아래를 옅게 채운다. 세션 등락을 선이 아니라 면적으로 먼저 읽힌다. */
  const area = points.length > 1 ? `${path} ${width},${height} 0,${height}` : "";
  const lastX = (points.length - 1) * stepX;
  const lastY = points.length > 0 ? scaleY(points[points.length - 1]) : height / 2;

  return (
    <svg
      className={`spark is-${tone}`}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <clipPath id={clipId}>
          <rect x="0" y="0" width={width} height={height} />
        </clipPath>
      </defs>
      <g clipPath={`url(#${clipId})`}>
        <line className="spark-base" x1="0" x2={width} y1={scaleY(prevClose)} y2={scaleY(prevClose)} />
        {area && <polygon className="spark-area" points={area} />}
        <polyline className="spark-line" points={path} vectorEffect="non-scaling-stroke" />
        {points.length > 0 && <circle className="spark-dot" cx={lastX} cy={lastY} r="1.6" />}
      </g>
    </svg>
  );
}
