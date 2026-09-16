/**
 * 브랜드 마크. 원형 로고라서 원으로 잘라 흰 모서리를 없앤다.
 * 옆에 워드마크가 있으므로 이미지 자체는 장식으로 둔다.
 */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <img
      className="brand-mark"
      src="/brand/mark-160.png"
      width={size}
      height={size}
      alt=""
      decoding="async"
      fetchPriority="high"
    />
  );
}
